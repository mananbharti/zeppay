@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package `in`.zeppay.zeppay.ui.screens

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import `in`.zeppay.zeppay.ZepViewModel
import `in`.zeppay.zeppay.data.Expense
import `in`.zeppay.zeppay.data.Group
import `in`.zeppay.zeppay.data.Lib
import `in`.zeppay.zeppay.data.ME
import `in`.zeppay.zeppay.data.Person
import `in`.zeppay.zeppay.data.STATUS_FAILED
import `in`.zeppay.zeppay.data.STATUS_PENDING
import `in`.zeppay.zeppay.data.STATUS_UNKNOWN
import `in`.zeppay.zeppay.data.Settlement
import `in`.zeppay.zeppay.data.SplitMath
import `in`.zeppay.zeppay.data.Spends
import `in`.zeppay.zeppay.ui.Screen
import `in`.zeppay.zeppay.ui.kit.*
import `in`.zeppay.zeppay.ui.theme.ZIcon
import `in`.zeppay.zeppay.ui.theme.ZType
import `in`.zeppay.zeppay.ui.theme.Zep
import java.text.DateFormatSymbols

/* ---------------- Split tab ---------------- */

@Composable
fun SplitsTab(vm: ZepViewModel) {
    val c = Zep.c
    val groups by vm.groups.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Split a bill", style = ZType.hero, color = c.text)
        PayCard(Modifier.fillMaxWidth(), top = {
            Kicker("You owe", color = c.text.copy(alpha = 0.8f))
            Text(money(SplitMath.totalYouOwe(groups)), style = ZType.title.copy(fontSize = ZType.hero.fontSize), color = c.text)
        }, bottom = {
            Kicker("Owed to you", color = c.text.copy(alpha = 0.8f))
            Text(money(SplitMath.totalOwedToYou(groups)), style = ZType.title, color = c.primary)
        })
        ActionButton("Split with one person", { vm.go(Screen.NewSplit(duo = true)) }, Modifier.fillMaxWidth(), icon = ZIcon.Contacts)
        PlainButton("Make a group", { vm.go(Screen.NewSplit(duo = false)) }, Modifier.fillMaxWidth(), icon = ZIcon.Plus)
        if (groups.isNotEmpty()) Kicker("Your splits", Modifier.padding(top = 8.dp))
        groups.sortedByDescending { it.createdAt }.forEach { g ->
            val mine = SplitMath.debts(g)
            val owe = mine.filter { it.from == ME }.sumOf { it.amount }
            val owed = mine.filter { it.to == ME }.sumOf { it.amount }
            TileRow(g.name, when { owe > 0 -> "You owe ${money(owe)}"; owed > 0 -> "You're owed ${money(owed)}"; else -> "All settled" },
                if (g.duo) ZIcon.Contacts else ZIcon.Split, { vm.go(Screen.GroupView(g.id)) }, bubble = if (owe > 0) c.failed else if (owed > 0) c.primary else c.paid)
        }
        Panel(color = c.card) {
            Text("Saved on this phone only", style = ZType.bodyBold, color = c.text)
            Fine("Splits don't sync to anyone else (that would need internet and an account, which ZepPay doesn't have). Share a summary from a split to keep others in the loop.")
        }
    }
}

