package `in`.zeppay.zeppay.ussd

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import `in`.zeppay.zeppay.BuildConfig
import `in`.zeppay.zeppay.data.Carrier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One active SIM, as far as the OS lets ZepPay see it. */
data class Sim(val slot: Int, val subId: Int, val label: String, val carrier: Carrier?, val handle: PhoneAccountHandle?)

object Sims {
    fun hasPhoneState(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun list(c: Context): List<Sim> {
        if (!hasPhoneState(c)) return emptyList()
        return try {
            val sm = c.getSystemService(SubscriptionManager::class.java)
            val tm = c.getSystemService(TelecomManager::class.java)
            val handles = tm.callCapablePhoneAccounts
            sm.activeSubscriptionInfoList.orEmpty().map { s ->
                val name = s.carrierName?.toString().orEmpty().ifEmpty { s.displayName?.toString().orEmpty() }
                val handle = handles.firstOrNull { h ->
                    h.id == s.subscriptionId.toString() || h.id == s.iccId ||
                        (Build.VERSION.SDK_INT >= 30 && try { tm.getPhoneAccount(h)?.let { a -> a.label?.toString() == s.displayName?.toString() } == true } catch (_: Exception) { false })
                }
                Sim(s.simSlotIndex, s.subscriptionId, name, Carrier.detect(name) ?: Carrier.detect(s.displayName?.toString()), handle)
            }.sortedBy { it.slot }
        } catch (_: Exception) { emptyList() }
    }

    /** True while the phone is on a voice call. Read-only: ZepPay never holds or ends a call. */
    fun onCall(c: Context): Boolean = try {
        val m = c.getSystemService(AudioManager::class.java).mode
        m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION
    } catch (_: Exception) { false }
}

/** What the UI and the overlay show about the running session. */
sealed interface Phase {
    data object Dialing : Phase
    data class Working(val label: String, val step: Int) : Phase
    data object PinHandoff : Phase
    data class NeedsUser(val prompt: String) : Phase
    /**
     * The bank's screen went out of sight while the session was running (the user went Home
     * or switched apps). On the Android 15 emulator the *99# dialog is then stopped, hidden
     * and not listed in Recents, so ZepPay can neither see it nor type into it. ZepPay waits
     * for it to come back and ends the session honestly if it doesn't.
     */
    data class Away(val before: Phase, val pinSeen: Boolean, val since: Long) : Phase
    data class Done(val outcome: Outcome) : Phase
}

data class Session(val id: Int, val op: Op, val txId: String?, val phase: Phase, val frames: List<String> = emptyList())

/**
 * Runs one *99# session: dials the menu code, feeds frames to [FlowRunner], types the
 * replies it allows, and steps aside for the PIN. Session hygiene follows the pattern
 * studied in OffPay: increasing session ids, leftover-dialog dismissal, a double-tap
 * cooldown, inactivity + hard-cap timeouts and paced replies.
 */
object Engine {
    private const val COOLDOWN_MS = 2_500L
    private const val PACE_MS = 450L
    private const val IDLE_AUTOMATING_MS = 20_000L
    private const val IDLE_WITH_USER_MS = 120_000L
    private const val HARD_CAP_MS = 240_000L

    private val main = Handler(Looper.getMainLooper())
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private var counter = 0
    private var lastStart = 0L
    private var runner: FlowRunner? = null
    private var stepNo = 0
    /** A reply chosen but not yet typed (the screen went away first). Typed when it comes back. */
    private var pendingReply: String? = null

    /** Debug builds: route dialling to the simulated carrier screen instead of the network. */
    @Volatile var simulate = false
    @Volatile var simScript = "ok"

    private var attach: ((UssdService) -> Unit)? = null

    /** The helper was restarted mid-session (battery manager, test tooling): hook the new instance back in. */
    fun reattach(svc: UssdService) { if (busy()) { svc.resetForSession(); attach?.invoke(svc); svc.readSoon() } }

    fun busy() = _session.value?.phase.let { it != null && it !is Phase.Done }

    sealed interface StartResult {
        data object Started : StartResult
        data object NeedsAccessibility : StartResult
        data object NeedsCallPermission : StartResult
        data object Busy : StartResult
    }

