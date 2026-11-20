package `in`.zeppay.zeppay.ui.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.zeppay.zeppay.data.Lib
import `in`.zeppay.zeppay.data.STATUS_FAILED
import `in`.zeppay.zeppay.data.STATUS_PENDING
import `in`.zeppay.zeppay.data.STATUS_STOPPED
import `in`.zeppay.zeppay.data.STATUS_SUCCESS
import `in`.zeppay.zeppay.ui.theme.Motion
import `in`.zeppay.zeppay.ui.theme.Shapes
import `in`.zeppay.zeppay.ui.theme.ZIcon
import `in`.zeppay.zeppay.ui.theme.ZType
import `in`.zeppay.zeppay.ui.theme.Zep

/** Press feedback: scale to 96%. The only tap motion in the app. */
@Composable
fun Modifier.pressable(enabled: Boolean = true, role: Role = Role.Button, label: String? = null, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val reduce = Zep.reduceMotion
    val s by animateFloatAsState(if (pressed && enabled && !reduce) 0.96f else 1f, Motion.press, label = "press")
    return this.scale(s).clickable(src, null, enabled, label, role, onClick)
}

/** The one next step. Mint pill. */
@Composable
fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val c = Zep.c
    val bg = if (enabled) c.primary else c.secondary
    val fg = if (enabled) c.onPrimary else c.muted
    Row(
        modifier.heightIn(min = 64.dp).clip(Shapes.pill).background(bg).pressable(enabled, onClick = onClick).padding(horizontal = 26.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(10.dp)) }
        Text(text, style = ZType.button, color = fg, textAlign = TextAlign.Center)
    }
}

/** Any other choice. Navy pill. [tint] recolours the label (e.g. a destructive choice). */
@Composable
fun PlainButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, tint: Color? = null) {
    val c = Zep.c
    val fg = if (enabled) (tint ?: c.onSecondary) else c.muted
    Row(
        modifier.heightIn(min = 58.dp).clip(Shapes.pill).background(c.secondary).pressable(enabled, onClick = onClick).padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)) }
        Text(text, style = ZType.button.copy(fontSize = 18.sp), color = fg, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Bubble(icon: ImageVector, color: Color, size: Dp = 52.dp) {
    val c = Zep.c
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = c.onBubble, modifier = Modifier.size(size * 0.48f))
    }
}

/** Shortcut tile: borderless, coloured icon bubble, big label. With large system text the bubble sits above the label so words never break. */
@Composable
fun Tile(label: String, icon: ImageVector, bubble: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Zep.c
    val stacked = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f
    val base = modifier.heightIn(min = if (stacked) 120.dp else 84.dp).clip(Shapes.tile).background(c.tile)
        .then(if (!c.dark) Modifier.border(1.dp, c.line, Shapes.tile) else Modifier).pressable(label = label, onClick = onClick).padding(14.dp)
    val text = @Composable { Text(label, style = ZType.label.copy(fontSize = 19.sp, lineHeight = 22.sp), color = c.text) }
    if (stacked) Column(base, verticalArrangement = Arrangement.spacedBy(10.dp)) { Bubble(icon, bubble, 46.dp); text() }
    else Row(base, verticalAlignment = Alignment.CenterVertically) { Bubble(icon, bubble, 50.dp); Spacer(Modifier.width(12.dp)); text() }
}

/** Full-width shortcut row (settings, secondary actions). */
@Composable
fun TileRow(title: String, sub: String? = null, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, bubble: Color? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = Zep.c
    Row(
        modifier.fillMaxWidth().heightIn(min = 80.dp).clip(Shapes.tile).background(c.tile).then(if (!c.dark) Modifier.border(1.dp, c.line, Shapes.tile) else Modifier)
            .pressable(label = title, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Bubble(icon, bubble ?: c.secondary.let { if (c.dark) Color(0xFF243150) else it }, 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = ZType.label, color = c.text)
            if (sub != null) Text(sub, style = ZType.small, color = c.muted)
        }
        if (trailing != null) trailing() else Icon(ZIcon.Arrow, null, tint = c.muted, modifier = Modifier.size(24.dp))
    }
}

