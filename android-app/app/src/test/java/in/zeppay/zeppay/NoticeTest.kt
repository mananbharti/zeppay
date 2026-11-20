package `in`.zeppay.zeppay

import `in`.zeppay.zeppay.notify.NoticeText
import `in`.zeppay.zeppay.ussd.Op
import `in`.zeppay.zeppay.ussd.Outcome
import `in`.zeppay.zeppay.ussd.OutcomeKind
import `in`.zeppay.zeppay.ussd.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeTest {
    private val what = "₹250 to Asha"
    private fun done(k: OutcomeKind) = NoticeText.of(Phase.Done(Outcome(k, "raw", "reason")), Op.PAY_VPA, what)

    @Test fun onlyBankConfirmedPaymentSaysPaid() {
        assertTrue(done(OutcomeKind.PAID).title.startsWith("Paid"))
        for (k in OutcomeKind.entries - OutcomeKind.PAID) assertFalse("$k", done(k).title.contains("Paid"))
        assertTrue(done(OutcomeKind.UNKNOWN).text.contains("bank SMS"))
    }

    @Test fun runningHasCancelAndFinishedDoesNot() {
        assertEquals("Cancel", NoticeText.of(Phase.Dialing, Op.PAY_VPA, what).cancel)
        assertTrue(NoticeText.of(Phase.Working("Typing the amount", 1), Op.PAY_VPA, what).ongoing)
        assertNull(done(OutcomeKind.PAID).cancel)
        assertFalse(done(OutcomeKind.PAID).ongoing)
    }

    @Test fun afterThePinCancelIsHonest() {
        // After the PIN step ZepPay can't promise a cancel: the button says what it really does.
        assertEquals("Stop watching", NoticeText.of(Phase.PinHandoff, Op.PAY_VPA, what).cancel)
        val awayAfterPin = NoticeText.of(Phase.Away(Phase.PinHandoff, pinSeen = true, since = 0), Op.PAY_VPA, what)
        assertEquals("Stop watching", awayAfterPin.cancel)
        assertTrue(awayAfterPin.text.contains("bank SMS"))
        assertEquals("Cancel", NoticeText.of(Phase.Away(Phase.Dialing, pinSeen = false, since = 0), Op.PAY_VPA, what).cancel)
    }

    @Test fun lockScreenVersionHasNoPayeeOrAmount() {
        for (s in listOf(NoticeText.PUBLIC_TITLE, NoticeText.PUBLIC_RUNNING, NoticeText.PUBLIC_DONE)) {
            assertFalse(s.contains("₹")); assertFalse(s.contains("Asha"))
        }
    }

    @Test fun balanceCheckIsNeverAPayment() {
        val t = NoticeText.of(Phase.Done(Outcome(OutcomeKind.INFO, "Your balance is Rs 1,234", "")), Op.BALANCE, "Balance check")
        assertFalse(t.title.contains("Paid")); assertFalse(t.text.contains("1,234"))
    }
}