@Composable
fun GroupScreen(vm: ZepViewModel, id: String) {
    val c = Zep.c
    val ctx = LocalContext.current
    val groups by vm.groups.collectAsState()
    val g = groups.firstOrNull { it.id == id } ?: run { Page("Split", { vm.back() }) { Body("This split was deleted.") }; return }
    val debts = SplitMath.debts(g)
    var confirmDelete by remember { mutableStateOf(false) }
    Page(g.name, { vm.back() }, footer = { ActionButton("Add a bill", { vm.go(Screen.AddExpense(g.id)) }, Modifier.fillMaxWidth(), icon = ZIcon.Plus) }) {
        Kicker("Who owes what")
        if (debts.isEmpty()) Body("Everyone is settled up.")
        debts.forEach { d ->
            PayCard(Modifier.fillMaxWidth(), color = if (d.from == ME) c.card else c.card, top = {
                Text("${g.nameOf(d.from)} ${if (d.from == ME) "owe" else "owes"} ${g.nameOf(d.to).let { if (it == "You") "you" else it }}",
                    style = ZType.bodyBold, color = if (d.from == ME) c.text else c.text)
                Text(money(d.amount), style = ZType.title, color = if (d.from == ME) c.primary else c.text)
                Spacer(Modifier.height(8.dp))
                when {
                    d.from == ME -> {
                        val p = g.person(d.to)
                        if (p != null && p.payable) ActionButton("Pay ${p.name} ${money(d.amount)}", {
                            vm.go(Screen.Amount(p.payee(), amount = Lib.formatINR(d.amount).drop(1).replace(",", ""), note = g.name.take(30), splitRef = "${g.id}:${p.id}"))
                        }, Modifier.fillMaxWidth(), icon = ZIcon.Arrow)
                        else Fine("Add ${p?.name ?: "their"} phone or UPI ID to pay from here.")
                    }
                    d.to == ME -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PlainButton("They paid me", { vm.addSettlement(g.id, Settlement(vm.newId(), d.from, ME, d.amount, System.currentTimeMillis())) }, Modifier.weight(1f))
                        PlainButton("Remind", {
                            val t = "Hi ${g.nameOf(d.from)}, for \"${g.name}\" you owe me ${money(d.amount)}. (Sent from ZepPay)"
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Remind").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }, Modifier.weight(1f), icon = ZIcon.Share)
                    }
                    else -> Fine("Between them. Not your payment.")
                }
            })
        }
        if (g.expenses.isNotEmpty()) {
            Kicker("Bills", Modifier.padding(top = 8.dp))
            g.expenses.sortedByDescending { it.at }.forEach { e ->
                Panel(Modifier.fillMaxWidth()) {
                    Row { Text(e.title, style = ZType.bodyBold, color = c.text, modifier = Modifier.weight(1f)); Text(money(e.amount), style = ZType.label, color = c.text) }
                    Fine("${g.nameOf(e.paidBy)} paid · split ${e.shares.size} ways · ${whenLabel(e.at)}")
                }
            }
        }
        if (g.settlements.isNotEmpty()) {
            Kicker("Settled", Modifier.padding(top = 8.dp))
            g.settlements.sortedByDescending { it.at }.forEach { s -> Fine("${g.nameOf(s.from)} paid ${g.nameOf(s.to)} ${money(s.amount)} · ${whenLabel(s.at)}${if (s.txId.isNotEmpty()) " · via ZepPay" else ""}") }
        }
        PlainButton(if (confirmDelete) "Tap again to delete this split" else "Delete split", {
            if (confirmDelete) { vm.deleteGroup(g.id); vm.back() } else confirmDelete = true
        }, Modifier.fillMaxWidth(), icon = ZIcon.Trash, tint = c.failed)
    }
}

@Composable
fun NewSplitScreen(vm: ZepViewModel, duo: Boolean) {
    var name by rememberSaveable { mutableStateOf("") }
    val people = remember { mutableStateListOf<Person>() }
    var pName by rememberSaveable { mutableStateOf("") }
    var pContact by rememberSaveable { mutableStateOf("") }
    val canAdd = pName.isNotBlank() && (pContact.isBlank() || Lib.isValidMobile(pContact) || Lib.isValidVpa(pContact))
    fun add() {
        if (!canAdd) return
        val v = pContact.trim()
        people += Person(vm.newId(), pName.trim(), mobile = if (Lib.isValidMobile(v)) Lib.normMobile(v) else "", vpa = if (Lib.isValidVpa(v)) v else "")
        pName = ""; pContact = ""
    }
    val ready = if (duo) people.size == 1 || canAdd else people.isNotEmpty() && name.isNotBlank()
    Page(if (duo) "Split with one person" else "New group", { vm.back() }, footer = {
        ActionButton("Save", {
            if (duo && people.isEmpty()) add()
            val g = Group(vm.newId(), if (duo) people.first().name else name.trim(), people.toList(), duo, createdAt = System.currentTimeMillis())
            vm.saveGroup(g); vm.replace(Screen.AddExpense(g.id))
        }, Modifier.fillMaxWidth(), enabled = ready, icon = ZIcon.Check)
    }) {
        if (!duo) Field(name, { name = it.take(30) }, "Group name", placeholder = "e.g. Goa trip, Flat 4B")
        people.forEach { p -> Panel(Modifier.fillMaxWidth()) { Text(p.name, style = ZType.bodyBold, color = Zep.c.text); Fine(p.vpa.ifEmpty { p.mobile.ifEmpty { "No number: you can't pay them from ZepPay" } }) } }
        if (!duo || people.isEmpty()) {
            Field(pName, { pName = it.take(30) }, if (duo) "Their name" else "Add a person: name", placeholder = "Name")
            Field(pContact, { pContact = it }, "Their phone or UPI ID (optional)", placeholder = "Needed to pay them", keyboard = KeyboardType.Email,
                error = if (pContact.isNotBlank() && !canAdd) "Enter a 10-digit mobile number or a UPI ID like name@bank." else null)
            if (!duo) PlainButton("Add person", ::add, Modifier.fillMaxWidth(), icon = ZIcon.Plus, enabled = canAdd)
        }
        Fine("You're included automatically.")
    }
}

@Composable
fun AddExpenseScreen(vm: ZepViewModel, gid: String) {
    val groups by vm.groups.collectAsState()
    val g = groups.firstOrNull { it.id == gid } ?: run { vm.back(); return }
    var title by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var paidBy by rememberSaveable { mutableStateOf(ME) }
    val everyone = listOf(ME) + g.people.map { it.id }
    val among = remember { mutableStateListOf<String>().apply { addAll(everyone) } }
    val amt = amount.toDoubleOrNull()
    val ok = amt != null && amt > 0 && among.isNotEmpty() && title.isNotBlank()
    Page("Add a bill", { vm.back() }, footer = {
        ActionButton(if (amt != null && among.isNotEmpty()) "Save · ${money(amt / among.size)} each" else "Save", {
            vm.addExpense(g.id, Expense(vm.newId(), title.trim(), amt!!, paidBy, SplitMath.equalShares(amt, among.toList()), System.currentTimeMillis()))
            vm.replace(Screen.GroupView(g.id))
        }, Modifier.fillMaxWidth(), enabled = ok, icon = ZIcon.Check)
    }) {
        Field(title, { title = it.take(30) }, "What was it?", placeholder = "e.g. Dinner")
        Field(amount, { v -> amount = v.filter { it.isDigit() || it == '.' }.take(9) }, "Total amount (₹)", placeholder = "0", keyboard = KeyboardType.Decimal, big = true)
        Kicker("Who paid?")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            everyone.forEach { id -> Chip(g.nameOf(id), paidBy == id) { paidBy = id } }
        }
        Kicker("Split equally between")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            everyone.forEach { id -> Chip(g.nameOf(id), id in among) { if (id in among) among.remove(id) else among.add(id) } }
        }
    }
}

