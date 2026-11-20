package `in`.zeppay.zeppay.ussd

import java.util.Locale

/*
 * Stepwise *99# flow logic. Pure Kotlin: no Android types, fully unit-tested.
 *
 * Approach (studied from github.com/laksh-ya/OffPayApp, MIT, rewritten here, not copied):
 * dial only the menu code (e.g. *99*1*3#), then answer the carrier's prompts one at a
 * time. Two deliberate departures from that reference:
 *  1. ZepPay NEVER answers a PIN prompt. When the bank asks for the UPI PIN the flow
 *     hands the screen to the user and only watches for the next, non-editable frame.
 *  2. A payment counts as Paid only from a payment-confirmation sentence after the PIN
 *     step. A bare reference number, a generic "thank you", a balance reply or a
 *     conflicting message is Unknown, shown with the carrier's raw text.
 *
 * Every prompt regex here is a starting point. Real frame texts must be captured on the
 * user's own SIM (Settings > Carrier menu check) and these patterns tuned against them.
 */

enum class Op(val code: String, val needsPin: Boolean, val isPayment: Boolean) {
    PAY_VPA("*99*1*3#", true, true),
    PAY_MOBILE("*99*1*1#", true, true),
    PAY_ACCOUNT("*99*1*5#", true, true),
    BALANCE("*99*3#", true, false),
    HISTORY("*99*6#", true, false), // menu position unverified: enabled only after discovery
    DISCOVER("*99#", false, false),
}

/** One screen of the carrier dialog, as read by the accessibility service (editable fields excluded). */
data class Frame(val text: String, val hasInput: Boolean)

data class Step(val key: String, val match: Regex, val label: String)

enum class OutcomeKind {
    PAID,       // payment flow's own confirmation, after the PIN step
    FAILED,     // the carrier/bank said it failed
    UNKNOWN,    // ambiguous, conflicting, unreadable, or the session ended after the PIN step
    STOPPED,    // ended before the PIN step: no money can have moved
    INFO,       // balance / history / menu text: never a payment result
}

data class Outcome(val kind: OutcomeKind, val raw: String, val reason: String)

sealed interface Decision {
    data class Reply(val text: String, val label: String) : Decision
    /** The bank wants the UPI PIN. ZepPay steps aside and types nothing. */
    data object PinHandoff : Decision
    /** A prompt ZepPay does not recognise (e.g. pick an account). The user answers it. */
    data class NeedsUser(val prompt: String) : Decision
    data object Wait : Decision
    data class Finish(val outcome: Outcome) : Decision
}

private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

object Patterns {
    val PIN = rx("\\b(upi\\s*)?pin\\b|\\bm-?pin\\b|पिन")

    val SUCCESS = listOf(
        rx("\\b(payment|transaction|txn|transfer|fund\\s+transfer)\\b[^\\n]{0,80}?\\b(successful(ly)?|success|completed)\\b"),
        rx("\\bsuccessfully\\s+(sent|paid|transferred|debited|completed)\\b"),
        rx("\\b(sent|paid|transferred)\\b[^\\n]{0,80}?\\bsuccessfully\\b"),
    )

    val NEGATED_SUCCESS = rx("\\b(not|un)\\s*success(ful(ly)?)?\\b|\\bunsuccessful\\b")

