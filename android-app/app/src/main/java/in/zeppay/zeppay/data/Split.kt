package `in`.zeppay.zeppay.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.round

const val ME = "me"

data class Person(val id: String, val name: String, val mobile: String = "", val vpa: String = "") {
    fun payee() = Payee(vpa = vpa, mobile = if (vpa.isEmpty()) mobile else "", name = name)
    val payable get() = vpa.isNotEmpty() || mobile.isNotEmpty()
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("mobile", mobile).put("vpa", vpa)
    companion object { fun fromJson(o: JSONObject) = Person(o.optString("id"), o.optString("name"), o.optString("mobile"), o.optString("vpa")) }
}

/** [shares] maps person id (or [ME]) to the part of [amount] they owe. Shares sum to amount. */
data class Expense(val id: String, val title: String, val amount: Double, val paidBy: String, val shares: Map<String, Double>, val at: Long) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("amount", amount).put("paidBy", paidBy).put("at", at)
        .put("shares", JSONObject().apply { shares.forEach { (k, v) -> put(k, v) } })
    companion object {
        fun fromJson(o: JSONObject): Expense {
            val s = o.optJSONObject("shares") ?: JSONObject()
            return Expense(o.optString("id"), o.optString("title"), o.optDouble("amount"), o.optString("paidBy"),
                s.keys().asSequence().associateWith { s.getDouble(it) }, o.optLong("at"))
        }
    }
}

/** Money that moved between two people to settle up. [txId] links a ZepPay payment, if any. */
data class Settlement(val id: String, val from: String, val to: String, val amount: Double, val at: Long, val txId: String = "") {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("from", from).put("to", to).put("amount", amount).put("at", at).put("txId", txId)
    companion object { fun fromJson(o: JSONObject) = Settlement(o.optString("id"), o.optString("from"), o.optString("to"), o.optDouble("amount"), o.optLong("at"), o.optString("txId")) }
}

/** A split: a named group, or a one-contact split ([duo]). Stored on this phone only. */
data class Group(
    val id: String, val name: String, val people: List<Person>, val duo: Boolean,
    val expenses: List<Expense> = emptyList(), val settlements: List<Settlement> = emptyList(), val createdAt: Long = 0,
) {
    fun person(id: String): Person? = people.firstOrNull { it.id == id }
    fun nameOf(id: String) = if (id == ME) "You" else person(id)?.name ?: "Someone"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("duo", duo).put("createdAt", createdAt)
        .put("people", JSONArray().apply { people.forEach { put(it.toJson()) } })
        .put("expenses", JSONArray().apply { expenses.forEach { put(it.toJson()) } })
        .put("settlements", JSONArray().apply { settlements.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Group {
            fun <T> arr(k: String, f: (JSONObject) -> T) = (o.optJSONArray(k) ?: JSONArray()).let { a -> (0 until a.length()).map { f(a.getJSONObject(it)) } }
            return Group(o.optString("id"), o.optString("name"), arr("people", Person::fromJson), o.optBoolean("duo"),
                arr("expenses", Expense::fromJson), arr("settlements", Settlement::fromJson), o.optLong("createdAt"))
        }
    }
}

/** One line of "who owes whom" after netting. */
data class Debt(val from: String, val to: String, val amount: Double)

object SplitMath {
    fun r2(v: Double) = round(v * 100) / 100

    /** Equal split of [amount] across [ids]; the last share absorbs the paisa remainder. */
    fun equalShares(amount: Double, ids: List<String>): Map<String, Double> {
        if (ids.isEmpty()) return emptyMap()
        val base = kotlin.math.floor(amount * 100 / ids.size) / 100
        val out = LinkedHashMap<String, Double>()
        ids.forEachIndexed { i, id -> out[id] = if (i == ids.lastIndex) r2(amount - base * (ids.size - 1)) else base }
        return out
    }

    /** Pairwise netted debts: for each pair, who owes whom how much. */
    fun debts(g: Group): List<Debt> {
        val owe = HashMap<Pair<String, String>, Double>()
        fun add(from: String, to: String, v: Double) { if (from != to && v != 0.0) owe[from to to] = (owe[from to to] ?: 0.0) + v }
        for (e in g.expenses) for ((who, share) in e.shares) add(who, e.paidBy, share)
        for (s in g.settlements) add(s.to, s.from, s.amount) // a payment from A to B cancels A's debt to B
        val ids = (g.people.map { it.id } + ME).distinct()
        val out = mutableListOf<Debt>()
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val a = ids[i]; val b = ids[j]
            val net = r2((owe[a to b] ?: 0.0) - (owe[b to a] ?: 0.0))
            if (abs(net) >= 0.01) out += if (net > 0) Debt(a, b, net) else Debt(b, a, -net)
        }
        return out
    }

    /** Positive: they owe you. Negative: you owe them. */
    fun netWithMe(g: Group, personId: String): Double =
        debts(g).sumOf { d -> when { d.from == personId && d.to == ME -> d.amount; d.from == ME && d.to == personId -> -d.amount; else -> 0.0 } }

    fun totalYouOwe(gs: List<Group>) = r2(gs.sumOf { g -> debts(g).filter { it.from == ME }.sumOf { it.amount } })
    fun totalOwedToYou(gs: List<Group>) = r2(gs.sumOf { g -> debts(g).filter { it.to == ME }.sumOf { it.amount } })
}

/** A month of ZepPay-recorded payments. Not a bank statement: only payments started in ZepPay. */
data class MonthSpend(val year: Int, val month: Int, val paid: Double, val paidCount: Int, val unconfirmed: Int, val failed: Int, val top: List<Pair<String, Double>>)

object Spends {
    fun monthly(history: List<Tx>, months: Int = 6, now: Long = System.currentTimeMillis()): List<MonthSpend> {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val out = mutableListOf<MonthSpend>()
        repeat(months) {
            val y = cal.get(Calendar.YEAR); val m = cal.get(Calendar.MONTH)
            val inMonth = history.filter { t -> Calendar.getInstance().apply { timeInMillis = t.at }.let { it.get(Calendar.YEAR) == y && it.get(Calendar.MONTH) == m } }
            val paid = inMonth.filter { it.status == STATUS_SUCCESS }
            val top = paid.groupBy { it.payee.label }.mapValues { (_, v) -> SplitMath.r2(v.sumOf { it.amount }) }
                .entries.sortedByDescending { it.value }.take(3).map { it.key to it.value }
            out += MonthSpend(y, m, SplitMath.r2(paid.sumOf { it.amount }), paid.size,
                inMonth.count { it.status == STATUS_UNKNOWN || it.status == STATUS_PENDING },
                inMonth.count { it.status == STATUS_FAILED }, top)
            cal.add(Calendar.MONTH, -1)
        }
        return out
    }
}
