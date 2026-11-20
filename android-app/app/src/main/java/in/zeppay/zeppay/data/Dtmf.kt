package `in`.zeppay.zeppay.data

/**
 * DTMF assist for the UPI 123PAY IVR (Jio and other USSD-less networks).
 *
 * The IVR menu (documented from NPCI 123PAY guides):
 *   language (1=Hindi 2=English 3=Bangla #=more)
 *   -> 1 = money transfer
 *   -> 1 = pay via mobile number / 2 = pay via account number
 *   -> enter payee number
 *   -> enter amount
 *   -> 1 = continue
 *   -> the IVR hangs up and CALLS BACK: user picks up, presses 1, enters UPI PIN.
 *
 * A tel: URI comma is a ~2 second DTMF pause, so the whole first call can be
 * keyed automatically. The callback confirmation and the UPI PIN are always
 * manual. Timing is carrier-dependent and NOT device-tested: every step the
 * tones miss can simply be keyed by hand, the IVR waits.
 */
object Dtmf {

    data class Delays(
        val afterConnectSec: Int = 6,
        val betweenMenuSec: Int = 4,
        val beforeConfirmSec: Int = 4,
    )

    data class Plan(
        val dialString: String,       // full number with DTMF pauses, for tel:
        val possible: Boolean,        // false = this payee can't be paid over IVR
        val blockedReason: String = "",
        val autoSummary: List<String> = emptyList(),   // what the tones key in
        val manualSummary: List<String> = emptyList(), // what the user must do
    )

    private fun commas(seconds: Int): String = ",".repeat((seconds / 2).coerceAtLeast(1))

    private fun amountDigits(amount: Double): String =
        if (amount % 1.0 == 0.0) amount.toLong().toString()
        else UssdChain.amountToken(amount).replace(".", "*") // IVR decimal convention: '*' as point (UNVERIFIED - device test)

    /**
     * @param langKey "1"=Hindi, "2"=English, "3"=Bangla
     */
    fun pay(payee: Payee, amount: Double, langKey: String, delays: Delays): Plan {
        val viaAccount = payee.mobile.isEmpty() && payee.account.isNotEmpty()
        if (payee.mobile.isEmpty() && payee.account.isEmpty()) {
            return Plan(
                dialString = "",
                possible = false,
                blockedReason = "The 123PAY call can only pay a mobile number or a bank account. A UPI ID like ${payee.vpa} has letters and can't be keyed into a call.",
            )
        }
        val d0 = commas(delays.afterConnectSec)
        val d1 = commas(delays.betweenMenuSec)
        val d2 = commas(delays.beforeConfirmSec)
        val payType = if (viaAccount) "2" else "1"
        val payeeDigits = if (viaAccount) payee.account.filter { it.isDigit() } else payee.mobile.filter { it.isDigit() }.takeLast(10)
        val seq = buildString {
            append(IVR_123PAY_PRIMARY)
            append(d0); append(langKey)          // language
            append(d1); append("1")              // money transfer
            append(d1); append(payType)          // via mobile / account
            append(d1); append(payeeDigits)      // payee number
            append(d1); append(amountDigits(amount)) // amount
            append(d2); append("1")              // continue
        }
        return Plan(
            dialString = seq,
            possible = true,
            autoSummary = listOf(
                "Picks your language",
                "Chooses Money Transfer",
                "Chooses pay by ${if (viaAccount) "account" else "mobile"} number",
                "Keys in $payeeDigits",
                "Keys in the amount ${UssdChain.amountToken(amount)}",
                "Confirms the details",
            ),
            manualSummary = listOf(
                "The IVR hangs up and calls you back in a few seconds",
                "Pick up the callback and press 1 to confirm",
                "Enter your UPI PIN on the call",
            ),
        )
    }

    /** Balance check on the IVR: language -> main menu 2. Rest is on the call. */
    fun balance(langKey: String, delays: Delays): String = buildString {
        append(IVR_123PAY_PRIMARY)
        append(commas(delays.afterConnectSec)); append(langKey)
        append(commas(delays.betweenMenuSec)); append("2")
    }
}
