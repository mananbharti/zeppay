package `in`.zeppay.zeppay.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import `in`.zeppay.zeppay.ZepViewModel
import `in`.zeppay.zeppay.data.Setup
import `in`.zeppay.zeppay.data.SetupEnv
import `in`.zeppay.zeppay.data.SetupProgress
import `in`.zeppay.zeppay.data.SetupStep
import `in`.zeppay.zeppay.guide.Guide
import `in`.zeppay.zeppay.overlay.HandoffOverlay
import `in`.zeppay.zeppay.ui.kit.*
import `in`.zeppay.zeppay.ui.theme.ZIcon
import `in`.zeppay.zeppay.ui.theme.ZType
import `in`.zeppay.zeppay.ui.theme.Zep
import `in`.zeppay.zeppay.ussd.UssdService
import kotlinx.coroutines.delay

/**
 * Installed from an APK file (a download or a local file): Android 13+ then blocks the helper
 * switch until "Allow restricted settings". Store and adb installs aren't restricted.
 */
fun sideloaded(c: Context): Boolean = try {
    if (Build.VERSION.SDK_INT >= 33) c.packageManager.getInstallSourceInfo(c.packageName).packageSource.let {
        it == android.content.pm.PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE || it == android.content.pm.PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE ||
            it == android.content.pm.PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED
    }
    else @Suppress("DEPRECATION") c.packageManager.getInstallerPackageName(c.packageName) != "com.android.vending"
} catch (_: Exception) { true }

private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

fun setupEnv(c: Context, overlayCard: Boolean, installingUpdate: Boolean) = SetupEnv(
    sdk = Build.VERSION.SDK_INT,
    callGranted = granted(c, Manifest.permission.CALL_PHONE),
    helperOn = UssdService.isEnabled(c),
    sideloaded = sideloaded(c),
    overlayGranted = HandoffOverlay.canShow(c),
    overlayCardOn = overlayCard,
    notificationsGranted = Build.VERSION.SDK_INT < 33 || granted(c, Manifest.permission.POST_NOTIFICATIONS),
    installingUpdate = installingUpdate,
    installGranted = c.packageManager.canRequestPackageInstalls(),
)

