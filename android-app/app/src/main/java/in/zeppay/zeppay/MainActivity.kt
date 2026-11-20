package `in`.zeppay.zeppay

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import `in`.zeppay.zeppay.data.BY_BANK_REPLY
import `in`.zeppay.zeppay.data.BY_USER
import `in`.zeppay.zeppay.data.Carrier
import `in`.zeppay.zeppay.data.Expense
import `in`.zeppay.zeppay.data.Group
import `in`.zeppay.zeppay.data.MenuScan
import `in`.zeppay.zeppay.data.Payee
import `in`.zeppay.zeppay.data.Person
import `in`.zeppay.zeppay.data.ROUTE_IVR
import `in`.zeppay.zeppay.data.ROUTE_MANUAL
import `in`.zeppay.zeppay.data.ROUTE_USSD
import `in`.zeppay.zeppay.data.STATUS_FAILED
import `in`.zeppay.zeppay.data.STATUS_PENDING
import `in`.zeppay.zeppay.data.STATUS_STOPPED
import `in`.zeppay.zeppay.data.STATUS_SUCCESS
import `in`.zeppay.zeppay.data.STATUS_UNKNOWN
import `in`.zeppay.zeppay.data.Settlement
import `in`.zeppay.zeppay.data.Store
import `in`.zeppay.zeppay.data.Tx
import `in`.zeppay.zeppay.notify.PaymentNotice
import `in`.zeppay.zeppay.ui.Dock
import `in`.zeppay.zeppay.ui.Screen
import `in`.zeppay.zeppay.ui.ScreenHost
import `in`.zeppay.zeppay.ui.TabHost
import `in`.zeppay.zeppay.ui.kit.ToastHost
import `in`.zeppay.zeppay.ui.screens.OnboardingFlow
import `in`.zeppay.zeppay.ui.theme.Motion
import `in`.zeppay.zeppay.ui.theme.ZepTheme
import `in`.zeppay.zeppay.ui.theme.Zep
import `in`.zeppay.zeppay.update.Updater
import `in`.zeppay.zeppay.ussd.Engine
import `in`.zeppay.zeppay.ussd.Flows
import `in`.zeppay.zeppay.ussd.Op
import `in`.zeppay.zeppay.ussd.OutcomeKind
import `in`.zeppay.zeppay.ussd.Outcome
import `in`.zeppay.zeppay.ussd.Phase
import `in`.zeppay.zeppay.ussd.Sim
import `in`.zeppay.zeppay.ussd.Sims
import `in`.zeppay.zeppay.ussd.Ussd
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How a payment will run on this phone right now. */
enum class Route { STEPWISE, MANUAL, IVR }

class ZepViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    private val ctx get() = getApplication<Application>()

    val onboarded = MutableStateFlow(store.onboarded && store.setupVersion >= 2)
    val theme = MutableStateFlow(store.theme)
    val history = MutableStateFlow(store.history)
    val groups = MutableStateFlow(store.groups)
    val menuScan = MutableStateFlow(store.menuScan)
    val tab = MutableStateFlow("pay")
    private val _stack = MutableStateFlow<List<Screen>>(emptyList())
    val stack = _stack.asStateFlow()
    var lastNavForward = true; private set
    val toast = MutableStateFlow("")
    val sims = MutableStateFlow<List<Sim>>(emptyList())
    val simSubId = MutableStateFlow(store.simSubId)
    val carrierOverride = MutableStateFlow(store.carrierOverride)
    /** A payment handed to the dialer / IVR that the user still has to tell us about. */
    val awaitingUser = MutableStateFlow<Tx?>(null)

    fun refreshSims() {
        sims.value = Sims.list(ctx)
        if (simSubId.value == -1) sims.value.firstOrNull { it.carrier?.ussdOk == true }?.let { setSim(it.subId) }
    }
    fun setSim(id: Int) { store.simSubId = id; simSubId.value = id }
    fun chosenSim(): Sim? = sims.value.firstOrNull { it.subId == simSubId.value } ?: sims.value.firstOrNull()

    /** Carrier for routing: the chosen SIM, else a pinned choice, else the default SIM's name. */
    fun carrier(): Carrier = when {
        carrierOverride.value != "auto" -> Carrier.fromId(carrierOverride.value)
        chosenSim()?.carrier != null -> chosenSim()!!.carrier!!
        else -> Ussd.detectCarrier(ctx) ?: Carrier.OTHER
    }
    fun setCarrierOverride(v: String) { store.carrierOverride = v; carrierOverride.value = v }

    fun route(helperOn: Boolean): Route = when {
        !carrier().ussdOk -> Route.IVR
        helperOn -> Route.STEPWISE
        else -> Route.MANUAL
    }

    fun go(s: Screen) { lastNavForward = true; _stack.value = _stack.value + s }
    fun replace(s: Screen) { lastNavForward = true; _stack.value = _stack.value.dropLast(1) + s }
    fun back() { lastNavForward = false; _stack.value = _stack.value.dropLast(1) }
    fun home(t: String = tab.value) { lastNavForward = false; _stack.value = emptyList(); tab.value = t }
    fun setTab(t: String) { tab.value = t; _stack.value = emptyList() }

    fun showToast(m: String) { viewModelScope.launch { toast.value = m; delay(2600); if (toast.value == m) toast.value = "" } }
    fun setTheme(v: String) { store.theme = v; theme.value = v }
    fun finishSetup() { store.onboarded = true; store.setupVersion = 2; onboarded.value = true }
    fun replaySetup() { store.setupVersion = 0; onboarded.value = false }

    fun newId() = System.currentTimeMillis().toString(36) + (Math.random() * 1e6).toLong().toString(36)

    fun upsert(tx: Tx) { store.record(tx); history.value = store.history }

    /** Starts the stepwise *99# session. Returns null on success, or a reason the UI must handle. */
    fun startStepwise(tx: Tx): Engine.StartResult {
        val p = tx.payee
        val op = when { p.vpa.isNotEmpty() -> Op.PAY_VPA; p.mobile.isNotEmpty() -> Op.PAY_MOBILE; else -> Op.PAY_ACCOUNT }
        val vars = Flows.vars(op, p.vpa, p.mobile, p.account, p.ifsc, tx.amount, tx.note)
        val r = Engine.start(ctx, op, vars, tx.amount, tx.id, chosenSim())
        if (r == Engine.StartResult.Started) { upsert(tx.copy(status = STATUS_PENDING, route = ROUTE_USSD, at = System.currentTimeMillis())); Updater.paymentOpen = true }
        return r
    }

    fun startInfo(op: Op): Engine.StartResult = Engine.start(ctx, op, emptyMap(), null, null, chosenSim())

    /** Reload after the app-level recorder wrote an engine outcome. */
    fun reload() { history.value = store.history; groups.value = store.groups; if (!Engine.busy()) Updater.paymentOpen = false }

    /** The user reports what their bank SMS / the call said. Only for manual, IVR and not-confirmed payments. */
    fun userSays(tx: Tx, status: String) {
        upsert(tx.copy(status = status, decidedBy = BY_USER, at = tx.at))
        if (status == STATUS_SUCCESS) settleSplit(tx)
        awaitingUser.value = null
        Updater.paymentOpen = false
    }

    fun startManual(tx: Tx) {
        val p = tx.payee
        val code = when { p.vpa.isNotEmpty() -> Op.PAY_VPA.code; p.mobile.isNotEmpty() -> Op.PAY_MOBILE.code; else -> Op.PAY_ACCOUNT.code }
        val t = tx.copy(status = STATUS_PENDING, route = ROUTE_MANUAL)
        upsert(t); awaitingUser.value = t; Updater.paymentOpen = true
        Ussd.openDialer(ctx, code)
    }

    fun startIvr(tx: Tx, dial: String) {
        val t = tx.copy(status = STATUS_PENDING, route = ROUTE_IVR)
        upsert(t); awaitingUser.value = t; Updater.paymentOpen = true
        Ussd.callNumber(ctx, dial)
    }

    fun saveMenuScan(raw: String) {
        val m = MenuScan(carrier().label, System.currentTimeMillis(), raw.take(1200), Flows.menuItems(raw))
        store.menuScan = m; menuScan.value = m
    }

    fun clearHistory() { store.history = emptyList(); history.value = emptyList() }

    // ---- splits (on this phone only; no sync) ----
    fun saveGroup(g: Group) { val list = groups.value.filter { it.id != g.id } + g; store.groups = list; groups.value = list }
    fun deleteGroup(id: String) { val list = groups.value.filter { it.id != id }; store.groups = list; groups.value = list }
    fun addExpense(gid: String, e: Expense) { groups.value.firstOrNull { it.id == gid }?.let { saveGroup(it.copy(expenses = it.expenses + e)) } }
    fun addSettlement(gid: String, s: Settlement) { groups.value.firstOrNull { it.id == gid }?.let { saveGroup(it.copy(settlements = it.settlements + s)) } }

    private fun settleSplit(tx: Tx) { PaymentBook.settleSplit(store, tx, newId()); groups.value = store.groups }

    @Suppress("unused") fun person(name: String, mobile: String, vpa: String) = Person(newId(), name, mobile, vpa)
    @Suppress("unused") fun payeeOf(p: Person) = Payee(vpa = p.vpa, mobile = p.mobile, name = p.name)
}

