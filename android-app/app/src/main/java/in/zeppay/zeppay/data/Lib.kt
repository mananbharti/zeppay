package `in`.zeppay.zeppay.data

import java.net.URLDecoder
import java.util.Locale

const val MAX_AMOUNT = 5000.0
const val MIN_AMOUNT = 1.0
const val USSD_MENU = "*99#"
const val USSD_BALANCE = "*99*3#"

// UPI 123PAY IVR numbers (NPCI): works on any SIM incl. Jio. Source: NPCI UPI 123PAY product booklet.
const val IVR_123PAY_PRIMARY = "08045163666"
const val IVR_123PAY_ALT = "08045163581"
const val IVR_123PAY_TONETAG = "6366200200"

/** Who the money goes to. Exactly one of vpa / mobile / account(+ifsc) is set. */
data class Payee(
    val vpa: String = "",
    val mobile: String = "",
    val account: String = "",
    val ifsc: String = "",
    val name: String = "",
) {
    val label: String
        get() = name.ifEmpty {
            when {
                vpa.isNotEmpty() -> vpa
                mobile.isNotEmpty() -> mobile
                account.isNotEmpty() -> "A/c $account"
                else -> ""
            }
        }
    val target: String
        get() = when {
            vpa.isNotEmpty() -> vpa
            mobile.isNotEmpty() -> mobile
            else -> account
        }
}

enum class Carrier(val id: String, val label: String, val ussdOk: Boolean) {
    AIRTEL("airtel", "Airtel", true),
    VI("vi", "Vi", true),
    BSNL("bsnl", "BSNL", true),
    JIO("jio", "Jio", false),
    OTHER("other", "Other", true);

    companion object {
        fun fromId(id: String): Carrier = entries.firstOrNull { it.id == id } ?: OTHER

        /** Best-effort SIM carrier detection from the operator name. null = could not tell. */
        fun detect(operatorName: String?): Carrier? {
            val n = (operatorName ?: "").trim().lowercase(Locale.US)
            if (n.isEmpty()) return null
            return when {
                n.contains("jio") || n.contains("reliance") -> JIO
                n.contains("airtel") -> AIRTEL
                n.contains("vodafone") || n.contains("idea") || n == "vi" || n.startsWith("vi ") || n.contains(" vi ") -> VI
                n.contains("bsnl") -> BSNL
                else -> null
            }
        }
    }
}

object Lib {
    private val VPA_RE = Regex("^[a-zA-Z0-9._-]{2,256}@[a-zA-Z][a-zA-Z0-9]{1,63}$")
    private val MOBILE_RE = Regex("^[6-9]\\d{9}$")
    private val ACCOUNT_RE = Regex("^\\d{9,18}$")
    private val IFSC_RE = Regex("^[A-Z]{4}0[A-Z0-9]{6}$")

    fun isValidVpa(v: String): Boolean = VPA_RE.matches(v.trim())

    fun normMobile(v: String): String =
        v.replace(Regex("[\\s-]"), "").replace(Regex("^(\\+91|91|0)(?=\\d{10}$)"), "")

    fun isValidMobile(v: String): Boolean = MOBILE_RE.matches(normMobile(v))

    fun normAccount(v: String): String = v.replace(Regex("[\\s-]"), "")
    fun isValidAccount(v: String): Boolean = ACCOUNT_RE.matches(normAccount(v))

    fun normIfsc(v: String): String = v.trim().uppercase(Locale.US)
    fun isValidIfsc(v: String): Boolean = IFSC_RE.matches(normIfsc(v))

    fun validateAmount(raw: String): String {
        if (raw.isBlank()) return "Enter an amount"
        val n = raw.toDoubleOrNull() ?: return "Enter an amount"
        if (n < MIN_AMOUNT) return "Minimum is ₹1"
        if (n > MAX_AMOUNT) return "*99# allows up to ₹5,000 per payment"
        if (Math.round(n * 100).toDouble() != n * 100) return "Use at most 2 decimals"
        return ""
    }

    /** Indian digit grouping, no decimals for whole rupees, 2 decimals otherwise. */
    fun formatINR(n: Double): String {
        val whole = kotlin.math.floor(n).toLong()
        val frac = Math.round((n - whole) * 100)
        val w = if (frac == 100L) whole + 1 else whole
        val f = if (frac == 100L) 0L else frac
        val grouped = groupIndian(w)
        return if (f == 0L) "₹$grouped" else "₹$grouped.${f.toString().padStart(2, '0')}"
    }