/** Grouped information. Card, not tappable. */
@Composable
fun Panel(modifier: Modifier = Modifier, color: Color? = null, pad: PaddingValues = PaddingValues(20.dp), content: @Composable ColumnScope.() -> Unit) {
    val c = Zep.c
    Column(modifier.clip(Shapes.card).background(color ?: c.card).then(if (!c.dark && color == null) Modifier.border(1.dp, c.line, Shapes.card) else Modifier).padding(pad), content = content)
}

/** A payment's details: card with a hairline between "what" and "how". Tappable if [onClick]. */
@Composable
fun PayCard(modifier: Modifier = Modifier, color: Color? = null, onClick: (() -> Unit)? = null, top: @Composable ColumnScope.() -> Unit, bottom: (@Composable ColumnScope.() -> Unit)? = null) {
    val c = Zep.c
    Column(
        modifier.clip(Shapes.card).background(color ?: c.card).then(if (!c.dark) Modifier.border(1.dp, c.line, Shapes.card) else Modifier)
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = if (bottom != null) 16.dp else 20.dp), content = top)
        if (bottom != null) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp).height(1.dp).background(c.line))
            Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 20.dp), content = bottom)
        }
    }
}

/** The aurora: soft teal and violet light. Used ONLY behind the signal dial. Radial gradients, so it works on every Android version. */
@Composable
fun BoxScope.Aurora(strength: Float = 1f) {
    val c = Zep.c
    Canvas(Modifier.matchParentSize()) {
        val a = (if (c.dark) 0.5f else 0.32f) * strength
        val r = size.minDimension * 0.62f
        fun blob(col: Color, at: Offset) = drawCircle(Brush.radialGradient(listOf(col.copy(alpha = a), col.copy(alpha = 0f)), at, r), r, at)
        blob(c.auroraA, Offset(size.width * 0.18f, size.height * 0.45f))
        blob(c.auroraB, Offset(size.width * 0.84f, size.height * 0.5f))
    }
}

/**
 * The signal dial: a disc with three gradient rings. It's the scan button on Home and the
 * live-payment indicator. Rings ripple outward ONLY when [working].
 */
@Composable
fun SignalDial(modifier: Modifier = Modifier, size: Dp = 236.dp, working: Boolean = false, onClick: (() -> Unit)? = null, label: String = "", content: @Composable ColumnScope.() -> Unit) {
    val c = Zep.c
    val reduce = Zep.reduceMotion
    val t = rememberInfiniteTransition(label = "dial")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "p")
    val ring = Brush.sweepGradient(listOf(c.auroraA, c.auroraB, c.auroraA))
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val r = this.size.minDimension / 2
            for (i in 0 until 3) {
                val base = r - i * 9.dp.toPx() - 2.dp.toPx()
                val grow = if (working && !reduce) ((p + i / 3f) % 1f) else 0f
                drawCircle(ring, base + grow * 8.dp.toPx(), alpha = if (working && !reduce) 1f - grow * 0.7f else 0.95f - i * 0.15f, style = Stroke(2.5.dp.toPx()))
            }
        }
        Column(
            Modifier.size(size - 46.dp).clip(CircleShape).background(if (c.dark) Color(0xFF0A1222) else c.card)
                .then(if (onClick != null) Modifier.pressable(label = label, onClick = onClick) else Modifier.semantics { contentDescription = label }),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, content = content,
        )
    }
}

/** Small "working" indicator for rows: three rings, rippling. */
@Composable
fun Working(modifier: Modifier = Modifier) {
    SignalDial(modifier, size = 56.dp, working = true, label = "Working") {}
}