object SetupLinks {
    fun appInfo(c: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))

    /**
     * The Accessibility list. (A direct link to ZepPay's own switch exists, but on Android 15 it
     * needs the system-only OPEN_ACCESSIBILITY_DETAILS_SETTINGS permission: checked on the emulator.)
     */
    fun accessibility(@Suppress("UNUSED_PARAMETER") c: Context) = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun overlay(c: Context) = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}"))
    fun notifications(c: Context) = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
    fun install(c: Context) = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))

    fun open(c: Context, i: Intent) = try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {
        c.startActivity(appInfo(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private data class Copy(val title: String, val why: String, val button: String, val hint: String? = null, val skip: String = "Not now")

private fun copy(step: SetupStep): Copy = when (step) {
    SetupStep.PHONE -> Copy("Let ZepPay start *99#",
        "So tapping Pay opens *99# (or the 123PAY call on Jio) on the right SIM. ZepPay never calls anyone else and never holds or ends your calls.",
        "Allow", skip = "Not now: I'll press Call myself")
    SetupStep.HELPER -> Copy("Turn on the ZepPay helper",
        "It fills in the payee, amount and note on the *99# screen. It never reads what you type there, so it can't see your PIN. Without it, you type each step yourself.",
        "Open Accessibility", hint = "Find ZepPay (under Downloaded apps on some phones), open it and switch it on.", skip = "I'll type the steps myself")
    SetupStep.RESTRICTED -> Copy("Android blocked the switch? Allow it once",
        "Android 13 and newer block this switch for apps installed from a file. Allow it once in ZepPay's App info, then turn the helper on again.",
        "Open App info", hint = "In App info, tap ⋮ at the top right, then \"Allow restricted settings\" and confirm. The wording differs by phone.", skip = "I'll type the steps myself")
    SetupStep.OVERLAY -> Copy("Show the progress card",
        "A small card above the bank's screen shows what ZepPay is doing. It never covers the bank's screen. You can turn it off in Settings.",
        "Open the setting", hint = "Android shows a list of apps: tap ZepPay, then switch on \"Allow display over other apps\".")
    SetupStep.NOTIFICATIONS -> Copy("Payment status in your notifications",
        "While *99# runs, a notification shows its progress and a Cancel button. On your lock screen it hides the payee and amount.",
        "Allow")
    SetupStep.INSTALL -> Copy("Let ZepPay install its update",
        "Android asks once before an app can install updates. You still confirm every update yourself.",
        "Open the setting", hint = "Switch on \"Allow from this source\" for ZepPay.")
}

/**
 * The setup walkthrough: one step per screen, a line on what and why, a button to the exact
 * system screen, "Watch how" (floating guide clip), and auto-detection when you come back.
 * [only] shows just that step (just-in-time: overlay card, update install).
 */
@Composable
fun SetupWalkthrough(vm: ZepViewModel, only: SetupStep? = null, onBack: (() -> Unit)?, onFinished: () -> Unit) {
    val ctx = LocalContext.current
    val c = Zep.c
    val tick = resumeTick()
    var progress by remember { mutableStateOf(Setup.decode(vm.store.setupSkipped, vm.store.helperAttempted)) }
    fun save(p: SetupProgress) { progress = p; vm.store.setupSkipped = Setup.encode(p); vm.store.helperAttempted = p.helperAttempted }
    val env = remember(tick, progress) { setupEnv(ctx, vm.store.overlayCard, installingUpdate = only == SetupStep.INSTALL) }
    val next = if (only != null) Setup.single(only, env) else Setup.current(env, progress)

    // Show "Done" on a step for a moment after it is detected, then move on.
    var shown by rememberSaveable { mutableStateOf<String?>(null) }
    val step = shown?.let { SetupStep.byId(it) }
    LaunchedEffect(next, env) {
        if (step != null && step != next && Setup.done(step, env)) { Guide.close(); delay(900) }
        shown = next?.id
        if (next == null) onFinished()
    }
    var inline by rememberSaveable { mutableStateOf(false) }
    val pipFailed by Guide.pipFailed.collectAsState()
    // A new step: the previous step's floating clip no longer applies.
    LaunchedEffect(shown) { Guide.close(); inline = false; Guide.pipFailed.value = false }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshSims() }
    if (step == null) return
    val isDone = Setup.done(step, env)
    val cp = copy(step)

    fun asked(p: String) = p in vm.store.askedPermissions
    fun markAsked(vararg p: String) { vm.store.askedPermissions = vm.store.askedPermissions + p }
    /** Asked before and Android won't show the dialog again: only App info can change it. */
    fun blocked(p: String) = asked(p) && (ctx as? Activity)?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, p) } == true

    /** The system screen for this step (null for a runtime dialog that appears inside ZepPay). */
    fun target(): Intent? = when (step) {
        SetupStep.PHONE -> if (blocked(Manifest.permission.CALL_PHONE)) SetupLinks.appInfo(ctx) else null
        SetupStep.HELPER -> SetupLinks.accessibility(ctx)
        SetupStep.RESTRICTED -> SetupLinks.appInfo(ctx)
        SetupStep.OVERLAY -> SetupLinks.overlay(ctx)
        SetupStep.NOTIFICATIONS -> if (blocked(Manifest.permission.POST_NOTIFICATIONS)) SetupLinks.notifications(ctx) else null
        SetupStep.INSTALL -> SetupLinks.install(ctx)
    }

    fun act() {
        if (step == SetupStep.HELPER || step == SetupStep.RESTRICTED) save(Setup.triedHelper(progress))
        target()?.let { SetupLinks.open(ctx, it); return }
        when (step) {
            SetupStep.PHONE -> { markAsked(Manifest.permission.CALL_PHONE); perms.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)) }
            SetupStep.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) { markAsked(Manifest.permission.POST_NOTIFICATIONS); perms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
            else -> Unit
        }
    }

    fun watch() {
        if (step == SetupStep.HELPER || step == SetupStep.RESTRICTED) save(Setup.triedHelper(progress))
        if (!Guide.watch(ctx, step, target())) inline = true
    }

    val (pos, total) = if (only != null) 0 to 1 else Setup.position(step, env, progress)
    Page("", onBack, footer = {
        if (isDone) DoneTag()
        else {
            ActionButton(cp.button, ::act, Modifier.fillMaxWidth(), icon = ZIcon.Arrow)
            if (step == SetupStep.RESTRICTED) PlainButton("Turn the helper on again", { SetupLinks.open(ctx, SetupLinks.accessibility(ctx)) }, Modifier.fillMaxWidth(), icon = ZIcon.Accessibility)
            if (step.optional) PlainButton(cp.skip, { if (only != null) onFinished() else save(Setup.skip(step, progress)) }, Modifier.fillMaxWidth())
        }
    }) {
        if (total > 1) Stepper(total, pos)
        Text(cp.title, style = ZType.hero, color = c.text)
        Body(cp.why)
        cp.hint?.let { Fine(it) }
        if (!isDone) PlainButton(if (inline || pipFailed) "Watch again" else "Watch how", ::watch, Modifier.fillMaxWidth(), icon = ZIcon.Image)
        if ((inline || pipFailed) && !isDone) Panel(Modifier.fillMaxWidth()) {
            GuidePlayer(step)
            Fine("Picture-in-picture isn't available, so the guide plays here. Watch it, then tap \"${cp.button}\".")
        }
        if (step == SetupStep.HELPER || step == SetupStep.RESTRICTED) Panel {
            Text("What the helper can see", style = ZType.bodyBold, color = c.text)
            Fine("Only the *99# screen from your phone app, and only while a ZepPay session runs. Never what you type there, so never your PIN.", color = c.text.copy(alpha = 0.85f))
        }
    }
}

/** Inline fallback player: muted, looping, offline. */
@Composable
fun GuidePlayer(step: SetupStep) {
    val ctx = LocalContext.current
    AndroidView(
        factory = { VideoView(it).apply {
            setOnPreparedListener { mp -> mp.isLooping = true; mp.setVolume(0f, 0f); start() }
            setVideoURI(Guide.uri(ctx, Guide.clip(step)))
            contentDescription = "Guide video: ${copy(step).title}"
        } },
        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).aspectRatio(9f / 16f),
    )
}

