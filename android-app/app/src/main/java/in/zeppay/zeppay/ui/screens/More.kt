@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package `in`.zeppay.zeppay.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import `in`.zeppay.zeppay.BuildConfig
import `in`.zeppay.zeppay.ZepViewModel
import `in`.zeppay.zeppay.data.Carrier
import `in`.zeppay.zeppay.overlay.HandoffOverlay
import `in`.zeppay.zeppay.ui.Screen
import `in`.zeppay.zeppay.ui.kit.*
import `in`.zeppay.zeppay.ui.theme.ZIcon
import `in`.zeppay.zeppay.ui.theme.ZType
import `in`.zeppay.zeppay.ui.theme.Zep
import `in`.zeppay.zeppay.update.UpdateState
import `in`.zeppay.zeppay.update.Updater
import `in`.zeppay.zeppay.ussd.Engine
import `in`.zeppay.zeppay.ussd.Op
import `in`.zeppay.zeppay.ussd.OutcomeKind
import `in`.zeppay.zeppay.ussd.Phase
import `in`.zeppay.zeppay.ussd.Sims
import `in`.zeppay.zeppay.ussd.Ussd
import kotlinx.coroutines.launch

@Composable
fun MoreTab(vm: ZepViewModel) {
    val c = Zep.c
    val update by Updater.state.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("More", style = ZType.hero, color = c.text)
        TileRow("Settings", "SIM, look, 123PAY", ZIcon.Gear, { vm.go(Screen.Settings) })
        TileRow("ZepPay helper", if (helperOn()) "On: fills in *99# for you" else "Off: tap to set up", ZIcon.Accessibility, { vm.go(Screen.Helper) }, bubble = if (helperOn()) null else c.bubbleB)
        TileRow("Link my bank", "First-time *99# setup", ZIcon.Shield, { vm.go(Screen.BankSetup) })
        TileRow("Check my SIM's menu", "See what *99# offers on your SIM", ZIcon.Sim, { vm.go(Screen.MenuCheck) })
        TileRow("Bank history", "*99# statement, if your SIM has it", ZIcon.List, { vm.go(Screen.Info(Op.HISTORY)) })
        TileRow("Updates", if (update is UpdateState.Available) "New version ready" else "Version ${BuildConfig.VERSION_NAME}", ZIcon.Update, { vm.go(Screen.Update) }, bubble = if (update is UpdateState.Available) c.bubbleA else null)
        TileRow("Help", "How ZepPay works, limits, privacy", ZIcon.Help, { vm.go(Screen.Help) })
        TileRow("Licences", null, ZIcon.List, { vm.go(Screen.Licenses) })
    }
}

