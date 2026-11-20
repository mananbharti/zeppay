package `in`.zeppay.zeppay.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import `in`.zeppay.zeppay.data.Bank
import `in`.zeppay.zeppay.data.Branch
import `in`.zeppay.zeppay.data.Ifsc
import `in`.zeppay.zeppay.data.IfscDirectory
import `in`.zeppay.zeppay.ui.kit.*
import `in`.zeppay.zeppay.ui.theme.ZIcon
import `in`.zeppay.zeppay.ui.theme.ZType
import `in`.zeppay.zeppay.ui.theme.Zep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The bundled directory, opened once per process from the APK's assets. */
object Directory {
    @Volatile private var d: IfscDirectory? = null
    fun get(c: Context): IfscDirectory = d ?: synchronized(this) {
        d ?: IfscDirectory { name -> try { c.applicationContext.assets.open(name) } catch (_: Exception) { null } }.also { d = it }
    }
}

/**
 * What ZepPay's offline list says about a typed IFSC. Never claims a code is live: "listed"
 * means it was in the published list on its date; "not listed" may still be a valid code.
 */
@Composable
fun IfscNote(ifsc: String) {
    val ctx = LocalContext.current
    val dir = remember { Directory.get(ctx) }
    var found by remember { mutableStateOf<Pair<Branch?, Bank?>?>(null) }
    val s = Ifsc.normalize(ifsc)
    LaunchedEffect(s) {
        found = if (Ifsc.isValidFormat(s)) withContext(Dispatchers.IO) { dir.lookup(s) to dir.bank(s.take(4)) } else null
    }
    val f = found ?: return
    val info = dir.info
    if (f.first != null) Panel(Modifier.fillMaxWidth()) {
        Kicker("Branch for this IFSC")
        Text("${f.second?.name ?: s.take(4)}: ${f.first!!.branch}", style = ZType.bodyBold, color = Zep.c.text)
        Fine(f.first!!.place)
        Fine("From ZepPay's offline list${info?.rbiDate?.let { " (RBI list of $it)" } ?: ""}. Check it's the branch the person you're paying uses.")
    } else if (dir.available) Fine("This IFSC isn't in ZepPay's offline list${info?.rbiDate?.let { " of $it" } ?: ""}. It may still be right: newer branches and co-operative banks may be missing. Check it with the person you're paying.")
}

/**
 * Pick an IFSC by bank, then branch or town. Only fills in the field; the user can still edit
 * it. Nothing here links, verifies or looks up anyone's account.
 */
@Composable
fun IfscFinder(onPick: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val c = Zep.c
    val dir = remember { Directory.get(ctx) }
    var bankQ by rememberSaveable { mutableStateOf("") }
    var bankCode by rememberSaveable { mutableStateOf<String?>(null) }
    var branchQ by rememberSaveable { mutableStateOf("") }
    var banks by remember { mutableStateOf<List<Bank>>(emptyList()) }
    var branches by remember { mutableStateOf<List<Branch>>(emptyList()) }
    LaunchedEffect(bankQ) { banks = withContext(Dispatchers.IO) { dir.searchBanks(bankQ, 8) } }
    LaunchedEffect(bankCode, branchQ) { branches = bankCode?.let { b -> withContext(Dispatchers.IO) { dir.searchBranches(b, branchQ, 25) } }.orEmpty() }

    Panel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!dir.available) {
                Body("The offline bank list isn't in this version of ZepPay. Ask the person you're paying for their IFSC; it's on their cheque book or passbook.")
                PlainButton("Close", onClose, Modifier.fillMaxWidth(), icon = ZIcon.Close)
                return@Column
            }
            val bank = bankCode?.let { dir.bank(it) }
            if (bank == null) {
                Field(bankQ, { bankQ = it.take(40) }, "Their bank", placeholder = "e.g. State Bank, HDFC, SBIN")
                banks.forEach { b -> Row(b.name, "${b.code} · ${b.branches} branches") { bankCode = b.code; branchQ = "" } }
            } else {
                Row(bank.name, "Change bank") { bankCode = null }
                Field(branchQ, { branchQ = it.take(40) }, "Branch, town or area", placeholder = "e.g. Katargam, Surat")
                if (branches.isEmpty()) Fine("No listed branch matches. Try the town, or ask them for the IFSC.")
                branches.forEach { br -> Row("${br.branch} · ${br.ifsc}", br.place) { onPick(br.ifsc) } }
            }
            dir.info?.let { Fine("Offline list: ${it.branches} branches, RBI list of ${it.rbiDate}. ${it.limits}") }
            PlainButton("Close", onClose, Modifier.fillMaxWidth(), icon = ZIcon.Close)
        }
    }
}

@Composable
private fun Row(title: String, sub: String, onClick: () -> Unit) {
    val c = Zep.c
    Column(Modifier.fillMaxWidth().heightIn(min = 58.dp).pressable(label = "$title, $sub", onClick = onClick).padding(vertical = 8.dp)) {
        Text(title, style = ZType.bodyBold, color = c.text)
        Text(sub, style = ZType.small, color = c.muted)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
}

/** First-time *99# setup: find your bank's 4-letter code to type on the bank's screen. */
@Composable
fun BankCodeLookup() {
    val ctx = LocalContext.current
    val dir = remember { Directory.get(ctx) }
    var q by rememberSaveable { mutableStateOf("") }
    var res by remember { mutableStateOf<List<Bank>>(emptyList()) }
    LaunchedEffect(q) { res = if (q.isBlank()) emptyList() else withContext(Dispatchers.IO) { dir.searchBanks(q, 6) } }
    if (!dir.available) return
    Panel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(q, { q = it.take(40) }, "Look up your bank's code", placeholder = "e.g. Canara, Punjab National")
            res.forEach { b ->
                Text("${b.code}  ${b.name}", style = ZType.bodyBold, color = Zep.c.text)
            }
            Fine("You type the code yourself on the bank's screen. ZepPay doesn't link or check your account.")
        }
    }
}