/** The bank's counter. Dashed sky frame with its own label. Used wherever ZepPay steps aside. */
@Composable
fun BankCounter(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Zep.c
    Column(
        modifier.drawBehind {
            drawRoundRect(c.bank, cornerRadius = CornerRadius(24.dp.toPx()),
                style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
        }.padding(22.dp),
    ) {
        Text("BANK'S COUNTER", style = ZType.small.copy(fontSize = 14.sp, letterSpacing = 2.sp, fontWeight = androidx.compose.ui.text.font.FontWeight(800)), color = c.bank)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
fun Avatar(name: String, size: Dp = 48.dp) {
    val c = Zep.c
    Box(Modifier.size(size).clip(CircleShape).background(if (c.dark) Color(0xFF26324C) else c.secondary).semantics { contentDescription = name }, contentAlignment = Alignment.Center) {
        Text(Lib.initials(name), style = ZType.label.copy(fontSize = (size.value * 0.36f).sp), color = c.text)
    }
}

enum class SignKind { PAID, FAILED, UNSURE, STOPPED }

fun signFor(status: String) = when (status) {
    STATUS_SUCCESS -> SignKind.PAID; STATUS_FAILED -> SignKind.FAILED; STATUS_STOPPED -> SignKind.STOPPED; else -> SignKind.UNSURE
}

/** Outcome badge: big circle + word. Paid pops with one ring; Failed shakes once; others stay still. */
@Composable
fun ResultSign(kind: SignKind, modifier: Modifier = Modifier) {
    val c = Zep.c
    val reduce = Zep.reduceMotion
    val (col, label, icon) = when (kind) {
        SignKind.PAID -> Triple(c.paid, "Paid", ZIcon.Check)
        SignKind.FAILED -> Triple(c.failed, "Failed", ZIcon.Cross)
        SignKind.UNSURE -> Triple(c.unsure, "Not confirmed", ZIcon.Question)
        SignKind.STOPPED -> Triple(c.muted, "Stopped", ZIcon.Close)
    }
    val pop = remember { Animatable(if (kind == SignKind.PAID && !reduce) 0.6f else 1f) }
    val ring = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(kind) {
        if (reduce) return@LaunchedEffect
        if (kind == SignKind.PAID) { pop.animateTo(1f, Motion.pop()); ring.animateTo(1f, tween(700)) }
        if (kind == SignKind.FAILED) shake.animateTo(0f, keyframes { durationMillis = 420; 10f at 70; -9f at 150; 6f at 230; -3f at 310 })
    }
    Column(modifier.graphicsLayer { translationX = shake.value * density }.semantics { contentDescription = "Status: " + label.lowercase() }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                if (ring.value in 0.01f..0.99f) drawCircle(col.copy(alpha = 1f - ring.value), size.minDimension / 2 * (0.7f + 0.3f * ring.value), style = Stroke(3.dp.toPx()))
            }
            Box(Modifier.size(96.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }.clip(CircleShape).background(col.copy(alpha = 0.16f)).border(2.dp, col, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = col, modifier = Modifier.size(46.dp))
            }
        }
        Text(label, style = ZType.title, color = col)
    }
}

/** "Done" marker for a finished setup step (tinted pill, never pressable). */
@Composable
fun DoneTag() { val c = Zep.c
    Text("Done", style = ZType.small.copy(fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight(800)), color = c.paid,
        modifier = Modifier.clip(Shapes.pill).background(c.paid.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 4.dp))
}

/** A status as a small tinted pill (no edge, never pressable). */
@Composable
fun StatusPill(status: String) {
    val c = Zep.c
    val (col, t) = when (status) {
        STATUS_SUCCESS -> c.paid to "Paid"; STATUS_FAILED -> c.failed to "Failed"; STATUS_STOPPED -> c.muted to "Stopped"
        STATUS_PENDING -> c.unsure to "Pending"; else -> c.unsure to "Not confirmed"
    }
    Text(t, style = ZType.small.copy(fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight(800)), color = col,
        modifier = Modifier.clip(Shapes.pill).background(col.copy(alpha = if (c.dark) 0.16f else 0.12f)).padding(horizontal = 12.dp, vertical = 4.dp))
}

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, action: (@Composable () -> Unit)? = null) {
    val c = Zep.c
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Box(Modifier.size(54.dp).clip(CircleShape).background(c.secondary).pressable(label = "Back", onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(ZIcon.Back, "Back", tint = c.onSecondary, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
        }
        Text(title, style = ZType.title, color = c.text, modifier = Modifier.weight(1f).semantics { heading() })
        action?.invoke()
    }
}

@Composable
fun Field(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text, caps: KeyboardCapitalization = KeyboardCapitalization.None, error: String? = null,
    big: Boolean = false, onDone: (() -> Unit)? = null,
) {
    val c = Zep.c
    Column(modifier) {
        Text(label, style = ZType.bodyBold, color = c.text)
        Spacer(Modifier.height(6.dp))
        val style = if (big) ZType.title.copy(color = c.text) else ZType.body.copy(fontSize = 21.sp, color = c.text)
        BasicTextField(
            value, onChange, singleLine = true, textStyle = style, cursorBrush = SolidColor(c.auroraA),
            keyboardOptions = KeyboardOptions(capitalization = caps, autoCorrectEnabled = false, keyboardType = keyboard, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(Shapes.input).background(c.tile)
                    .border(if (error != null) 2.dp else 1.dp, if (error != null) c.failed else c.line, Shapes.input).padding(horizontal = 18.dp, vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = style.copy(color = c.muted.copy(alpha = 0.75f)))
                    inner()
                }
            },
        )
        if (error != null) { Spacer(Modifier.height(6.dp)); Text(error, style = ZType.small, color = c.failed) }
    }
}

