package `in`.zeppay.zeppay

import `in`.zeppay.zeppay.data.Expense
import `in`.zeppay.zeppay.data.Group
import `in`.zeppay.zeppay.data.ME
import `in`.zeppay.zeppay.data.Payee
import `in`.zeppay.zeppay.data.Person
import `in`.zeppay.zeppay.data.STATUS_SUCCESS
import `in`.zeppay.zeppay.data.STATUS_UNKNOWN
import `in`.zeppay.zeppay.data.Settlement
import `in`.zeppay.zeppay.data.SplitMath
import `in`.zeppay.zeppay.data.Spends
import `in`.zeppay.zeppay.data.Tx
import org.junit.Assert.assertEquals
import org.junit.Test

class SplitTest {
    private val asha = Person("a", "Asha", mobile = "9876543210")
    private val ravi = Person("r", "Ravi")

    @Test fun equalSharesSumExactly() {
        val s = SplitMath.equalShares(100.0, listOf(ME, "a", "r"))
        assertEquals(100.0, s.values.sum(), 0.001)
        assertEquals(33.33, s[ME]!!, 0.001)
    }

    @Test fun debtsNetAndSettle() {
        var g = Group("g", "Trip", listOf(asha, ravi), false, listOf(
            Expense("1", "Dinner", 300.0, ME, SplitMath.equalShares(300.0, listOf(ME, "a", "r")), 0),
            Expense("2", "Cab", 90.0, "a", SplitMath.equalShares(90.0, listOf(ME, "a")), 0),
        ))
        assertEquals(55.0, SplitMath.netWithMe(g, "a"), 0.001)   // Asha owes 100, I owe her 45
        assertEquals(100.0, SplitMath.netWithMe(g, "r"), 0.001)
        g = g.copy(settlements = listOf(Settlement("s", "a", ME, 55.0, 0)))
        assertEquals(0.0, SplitMath.netWithMe(g, "a"), 0.001)
        assertEquals(100.0, SplitMath.totalOwedToYou(listOf(g)), 0.001)
    }

    @Test fun spendsCountOnlyConfirmedPayments() {
        val now = System.currentTimeMillis()
        val h = listOf(
            Tx("1", Payee(vpa = "x@y", name = "X"), 100.0, "", STATUS_SUCCESS, now, "ussd"),
            Tx("2", Payee(vpa = "x@y", name = "X"), 50.0, "", STATUS_UNKNOWN, now, "ussd"),
        )
        val m = Spends.monthly(h, 1, now).first()
        assertEquals(100.0, m.paid, 0.001); assertEquals(1, m.unconfirmed)
    }
}
