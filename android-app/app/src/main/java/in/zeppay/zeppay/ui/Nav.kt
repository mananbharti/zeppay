package `in`.zeppay.zeppay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.zeppay.zeppay.ZepViewModel
import `in`.zeppay.zeppay.data.Payee
import `in`.zeppay.zeppay.data.Tx
import `in`.zeppay.zeppay.ui.kit.pressable
import androidx.compose.ui.draw.clip
import `in`.zeppay.zeppay.ui.theme.Shapes
import `in`.zeppay.zeppay.ui.screens.*
import `in`.zeppay.zeppay.ui.theme.ZIcon
import `in`.zeppay.zeppay.ui.theme.ZType
import `in`.zeppay.zeppay.ui.theme.Zep
import `in`.zeppay.zeppay.ussd.Op

sealed interface Screen {
    data object Scan : Screen
    data class PayTo(val kind: String, val splitRef: String = "") : Screen
    data class Amount(val payee: Payee, val amount: String = "", val note: String = "", val splitRef: String = "", val lockAmount: Boolean = false) : Screen
    data class Confirm(val tx: Tx) : Screen
    data class Live(val txId: String?) : Screen
    data class AskUser(val txId: String) : Screen
    data class Receipt(val txId: String) : Screen
    data class Info(val op: Op) : Screen
    data object BankSetup : Screen
    data object MenuCheck : Screen
    data object Helper : Screen
    data object Update : Screen
    data object Help : Screen
    data object Settings : Screen
    data object Activity : Screen
    data class GroupView(val id: String) : Screen
    data class NewSplit(val duo: Boolean) : Screen
    data class AddExpense(val gid: String) : Screen
    data object Licenses : Screen
}

@Composable
fun ScreenHost(vm: ZepViewModel, s: Screen) = when (s) {
    Screen.Scan -> ScanScreen(vm)
    is Screen.PayTo -> PayToScreen(vm, s.kind, s.splitRef)
    is Screen.Amount -> AmountScreen(vm, s)
    is Screen.Confirm -> ConfirmScreen(vm, s.tx)
    is Screen.Live -> LiveScreen(vm, s.txId)
    is Screen.AskUser -> AskUserScreen(vm, s.txId)
    is Screen.Receipt -> ReceiptScreen(vm, s.txId)
    is Screen.Info -> InfoScreen(vm, s.op)
    Screen.BankSetup -> BankSetupScreen(vm)
    Screen.MenuCheck -> MenuCheckScreen(vm)
    Screen.Helper -> HelperScreen(vm)
    Screen.Update -> UpdateScreen(vm)
    Screen.Help -> HelpScreen(vm)
    Screen.Settings -> SettingsScreen(vm)
    Screen.Activity -> ActivityScreen(vm)
    is Screen.GroupView -> GroupScreen(vm, s.id)
    is Screen.NewSplit -> NewSplitScreen(vm, s.duo)
    is Screen.AddExpense -> AddExpenseScreen(vm, s.gid)
    Screen.Licenses -> LicensesScreen(vm)
}

@Composable
fun TabHost(vm: ZepViewModel, tab: String) = when (tab) {
    "split" -> SplitsTab(vm)
    "spends" -> SpendsTab(vm)
    "more" -> MoreTab(vm)
    else -> PayTab(vm)
}

/** The dock: a floating navy pill. The current tab becomes a mint pill; labels always show. */
@Composable
fun Dock(tab: String, onTab: (String) -> Unit) {
    val c = Zep.c
    val items = listOf(Triple("pay", "Pay", ZIcon.Scan), Triple("split", "Split", ZIcon.Contacts), Triple("spends", "Spends", ZIcon.Chart), Triple("more", "More", ZIcon.More))
    Row(
        Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)
            .clip(Shapes.pill).background(c.card).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (id, label, icon) ->
            val on = id == tab
            val fg = if (on) c.onPrimary else c.muted
            Column(
                Modifier.weight(1f).heightIn(min = 62.dp).clip(Shapes.pill).background(if (on) c.primary else Color.Transparent)
                    .semantics { selected = on }.pressable(role = Role.Tab, label = label) { onTab(id) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(24.dp))
                Text(label, style = ZType.bodyBold.copy(fontSize = 15.sp, lineHeight = 18.sp), color = fg, maxLines = 1)
            }
        }
    }
}
