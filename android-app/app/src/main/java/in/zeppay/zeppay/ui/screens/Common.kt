package `in`.zeppay.zeppay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import `in`.zeppay.zeppay.data.Tx
import `in`.zeppay.zeppay.ui.kit.Footer
import `in`.zeppay.zeppay.ui.kit.TopBar
import `in`.zeppay.zeppay.ussd.UssdService
import `in`.zeppay.zeppay.overlay.HandoffOverlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Increments every time the screen resumes, so permission checks refresh after Settings. */
@Composable
fun resumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

@Composable
fun helperOn(): Boolean {
    val t = resumeTick(); val ctx = LocalContext.current
    return remember(t) { UssdService.isEnabled(ctx) }
}

@Composable
fun overlayOn(): Boolean {
    val t = resumeTick(); val ctx = LocalContext.current
    return remember(t) { HandoffOverlay.canShow(ctx) }
}

/** Standard page: top bar, scrolling body, optional pinned footer with the one main action. */
@Composable
fun Page(title: String, onBack: (() -> Unit)?, footer: (@Composable ColumnScope.() -> Unit)? = null, action: (@Composable () -> Unit)? = null, body: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title, onBack, action)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = body,
        )
        if (footer != null) Footer(footer)
    }
}

fun whenLabel(at: Long): String = SimpleDateFormat("d MMM, h:mm a", Locale("en", "IN")).format(Date(at))

fun payeeLine(t: Tx) = t.payee.vpa.ifEmpty { t.payee.mobile.ifEmpty { "A/c ${t.payee.account} · ${t.payee.ifsc}" } }