    val FAILURE = listOf(
        rx("\\bnot\\s+(a\\s+)?(valid|registered|recogni[sz]ed|allowed|found|active|enabled|supported)\\b"),
        rx("\\bnot\\s+(debited|sent|processed|completed|successful)\\b"),
        rx("\\bunsuccessful\\b"),
        rx("\\b(transaction|payment|txn|transfer)\\s+(has\\s+)?(been\\s+)?(declined|failed|cancelled|canceled|denied|aborted|rejected)\\b"),
        rx("\\b(declined|failed|rejected)\\b"),
        rx("unable\\s+to\\s+process"),
        rx("\\b(invalid|incorrect|wrong)\\s+(upi\\s+)?(pin|upi|vpa|amount|input|entry|id|number|account|ifsc|mobile)\\b"),
        rx("\\b(pin|upi\\s*pin|upi\\s*id|vpa|ifsc)\\s+(is\\s+)?(incorrect|invalid|wrong|mismatch)\\b"),
        rx("\\b(no|not\\s+a)\\s+(account|user|customer)\\s+(found|registered|exists)\\b"),
        rx("account\\s+(not\\s+found|does\\s+not\\s+exist)"),
        rx("insufficient\\s+(funds|balance)"),
        rx("exceed(s|ed)?\\s+(the\\s+)?(daily\\s+)?(limit|amount)"),
        rx("service\\s+(unavailable|not\\s+available|down)"),
        rx("try\\s+again\\s+later|temporarily\\s+unavailable"),
        rx("session\\s+(timed\\s*out|expired|terminated)"),
        rx("could\\s+not\\s+find\\s+(your|ur)\\s+bank|bank\\s+not\\s+found|no\\s+bank\\s+(linked|found)"),
        rx("max(imum)?\\s+(pin\\s+)?(attempts|tries|retries)|pin\\s+(blocked|locked)"),
        rx("connection\\s+problem|invalid\\s+mmi\\s+code|ussd\\s+code\\s+running"),
    )

    /** Rupee amounts mentioned in a frame ("Rs 250", "INR 250.00", "₹250"). */
    val AMOUNT = rx("(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")

    /** Lines like "1. Send Money" or "3 Check Balance" in a menu frame. */
    val MENU_ITEM = Regex("^\\s*(\\d{1,2})\\s*[.):-]?\\s+(.{2,60})$", RegexOption.MULTILINE)
}

object Flows {
    fun steps(op: Op): List<Step> = when (op) {
        Op.PAY_VPA -> listOf(
            Step("vpa", rx("\\b(upi\\s*id|vpa|virtual\\s+payment|payee\\s+address|beneficiary\\s+(upi|vpa))\\b"), "Typing the UPI ID"),
            amount, remark,
        )
        Op.PAY_MOBILE -> listOf(
            Step("mobile", rx("\\b(mobile|phone)\\s*(no\\.?|number)?\\b"), "Typing the mobile number"),
            amount, remark,
        )
        Op.PAY_ACCOUNT -> listOf(
            Step("ifsc", rx("\\bifsc\\b"), "Typing the IFSC"),
            Step("account", rx("\\b(account|a/c)\\s*(no\\.?|number)\\b|\\bbeneficiary\\s+account\\b"), "Typing the account number"),
            amount, remark,
        )
        else -> emptyList()
    }

    private val amount = Step("amount", rx("\\bamount\\b"), "Typing the amount")
    private val remark = Step("remark", rx("\\b(remark|remarks|comment|note|purpose)\\b"), "Adding the note")

    /** Values typed into prompts. Never contains a PIN: there is no PIN anywhere in ZepPay. */
    fun vars(op: Op, vpa: String, mobile: String, account: String, ifsc: String, amount: Double, note: String): Map<String, String> {
        val amt = if (amount % 1.0 == 0.0) amount.toLong().toString() else String.format(Locale.US, "%.2f", amount)
        // "1" is the documented "no remark" reply in the public NUUP spec. Unverified on real SIMs.
        val rem = note.replace(Regex("[*#]"), "").replace(Regex("\\s+"), " ").trim().take(40).ifEmpty { "1" }
        return mapOf("vpa" to vpa, "mobile" to mobile, "account" to account, "ifsc" to ifsc, "amount" to amt, "remark" to rem)
    }

    /** Parses "1. Send Money" style menu lines. Used by the carrier-menu check. */
    fun menuItems(text: String): List<Pair<String, String>> =
        Patterns.MENU_ITEM.findAll(text).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()
}

/** Classifies a terminal (or post-PIN) frame for a payment flow. */
object Classifier {
    fun payment(text: String, pinSeen: Boolean, expectedAmount: Double?): Outcome {
        val cleaned = Patterns.NEGATED_SUCCESS.replace(text, " ")
        val success = Patterns.SUCCESS.any { it.containsMatchIn(cleaned) }
        val failure = Patterns.FAILURE.any { it.containsMatchIn(text) }
        return when {
            success && failure -> Outcome(OutcomeKind.UNKNOWN, text, "The bank's message says both success and failure.")
            success && !pinSeen -> Outcome(OutcomeKind.UNKNOWN, text, "A success message arrived before the PIN step, which should not happen.")
            success && expectedAmount != null && !amountMatches(text, expectedAmount) ->
                Outcome(OutcomeKind.UNKNOWN, text, "The amount in the bank's message doesn't match this payment.")
            success -> Outcome(OutcomeKind.PAID, text, "Your bank's *99# reply confirmed this payment.")
            failure && !pinSeen -> Outcome(OutcomeKind.STOPPED, text, "Stopped before the PIN step. No money should have moved.")
            failure -> Outcome(OutcomeKind.FAILED, text, "Your bank's reply says this payment failed.")
            else -> Outcome(OutcomeKind.UNKNOWN, text, "ZepPay couldn't read a clear result from the bank's reply.")
        }
    }

