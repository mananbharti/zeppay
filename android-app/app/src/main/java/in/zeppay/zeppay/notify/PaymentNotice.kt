package `in`.zeppay.zeppay.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import `in`.zeppay.zeppay.MainActivity
import `in`.zeppay.zeppay.R
import `in`.zeppay.zeppay.ussd.Engine
import `in`.zeppay.zeppay.ussd.Op
import `in`.zeppay.zeppay.ussd.OutcomeKind
import `in`.zeppay.zeppay.ussd.Phase

/** What the status notification says. Pure, so the wording rules are unit-tested. */
data class NoticeText(
    val title: String,
    val text: String,
    /** Button label, or null when there is nothing to cancel (session finished). */
    val cancel: String?,
    val ongoing: Boolean,
) {
    companion object {
        /** Shown on a secure lock screen instead of the real text: never the payee or amount. */
        const val PUBLIC_TITLE = "ZepPay"
        const val PUBLIC_RUNNING = "A *99# session is running. Unlock to see it."
        const val PUBLIC_DONE = "A *99# session finished. Unlock to see it."

        /**
         * [what] is the private description, e.g. "₹250 to Asha" (payments) or "Balance check".
         * Honest-outcome rule: only [OutcomeKind.PAID] may say "Paid", and only because the
         * bank's own post-PIN reply said so.
         */
        fun of(phase: Phase, op: Op, what: String): NoticeText {
            val pay = op.isPayment
            val label = if (pay) "Paying $what" else what
            return when (phase) {
                Phase.Dialing -> NoticeText(label, "Opening *99# on your SIM…", "Cancel", true)
                is Phase.Working -> NoticeText(label, "${phase.label}. Please don't type yet.", "Cancel", true)
                Phase.PinHandoff -> NoticeText(label, "Your turn: type your UPI PIN on the bank's screen. ZepPay can't see it.",
                    if (pay) "Stop watching" else "Stop", true)
                is Phase.NeedsUser -> NoticeText(label, "Your bank is asking something ZepPay doesn't know. Answer it on the bank's screen.",
                    if (pay) "Stop watching" else "Stop", true)
                is Phase.Away -> NoticeText(label,
                    if (phase.pinSeen) "The bank's screen is out of sight. If you typed your PIN, check your bank SMS before paying again."
                    else "The bank's screen is out of sight, so ZepPay paused. Nothing is paid yet.",
                    if (phase.pinSeen) "Stop watching" else "Cancel", true)
                is Phase.Done -> when (phase.outcome.kind) {
                    OutcomeKind.PAID -> NoticeText("Paid $what", "Confirmed by your bank's reply.", null, false)
                    OutcomeKind.FAILED -> NoticeText("Payment failed: $what", "Your bank's reply says it didn't go through.", null, false)
                    OutcomeKind.STOPPED -> NoticeText("Stopped: $what", "Ended before the PIN step. Nothing was paid.", null, false)
                    OutcomeKind.UNKNOWN -> NoticeText("Not confirmed: $what", "Check your bank SMS before paying again. Don't pay twice.", null, false)
                    OutcomeKind.INFO -> NoticeText("$what finished", "Open ZepPay to see your bank's reply.", null, false)
                }
            }
        }
    }
}

/**
 * The status notification for a running *99# session. It mirrors [Engine.session] and has a
 * Cancel action. It is VISIBILITY_PRIVATE with a redacted public version, so on a secure lock
 * screen it shows only "ZepPay: a *99# session is running", and it is local-only (not bridged
 * to watches). It is a plain notification, not a foreground service: the process is kept by
 * the system's binding to the accessibility helper, which every stepwise session needs anyway.
 */
object PaymentNotice {
    private const val CH_LIVE = "payment_live"
    private const val CH_DONE = "payment_result"
    private const val ID = 301
    const val ACTION_CANCEL = "in.zeppay.zeppay.CANCEL_SESSION"

    private fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_LIVE, "Payment in progress", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows a running *99# session with a Cancel button. Hidden on the lock screen."
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Payment results", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "The result of a *99# session when you're not in ZepPay. Hidden on the lock screen."
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }

    fun canPost(c: Context) = NotificationManagerCompat.from(c).areNotificationsEnabled()

    /** [alertResult]: post the finished session as a result notification (the user isn't in ZepPay). */
    fun show(c: Context, phase: Phase, op: Op, what: String, alertResult: Boolean) {
        if (!canPost(c)) return
        if (phase is Phase.Done && !alertResult) { cancel(c); return }
        channels(c)
        val t = NoticeText.of(phase, op, what)
        val open = PendingIntent.getActivity(c, 11,
            Intent(c, MainActivity::class.java).putExtra("open", "live").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val ch = if (t.ongoing) CH_LIVE else CH_DONE
        val public = NotificationCompat.Builder(c, ch).setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(NoticeText.PUBLIC_TITLE).setContentText(if (t.ongoing) NoticeText.PUBLIC_RUNNING else NoticeText.PUBLIC_DONE).build()
        val b = NotificationCompat.Builder(c, ch).setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(t.title).setContentText(t.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(t.text))
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setLocalOnly(true).setOnlyAlertOnce(true).setOngoing(t.ongoing).setAutoCancel(!t.ongoing)
            .setCategory(if (t.ongoing) NotificationCompat.CATEGORY_PROGRESS else NotificationCompat.CATEGORY_STATUS)
        if (t.ongoing) b.setProgress(0, 0, true)
        t.cancel?.let { label ->
            val pi = PendingIntent.getBroadcast(c, 12, Intent(c, CancelReceiver::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, label, pi)
        }
        try { NotificationManagerCompat.from(c).notify(ID, b.build()) } catch (_: SecurityException) { /* not allowed */ }
    }

    fun cancel(c: Context) = NotificationManagerCompat.from(c).cancel(ID)
}

/** The notification's Cancel / Stop watching. Same as Stop in ZepPay: never retries, never types. */
class CancelReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { if (i.action == PaymentNotice.ACTION_CANCEL) Engine.stop() }
}
