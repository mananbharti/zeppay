package `in`.zeppay.zeppay.ussd

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import `in`.zeppay.zeppay.BuildConfig

/**
 * Reads the carrier's *99# dialog and answers non-secret prompts for ZepPay.
 *
 * Privacy rules enforced here, not just in the UI:
 *  - Active only while ZepPay has a session running ([active]); every other event is dropped.
 *  - Listens only to dialer/telephony packages (see res/xml/ussd_service.xml).
 *  - Never subscribes to text-changed events, and never reads editable or password
 *    nodes. What the user types into the bank's box (the UPI PIN) is never seen.
 *  - Never logs frame text.
 */
class UssdService : AccessibilityService() {

    companion object {
        @Volatile var instance: UssdService? = null; private set

        val PACKAGES = setOf(
            "com.android.phone",
            "com.android.server.telecom",
            "com.samsung.android.app.telephonyui",
            "com.google.android.dialer",
            "com.android.dialer",
        )
        /** Debug builds only: the simulated carrier dialog lives in ZepPay's own package. */
        const val SIM_WINDOW_TITLE = "ZepPay simulated carrier"
        /** Seconds without the bank's dialog (and another app on top) before showing "out of sight". */
        const val AWAY_AFTER = 6

        private val PLACEHOLDER = Regex("^(ussd code running|please wait|loading|sending|running ussd code)[.…\\s]*$", RegexOption.IGNORE_CASE)
        private val SEND = listOf("send", "reply", "ok", "submit", "भेजें")
        private val DISMISS = listOf("cancel", "dismiss", "close", "ok", "done")

        fun isEnabled(context: Context): Boolean {
            val want = ComponentName(context, UssdService::class.java).flattenToString()
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val split = TextUtils.SimpleStringSplitter(':').apply { setString(list) }
            return split.any { it.equals(want, ignoreCase = true) }
        }
    }

    @Volatile var active = false
    var listener: ((Frame) -> Unit)? = null
    var onGone: (() -> Unit)? = null
    /** The bank's screen went out of sight with another app (not ZepPay) on top: the user left. */
    var onAway: (() -> Unit)? = null
    /** The bank's screen is visible again after [onAway]. */
    var onBack: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var lastText: String? = null
    private var seenDialog = false
    private val readNow = Runnable { readFrame() }
    private var misses = 0

    /**
     * Window-change event from the phone app: the same rule as 2.0.0. If the bank's dialog is
     * gone, the session ends (Stopped before the PIN step, Not confirmed after it). Only checked
     * once the dialog has been seen, so nothing ends while *99# is still being dialled.
     */
    private val checkGone = Runnable {
        if (!active) return@Runnable
        if (findRoot() != null) { misses = 0; seenDialog = true; onBack?.invoke(); return@Runnable }
        if (seenDialog) onGone?.invoke()
    }

    /**
     * Once a second during a session: display only, it never ends a session. Window events
     * don't arrive when the user switches to the launcher or another app, so this is how ZepPay
     * notices it is out of sight. It needs [AWAY_AFTER] seconds without the dialog AND another
     * app on top: on real phones the dialog can vanish for several seconds while the bank
     * processes a reply (2.1.0 treated that gap as "closed" and ended payments as Not confirmed).
     * Only window types and package names are read, never other apps' content.
     */
    private val watch = object : Runnable {
        override fun run() {
            if (!active) return
            if (findRoot() != null) { misses = 0; seenDialog = true; onBack?.invoke() }
            else if (seenDialog && ++misses >= AWAY_AFTER && !zepPayOnTop()) onAway?.invoke()
            main.postDelayed(this, 1_000)
        }
    }

    /** Called when a session attaches: watch the bank's screen until the session ends. */
    fun startWatching() { misses = 0; main.removeCallbacks(watch); main.postDelayed(watch, 1_000) }

    override fun onServiceConnected() { instance = this; Engine.reattach(this) }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || !active) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> { main.removeCallbacks(checkGone); main.postDelayed(checkGone, 700) }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!allowed(e.packageName?.toString())) return
                // Content changes arrive in bursts; read once the dialog settles.
                main.removeCallbacks(readNow); main.postDelayed(readNow, 250)
            }
        }
    }

    /** Read the dialog as it is now (used after a restart, when no event may come). */
    fun readSoon() { main.removeCallbacks(readNow); main.postDelayed(readNow, 400) }

    fun resetForSession() { lastText = null; seenDialog = false; misses = 0; main.removeCallbacks(readNow); main.removeCallbacks(checkGone); main.removeCallbacks(watch) }

    /** Types a non-secret value (payee, amount, note) into the bank's box and presses Send. */
    fun reply(value: String): Boolean {
        val root = findRoot() ?: return false
        val edit = find(root) { isInput(it) } ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        lastText = null
        val btn = find(root) { n -> isButton(n) && label(n).let { l -> SEND.any { l == it || l.startsWith(it) } } }
        return btn?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    /** Presses the dialog's Cancel/OK. Used for ZepPay's own leftovers and after a result was read. */
    fun dismiss(): Boolean {
        val root = findRoot() ?: return false
        val btn = find(root) { n -> isButton(n) && label(n).let { l -> DISMISS.any { l == it } } } ?: return false
        lastText = null
        return btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun dialogVisible(): Boolean = findRoot() != null

    private fun readFrame() {
        if (!active) return
        val root = findRoot() ?: return
        seenDialog = true
        val texts = mutableListOf<String>()
        var input = false
        walk(root) { n ->
            when {
                isInput(n) -> input = true          // presence only; its text is never read
                isButton(n) -> Unit
                else -> n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts += it }
            }
        }
        val text = texts.joinToString("\n").trim()
        if (text.isEmpty() || PLACEHOLDER.matches(text)) return
        val key = "$input|$text"
        if (key == lastText) return
        lastText = key
        listener?.invoke(Frame(text, input))
    }

    /** Whether ZepPay's own screen is the top app window. Reads only the window's package name. */
    private fun zepPayOnTop(): Boolean {
        val top = windows.orEmpty().firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION } ?: return false
        if (BuildConfig.DEBUG && top.title?.toString() == SIM_WINDOW_TITLE) return false
        return top.root?.packageName?.toString() == packageName
    }

    private fun allowed(pkg: String?): Boolean =
        pkg != null && (pkg in PACKAGES || (BuildConfig.DEBUG && pkg == packageName))

    private fun findRoot(): AccessibilityNodeInfo? {
        for (w in windows.orEmpty()) {
            val r = w.root ?: continue
            val pkg = r.packageName?.toString()
            if (pkg in PACKAGES) return r
            if (BuildConfig.DEBUG && pkg == packageName && w.title?.toString() == SIM_WINDOW_TITLE) return r
        }
        return null
    }

    private fun isInput(n: AccessibilityNodeInfo) =
        n.isEditable || n.isPassword || n.className?.toString()?.contains("EditText") == true

    private fun isButton(n: AccessibilityNodeInfo) = n.className?.toString()?.contains("Button") == true

    private fun label(n: AccessibilityNodeInfo) = n.text?.toString()?.trim()?.lowercase().orEmpty()

    private fun find(n: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (test(n)) return n
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> find(c, test)?.let { return it } }
        return null
    }

    private fun walk(n: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        visit(n)
        if (isInput(n)) return
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, visit) }
    }
}
