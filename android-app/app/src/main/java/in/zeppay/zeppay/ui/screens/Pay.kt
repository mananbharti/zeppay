@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package `in`.zeppay.zeppay.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import `in`.zeppay.zeppay.ui.theme.Shapes
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.zeppay.zeppay.Route
import `in`.zeppay.zeppay.ZepViewModel
import `in`.zeppay.zeppay.data.Lib
import `in`.zeppay.zeppay.data.MAX_AMOUNT
import `in`.zeppay.zeppay.data.Payee
import `in`.zeppay.zeppay.data.ROUTE_USSD
import `in`.zeppay.zeppay.data.STATUS_PENDING
import `in`.zeppay.zeppay.data.Tx
import `in`.zeppay.zeppay.ui.Screen
import `in`.zeppay.zeppay.ui.kit.*
import `in`.zeppay.zeppay.ui.theme.ZIcon
import `in`.zeppay.zeppay.ui.theme.ZType
import `in`.zeppay.zeppay.ui.theme.Zep
import `in`.zeppay.zeppay.update.UpdateState
import `in`.zeppay.zeppay.update.Updater
import `in`.zeppay.zeppay.ussd.Sims

/* ---------------- Pay tab ---------------- */

@Composable
fun PayTab(vm: ZepViewModel) {
    val c = Zep.c
    val history by vm.history.collectAsState()
    val update by Updater.state.collectAsState()
    val helper = helperOn()
    val route = vm.route(helper)
    val carrier = vm.carrier()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("zeppay", style = ZType.hero.copy(fontSize = 34.sp), color = c.text, modifier = Modifier.weight(1f))
            Row(Modifier.clip(Shapes.pill).background(c.secondary).pressable(label = "SIM and route settings") { vm.go(Screen.Settings) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (route == Route.IVR) c.unsure else c.paid))
                Spacer(Modifier.width(8.dp))
                Text("${carrier.label} · ${if (route == Route.IVR) "123PAY" else "*99#"}", style = ZType.bodyBold.copy(fontSize = 16.sp), color = c.onSecondary)
            }
        }
        // Hero: the signal dial, with the aurora behind it. Tap to scan.
        Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
            Aurora()
            SignalDial(size = 224.dp, onClick = { vm.go(Screen.Scan) }, label = "Scan a QR to pay") {
                Icon(ZIcon.Scan, null, tint = c.primary.let { if (c.dark) it else c.auroraA }, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(10.dp))
                Text("Scan to pay", style = ZType.title, color = c.text)
            }
        }
        Fine("Any UPI QR. No internet needed.", Modifier.fillMaxWidth(), align = androidx.compose.ui.text.style.TextAlign.Center)
        Kicker("Pay someone", Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Phone number", ZIcon.Phone, c.bubbleA, { vm.go(Screen.PayTo("mobile")) }, Modifier.weight(1f))
            Tile("UPI ID", ZIcon.At, c.bubbleB, { vm.go(Screen.PayTo("upi")) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Bank account", ZIcon.Bank, c.bubbleC, { vm.go(Screen.PayTo("bank")) }, Modifier.weight(1f))
            Tile("Balance", ZIcon.Wallet, c.bubbleD, { vm.go(Screen.Info(`in`.zeppay.zeppay.ussd.Op.BALANCE)) }, Modifier.weight(1f))
        }

        if (route == Route.MANUAL) Panel {
            Text("Let ZepPay fill in *99# for you", style = ZType.bodyBold, color = c.text)
            Fine("Without the ZepPay helper you type each step in the dialer yourself. Your PIN always stays with your bank.")
            Spacer(Modifier.height(12.dp))
            ActionButton("Turn on helper", { vm.go(Screen.Helper) }, Modifier.fillMaxWidth(), icon = ZIcon.Accessibility)
        }
        (update as? UpdateState.Available)?.let { u ->
            TileRow("Update ready", "ZepPay ${u.r.versionName}: see what's new", ZIcon.Update, { vm.go(Screen.Update) }, bubble = c.bubbleA)
        }
        if (history.isNotEmpty()) Panel(pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Kicker("Recent", Modifier.weight(1f))
                Text("See all", style = ZType.bodyBold, color = c.auroraA, modifier = Modifier.clip(Shapes.pill).pressable(label = "See all activity") { vm.go(Screen.Activity) }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            history.take(3).forEachIndexed { i, t ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                TxRow(t) { vm.go(Screen.Receipt(t.id)) }
            }
        }
        TileRow("New to *99#?", "Link your bank once, step by step", ZIcon.Shield, { vm.go(Screen.BankSetup) }, bubble = c.bubbleC)
        Fine("UPI is a trademark of NPCI. ZepPay is an independent app, not a bank or payment service. Your bank completes every payment.")
        Spacer(Modifier.height(12.dp))
    }
}

/** One recorded payment as a list row. */
@Composable
fun TxRow(t: Tx, onClick: () -> Unit) {
    val c = Zep.c
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).pressable(label = "${t.payee.label}, ${money(t.amount)}", onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(t.payee.label, 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.payee.label, style = ZType.bodyBold, color = c.text, maxLines = 1)
            Text(whenLabel(t.at), style = ZType.small, color = c.muted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(money(t.amount), style = ZType.label, color = c.text)
            Spacer(Modifier.height(4.dp))
            StatusPill(t.status)
        }
    }
}

