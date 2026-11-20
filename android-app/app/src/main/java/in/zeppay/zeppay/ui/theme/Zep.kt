@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package `in`.zeppay.zeppay.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.zeppay.zeppay.R

/*
 * ZepPay identity: "Aurora Signal". Midnight base, a cool aurora (teal into violet) that
 * lives only around the signal dial, mint pills for action. Inspired by the user's dark
 * fintech references for feel, deliberately different in colour and form.
 * Every token has ONE job; the full contract is in docs/DESIGN.md.
 */

@Immutable
data class ZepColors(
    val bg: Color,                       // the page
    val card: Color,                     // grouped information (lists, payment details)
    val tile: Color,                     // shortcut tiles and inputs
    val line: Color,                     // hairline borders and dividers
    val text: Color, val muted: Color,
    val primary: Color, val onPrimary: Color,       // mint pill: the one next action
    val secondary: Color, val onSecondary: Color,   // navy pill: any other choice
    val auroraA: Color, val auroraB: Color,         // the aurora: signal dial + "working" only
    val bubbleA: Color, val bubbleB: Color, val bubbleC: Color, val bubbleD: Color, val onBubble: Color, // tile icon bubbles
    val paid: Color, val failed: Color, val unsure: Color,   // status colour (text + tinted pill)
    val bank: Color,                     // dashed "bank's counter" frame
    val dark: Boolean,
)

/** Default: night. */
val Dark = ZepColors(
    bg = Color(0xFF070B14), card = Color(0xFF0F1626), tile = Color(0xFF0F1626), line = Color(0xFF1E2940),
    text = Color(0xFFEEF2FA), muted = Color(0xFF8E9AB4),
    primary = Color(0xFF3EF0B1), onPrimary = Color(0xFF04150F),
    secondary = Color(0xFF18233A), onSecondary = Color(0xFFEEF2FA),
    auroraA = Color(0xFF19E3C8), auroraB = Color(0xFF6A4BFF),
    bubbleA = Color(0xFF3EF0B1), bubbleB = Color(0xFF8B6CFF), bubbleC = Color(0xFF22D3E6), bubbleD = Color(0xFFFFC44D), onBubble = Color(0xFF07111C),
    paid = Color(0xFF3EF0B1), failed = Color(0xFFFF6B7A), unsure = Color(0xFFFFC44D),
    bank = Color(0xFF9CC9FF), dark = true,
)

/** Daylight: for bright sun. Same roles, ink on cool white. */
val Light = ZepColors(
    bg = Color(0xFFF5F7FC), card = Color(0xFFFFFFFF), tile = Color(0xFFFFFFFF), line = Color(0xFFDCE2EE),
    text = Color(0xFF0A1020), muted = Color(0xFF515D78),
    primary = Color(0xFF0A1020), onPrimary = Color(0xFF3EF0B1),
    secondary = Color(0xFFE7ECF6), onSecondary = Color(0xFF0A1020),
    auroraA = Color(0xFF12B8A2), auroraB = Color(0xFF5B3DF5),
    bubbleA = Color(0xFF2FD39C), bubbleB = Color(0xFF8B6CFF), bubbleC = Color(0xFF22C3D6), bubbleD = Color(0xFFFFC44D), onBubble = Color(0xFF07111C),
    paid = Color(0xFF0B8A5E), failed = Color(0xFFC62F43), unsure = Color(0xFF8F5F00),
    bank = Color(0xFF1B6FB5), dark = false,
)

private fun bricolage(w: Int) = Font(R.font.bricolage, FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w), FontVariation.width(100f), FontVariation.Setting("opsz", 36f)))
private fun body(w: Int) = Font(R.font.atkinson, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

/** Headings and numerals: Bricolage Grotesque, normal width. */
val Display = FontFamily(bricolage(700), bricolage(600))
/** Atkinson Hyperlegible Next: built for low-vision readers. Every sentence. */
val Body = FontFamily(body(400), body(700), body(800))

object ZType {
    val amount = TextStyle(fontFamily = Display, fontWeight = FontWeight(700), fontSize = 64.sp, lineHeight = 66.sp, letterSpacing = (-1.5).sp)
    val hero = TextStyle(fontFamily = Display, fontWeight = FontWeight(700), fontSize = 38.sp, lineHeight = 42.sp, letterSpacing = (-0.8).sp)
    val title = TextStyle(fontFamily = Display, fontWeight = FontWeight(700), fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp)
    val label = TextStyle(fontFamily = Display, fontWeight = FontWeight(600), fontSize = 21.sp, lineHeight = 25.sp)
    val body = TextStyle(fontFamily = Body, fontWeight = FontWeight(400), fontSize = 18.sp, lineHeight = 26.sp)
    val bodyBold = body.copy(fontWeight = FontWeight(700))
    val small = TextStyle(fontFamily = Body, fontWeight = FontWeight(400), fontSize = 16.sp, lineHeight = 23.sp)
    val button = TextStyle(fontFamily = Body, fontWeight = FontWeight(800), fontSize = 20.sp, lineHeight = 24.sp)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 21.sp)
}

/*
 * Shapes, each with one role:
 *   Pill (full radius)          = an action. Mint = the next step; navy = any other choice.
 *   Small tinted pill, no edge  = a status (Paid / Not confirmed). Never pressable.
 *   Card (24dp)                 = grouped information.
 *   Tile (24dp) + icon bubble   = a shortcut. Inputs are 18dp tiles with a hairline.
 *   Signal dial (circle + rings)= the ONE hero object (Scan to pay; the live payment).
 *   Small circle                = a person.
 *   Dashed sky frame            = the bank's counter, where ZepPay steps aside.
 */
object Shapes {
    val pill = CircleShape.let { RoundedCornerShape(50) }
    val card = RoundedCornerShape(24.dp)
    val tile = RoundedCornerShape(24.dp)
    val input = RoundedCornerShape(18.dp)
}

/*
 * Motion, each with one role:
 *   press   = pill/tile scales to 96%: your tap registered.
 *   forward = next screen rises in; back = it sinks away.
 *   rings   = the dial's rings ripple outward ONLY while the carrier is working.
 *   outcome = Paid badge pops with one ring; Failed shakes once; Not confirmed stays still.
 * With the system's "remove animations" on, every motion is instant.
 */
object Motion {
    val press = spring<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)
    val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    fun <T> shutter() = tween<T>(340, easing = emphasized)
    fun <T> pop() = spring<T>(dampingRatio = 0.45f, stiffness = 420f)
}

val LocalZep = staticCompositionLocalOf { Dark }
val LocalReduceMotion = staticCompositionLocalOf { false }

object Zep {
    val c: ZepColors @Composable get() = LocalZep.current
    val reduceMotion: Boolean @Composable get() = LocalReduceMotion.current
}

@Composable
fun ZepTheme(mode: String, content: @Composable () -> Unit) {
    // Aurora Signal is night-first; "auto" follows the system, "light" is the daylight variant.
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val reduce = remember { try { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (_: Exception) { false } }
    CompositionLocalProvider(LocalZep provides if (dark) Dark else Light, LocalReduceMotion provides reduce, content = content)
}
