package `in`.zeppay.zeppay.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.zeppay.zeppay.PaymentBook
import `in`.zeppay.zeppay.Route
import `in`.zeppay.zeppay.ZepViewModel
import `in`.zeppay.zeppay.data.BY_BANK_REPLY
import `in`.zeppay.zeppay.data.Dtmf
import `in`.zeppay.zeppay.data.IVR_123PAY_PRIMARY
import `in`.zeppay.zeppay.data.ROUTE_IVR
import `in`.zeppay.zeppay.data.ROUTE_MANUAL
import `in`.zeppay.zeppay.data.STATUS_FAILED
import `in`.zeppay.zeppay.data.STATUS_PENDING
import `in`.zeppay.zeppay.data.STATUS_STOPPED
import `in`.zeppay.zeppay.data.STATUS_SUCCESS
import `in`.zeppay.zeppay.data.STATUS_UNKNOWN
import `in`.zeppay.zeppay.data.Tx
import `in`.zeppay.zeppay.ui.Screen
import `in`.zeppay.zeppay.ui.kit.*
import `in`.zeppay.zeppay.ui.theme.ZIcon
import `in`.zeppay.zeppay.ui.theme.ZType
import `in`.zeppay.zeppay.ui.theme.Zep
import `in`.zeppay.zeppay.ussd.Engine
import `in`.zeppay.zeppay.ussd.Op
import `in`.zeppay.zeppay.ussd.OutcomeKind
import `in`.zeppay.zeppay.ussd.Phase
import `in`.zeppay.zeppay.ussd.Ussd

/* ---------------- Live *99# payment ---------------- */

@Composable
fun LiveScreen(vm: ZepViewModel, txId: String?) {
    val c = Zep.c
    val session by Engine.session.collectAsState()
    val history by vm.history.collectAsState()
    val s = session?.takeIf { it.txId == txId }
    val phase = s?.phase
    LaunchedEffect(phase) {
        if (phase is Phase.Done && txId != null) { PaymentBook.record(vm.store, txId, phase.outcome); vm.reload() }
    }
    val tx = history.firstOrNull { it.id == txId }
    if (tx == null) { Page("Payment", { vm.home() }) { Body("This payment isn't on this phone any more.") }; return }

    // Session gone (app restarted) or finished: show what was recorded.
    if (phase == null || phase is Phase.Done) {
        val raw = (phase as? Phase.Done)?.outcome?.raw ?: tx.raw
        val reason = (phase as? Phase.Done)?.outcome?.reason
        OutcomeView(vm, tx, raw, reason)
        return
    }
    Page("Paying ${money(tx.amount)}", null, footer = {
        val beforePin = !(phase is Phase.PinHandoff || (phase is Phase.NeedsUser && s.frames.any { Regex("(?i)\\bpin\\b").containsMatchIn(it) }))
        PlainButton(if (beforePin) "Stop. Nothing is paid yet" else "Stop watching", { Engine.stop() }, Modifier.fillMaxWidth(), icon = ZIcon.Close)
    }) {
        Text("to ${tx.payee.label}", style = ZType.label, color = c.text)
        AnimatedContent(phase::class, label = "phase") { _ ->
            when (phase) {
                is Phase.PinHandoff -> BankCounter(Modifier.fillMaxWidth()) {
                    Kicker("Your turn", color = c.bank)
                    Text("Type your UPI PIN on the bank's screen", style = ZType.title, color = c.text)
                    Spacer(Modifier.height(6.dp))
                    Body("ZepPay has stepped aside. It can't see your PIN and isn't typing anything. When your bank replies, ZepPay reads the result.")
                }
                is Phase.NeedsUser -> BankCounter(Modifier.fillMaxWidth()) {
                    Kicker("Your bank is asking", color = c.bank)
                    Text(phase.prompt.take(240), style = ZType.mono, color = c.text)
                    Spacer(Modifier.height(8.dp))
                    Body("ZepPay doesn't know this step, so it won't guess. Answer it on the bank's screen. ZepPay keeps watching for the result.")
                }
                else -> Panel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Working()
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(if (phase is Phase.Working) phase.label else "Opening *99#…", style = ZType.label, color = c.text)
                            Fine("Please don't type yet. ZepPay is filling in the details.")
                        }
                    }
                }
            }
        }
        Steps(phase)
        Fine("Your bank completes the payment. ZepPay only fills in the payee, amount and note.")
    }
}