    fun start(context: Context, op: Op, vars: Map<String, String>, amount: Double?, txId: String?, sim: Sim?): StartResult {
        val svc = UssdService.instance
        if (svc == null || !UssdService.isEnabled(context)) return StartResult.NeedsAccessibility
        val simulated = simulate && BuildConfig.DEBUG
        if (!simulated && ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return StartResult.NeedsCallPermission
        val now = SystemClock.elapsedRealtime()
        if (busy() || now - lastStart < COOLDOWN_MS) return StartResult.Busy
        lastStart = now

        // Clear a dialog ZepPay left behind, then start clean.
        svc.active = true
        svc.dismiss()
        svc.resetForSession()
        val id = ++counter
        val r = FlowRunner(op, vars, amount)
        runner = r
        stepNo = 0
        pendingReply = null
        _session.value = Session(id, op, txId, Phase.Dialing)
        log(id, "start ${op.name}${if (simulated) " (simulated)" else ""}")
        attach = { sv ->
            sv.active = true
            sv.listener = { f -> main.post { onFrame(id, f) } }
            sv.onGone = { main.post {
                if (_session.value?.id != id) return@post
                // After the PIN step the bank may take a while to reply, and some phones show no
                // dialog meanwhile. Only the reply or the usual wait ends it (Not confirmed).
                if (r.pinSeen) log(id, "dialog gone after the PIN step: waiting for the reply")
                else { log(id, "dialog closed"); end(id, r.onSessionEnded("The bank's screen closed.")) }
            } }
            sv.onAway = { main.post { away(id) } }
            sv.onBack = { main.post { back(id) } }
            sv.startWatching()
        }
        attach?.invoke(svc)
        armIdle(id, IDLE_AUTOMATING_MS)
        main.postDelayed({ if (_session.value?.id == id && busy()) end(id, r.onSessionEnded("Timed out after ${HARD_CAP_MS / 1000}s.")) }, HARD_CAP_MS)

        val intent = if (simulated) Intent().setClassName(context, "in.zeppay.zeppay.debugtools.SimulatedCarrierActivity").putExtra("code", op.code).putExtra("script", simScript)
        else Intent(Intent.ACTION_CALL, Uri.parse("tel:" + op.code.replace("#", Uri.encode("#")))).apply {
            sim?.handle?.let { putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent); StartResult.Started
        } catch (_: Exception) {
            end(id, r.onSessionEnded("The phone would not open the *99# session.")); StartResult.Started
        }
    }

    /** User pressed Stop in ZepPay. Before the PIN step this cancels; after it, the outcome stays Unknown. */
    fun stop() {
        val s = _session.value ?: return
        val r = runner ?: return
        if (s.phase is Phase.Done) return
        UssdService.instance?.dismiss()
        end(s.id, r.onSessionEnded("Stopped in ZepPay."))
    }

    fun clear() { if (!busy()) _session.value = null }

    private fun onFrame(id: Int, f: Frame) {
        val s = _session.value ?: return
        val r = runner ?: return
        if (s.id != id || s.phase is Phase.Done) return
        if (s.phase is Phase.Away) { back(id); return onFrame(id, f) }
        val frames = (s.frames + f.text).takeLast(12)
        when (val d = r.onFrame(f)) {
            is Decision.Reply -> {
                stepNo++
                _session.value = s.copy(phase = Phase.Working(d.label, stepNo), frames = frames)
                armIdle(id, IDLE_AUTOMATING_MS)
                pendingReply = d.text
                main.postDelayed({ typePending(id) }, PACE_MS)
            }
            Decision.PinHandoff -> { log(id, "pin handoff"); _session.value = s.copy(phase = Phase.PinHandoff, frames = frames); armIdle(id, IDLE_WITH_USER_MS) }
            is Decision.NeedsUser -> { log(id, "needs user"); _session.value = s.copy(phase = Phase.NeedsUser(d.prompt), frames = frames); armIdle(id, IDLE_WITH_USER_MS) }
            is Decision.Finish -> {
                _session.value = s.copy(frames = frames)
                // Close the result dialog only after its text is captured (and, for menu checks, right away).
                main.postDelayed({ UssdService.instance?.dismiss() }, if (s.op == Op.DISCOVER) 300 else 1_500)
                end(id, d.outcome)
            }
            Decision.Wait -> Unit
        }
    }

    private fun typePending(id: Int) {
        val text = pendingReply ?: return
        val r = runner ?: return
        if (_session.value?.id != id || _session.value?.phase !is Phase.Working) return
        if (UssdService.instance?.reply(text) == true) pendingReply = null
        else end(id, r.onSessionEnded("ZepPay couldn't type into the bank's screen."))
    }

    /**
     * The bank's screen is out of sight (another app is on top). Display only: ZepPay pauses
     * typing, but the session's normal timeouts keep running and decide the outcome, exactly as
     * in 2.0.0 (Stopped if nothing comes back before the PIN step, Not confirmed after it).
     */
    private fun away(id: Int) {
        val s = _session.value ?: return
        val r = runner ?: return
        if (s.id != id || s.phase is Phase.Done || s.phase is Phase.Away) return
        log(id, "away")
        _session.value = s.copy(phase = Phase.Away(s.phase, r.pinSeen, SystemClock.elapsedRealtime()))
    }

    /** The bank's screen is visible again: carry on where the session was. */
    private fun back(id: Int) {
        val s = _session.value ?: return
        val a = s.phase as? Phase.Away ?: return
        if (s.id != id) return
        log(id, "back")
        _session.value = s.copy(phase = a.before)
        if (a.before is Phase.Working && pendingReply != null) main.postDelayed({ typePending(id) }, PACE_MS)
        UssdService.instance?.readSoon()
    }

    /** Session state changes only: never frame text, payee, amount or anything typed. */
    private fun log(id: Int, what: String) { android.util.Log.i("ZepPayEngine", "session $id: $what") }

    private var idleToken = Any()
    private fun armIdle(id: Int, ms: Long) {
        val token = Any(); idleToken = token
        main.postDelayed({
            if (idleToken === token && _session.value?.id == id && busy()) {
                UssdService.instance?.dismiss()
                runner?.let { end(id, it.onSessionEnded("No reply from the bank for ${ms / 1000}s.")) }
            }
        }, ms)
    }

    private fun end(id: Int, o: Outcome) {
        val s = _session.value ?: return
        if (s.id != id || s.phase is Phase.Done) return
        _session.value = s.copy(phase = Phase.Done(o))
        log(id, "end ${o.kind}")
        attach = null
        pendingReply = null
        UssdService.instance?.let { it.active = false; it.listener = null; it.onGone = null; it.onAway = null; it.onBack = null }
    }
}