/** A recorded payment on its own (Activity list): the row inside a card. */
@Composable
fun TxTicket(t: Tx, onClick: () -> Unit) = Panel(Modifier.fillMaxWidth(), pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 2.dp)) { TxRow(t, onClick) }

/* ---------------- Payee ---------------- */

private class PickPhone : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit) = Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)
    override fun parseResult(resultCode: Int, intent: Intent?) = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** Reads the one number the user picked. No contacts permission: the picker grants just that row. */
private fun readPicked(ctx: Context, uri: Uri): Pair<String, String>? = try {
    ctx.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)?.use { cur ->
        if (cur.moveToFirst()) cur.getString(0).orEmpty() to cur.getString(1).orEmpty() else null
    }
} catch (_: Exception) { null }

@Composable
fun PayToScreen(vm: ZepViewModel, kind: String, splitRef: String) {
    val ctx = LocalContext.current
    var v1 by rememberSaveable { mutableStateOf("") }
    var v2 by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    var finder by rememberSaveable { mutableStateOf(false) }
    val isIvr = vm.route(helperOn()) == Route.IVR
    val ok = when (kind) { "upi" -> Lib.isValidVpa(v1); "mobile" -> Lib.isValidMobile(v1); else -> Lib.isValidAccount(v1) && Lib.isValidIfsc(v2) }
    val picker = rememberLauncherForActivityResult(PickPhone()) { uri ->
        uri?.let { readPicked(ctx, it) }?.let { (num, n) ->
            if (Lib.isValidMobile(num)) { v1 = Lib.normMobile(num); if (name.isEmpty()) name = n } else vm.showToast("That contact has no Indian mobile number")
        }
    }
    fun next() {
        tried = true
        if (!ok) return
        val p = when (kind) {
            "upi" -> Payee(vpa = v1.trim(), name = name.trim())
            "mobile" -> Payee(mobile = Lib.normMobile(v1), name = name.trim())
            else -> Payee(account = Lib.normAccount(v1), ifsc = Lib.normIfsc(v2), name = name.trim())
        }
        vm.replace(Screen.Amount(p, splitRef = splitRef))
    }
    val title = when (kind) { "upi" -> "Pay a UPI ID"; "mobile" -> "Pay a phone number"; else -> "Pay a bank account" }
    Page(title, { vm.back() }, footer = { ActionButton("Next", ::next, Modifier.fillMaxWidth(), icon = ZIcon.Arrow, enabled = v1.isNotBlank()) }) {
        when (kind) {
            "upi" -> Field(v1, { v1 = it }, "Their UPI ID", placeholder = "name@bank", keyboard = KeyboardType.Email, big = true, onDone = ::next,
                error = if (tried && !ok) "That doesn't look like a UPI ID. It should look like name@bank." else null)
            "mobile" -> {
                Field(v1, { v1 = it.filter { ch -> ch.isDigit() || ch == '+' || ch == ' ' } }, "Their phone number", placeholder = "10 digits", keyboard = KeyboardType.Phone, big = true, onDone = ::next,
                    error = if (tried && !ok) "Enter a 10-digit Indian mobile number." else null)
                PlainButton("Pick from contacts", { picker.launch(Unit) }, Modifier.fillMaxWidth(), icon = ZIcon.Contacts)
            }
            else -> {
                Field(v1, { v1 = it.filter(Char::isDigit) }, "Account number", placeholder = "9 to 18 digits", keyboard = KeyboardType.Number, big = true,
                    error = if (tried && !Lib.isValidAccount(v1)) "Account numbers are 9 to 18 digits." else null)
                Field(v2, { v2 = it.uppercase().filterNot(Char::isWhitespace).take(11) }, "IFSC", placeholder = "e.g. SBIN0001234", caps = KeyboardCapitalization.Characters, onDone = ::next,
                    error = if (tried && !Lib.isValidIfsc(v2)) `in`.zeppay.zeppay.data.Ifsc.problem(v2) else null)
                IfscNote(v2)
                if (finder) IfscFinder({ v2 = it; finder = false }, { finder = false })
                else PlainButton("Find IFSC by bank and branch", { finder = true }, Modifier.fillMaxWidth(), icon = ZIcon.Bank)
            }
        }
        Field(name, { name = it.take(40) }, "Their name (optional)", placeholder = "Only for your records")
        if (isIvr && kind == "upi") Panel(color = Zep.c.unsure.copy(alpha = 0.25f)) {
            Text("UPI IDs can't be paid on the 123PAY call", style = ZType.bodyBold, color = Zep.c.text)
            Fine("The call takes number keys only. Pay their phone number or bank account instead, or pick a *99# SIM in Settings.")
        }
    }
}