    /** True when no amount is mentioned, or one of the mentioned amounts equals [expected]. */
    fun amountMatches(text: String, expected: Double): Boolean {
        val found = Patterns.AMOUNT.findAll(text).mapNotNull { it.groupValues[1].replace(",", "").toDoubleOrNull() }.toList()
        return found.isEmpty() || found.any { kotlin.math.abs(it - expected) < 0.005 }
    }
}

/**
 * The per-session state machine. The engine feeds it frames; it answers with a [Decision].
 * Holding no Android state makes every rule testable against captured carrier texts.
 */
class FlowRunner(private val op: Op, private val vars: Map<String, String>, private val expectedAmount: Double?) {
    private val steps = Flows.steps(op)
    private val answered = mutableSetOf<String>()
    var pinSeen = false; private set
    var finished = false; private set

    fun onFrame(f: Frame): Decision {
        if (finished) return Decision.Wait
        val text = f.text.trim()
        if (text.isEmpty()) return Decision.Wait

        if (op == Op.DISCOVER) return finish(Outcome(OutcomeKind.INFO, text, "Your SIM's *99# menu"))

        // 1. Any input frame that mentions a PIN (first ask, or "wrong PIN, try again"): hand over.
        //    Never reply, whatever else matches.
        if (f.hasInput && Patterns.PIN.containsMatchIn(text)) {
            pinSeen = true
            return Decision.PinHandoff
        }

        // 2. Frames without an input box are results.
        if (!f.hasInput) return finish(classify(text))

        // 3. Input frame after the PIN (e.g. "Paid ... 1. Save contact 2. Exit"): classify if clear.
        if (pinSeen) {
            val o = classify(text)
            return if (o.kind == OutcomeKind.UNKNOWN) Decision.NeedsUser(text) else finish(o)
        }

        // 4. Failure text on an input frame before the PIN (e.g. "Invalid UPI ID, re-enter").
        if (op.isPayment && looksLikeFailure(text)) return finish(Outcome(OutcomeKind.STOPPED, text, "The bank rejected a detail before the PIN step. No money should have moved."))

        // 5. A prompt we know and haven't answered yet.
        val step = steps.firstOrNull { it.key !in answered && it.match.containsMatchIn(text) }
        if (step != null) {
            val value = vars[step.key].orEmpty()
            if (value.isEmpty()) return Decision.NeedsUser(text)
            answered += step.key
            return Decision.Reply(value, step.label)
        }

        // 6. Anything else (choose account, a repeated prompt, a new menu): the user decides.
        return Decision.NeedsUser(text)
    }

    /** The carrier dialog vanished, or a timeout fired. */
    fun onSessionEnded(reason: String): Outcome {
        val o = if (op.isPayment && pinSeen) Outcome(OutcomeKind.UNKNOWN, reason, "The bank's screen closed after the PIN step before ZepPay could read a result.")
        else if (op.isPayment) Outcome(OutcomeKind.STOPPED, reason, "Stopped before the PIN step. No money should have moved.")
        else Outcome(OutcomeKind.INFO, reason, "The session ended.")
        finished = true
        return o
    }

    private fun classify(text: String): Outcome =
        if (op.isPayment) Classifier.payment(text, pinSeen, expectedAmount)
        else Outcome(OutcomeKind.INFO, text, if (op == Op.BALANCE) "Your bank's balance reply" else "Your bank's reply")

    private fun looksLikeFailure(text: String) = Patterns.FAILURE.any { it.containsMatchIn(text) }

    private fun finish(o: Outcome): Decision {
        finished = true
        return Decision.Finish(o)
    }
}
