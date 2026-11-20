package `in`.zeppay.zeppay.ui.screens

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import `in`.zeppay.zeppay.ZepViewModel
import `in`.zeppay.zeppay.overlay.HandoffOverlay
import `in`.zeppay.zeppay.ui.kit.*
import `in`.zeppay.zeppay.ui.theme.ZIcon
import `in`.zeppay.zeppay.ui.theme.ZType
import `in`.zeppay.zeppay.ui.theme.Zep
import `in`.zeppay.zeppay.update.Updater
import `in`.zeppay.zeppay.ussd.Sims
import `in`.zeppay.zeppay.ussd.Ussd

/**
 * First run (and the v2 upgrade for existing users: history and settings are kept).
 * One action per screen; every permission is optional except where a payment needs it,
 * and each says plainly what it is for.
 */
@Composable
fun OnboardingFlow(vm: ZepViewModel) {
    val ctx = LocalContext.current
    val c = Zep.c
    var i by rememberSaveable { mutableIntStateOf(0) }
    val sims by vm.sims.collectAsState()
    val helper = helperOn(); val overlay = overlayOn(); resumeTick()
    val phoneState = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshSims(); i++ }
    val call = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { i++ }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { i++ }
    val canInstall = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()
    val upgrading = vm.store.onboarded // v1 user seeing the new setup

    data class Step(val title: String, val body: String, val action: String?, val run: (() -> Unit)?, val skip: String = "Not now", val done: Boolean = false)
    val steps = listOf(
        Step(if (upgrading) "ZepPay got a new look" else "Pay without internet", if (upgrading) "Your payments and settings are all still here. A few quick steps turn on what's new." else "ZepPay pays any UPI QR, phone number or bank account through *99# on your SIM. No mobile data, no Wi-Fi.", "Let's start", { i++ }, skip = ""),
        Step("Your PIN stays with your bank", "ZepPay never asks for, sees or saves your UPI PIN. You type it only on your bank's own screen, or the call keypad on Jio.", "Got it", { i++ }, skip = ""),
        Step("Which SIMs do you have?", "So ZepPay uses the right SIM: *99# on Airtel, Vi or BSNL, and the UPI 123PAY call on Jio. It only reads carrier names.",
            "Show my SIMs", { phoneState.launch(android.Manifest.permission.READ_PHONE_STATE) }, done = Sims.hasPhoneState(ctx)),
        Step("Let ZepPay open *99#", "Needed to start *99# or the 123PAY call for you when you tap Pay. ZepPay never calls anyone else and never holds or ends your calls.",
            "Allow", { call.launch(android.Manifest.permission.CALL_PHONE) }, done = Ussd.hasCallPermission(ctx)),
        Step("Turn on the ZepPay helper", "It fills in the payee, amount and note on the *99# screen. It can't see what you type there, so it can't see your PIN. Without it, you type each step yourself.",
            "Open Accessibility", { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, skip = "I'll type steps myself", done = helper),
        Step("Show progress over the bank's screen", "A small ZepPay sign at the top tells you what's happening. It never covers the bank's screen. Optional.",
            "Allow", { HandoffOverlay.openPermission(ctx) }, done = overlay),
        Step("Updates inside the app", "When a new ZepPay is ready, you tap Update and Android asks you to confirm. No more hunting for APK files. Allow ZepPay to install its own updates (once):",
            "Allow updates", { Updater.openInstallPermission(ctx) }, done = canInstall),
        Step("Notifications", "For \"update ready\" and \"did your payment go through?\" reminders. Optional.", "Allow",
            { if (Build.VERSION.SDK_INT >= 33) notif.launch(android.Manifest.permission.POST_NOTIFICATIONS) else i++ }, done = Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED),
        Step("Is your bank linked to *99#?", "You do this once per SIM, on the bank's own screen. ZepPay can walk you through it later from the Pay screen.", "Yes, start using ZepPay", { vm.finishSetup() }, skip = "Not yet: show me later"),
    )
    val s = steps[i.coerceAtMost(steps.lastIndex)]
    Page("", if (i > 0) ({ i-- }) else null, footer = {
        if (s.done) ActionButton("Next", { i++ }, Modifier.fillMaxWidth(), icon = ZIcon.Arrow)
        else s.action?.let { a -> ActionButton(a, { s.run?.invoke() }, Modifier.fillMaxWidth(), icon = ZIcon.Arrow) }
        if (!s.done && s.skip.isNotEmpty()) PlainButton(s.skip, { if (i == steps.lastIndex) vm.finishSetup() else i++ }, Modifier.fillMaxWidth())
    }) {
        Stepper(steps.size, i)
        Text(s.title, style = ZType.hero, color = c.text)
        Body(s.body)
        if (s.done) DoneTag()
        if (i == 2 && sims.isNotEmpty()) sims.forEach { Fine("SIM ${it.slot + 1}: ${it.label}${if (it.carrier?.ussdOk == false) " (123PAY call)" else ""}") }
        if (i == 4 && !helper && Build.VERSION.SDK_INT >= 33) Panel {
            Text("Says \"Restricted setting\"?", style = ZType.bodyBold, color = c.text)
            Fine("Open App info for ZepPay, tap ⋮ at the top right, then \"Allow restricted settings\". Come back and try again. Wording differs by phone.", color = c.text.copy(alpha = 0.85f))
            PlainButton("Open App info", { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, Modifier.fillMaxWidth())
        }
    }
}