    private fun groupIndian(n: Long): String {
        val s = n.toString()
        if (s.length <= 3) return s
        val last3 = s.takeLast(3)
        val rest = s.dropLast(3)
        val sb = StringBuilder()
        var i = rest.length
        while (i > 0) {
            val from = maxOf(0, i - 2)
            sb.insert(0, rest.substring(from, i))
            if (from > 0) sb.insert(0, ",")
            i = from
        }
        return "$sb,$last3"
    }

    fun initials(name: String): String {
        val parts = name.substringBefore('@').split(Regex("[\\s._-]+")).filter { it.isNotEmpty() }
        val a = parts.firstOrNull()?.firstOrNull() ?: '?'
        val b = parts.getOrNull(1)?.firstOrNull()
        return (a.toString() + (b?.toString() ?: "")).uppercase(Locale.US)
    }
}

data class UpiPayload(val vpa: String, val name: String, val amount: String, val note: String)

/** Parses upi://pay?pa=..&pn=..&am=..&tn=.. or a bare VPA. Returns null when not UPI. */
object UpiParser {
    fun parse(text: String?): UpiPayload? {
        val s = text?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (Lib.isValidVpa(s)) return UpiPayload(s, "", "", "")
        if (!s.startsWith("upi://", ignoreCase = true)) return null
        val q = s.substringAfter('?', "")
        val params = LinkedHashMap<String, String>()
        for (pair in q.split('&')) {
            val k = pair.substringBefore('=').lowercase(Locale.US)
            val v = pair.substringAfter('=', "")
            if (k.isNotEmpty()) params[k] = try {
                URLDecoder.decode(v.replace('+', '\u0002'), "UTF-8").replace('\u0002', '+')
            } catch (e: Exception) { v }
        }
        val pa = params["pa"]?.trim().orEmpty()
        if (!Lib.isValidVpa(pa)) return null
        val am = params["am"]?.trim().orEmpty()
        val amOk = am.toDoubleOrNull()?.let { it > 0 } == true
        return UpiPayload(
            vpa = pa,
            name = params["pn"]?.trim()?.take(60).orEmpty(),
            amount = if (amOk) trimNum(am) else "",
            note = params["tn"]?.trim()?.take(50).orEmpty(),
        )
    }

    private fun trimNum(am: String): String {
        val d = am.toDoubleOrNull() ?: return am
        return if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
    }
}

/**
 * Builds the full *99# fast-path command with payee, amount and remark embedded,
 * so the bank session opens already at the UPI PIN prompt.
 *
 * Chain shapes (NUUP spec, github.com/librefin-in/nuup-specification):
 *  - mobile:  *99*1*1*<mobile>*<amount>*<remark-or-1>#
 *  - UPI ID:  *99*1*3*<vpa>*<amount>*<remark-or-1>#
 *  - bank:    *99*1*5*<ifsc>*<account>*<amount>*<remark-or-1>#   (amount/remark embed needs device test)
 * Remark `1` disables remarks when the user left the note empty (per spec).
 */
object UssdChain {
    fun sanitizeRemark(raw: String): String =
        raw.replace(Regex("[*#]"), "").replace(Regex("\\s+"), " ").trim().take(40)

    fun amountToken(amount: Double): String =
        if (amount % 1.0 == 0.0) amount.toLong().toString()
        else String.format(Locale.US, "%.2f", amount)

    fun pay(payee: Payee, amount: Double, note: String): String {
        val remark = sanitizeRemark(note).ifEmpty { "1" }
        val amt = amountToken(amount)
        return when {
            payee.vpa.isNotEmpty() -> "*99*1*3*${payee.vpa}*$amt*$remark#"
            payee.mobile.isNotEmpty() -> "*99*1*1*${payee.mobile}*$amt*$remark#"
            payee.account.isNotEmpty() -> "*99*1*5*${payee.ifsc}*${payee.account}*$amt*$remark#"
            else -> USSD_MENU
        }
    }

    fun balance(): String = USSD_BALANCE

    /** tel: URI for ACTION_CALL / ACTION_DIAL. '#' must be %23-encoded. */
    fun telUri(code: String): String = "tel:" + code.replace("#", "%23")
}

/** What a payment attempt should do first, given the carrier. */
enum class PaymentRoute { USSD_BACKGROUND, IVR_123PAY }

fun paymentRoute(carrier: Carrier): PaymentRoute =
    if (carrier.ussdOk) PaymentRoute.USSD_BACKGROUND else PaymentRoute.IVR_123PAY
