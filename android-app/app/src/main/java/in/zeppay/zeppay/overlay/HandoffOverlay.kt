package `in`.zeppay.zeppay.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.core.content.res.ResourcesCompat
import `in`.zeppay.zeppay.R
import `in`.zeppay.zeppay.ussd.Phase

/**
 * ZepPay's layer above the carrier dialog.
 *  - While ZepPay types (Working): a ticket banner at the top with live signal bars.
 *  - PIN handoff / bank asks something (PinHandoff, NeedsUser): the banner shrinks into a
 *    small chip with a dashed sky outline: the "bank's turf" mark. It sits above the
 *    dialog, never over it, and is not touchable, so the bank's screen is fully usable.
 * It never draws a keypad, a PIN box or anything that imitates the bank's surface.
 */
object HandoffOverlay {
    private var view: OverlayView? = null

    fun canShow(c: Context) = Settings.canDrawOverlays(c)

    fun openPermission(c: Context) = c.startActivity(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun show(c: Context, phase: Phase, payeeLine: String) {
        if (!canShow(c)) return
        val app = c.applicationContext
        val v = view ?: OverlayView(app).also { nv ->
            val wm = app.getSystemService(WindowManager::class.java)
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP; y = (32 * app.resources.displayMetrics.density).toInt() }
            try { wm.addView(nv, p); view = nv } catch (_: Exception) { return }
        }
        v.bind(phase, payeeLine)
    }

    fun hide(c: Context) {
        val v = view ?: return
        try { c.applicationContext.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Exception) {}
        view = null
    }
}

private class OverlayView(c: Context) : View(c) {
    private val d = resources.displayMetrics.density
    private val display: Typeface? = ResourcesCompat.getFont(c, R.font.bricolage)
    private val body: Typeface? = ResourcesCompat.getFont(c, R.font.atkinson)
    private val ink = 0xFF070B14.toInt(); private val plum = 0xFF0F1626.toInt(); private val zap = 0xFF3EF0B1.toInt()
    private val paper = 0xFFEEF2FA.toInt(); private val sky = 0xFF9CC9FF.toInt(); private val black = 0xFFEEF2FA.toInt()
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * resources.displayMetrics.density; color = 0xFF1E2940.toInt() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; pathEffect = DashPathEffect(floatArrayOf(9 * d, 6 * d), 0f); color = sky }
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = display; fontVariationSettings = "'wght' 800"; textSize = 21 * d }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = body; textSize = 16 * d; fontVariationSettings = "'wght' 500" }

    private var phase: Phase = Phase.Dialing
    private var line = ""
    private var compact = 0f          // 0 = banner, 1 = chip. Animated: the "step aside" move.
    private var bars = 0f             // signal bars, only animate while ZepPay is waiting on the carrier
    private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val barsAnim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 900; repeatCount = ValueAnimator.INFINITE; addUpdateListener { bars = it.animatedValue as Float; invalidate() } }
    private var morph: ValueAnimator? = null

    fun bind(p: Phase, payee: String) {
        val wasCompact = phase is Phase.PinHandoff || phase is Phase.NeedsUser
        phase = p; line = payee
        val nowCompact = p is Phase.PinHandoff || p is Phase.NeedsUser
        if (nowCompact != wasCompact || morph == null) {
            morph?.cancel()
            val animate = ValueAnimator.areAnimatorsEnabled()
            morph = ValueAnimator.ofFloat(compact, if (nowCompact) 1f else 0f).apply {
                duration = if (animate) 420 else 0; interpolator = ease
                addUpdateListener { compact = it.animatedValue as Float; requestLayout(); invalidate() }
                start()
            }
        }
        if (!nowCompact && ValueAnimator.areAnimatorsEnabled()) { if (!barsAnim.isStarted) barsAnim.start() } else barsAnim.cancel()
        contentDescription = headline() + ". " + sub()
        requestLayout(); invalidate()
    }

    private fun headline() = when (val p = phase) {
        is Phase.PinHandoff -> "BANK'S COUNTER: type your UPI PIN there"
        is Phase.NeedsUser -> "Your bank is asking you something"
        is Phase.Working -> p.label
        else -> "Opening *99#…"
    }

    private fun sub() = when (phase) {
        is Phase.PinHandoff -> "ZepPay can't see your PIN. It's waiting for the result."
        is Phase.NeedsUser -> "Answer it on the bank's screen below. ZepPay will keep watching."
        else -> "ZepPay is filling in $line. Please don't type yet."
    }

    private fun layouts(w: Int): Pair<StaticLayout, StaticLayout> {
        val inner = (w - pad() * 2 - 56 * d * (1 - compact)).toInt().coerceAtLeast(10)
        title.color = if (compact > 0.5f) paper else black
        text.color = 0xFF8E9AB4.toInt()
        fun mk(s: String, p: TextPaint) = StaticLayout.Builder.obtain(s, 0, s.length, p, inner).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.1f).build()
        title.textSize = (21 - 3 * compact) * d
        return mk(headline(), title) to mk(sub(), text)
    }

    private fun margin() = (16 + 24 * compact) * d
    private fun pad() = margin() + 18 * d

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val (a, b) = layouts(w)
        setMeasuredDimension(w, (a.height + b.height + 46 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = RectF(margin(), 4 * d, w - margin(), h - 4 * d)
        // Ticket body (banner) morphing into the bank's-turf chip.
        fill.color = blend(plum, ink, compact)
        c.drawRoundRect(r, 24 * d, 24 * d, fill)
        if (compact < 1f) { frame.alpha = (255 * (1 - compact)).toInt(); c.drawRoundRect(r, 24 * d, 24 * d, frame) }
        if (compact > 0f) { dash.alpha = (255 * compact).toInt(); c.drawRoundRect(r, 24 * d, 24 * d, dash) }
        val (a, b) = layouts(width)
        c.save(); c.translate(pad(), r.top + 14 * d); a.draw(c); c.translate(0f, a.height + 4f * d); b.draw(c); c.restore()
        if (compact < 1f) drawBars(c, r.right - 46 * d, r.top + 18 * d, 1 - compact)
    }

    /** Three signal dots: chase only while ZepPay waits on the carrier. */
    private fun drawBars(c: Canvas, x: Float, y: Float, alpha: Float) {
        val lit = (bars * 3).toInt()
        for (i in 0 until 3) {
            fill.color = if (i == lit) zap else 0xFF2A3A5C.toInt(); fill.alpha = (255 * alpha).toInt()
            c.drawCircle(x + i * 13 * d, y + 14 * d, 5 * d, fill)
        }
        fill.alpha = 255
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    override fun onDetachedFromWindow() { barsAnim.cancel(); morph?.cancel(); super.onDetachedFromWindow() }
}