/* ---------------- Amount ---------------- */

@Composable
fun AmountScreen(vm: ZepViewModel, s: Screen.Amount) {
    val c = Zep.c
    var amount by rememberSaveable { mutableStateOf(s.amount) }
    var note by rememberSaveable { mutableStateOf(s.note) }
    val err = Lib.validateAmount(amount)
    Page("How much?", { vm.back() }, footer = {
        ActionButton(if (err.isEmpty()) "Check ${money(amount.toDouble())}" else "Check the amount", {
            if (err.isEmpty()) vm.replace(Screen.Confirm(Tx(vm.newId(), s.payee, amount.toDouble(), note.trim(), STATUS_PENDING, System.currentTimeMillis(), ROUTE_USSD, splitRef = s.splitRef)))
        }, Modifier.fillMaxWidth(), enabled = err.isEmpty(), icon = ZIcon.Arrow)
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(s.payee.label, 52.dp); Spacer(Modifier.width(12.dp))
            Column { Text(s.payee.name.ifEmpty { "Paying" }, style = ZType.bodyBold, color = c.text); Fine(s.payee.vpa.ifEmpty { s.payee.mobile.ifEmpty { "A/c ${s.payee.account}" } }) }
        }
        PayCard(Modifier.fillMaxWidth(), top = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("₹", style = ZType.amount, color = c.primary)
                BasicTextField(
                    amount, { v -> if (!s.lockAmount) { val f = v.filter { it.isDigit() || it == '.' }; if (f.count { it == '.' } < 2 && f.length <= 8) amount = f } },
                    textStyle = ZType.amount.copy(color = c.text), singleLine = true, cursorBrush = SolidColor(c.primary), readOnly = s.lockAmount,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Amount in rupees" },
                    decorationBox = { inner -> Box { if (amount.isEmpty()) Text("0", style = ZType.amount, color = c.text.copy(alpha = 0.35f)); inner() } },
                )
            }
            Text("*99# allows ₹1 to ${money(MAX_AMOUNT)} per payment", style = ZType.small, color = c.text.copy(alpha = 0.8f))
        })
        if (amount.isNotEmpty() && err.isNotEmpty()) Text(err, style = ZType.bodyBold, color = c.failed)
        if (!s.lockAmount) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(50, 100, 200, 500, 1000).forEach { v -> Chip("₹$v", amount == v.toString()) { amount = v.toString() } }
        }
        Field(note, { note = it.take(40) }, "Note (optional)", placeholder = "e.g. Chai, rent")
    }
}

/* ---------------- Confirm ---------------- */