/* ---------------- Spends tab ---------------- */

@Composable
fun SpendsTab(vm: ZepViewModel) {
    val c = Zep.c
    val history by vm.history.collectAsState()
    val months = remember(history) { Spends.monthly(history) }
    val now = months.first()
    val names = DateFormatSymbols(java.util.Locale("en", "IN")).shortMonths
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Spends", style = ZType.hero, color = c.text)
        Panel(Modifier.fillMaxWidth()) {
            Text("Only what you paid with ZepPay", style = ZType.bodyBold, color = c.text)
            Fine("This isn't your bank statement. Payments made in other apps, at the bank or by card aren't here.")
        }
        PayCard(Modifier.fillMaxWidth(), top = {
            Kicker("Paid in ${names[now.month]} with ZepPay", color = c.text.copy(alpha = 0.85f))
            Text(money(now.paid), style = ZType.amount, color = c.text)
            Text("${now.paidCount} payment${if (now.paidCount == 1) "" else "s"} confirmed", style = ZType.body, color = c.text)
        }, bottom = {
            val bars = months.reversed()
            val max = (bars.maxOfOrNull { it.paid } ?: 0.0).coerceAtLeast(1.0)
            Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                val w = size.width / (bars.size * 1.6f)
                bars.forEachIndexed { i, m ->
                    val h = (size.height - 8f) * (m.paid / max).toFloat().coerceAtLeast(0.02f)
                    drawRoundRect(if (i == bars.lastIndex) c.primary else c.text.copy(alpha = 0.45f), Offset(i * w * 1.6f, size.height - h), Size(w, h), CornerRadius(w / 2))
                }
            }
            Row(Modifier.fillMaxWidth()) { months.reversed().forEach { m -> Text(names[m.month], style = ZType.small, color = c.text, modifier = Modifier.weight(1f)) } }
        })
        if (now.unconfirmed > 0 || now.failed > 0) Panel(color = c.unsure.copy(alpha = 0.25f)) {
            Text("Not counted above", style = ZType.bodyBold, color = c.text)
            if (now.unconfirmed > 0) Fine("${now.unconfirmed} not confirmed yet. Check your bank SMS and mark them in Activity.")
            if (now.failed > 0) Fine("${now.failed} failed.")
        }
        if (now.top.isNotEmpty()) {
            Kicker("Most paid this month")
            now.top.forEach { (who, amt) -> Row(verticalAlignment = Alignment.CenterVertically) { Avatar(who, 40.dp); Spacer(Modifier.width(12.dp)); Text(who, style = ZType.body, color = c.text, modifier = Modifier.weight(1f)); Text(money(amt), style = ZType.bodyBold, color = c.text) } }
        }
        PlainButton("All activity", { vm.go(Screen.Activity) }, Modifier.fillMaxWidth(), icon = ZIcon.List)
    }
}

@Composable
fun ActivityScreen(vm: ZepViewModel) {
    val history by vm.history.collectAsState()
    Page("Activity", { vm.back() }) {
        Fine("Payments started in ZepPay on this phone. Statuses come from your bank's *99# reply, or from you.")
        if (history.isEmpty()) Body("Nothing yet. Your payments will show up here.")
        val unsure = history.filter { it.status == STATUS_UNKNOWN || it.status == STATUS_PENDING }
        if (unsure.isNotEmpty()) { Kicker("Check these"); unsure.forEach { t -> TxTicket(t) { vm.go(Screen.Receipt(t.id)) } } }
        val rest = history.filter { it !in unsure }
        rest.groupBy { whenLabel(it.at).substringBefore(",") }.forEach { (day, items) ->
            Kicker(day, Modifier.padding(top = 6.dp))
            items.forEach { t -> TxTicket(t) { vm.go(Screen.Receipt(t.id)) } }
        }
        @Suppress("UNUSED_VARIABLE") val unused = STATUS_FAILED
    }
}