@Composable
fun SettingsScreen(vm: ZepViewModel) {
    val ctx = LocalContext.current
    val theme by vm.theme.collectAsState()
    val sims by vm.sims.collectAsState()
    val simId by vm.simSubId.collectAsState()
    val override by vm.carrierOverride.collectAsState()
    var assist by remember { mutableStateOf(vm.store.dtmfAssist) }
    var lang by remember { mutableStateOf(vm.store.ivrLang) }
    var auto by remember { mutableStateOf(vm.store.autoUpdateCheck) }
    var sim by remember { mutableStateOf(Engine.simulate) }
    var url by remember { mutableStateOf(vm.store.updateUrlOverride) }
    var card by remember { mutableStateOf(vm.store.overlayCard) }
    var clear by remember { mutableIntStateOf(0) }
    val phonePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshSims() }
    Page("Settings", { vm.back() }) {
        Kicker("Which SIM pays")
        if (!Sims.hasPhoneState(ctx)) {
            Fine("Let ZepPay see which SIMs are in your phone so it can use the right one for *99#. It only reads carrier names.")
            PlainButton("Show my SIMs", { phonePerm.launch(android.Manifest.permission.READ_PHONE_STATE) }, Modifier.fillMaxWidth(), icon = ZIcon.Sim)
        } else if (sims.isEmpty()) Fine("No active SIM found.")
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            sims.forEach { s -> Chip("SIM ${s.slot + 1}: ${s.label}", s.subId == simId) { vm.setSim(s.subId) } }
        }
        Fine("Jio has no *99#. On a Jio SIM, ZepPay uses the UPI 123PAY call instead.")
        Kicker("Carrier (if detection is wrong)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("Auto", override == "auto") { vm.setCarrierOverride("auto") }
            Carrier.entries.forEach { cr -> Chip(cr.label, override == cr.id) { vm.setCarrierOverride(cr.id) } }
        }
        Kicker("Look")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("auto" to "Auto", "light" to "Whitewash", "dark" to "Teal wall").forEach { (k, l) -> Chip(l, theme == k) { vm.setTheme(k) } }
        }
        Kicker("While paying")
        Toggle("Progress card over the bank's screen", "Optional. A small card above the *99# screen shows what ZepPay is doing. The notification shows the same, with Cancel.", card) {
            card = it; vm.store.overlayCard = it
            if (it && !HandoffOverlay.canShow(ctx)) vm.go(Screen.Setup("overlay"))
        }
        Kicker("123PAY call (Jio)")
        Toggle("Key in the call steps for me", "Sends menu tones on the call. Not tested on a real Jio SIM yet; if a tone lands early, press it yourself.", assist) { assist = it; vm.store.dtmfAssist = it }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("1" to "Hindi", "2" to "English", "3" to "Bangla").forEach { (k, l) -> Chip(l, lang == k) { lang = k; vm.store.ivrLang = k } }
        }
        Kicker("Updates")
        Toggle("Check for updates", "Twice a day, when you have internet. ZepPay only tells you; it never installs by itself.", auto) { auto = it; vm.store.autoUpdateCheck = it; Updater.schedule(ctx, it) }
        if (BuildConfig.DEBUG) {
            Kicker("Debug build only")
            Toggle("Simulated carrier", "Replays sample *99# screens on this phone. No network, no money.", sim) { sim = it; Engine.simulate = it }
            Field(url, { url = it; vm.store.updateUrlOverride = it.trim() }, "Update manifest URL (testing)", placeholder = Updater.DEFAULT_URL)
        }
        Kicker("Your data")
        PlainButton(if (clear == 0) "Clear payment activity" else "Tap again: delete all activity", {
            if (clear == 0) clear = 1 else { vm.clearHistory(); clear = 0; vm.showToast("Activity cleared") }
        }, Modifier.fillMaxWidth(), icon = ZIcon.Trash, tint = Zep.c.failed)
        Kicker("Setup")
        PlainButton("Open setup walkthrough", {
            vm.store.setupSkipped = emptySet() // reopening offers skipped steps again; done ones stay done
            vm.go(Screen.Setup())
        }, Modifier.fillMaxWidth(), icon = ZIcon.Shield)
        PlainButton("Replay the welcome", { vm.replaySetup() }, Modifier.fillMaxWidth())
        Fine("Everything stays on this phone. No account, no analytics, no server.")
        @Suppress("UNUSED_VARIABLE") val unused = Uri.EMPTY
    }
}