@Composable
fun ConfirmScreen(vm: ZepViewModel, tx: Tx) {
    val c = Zep.c
    val ctx = LocalContext.current
    val helper = helperOn()
    val overlay = overlayOn()
    val route = vm.route(helper)
    var sure by rememberSaveable { mutableStateOf(false) }
    val onCall = remember(resumeTick()) { Sims.onCall(ctx) }
    val sim = vm.chosenSim()
    val ivrBlocked = route == Route.IVR && tx.payee.vpa.isNotEmpty()
    val callPerm = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { if (!it) vm.showToast("ZepPay needs Phone permission to open *99# for you") }

    fun payNow() {
        `in`.zeppay.zeppay.LiveInfo.payeeLine = "${tx.payee.label}, ${money(tx.amount)}"
        `in`.zeppay.zeppay.LiveInfo.what = "${money(tx.amount)} to ${tx.payee.label}"
        when (route) {
            Route.STEPWISE -> when (vm.startStepwise(tx)) {
                `in`.zeppay.zeppay.ussd.Engine.StartResult.Started -> vm.replace(Screen.Live(tx.id))
                `in`.zeppay.zeppay.ussd.Engine.StartResult.NeedsCallPermission -> callPerm.launch(android.Manifest.permission.CALL_PHONE)
                `in`.zeppay.zeppay.ussd.Engine.StartResult.NeedsAccessibility -> vm.go(Screen.Helper)
                `in`.zeppay.zeppay.ussd.Engine.StartResult.Busy -> vm.showToast("A *99# session is already running. Wait a moment.")
            }
            Route.MANUAL -> vm.startManual(tx)
            Route.IVR -> {
                val plan = `in`.zeppay.zeppay.data.Dtmf.pay(tx.payee, tx.amount, vm.store.ivrLang,
                    `in`.zeppay.zeppay.data.Dtmf.Delays(vm.store.dtmfAfterConnect, vm.store.dtmfBetweenMenu, vm.store.dtmfBeforeConfirm))
                if (plan.possible) vm.startIvr(tx, if (vm.store.dtmfAssist) plan.dialString else `in`.zeppay.zeppay.data.IVR_123PAY_PRIMARY)
            }
        }
    }

    // Just in time: the status notification needs this on Android 13+. Asked once; paying goes ahead either way.
    val notifPerm = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { payNow() }
    fun pay() {
        val p = android.Manifest.permission.POST_NOTIFICATIONS
        if (route == Route.STEPWISE && android.os.Build.VERSION.SDK_INT >= 33 && p !in vm.store.askedPermissions &&
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            vm.store.askedPermissions = vm.store.askedPermissions + p
            notifPerm.launch(p); return
        }
        payNow()
    }


    Page("Check and pay", { vm.back() }, footer = {
        ActionButton("Pay ${money(tx.amount)}", ::pay, Modifier.fillMaxWidth().height(72.dp), enabled = sure && !ivrBlocked, icon = ZIcon.Signal)
    }) {
        PayCard(Modifier.fillMaxWidth(), top = {
            Kicker("You're paying", color = c.text.copy(alpha = 0.8f))
            Text(money(tx.amount), style = ZType.amount, color = c.text)
            Text("to ${tx.payee.label}", style = ZType.label, color = c.text)
            Text(payeeLine(tx), style = ZType.small, color = c.text.copy(alpha = 0.85f))
            if (tx.note.isNotEmpty()) Text("Note: ${tx.note}", style = ZType.small, color = c.text.copy(alpha = 0.85f))
        }, bottom = {
            Text(when (route) {
                Route.STEPWISE -> "Over *99# on ${sim?.let { "your ${it.label} SIM" } ?: "your SIM"}. ZepPay fills in the details, then you type your UPI PIN on the bank's screen."
                Route.MANUAL -> "Over *99#. ZepPay opens the dialer with ${when { tx.payee.vpa.isNotEmpty() -> "*99*1*3#"; tx.payee.mobile.isNotEmpty() -> "*99*1*1#"; else -> "*99*1*5#" }}. You press Call and answer each step yourself."
                Route.IVR -> "Over the UPI 123PAY call (Jio has no *99#). You confirm and type your PIN on the call."
            }, style = ZType.small, color = c.text)
        })
        if (ivrBlocked) Panel(color = c.failed.copy(alpha = 0.2f)) {
            Text("This can't be paid on a 123PAY call", style = ZType.bodyBold, color = c.text)
            Fine("The call takes number keys only, and a UPI ID has letters. Go back and pay their phone number or bank account.")
        }
        if (route == Route.STEPWISE && vm.store.overlayCard && !overlay) Panel(color = c.unsure.copy(alpha = 0.25f)) {
            Text("Progress card can't show", style = ZType.bodyBold, color = c.text)
            Fine("You can still pay; the notification shows progress. To see the card above the bank's screen, allow \"display over other apps\".")
        }
        if (onCall) Panel(color = c.unsure.copy(alpha = 0.25f)) {
            Text("You're on a call", style = ZType.bodyBold, color = c.text)
            Fine("Some phones can't run ${if (route == Route.IVR) "a second call" else "*99#"} during a call. This isn't tested on your phone yet. ZepPay will never hold or end your call. If nothing happens, try again after the call.")
        }
        Row(Modifier.fillMaxWidth().pressable(role = androidx.compose.ui.semantics.Role.Checkbox) { sure = !sure }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(34.dp).height(34.dp).then(Modifier), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(if (sure) c.primary else c.tile).border(2.dp, if (sure) c.primary else c.muted, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)))
                if (sure) Icon(ZIcon.Check, null, tint = c.onPrimary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text("I've checked the name, number and amount.", style = ZType.bodyBold, color = c.text, modifier = Modifier.weight(1f))
        }
        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(ZIcon.Shield, null, tint = c.paid); Spacer(Modifier.width(10.dp))
                Text("Your PIN stays with your bank", style = ZType.bodyBold, color = c.text)
            }
            Fine("ZepPay never asks for, sees or saves your UPI PIN. You type it only on the bank's own screen${if (route == Route.IVR) " or the call keypad" else ""}.")
        }
        Text("Payee: ${payeeLine(tx)}", style = ZType.small, color = c.muted, textAlign = TextAlign.Start)
    }
}