class MainActivity : ComponentActivity() {
    private val vm: ZepViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm.refreshSims()
        Updater.schedule(this, vm.store.autoUpdateCheck)
        handleIntent(intent)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                Updater.foreground = true
                vm.refreshSims()
                vm.reload()
                // Back from the dialer or a 123PAY call: ask what happened (we can't read it on these routes).
                vm.awaitingUser.value?.let { t -> if (vm.stack.value.lastOrNull() !is Screen.AskUser) vm.go(Screen.AskUser(t.id)) }
            }
            override fun onPause(owner: LifecycleOwner) { Updater.foreground = false }
        })
        setContent {
            val theme by vm.theme.collectAsState()
            ZepTheme(theme) { Root(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(i: Intent?) {
        when (i?.getStringExtra("open")) {
            "update" -> vm.go(Screen.Update)
            "live" -> Engine.session.value?.txId?.let { id ->
                if ((vm.stack.value.lastOrNull() as? Screen.Live)?.txId != id) vm.go(Screen.Live(id))
            }
        }
        if (BuildConfig.DEBUG) i?.getStringExtra("demo")?.let { `in`.zeppay.zeppay.debugtools.Demo.apply(vm, it) }
    }
}

@Composable
fun Root(vm: ZepViewModel) {
    val onboarded by vm.onboarded.collectAsState()
    val stack by vm.stack.collectAsState()
    val tab by vm.tab.collectAsState()
    val toast by vm.toast.collectAsState()
    val c = Zep.c
    Box(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        if (!onboarded) { OnboardingFlow(vm); return@Box }
        BackHandler(enabled = stack.isNotEmpty()) { vm.back() }
        BackHandler(enabled = stack.isEmpty() && tab != "pay") { vm.setTab("pay") }
        val top = stack.lastOrNull()
        val reduce = Zep.reduceMotion
        AnimatedContent(
            targetState = top, label = "nav",
            transitionSpec = {
                if (reduce) fadeIn(androidx.compose.animation.core.tween(0)) togetherWith fadeOut(androidx.compose.animation.core.tween(0))
                else if (vm.lastNavForward) (slideInVertically(Motion.shutter()) { it / 3 } + fadeIn(Motion.shutter())) togetherWith fadeOut(Motion.shutter())
                else fadeIn(Motion.shutter()) togetherWith (slideOutVertically(Motion.shutter()) { it / 3 } + fadeOut(Motion.shutter()))
            },
        ) { s ->
            if (s == null) Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { TabHost(vm, tab) }
                Dock(tab) { vm.setTab(it) }
            } else ScreenHost(vm, s)
        }
        ToastHost(toast, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Mirrors the engine into the status notification and the optional overlay card. The engine
 * lives at process level, so a session keeps running when ZepPay's screen is closed or left;
 * the outcome is recorded here either way.
 */
class ZepApp : Application() {
    /** ZepPay's own screen is visible (started), e.g. under the bank's translucent dialog. */
    @Volatile var mainVisible = false; private set

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) { if (a is MainActivity) mainVisible = true }
            override fun onActivityStopped(a: android.app.Activity) { if (a is MainActivity) mainVisible = false }
            override fun onActivityCreated(a: android.app.Activity, b: Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        val scope = kotlinx.coroutines.MainScope()
        scope.launch {
            Engine.session.collect { s ->
                val p = s?.phase
                val store = Store(this@ZepApp)
                when {
                    s == null -> { `in`.zeppay.zeppay.overlay.HandoffOverlay.hide(this@ZepApp); PaymentNotice.cancel(this@ZepApp) }
                    p is Phase.Done -> {
                        `in`.zeppay.zeppay.overlay.HandoffOverlay.hide(this@ZepApp)
                        s.txId?.let { PaymentBook.record(store, it, p.outcome) }
                        Updater.paymentOpen = false
                        val here = mainVisible
                        // In ZepPay (under the bank's dialog): show the result there. Elsewhere: a
                        // private result notification. ZepPay doesn't pull you out of another app.
                        PaymentNotice.show(this@ZepApp, p, s.op, LiveInfo.what, alertResult = !here)
                        if (here) try { startActivity(Intent(this@ZepApp, MainActivity::class.java).putExtra("open", "live").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) } catch (_: Exception) {}
                    }
                    else -> {
                        PaymentNotice.show(this@ZepApp, p!!, s.op, LiveInfo.what, alertResult = false)
                        if (store.overlayCard && p !is Phase.Away) `in`.zeppay.zeppay.overlay.HandoffOverlay.show(this@ZepApp, p, LiveInfo.payeeLine)
                        else `in`.zeppay.zeppay.overlay.HandoffOverlay.hide(this@ZepApp)
                    }
                }
            }
        }
    }
}

/**
 * Records what a *99# session's own reply supports. Runs at app level so the result is
 * saved even if the screen was closed while the bank's dialog was up.
 */
object PaymentBook {
    fun record(store: Store, txId: String, o: Outcome) {
        val tx = store.history.firstOrNull { it.id == txId } ?: return
        if (tx.status != STATUS_PENDING) return
        val status = when (o.kind) {
            OutcomeKind.PAID -> STATUS_SUCCESS; OutcomeKind.FAILED -> STATUS_FAILED
            OutcomeKind.STOPPED -> STATUS_STOPPED; else -> STATUS_UNKNOWN
        }
        val done = tx.copy(status = status, raw = o.raw.take(600), decidedBy = BY_BANK_REPLY)
        store.record(done)
        if (status == STATUS_SUCCESS) settleSplit(store, done, System.currentTimeMillis().toString(36))
    }

    fun settleSplit(store: Store, tx: Tx, id: String) {
        if (tx.splitRef.isEmpty()) return
        val parts = tx.splitRef.split(":")
        val gid = parts[0]; val pid = parts.getOrElse(1) { "" }
        val gs = store.groups
        val g = gs.firstOrNull { it.id == gid } ?: return
        if (g.settlements.any { it.txId == tx.id }) return
        store.groups = gs.map { if (it.id == gid) it.copy(settlements = it.settlements + Settlement(id, `in`.zeppay.zeppay.data.ME, pid, tx.amount, System.currentTimeMillis(), tx.id)) else it }
    }
}

/** What the overlay and notification say about the session. Set when it starts. */
object LiveInfo {
    /** Overlay line, e.g. "Asha, ₹250". */
    @Volatile var payeeLine: String = ""
    /** Notification line, e.g. "₹250 to Asha" or "Balance check". Private: hidden on the lock screen. */
    @Volatile var what: String = ""
}