@Composable
private fun Steps(phase: Phase) {
    val c = Zep.c
    val idx = when (phase) { Phase.Dialing -> 0; is Phase.Working -> 1; is Phase.NeedsUser -> 1; Phase.PinHandoff -> 2; else -> 3 }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("Open *99# on your SIM", "Fill in payee, amount, note", "You type your UPI PIN", "Read the bank's reply").forEachIndexed { i, label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(when { i < idx -> c.paid; i == idx -> c.primary; else -> c.card }), contentAlignment = Alignment.Center) {
                    if (i < idx) Icon(ZIcon.Check, null, tint = c.bg, modifier = Modifier.size(18.dp))
                    else Text("${i + 1}", style = ZType.bodyBold, color = c.onPrimary)
                }
                Spacer(Modifier.width(12.dp))
                Text(label, style = if (i == idx) ZType.bodyBold else ZType.body, color = if (i <= idx) c.text else c.muted)
            }
        }
    }
}

/** The honest outcome. Paid only if the bank's payment reply said so; otherwise say exactly what we know. */
@Composable
fun OutcomeView(vm: ZepViewModel, tx: Tx, raw: String, reason: String?) {
    val c = Zep.c
    Page("Result", null, footer = {
        when (tx.status) {
            STATUS_SUCCESS -> ActionButton("Done", { vm.home("pay") }, Modifier.fillMaxWidth(), icon = ZIcon.Check)
            STATUS_UNKNOWN, STATUS_PENDING -> {
                PlainButton("My bank SMS says paid", { vm.userSays(tx, STATUS_SUCCESS) }, Modifier.fillMaxWidth(), icon = ZIcon.Check)
                PlainButton("My bank SMS says failed", { vm.userSays(tx, STATUS_FAILED) }, Modifier.fillMaxWidth(), icon = ZIcon.Cross)
                ActionButton("I'll check later", { vm.home("pay") }, Modifier.fillMaxWidth())
            }
            else -> {
                ActionButton("Done", { vm.home("pay") }, Modifier.fillMaxWidth())
                PlainButton("Try again", { vm.go(Screen.Confirm(tx.copy(id = vm.newId(), status = STATUS_PENDING, raw = "", decidedBy = ""))) }, Modifier.fillMaxWidth(), icon = ZIcon.Arrow)
            }
        }
    }) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { ResultSign(signFor(tx.status)) }
        Text(money(tx.amount), style = ZType.amount, color = c.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text("to ${tx.payee.label}", style = ZType.label, color = c.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Body(reason ?: statusExplainer(tx), Modifier.fillMaxWidth(), align = TextAlign.Center)
        when (tx.status) {
            STATUS_UNKNOWN, STATUS_PENDING -> Panel(color = c.unsure.copy(alpha = 0.25f)) {
                Text("Don't pay again yet", style = ZType.bodyBold, color = c.text)
                Fine("Paying again could take the money twice. Check the SMS from your bank first. It usually arrives within a few minutes.")
            }
            STATUS_FAILED -> Fine("If money left your account anyway, your bank usually returns it. Check your bank SMS before trying again.")
            STATUS_SUCCESS -> Fine("Your bank also sends an SMS. That's where most people double-check.")
        }
        if (raw.isNotBlank()) Panel {
            Kicker("What your bank said")
            Spacer(Modifier.height(6.dp))
            Text(raw, style = ZType.mono, color = c.text)
        }
    }
}

fun statusExplainer(t: Tx) = when (t.status) {
    STATUS_SUCCESS -> if (t.decidedBy == BY_BANK_REPLY) "Your bank's *99# reply confirmed this payment." else "You marked this as paid."
    STATUS_FAILED -> if (t.decidedBy == BY_BANK_REPLY) "Your bank's reply says this payment failed." else "You marked this as failed."
    STATUS_STOPPED -> "Stopped before the PIN step. No money should have moved."
    STATUS_PENDING -> "Started. ZepPay hasn't recorded a result yet."
    else -> "Not confirmed. ZepPay couldn't read a clear result."
}

/* ---------------- Back from the dialer / 123PAY call ---------------- */

@Composable
fun AskUserScreen(vm: ZepViewModel, txId: String) {
    val c = Zep.c
    val history by vm.history.collectAsState()
    val tx = history.firstOrNull { it.id == txId } ?: run { vm.back(); return }
    Page("Did it go through?", null, footer = {
        PlainButton("Yes, my bank says paid", { vm.userSays(tx, STATUS_SUCCESS); vm.replace(Screen.Receipt(tx.id)) }, Modifier.fillMaxWidth(), icon = ZIcon.Check)
        PlainButton("No, it failed", { vm.userSays(tx, STATUS_FAILED); vm.replace(Screen.Receipt(tx.id)) }, Modifier.fillMaxWidth(), icon = ZIcon.Cross)
        ActionButton("Not sure yet", { vm.userSays(tx, STATUS_UNKNOWN); vm.home("pay") }, Modifier.fillMaxWidth())
    }) {
        PayCard(Modifier.fillMaxWidth(), top = {
            Text(money(tx.amount), style = ZType.amount, color = c.text)
            Text("to ${tx.payee.label}", style = ZType.label, color = c.text)
        })
        Body(if (tx.route == ROUTE_IVR) "ZepPay can't hear the 123PAY call, so it can't tell if the payment worked." else "ZepPay can't read the dialer on this route, so it can't tell if the payment worked.")
        Body("Check the SMS from your bank, then tell ZepPay so your records are right.")
    }
}

/* ---------------- Receipt ---------------- */

@Composable
fun ReceiptScreen(vm: ZepViewModel, txId: String) {
    val c = Zep.c
    val ctx = LocalContext.current
    val history by vm.history.collectAsState()
    val tx = history.firstOrNull { it.id == txId } ?: run { Page("Payment", { vm.back() }) { Body("Not found.") }; return }
    val by = if (tx.decidedBy == BY_BANK_REPLY) "your bank's *99# reply" else "you"
    val share = {
        val text = "ZepPay record: ${money(tx.amount)} to ${tx.payee.label} on ${whenLabel(tx.at)}. Status: ${statusWord(tx.status)} (from $by). Your bank SMS is the usual confirmation."
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    Page("Payment", { vm.back() }, footer = {
        if (tx.status == STATUS_UNKNOWN || tx.status == STATUS_PENDING) {
            PlainButton("My bank SMS says paid", { vm.userSays(tx, STATUS_SUCCESS) }, Modifier.fillMaxWidth(), icon = ZIcon.Check)
            PlainButton("My bank SMS says failed", { vm.userSays(tx, STATUS_FAILED) }, Modifier.fillMaxWidth(), icon = ZIcon.Cross)
        } else ActionButton("Pay ${tx.payee.label.take(18)} again", { vm.go(Screen.Amount(tx.payee, note = tx.note)) }, Modifier.fillMaxWidth(), icon = ZIcon.Arrow)
    }, action = {
        Box(Modifier.size(52.dp).clip(CircleShape).pressable(label = "Share") { share() }, contentAlignment = Alignment.Center) { Icon(ZIcon.Share, "Share", tint = c.text) }
    }) {
        PayCard(Modifier.fillMaxWidth(), color = c.card, top = {
            ResultSign(signFor(tx.status))
            Spacer(Modifier.height(12.dp))
            Text(money(tx.amount), style = ZType.amount, color = c.text)
            Text(tx.payee.label, style = ZType.label, color = c.text)
        }, bottom = {
            Row2("To", payeeLine(tx)); Row2("When", whenLabel(tx.at))
            Row2("Route", when (tx.route) { ROUTE_IVR -> "UPI 123PAY call"; ROUTE_MANUAL -> "*99# (you typed each step)"; else -> "*99# (ZepPay filled in)" })
            Row2("Status from", if (tx.decidedBy == BY_BANK_REPLY) "Bank's *99# reply" else "You")
            if (tx.note.isNotEmpty()) Row2("Note", tx.note)
        })
        Body(statusExplainer(tx))
        if (tx.raw.isNotBlank()) Panel { Kicker("What your bank said"); Spacer(Modifier.height(6.dp)); Text(tx.raw, style = ZType.mono, color = c.text) }
        Fine("This is ZepPay's record on this phone. Your bank SMS is where most people confirm a payment.")
    }
}

@Composable
private fun Row2(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, style = ZType.small, color = Zep.c.muted, modifier = Modifier.width(110.dp))
        Text(v, style = ZType.bodyBold.copy(fontSize = ZType.small.fontSize), color = Zep.c.text, modifier = Modifier.weight(1f))
    }
}

