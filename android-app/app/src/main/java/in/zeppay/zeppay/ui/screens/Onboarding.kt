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
 * First run (and the upgrade for existing users: history and settings are kept).
 * Two intro pages, then the setup walkthrough (one permission per screen, each optional and
 * explained), then the bank question. The overlay card and update installs are asked for
 * just in time instead, when the user turns the card on or installs an update.
 */
@Composable
fun OnboardingFlow(vm: ZepViewModel) {
    val c = Zep.c
    var stage by rememberSaveable { mutableIntStateOf(0) }
    val upgrading = vm.store.onboarded // a v1/v2 user seeing the new setup
    when (stage) {
        0, 1 -> {
            val (title, body) = if (stage == 0)
                (if (upgrading) "ZepPay got a new look" else "Pay without internet") to
                    (if (upgrading) "Your payments and settings are all still here. A few quick steps turn on what's new." else "ZepPay pays any UPI QR, phone number or bank account through *99# on your SIM. No mobile data, no Wi-Fi.")
            else "Your PIN stays with your bank" to "ZepPay never asks for, sees or saves your UPI PIN. You type it only on your bank's own screen, or the call keypad on Jio."
            Page("", if (stage > 0) ({ stage-- }) else null, footer = {
                ActionButton(if (stage == 0) "Let's start" else "Got it", { stage++ }, Modifier.fillMaxWidth(), icon = ZIcon.Arrow)
            }) {
                Stepper(3, stage)
                Text(title, style = ZType.hero, color = c.text)
                Body(body)
            }
        }
        2 -> SetupWalkthrough(vm, null, onBack = { stage = 1 }) { stage = 3 }
        else -> Page("", { stage = 1 }, footer = {
            ActionButton("Yes, start using ZepPay", { vm.finishSetup() }, Modifier.fillMaxWidth(), icon = ZIcon.Arrow)
            PlainButton("Not yet: show me later", { vm.finishSetup() }, Modifier.fillMaxWidth())
        }) {
            Stepper(3, 2)
            Text("Is your bank linked to *99#?", style = ZType.hero, color = c.text)
            Body("You do this once per SIM, on the bank's own screen. ZepPay can walk you through it later from the Pay screen.")
            Fine("You can reopen this setup any time from More → Settings.")
        }
    }
}