/** Turn on the accessibility helper + status overlay. One action per card. */
@Composable
fun HelperScreen(vm: ZepViewModel) {
    val ctx = LocalContext.current
    val c = Zep.c
    val helper = helperOn(); val overlay = overlayOn()
    Page("ZepPay helper", { vm.back() }) {
        Body("The helper fills in the *99# steps for you: payee, amount and note. You still type your UPI PIN yourself, on your bank's screen.")
        Panel {
            Text("What it can see", style = ZType.bodyBold, color = c.text)
            Fine("Only the *99# screen from your phone app, and only while you're paying in ZepPay. It never reads what you type into that screen, so it can't see your PIN.", color = c.text.copy(alpha = 0.85f))
        }
        StepCard(1, "Turn on ZepPay in Accessibility", if (helper) "Done" else null) {
            ActionButton("Open Accessibility", { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, Modifier.fillMaxWidth(), icon = ZIcon.Accessibility)
            Fine("Find \"ZepPay\" under Downloaded apps, open it, and switch it on.")
        }
        if (!helper && Build.VERSION.SDK_INT >= 33) StepCard(2, "If Android says \"Restricted setting\"", null) {
            Body("Android blocks this for apps installed from a file until you allow it once:")
            Fine("1. Tap below to open ZepPay's App info.\n2. Tap the ⋮ menu at the top right.\n3. Tap \"Allow restricted settings\" and confirm.\n4. Come back and do step 1 again.\nThe exact wording differs by phone. This flow hasn't been checked on your phone yet.")
            PlainButton("Open App info", { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, Modifier.fillMaxWidth())
        }
        if (!helper) PlainButton("Walk me through it", { vm.go(Screen.Setup("helper")) }, Modifier.fillMaxWidth(), icon = ZIcon.Image)
        StepCard(3, "Show progress over the bank's screen (optional)", if (overlay) "Done" else null) {
            PlainButton("Allow display over apps", { vm.store.overlayCard = true; vm.go(Screen.Setup("overlay")) }, Modifier.fillMaxWidth(), icon = ZIcon.Layers)
            Fine("Shows a small ZepPay card at the top while paying. It never covers the bank's screen. You can switch it off in Settings.")
        }
    }
}

@Composable
private fun StepCard(n: Int, title: String, done: String?, content: @Composable () -> Unit) {
    val c = Zep.c
    Panel(Modifier.fillMaxWidth()) {
        Row { Text("$n", style = ZType.title, color = c.primary, modifier = Modifier.padding(end = 12.dp)); Text(title, style = ZType.label, color = c.text, modifier = Modifier.weight(1f)) }
        Spacer(Modifier.height(10.dp))
        if (done != null) DoneTag() else content()
    }
}

/** First-time *99# bank linking. One step per screen. ZepPay doesn't automate this. */
@Composable
fun BankSetupScreen(vm: ZepViewModel) {
    val ctx = LocalContext.current
    val c = Zep.c
    var i by rememberSaveable { mutableIntStateOf(0) }
    val steps = listOf(
        Triple("Dial *99#", "Open your phone's dialer and call *99#. Use the SIM whose number is registered with your bank. Jio SIMs don't have *99#.", true),
        Triple("Pick your language", "The bank's screen shows a list of languages. Type the number for yours and press Send.", false),
        Triple("Find your bank", "Type the first 4 letters of your bank's IFSC (like SBIN) or your bank's short name, then pick it.", false),
        Triple("Pick your account", "If you have more than one account, pick the one you want to pay from.", false),
        Triple("Check with your debit card", "Type the last 6 digits of your debit card and its expiry date, on the bank's screen.", false),
        Triple("Set your UPI PIN", "Make a 4 or 6 digit UPI PIN, on the bank's screen. Don't tell anyone, not even ZepPay. ZepPay never asks.", false),
        Triple("All set", "Now you can pay from ZepPay. Next time, ZepPay fills in the steps and you only type your PIN.", false),
    )
    val (t, d, dial) = steps[i]
    Page("Link my bank", { if (i == 0) vm.back() else i-- }, footer = {
        if (dial) ActionButton("Open *99#", { Ussd.openDialer(ctx, "*99#"); i++ }, Modifier.fillMaxWidth(), icon = ZIcon.Call)
        if (i < steps.lastIndex) PlainButton(if (dial) "I've opened it" else "Next", { i++ }, Modifier.fillMaxWidth(), icon = ZIcon.Arrow)
        else ActionButton("Done", { vm.back() }, Modifier.fillMaxWidth(), icon = ZIcon.Check)
    }) {
        Stepper(steps.size, i)
        Text("STEP ${i + 1}", style = ZType.label, color = c.primary)
        Text(t, style = ZType.hero, color = c.text)
        Body(d)
        if (i in 1..5) BankCounter(Modifier.fillMaxWidth()) { Body("You type this on the bank's screen. ZepPay isn't involved in this step.") }
        if (i == 2) BankCodeLookup()
    }
}

/** Read-only: open *99#, read the main menu, close it. Nothing is paid and no PIN is asked. */
@Composable
fun MenuCheckScreen(vm: ZepViewModel) {
    val c = Zep.c
    val session by Engine.session.collectAsState()
    val scan by vm.menuScan.collectAsState()
    val s = session?.takeIf { it.op == Op.DISCOVER }
    val p = s?.phase
    if (p is Phase.Done && p.outcome.kind == OutcomeKind.INFO && (scan?.raw != p.outcome.raw.take(1200))) vm.saveMenuScan(p.outcome.raw)
    val callPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    Page("Check my SIM's menu", { Engine.clear(); vm.back() }, footer = {
        ActionButton(if (scan == null) "Check now" else "Check again", {
            `in`.zeppay.zeppay.LiveInfo.what = "Menu check"; `in`.zeppay.zeppay.LiveInfo.payeeLine = "your menu check"
            when (vm.startInfo(Op.DISCOVER)) {
                Engine.StartResult.NeedsAccessibility -> vm.go(Screen.Helper)
                Engine.StartResult.NeedsCallPermission -> callPerm.launch(android.Manifest.permission.CALL_PHONE)
                Engine.StartResult.Busy -> vm.showToast("A *99# session is already running.")
                Engine.StartResult.Started -> Unit
            }
        }, Modifier.fillMaxWidth(), icon = ZIcon.Sim, enabled = p == null || p is Phase.Done)
    }) {
        Body("*99# menus differ by bank and network. ZepPay opens *99#, reads the first menu, and closes it. No payment, no PIN.")
        if (p != null && p !is Phase.Done) Row { Working(); Spacer(Modifier.padding(6.dp)); Text("Reading the menu…", style = ZType.label, color = c.text) }
        scan?.let { m ->
            Kicker("Your ${m.carrier} SIM offers")
            listOf(
                "Send money" to Regex("(?i)send|money|transfer|pay"), "Check balance" to Regex("(?i)balance"),
                "Transactions" to Regex("(?i)transaction|history|statement"), "UPI PIN" to Regex("(?i)pin"),
            ).forEach { (name, rx) ->
                Row(Modifier.fillMaxWidth()) { Text(name, style = ZType.bodyBold, color = c.text, modifier = Modifier.weight(1f)); PriceTagText(if (m.offers(rx)) "LISTED" else "NOT LISTED", m.offers(rx)) }
            }
            Panel { Kicker("Exact menu text", color = c.text); Text(m.raw, style = ZType.mono, color = c.text) }
            Fine("Checked ${whenLabel(m.at)}. Features not listed stay switched off in ZepPay.")
        } ?: Fine("Not checked yet.")
    }
}

@Composable
private fun PriceTagText(t: String, on: Boolean) {
    val c = Zep.c
    Text(t, style = ZType.label.copy(fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp)), color = c.bg,
        modifier = Modifier.background(if (on) c.paid else c.muted).padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
fun UpdateScreen(vm: ZepViewModel) {
    val c = Zep.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Updater.state.collectAsState()
    Page("Updates", { vm.back() }, footer = {
        when (val s = st) {
            is UpdateState.Available -> ActionButton("Update now", { scope.launch { Updater.downloadAndInstall(ctx, s.r) } }, Modifier.fillMaxWidth(), icon = ZIcon.Update)
            is UpdateState.NeedsInstallPermission -> {
                ActionButton("Allow updates from ZepPay", { vm.go(Screen.Setup("install")) }, Modifier.fillMaxWidth())
                PlainButton("I've allowed it: update", { scope.launch { Updater.downloadAndInstall(ctx, s.r) } }, Modifier.fillMaxWidth())
            }
            is UpdateState.Failed -> s.r?.let { r -> ActionButton("Try again", { scope.launch { Updater.downloadAndInstall(ctx, r) } }, Modifier.fillMaxWidth()) }
                ?: ActionButton("Check again", { scope.launch { Updater.check(ctx, vm.store) } }, Modifier.fillMaxWidth())
            is UpdateState.Blocked -> PlainButton("Back", { vm.back() }, Modifier.fillMaxWidth())
            is UpdateState.Downloading, is UpdateState.Verifying, is UpdateState.Installing, is UpdateState.WaitingForYou, UpdateState.Checking -> Unit
            else -> ActionButton("Check for updates", { scope.launch { Updater.check(ctx, vm.store) } }, Modifier.fillMaxWidth(), icon = ZIcon.Update)
        }
    }) {
        Text("You have ${BuildConfig.VERSION_NAME}", style = ZType.label, color = c.text)
        when (val s = st) {
            UpdateState.Checking -> Row { Working(); Spacer(Modifier.padding(6.dp)); Body("Checking…") }
            is UpdateState.UpToDate -> Body("You're on the newest version. Checked ${whenLabel(s.checkedAt)}.")
            is UpdateState.Available -> PayCard(Modifier.fillMaxWidth(), top = {
                Text("ZEPPAY ${s.r.versionName}", style = ZType.title, color = c.text)
                Text("What's new", style = ZType.bodyBold, color = c.text)
                s.r.notes.forEach { Text("• $it", style = ZType.body, color = c.text) }
                if (s.r.size > 0) Text("${s.r.size / 1_000_000} MB download", style = ZType.small, color = c.text.copy(alpha = 0.8f))
            })
            is UpdateState.Downloading -> {
                Body("Downloading…")
                val f = if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(24.dp).clip(`in`.zeppay.zeppay.ui.theme.Shapes.pill).background(c.secondary)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(c.primary))
                }
                Fine("${s.done / 1024} KB of ${s.total / 1024} KB")
            }
            is UpdateState.Verifying -> Body("Checking the file is genuine: checksum, ZepPay's signing key, and a newer version number.")
            is UpdateState.Installing -> Body("Handing the update to Android…")
            is UpdateState.WaitingForYou -> Panel { Text("Android needs your OK", style = ZType.bodyBold, color = c.text); Fine("Tap Update or Install on Android's screen. Your payments and splits stay as they are.", color = c.text) }
            is UpdateState.NeedsInstallPermission -> Body("Android needs you to allow ZepPay to install its own updates, once. Tap below, switch on \"Allow from this source\", then come back.")
            is UpdateState.Blocked -> Body(s.why)
            is UpdateState.Failed -> Body(s.why)
            UpdateState.Idle -> Body("ZepPay checks GitHub for a newer version. That needs internet. Payments don't.")
        }
        Fine("Android shows its own confirmation for each update on most phones. ZepPay doesn't skip it, and never updates during a payment or a call. Your data stays.")
    }
}

@Composable
fun HelpScreen(vm: ZepViewModel) {
    val qa = listOf(
        "Does ZepPay move my money?" to "No. Your bank does, through *99# on your SIM (or the UPI 123PAY call on Jio). ZepPay fills in the payee, amount and note.",
        "Does ZepPay see my UPI PIN?" to "Never. You type it only on your bank's own screen or the call keypad. ZepPay can't read it and doesn't store it.",
        "How do I know a payment worked?" to "ZepPay shows PAID only when your bank's *99# reply clearly says so. Otherwise it says NOT CONFIRMED and shows the bank's exact words. Your bank SMS is where most people double-check.",
        "Why shouldn't I pay again right away?" to "If a result is unclear, the money may already have gone. Paying again could take it twice. Check your SMS first.",
        "Which SIMs work?" to "*99# works on many Airtel, Vi and BSNL SIMs. Jio has no *99#, so ZepPay uses the 123PAY call. Use \"Check my SIM's menu\" to see yours.",
        "Do I need internet?" to "Not for payments: they use your SIM's signal. Updates do need internet.",
        "What are the limits?" to "*99# allows up to ₹5,000 per payment, and your bank sets a daily limit.",
        "Where's my data?" to "Only on this phone. No account, no analytics, no server. Splits don't sync to anyone.",
    )
    Page("Help", { vm.back() }) { qa.forEach { (q, a) -> Panel(Modifier.fillMaxWidth()) { Text(q, style = ZType.bodyBold, color = Zep.c.text); Fine(a, color = Zep.c.text.copy(alpha = 0.85f)) } } }
}

@Composable
fun LicensesScreen(vm: ZepViewModel) {
    Page("Licences", { vm.back() }) {
        Body("Bricolage Grotesque and Atkinson Hyperlegible Next: SIL Open Font License 1.1 (full text ships in the app).")
        Body("ZXing: Apache License 2.0. AndroidX and Jetpack Compose: Apache License 2.0.")
        Body("The stepwise *99# approach was studied in OffPay (github.com/laksh-ya/OffPayApp, MIT, © 2026 Lakshya and Harsh). ZepPay's code is its own; OffPay's PIN entry and success rules were deliberately not adopted.")
        Body("UPI is a trademark of NPCI. ZepPay isn't affiliated with NPCI or any bank.")
    }
}