@Composable fun Body(t: String, modifier: Modifier = Modifier, color: Color? = null, align: TextAlign = TextAlign.Start) =
    Text(t, style = ZType.body, color = color ?: Zep.c.text, modifier = modifier, textAlign = align)

@Composable fun Fine(t: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start, color: Color? = null) =
    Text(t, style = ZType.small, color = color ?: Zep.c.muted, modifier = modifier, textAlign = align)

/** Section heading. */
@Composable fun Kicker(t: String, modifier: Modifier = Modifier, color: Color? = null) =
    Text(t, style = ZType.title.copy(fontSize = 24.sp, lineHeight = 28.sp), color = color ?: Zep.c.text, modifier = modifier.semantics { heading() })

@Composable
fun Footer(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
fun ToastHost(msg: String, modifier: Modifier = Modifier) {
    val c = Zep.c
    AnimatedVisibility(msg.isNotEmpty(), modifier, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
        Text(msg, style = ZType.bodyBold, color = c.onSecondary, modifier = Modifier.padding(20.dp).clip(Shapes.card).background(c.secondary).padding(horizontal = 20.dp, vertical = 16.dp))
    }
}

/** A choice among several. Selected = mint. */
@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = Zep.c
    Text(text, style = ZType.bodyBold.copy(fontSize = 17.sp), color = if (on) c.onPrimary else c.onSecondary,
        modifier = Modifier.heightIn(min = 50.dp).clip(Shapes.pill).background(if (on) c.primary else c.secondary)
            .pressable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 18.dp, vertical = 13.dp))
}

/** On/off switch with the words ON / OFF always visible. */
@Composable
fun Toggle(title: String, sub: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Zep.c
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).pressable(role = Role.Switch, label = title) { onChange(!on) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = ZType.bodyBold, color = c.text); if (sub != null) Fine(sub) }
        Spacer(Modifier.width(12.dp))
        Text(if (on) "ON" else "OFF", style = ZType.button.copy(fontSize = 16.sp), color = if (on) c.onPrimary else c.onSecondary, textAlign = TextAlign.Center,
            modifier = Modifier.width(72.dp).clip(Shapes.pill).background(if (on) c.primary else c.secondary).padding(vertical = 10.dp))
    }
}

@Composable
fun Stepper(total: Int, at: Int, modifier: Modifier = Modifier) {
    val c = Zep.c
    Row(modifier.semantics { contentDescription = "Step ${at + 1} of $total" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i -> Box(Modifier.height(6.dp).width(if (i == at) 28.dp else 12.dp).clip(Shapes.pill).background(if (i <= at) c.primary else c.line)) }
    }
}

fun money(v: Double) = Lib.formatINR(v)