fun statusWord(s: String) = when (s) { STATUS_SUCCESS -> "Paid"; STATUS_FAILED -> "Failed"; STATUS_STOPPED -> "Stopped"; STATUS_PENDING -> "Pending"; else -> "Not confirmed" }

/* ---------------- Balance / history (never a payment) ---------------- */

@Composable
fun InfoScreen(vm: ZepViewModel, op: Op) {
    val c = Zep.c
    val ctx = LocalContext.current
    val session by Engine.session.collectAsState()
    val helper = helperOn()
    val route = vm.route(helper)
    val s = session?.takeIf { it.op == op && it.txId == null }
    val title = if (op == Op.BALANCE) "Check balance" else "Bank history"
    val callPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun start() {
        `in`.zeppay.zeppay.LiveInfo.payeeLine = if (op == Op.BALANCE) "your balance check" else "your history check"
        when (route) {
            Route.STEPWISE -> when (vm.startInfo(op)) {
                Engine.StartResult.NeedsAccessibility -> vm.go(Screen.Helper)
                Engine.StartResult.NeedsCallPermission -> callPerm.launch(android.Manifest.permission.CALL_PHONE)
                Engine.StartResult.Busy -> vm.showToast("A *99# session is already running.")
                Engine.StartResult.Started -> Unit
            }
            Route.MANUAL -> Ussd.openDialer(ctx, op.code)
            Route.IVR -> Ussd.callNumber(ctx, if (vm.store.dtmfAssist) Dtmf.balance(vm.store.ivrLang, Dtmf.Delays(vm.store.dtmfAfterConnect, vm.store.dtmfBetweenMenu, vm.store.dtmfBeforeConfirm)) else IVR_123PAY_PRIMARY)
        }
    }
    val p = s?.phase
    Page(title, { if (p == null || p is Phase.Done) { Engine.clear(); vm.back() } }, footer = {
        if (p == null || p is Phase.Done) ActionButton(if (p is Phase.Done) "Check again" else "Start", ::start, Modifier.fillMaxWidth(), icon = ZIcon.Signal)
        else PlainButton("Stop", { Engine.stop() }, Modifier.fillMaxWidth(), icon = ZIcon.Close)
    }) {
        when (p) {
            null -> {
                Body(when (route) {
                    Route.STEPWISE -> "ZepPay opens ${op.code} on your SIM. Your bank asks for your UPI PIN on its own screen. ZepPay then shows the bank's reply here."
                    Route.MANUAL -> "ZepPay opens the dialer with ${op.code}. Press call, then follow your bank's steps."
                    Route.IVR -> "ZepPay calls UPI 123PAY. Choose balance on the call and enter your PIN on the call keypad."
                })
                if (op == Op.HISTORY) Panel(color = c.unsure.copy(alpha = 0.25f)) {
                    Text("Not checked on your SIM yet", style = ZType.bodyBold, color = c.text)
                    Fine("*99# menus differ by bank and network. Run the carrier menu check first so ZepPay uses your SIM's real option.")
                }
            }
            is Phase.Done -> {
                val o = p.outcome
                if (o.kind == OutcomeKind.INFO) Panel {
                    Kicker("Your bank says"); Spacer(Modifier.height(6.dp))
                    Text(o.raw, style = ZType.mono, color = c.text)
                } else Body(o.reason)
                Fine("This is the bank's reply as shown on its screen. ZepPay doesn't save balances. It is not a payment and doesn't appear in your activity.")
            }
            Phase.PinHandoff -> BankCounter(Modifier.fillMaxWidth()) {
                Kicker("Your turn", color = c.bank)
                Text("Type your UPI PIN on the bank's screen", style = ZType.title, color = c.text)
                Body("ZepPay can't see it. It'll show the bank's reply here.")
            }
            is Phase.NeedsUser -> BankCounter(Modifier.fillMaxWidth()) {
                Kicker("Your bank is asking", color = c.bank)
                Text(p.prompt.take(240), style = ZType.mono, color = c.text)
                Body("Answer it on the bank's screen.")
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) { Working(); Spacer(Modifier.width(12.dp)); Text("Opening ${op.code}…", style = ZType.label, color = c.text) }
        }
    }
}
