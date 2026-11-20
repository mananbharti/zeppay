package `in`.zeppay.zeppay

import `in`.zeppay.zeppay.ussd.Classifier
import `in`.zeppay.zeppay.ussd.Decision
import `in`.zeppay.zeppay.ussd.FlowRunner
import `in`.zeppay.zeppay.ussd.Flows
import `in`.zeppay.zeppay.ussd.Frame
import `in`.zeppay.zeppay.ussd.Op
import `in`.zeppay.zeppay.ussd.OutcomeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frame texts here are illustrative; tune against real captures from the carrier-menu check. */
class FlowTest {
    private fun runner(amount: Double = 250.0, vpa: String = "priya.s@okhdfc", note: String = "") =
        FlowRunner(Op.PAY_VPA, Flows.vars(Op.PAY_VPA, vpa, "", "", "", amount, note), amount)

    @Test fun walksPayeeAmountRemarkThenHandsOverPin() {
        val r = runner()
        assertEquals(Decision.Reply("priya.s@okhdfc", "Typing the UPI ID"), r.onFrame(Frame("Enter UPI ID of the payee", true)))
        assertEquals("250", (r.onFrame(Frame("Enter amount in Rs.", true)) as Decision.Reply).text)
        assertEquals("1", (r.onFrame(Frame("Enter remarks (1 to skip)", true)) as Decision.Reply).text)
        assertEquals(Decision.PinHandoff, r.onFrame(Frame("Pay Rs 250 to priya.s@okhdfc\nEnter UPI PIN", true)))
        assertTrue(r.pinSeen)
    }

    @Test fun neverRepliesToAnyPinPrompt() {
        val prompts = listOf("Enter UPI PIN", "Enter your 6 digit UPI PIN to pay Rs 250 to x@ybl (enter amount)", "Incorrect PIN. Enter UPI PIN again", "Enter MPIN", "यूपीआई पिन दर्ज करें")
        for (p in prompts) {
            val d = runner().onFrame(Frame(p, true))
            assertEquals("prompt: $p", Decision.PinHandoff, d)
        }
    }

    @Test fun paidOnlyFromPaymentConfirmationAfterPin() {
        val r = runner()
        r.onFrame(Frame("Enter UPI PIN", true))
        val d = r.onFrame(Frame("Payment of Rs 250 to priya.s@okhdfc is successful. Ref no 412345678901", false)) as Decision.Finish
        assertEquals(OutcomeKind.PAID, d.outcome.kind)
    }

    @Test fun referenceNumberAloneIsNotSuccess() {
        val r = runner(); r.onFrame(Frame("Enter UPI PIN", true))
        val d = r.onFrame(Frame("Your request has been processed. Ref no 412345678901", false)) as Decision.Finish
        assertEquals(OutcomeKind.UNKNOWN, d.outcome.kind)
    }

    @Test fun conflictingTextIsUnknown() {
        assertEquals(OutcomeKind.UNKNOWN, Classifier.payment("Transaction successful. Amount debited but transaction failed at beneficiary bank", true, 250.0).kind)
    }

    @Test fun negatedSuccessIsFailure() {
        assertEquals(OutcomeKind.FAILED, Classifier.payment("Payment not successful", true, 250.0).kind)
        assertEquals(OutcomeKind.FAILED, Classifier.payment("Transaction unsuccessful", true, 250.0).kind)
    }

    @Test fun wrongAmountInSuccessIsUnknown() {
        assertEquals(OutcomeKind.UNKNOWN, Classifier.payment("Payment of Rs 2500 to x@ybl is successful", true, 250.0).kind)
        assertEquals(OutcomeKind.PAID, Classifier.payment("Payment of Rs.250.00 to x@ybl is successful", true, 250.0).kind)
    }

    @Test fun successBeforePinIsUnknown() {
        assertEquals(OutcomeKind.UNKNOWN, Classifier.payment("Transaction successful", false, 250.0).kind)
    }

    @Test fun rejectionBeforePinIsStopped() {
        val r = runner()
        val d = r.onFrame(Frame("Invalid UPI ID. Please check and try again", false)) as Decision.Finish
        assertEquals(OutcomeKind.STOPPED, d.outcome.kind)
    }

    @Test fun balanceReplyIsNeverPaid() {
        val r = FlowRunner(Op.BALANCE, emptyMap(), null)
        assertEquals(Decision.PinHandoff, r.onFrame(Frame("Enter UPI PIN", true)))
        val d = r.onFrame(Frame("Your balance is Rs 1,234.50. Transaction successful", false)) as Decision.Finish
        assertEquals(OutcomeKind.INFO, d.outcome.kind)
    }

    @Test fun unknownPromptGoesToUserNotGuessed() {
        val r = runner()
        assertTrue(r.onFrame(Frame("Select account\n1. SBI XX1234\n2. HDFC XX9876", true)) is Decision.NeedsUser)
    }

    @Test fun repeatedPromptIsNotAnsweredTwice() {
        val r = runner()
        r.onFrame(Frame("Enter UPI ID", true))
        assertTrue(r.onFrame(Frame("Enter UPI ID", true)) is Decision.NeedsUser)
    }

    @Test fun sessionEndAfterPinIsUnknownBeforeIsStopped() {
        val a = runner(); a.onFrame(Frame("Enter UPI PIN", true))
        assertEquals(OutcomeKind.UNKNOWN, a.onSessionEnded("closed").kind)
        assertEquals(OutcomeKind.STOPPED, runner().onSessionEnded("closed").kind)
    }

    @Test fun remarkSanitisedAndVarsHaveNoPin() {
        val v = Flows.vars(Op.PAY_VPA, "a@b", "", "", "", 10.5, "chai*#  bill")
        assertEquals("chai bill", v["remark"]); assertEquals("10.50", v["amount"])
        assertFalse(v.keys.any { it.contains("pin", ignoreCase = true) })
    }

    @Test fun menuItemsParse() {
        val items = Flows.menuItems("Welcome to *99#\n1. Send Money\n2. Request Money\n3. Check Balance")
        assertEquals(listOf("1" to "Send Money", "2" to "Request Money", "3" to "Check Balance"), items)
    }
}
