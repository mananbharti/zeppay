package `in`.zeppay.zeppay

import `in`.zeppay.zeppay.data.Carrier
import `in`.zeppay.zeppay.data.Dtmf
import `in`.zeppay.zeppay.data.Lib
import `in`.zeppay.zeppay.data.Payee
import `in`.zeppay.zeppay.data.UpiParser
import `in`.zeppay.zeppay.data.UssdChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpaTest {
    @Test fun acceptsValid() {
        assertTrue(Lib.isValidVpa("ravi.k@okaxis"))
        assertTrue(Lib.isValidVpa("9876543210@ybl"))
    }
    @Test fun rejectsInvalid() {
        for (v in listOf("", "abc", "a@b", "@ybl", "x y@ybl")) assertFalse(Lib.isValidVpa(v))
    }
}

class UpiParseTest {
    @Test fun parsesFullLink() {
        val p = UpiParser.parse("upi://pay?pa=shop@okicici&pn=Chai%20Point&am=120.50&tn=tea&cu=INR")!!
        assertEquals("shop@okicici", p.vpa)
        assertEquals("Chai Point", p.name)
        assertEquals("120.5", p.amount)
        assertEquals("tea", p.note)
    }
    @Test fun parsesBareVpa() {
        assertEquals("me@ybl", UpiParser.parse(" me@ybl ")!!.vpa)
    }
    @Test fun rejectsJunk() {
        assertNull(UpiParser.parse("https://example.com"))
        assertNull(UpiParser.parse("upi://pay?pn=x"))
        assertNull(UpiParser.parse("upi://pay?pa=bad"))
        assertNull(UpiParser.parse(""))
        assertNull(UpiParser.parse(null))
    }
    @Test fun dropsZeroAmount() {
        assertEquals("", UpiParser.parse("upi://pay?pa=a1@ybl&am=0")!!.amount)
    }
    @Test fun famPayloads() {
        val p = UpiParser.parse("upi://pay?pa=sample.user@fam&pn=Scanner%20Test&am=35&tr=ORDER12345&tn=ORDER12345&cu=INR")!!
        assertEquals("sample.user@fam", p.vpa)
        assertEquals("35", p.amount)
        assertEquals("ORDER12345", p.note)
    }
}

class AmountTest {
    @Test fun bounds() {
        assertTrue(Lib.validateAmount("").contains("Enter"))
        assertTrue(Lib.validateAmount("0").contains("Minimum"))
        assertTrue(Lib.validateAmount("5001").contains("5,000"))
        assertTrue(Lib.validateAmount("10.999").contains("decimals"))
        assertEquals("", Lib.validateAmount("1"))
        assertEquals("", Lib.validateAmount("5000"))
        assertEquals("", Lib.validateAmount("99.5"))
    }
}

class MiscTest {
    @Test fun formatINR() {
        assertEquals("₹12,34,567", Lib.formatINR(1234567.0))
        assertEquals("₹10.50", Lib.formatINR(10.5))
        assertEquals("₹1", Lib.formatINR(1.0))
        assertEquals("₹250", Lib.formatINR(250.0))
    }
    @Test fun telUriEncodesHash() {
        assertEquals("tel:*99*1*3%23", UssdChain.telUri("*99*1*3#"))
    }
    @Test fun carrierDetect() {
        assertEquals(Carrier.JIO, Carrier.detect("Jio 4G"))
        assertEquals(Carrier.JIO, Carrier.detect("Reliance Jio"))
        assertEquals(Carrier.AIRTEL, Carrier.detect("airtel"))
        assertEquals(Carrier.VI, Carrier.detect("Vi India"))
        assertEquals(Carrier.BSNL, Carrier.detect("BSNL Mobile"))
        assertNull(Carrier.detect(""))
        assertFalse(Carrier.JIO.ussdOk)
        assertTrue(Carrier.AIRTEL.ussdOk)
    }
    @Test fun initials() {
        assertEquals("RK", Lib.initials("Ravi Kumar"))
        assertEquals("?", Lib.initials(""))
    }
    @Test fun mobile() {
        assertTrue(Lib.isValidMobile("98765 43210"))
        assertTrue(Lib.isValidMobile("+919876543210"))
        assertFalse(Lib.isValidMobile("12345"))
        assertFalse(Lib.isValidMobile("5876543210"))
        assertEquals("9876543210", Lib.normMobile("+91 98765-43210"))
    }
    @Test fun accountIfsc() {
        assertTrue(Lib.isValidAccount("50100078635437"))
        assertFalse(Lib.isValidAccount("1234"))
        assertTrue(Lib.isValidAccount("5010 0078 635437"))
        assertTrue(Lib.isValidIfsc("HDFC0000798"))
        assertTrue(Lib.isValidIfsc("hdfc0000798"))
        assertFalse(Lib.isValidIfsc("HDFC1000798"))
        assertEquals("HDFC0000798", Lib.normIfsc("hdfc0000798"))
    }
}

/** The Android full fast-path chains: payee + amount + remark embedded, PIN prompt last. */
class UssdChainTest {
    @Test fun mobileWithAmountNoNote() {
        // Remark 1 disables remarks per the NUUP spec, so only the PIN prompt is left.
        assertEquals(
            "*99*1*1*9876543210*1*1#",
            UssdChain.pay(Payee(mobile = "9876543210"), 1.0, ""),
        )
    }
    @Test fun mobileWithAmountAndNote() {
        assertEquals(
            "*99*1*1*9876543210*250*chai#",
            UssdChain.pay(Payee(mobile = "9876543210"), 250.0, "chai"),
        )
    }
    @Test fun vpaChain() {
        assertEquals(
            "*99*1*3*ravi.k@okaxis*99.50*dinner#",
            UssdChain.pay(Payee(vpa = "ravi.k@okaxis"), 99.5, "dinner"),
        )
    }
    @Test fun vpaNoNote() {
        assertEquals(
            "*99*1*3*ravi.k@okaxis*500*1#",
            UssdChain.pay(Payee(vpa = "ravi.k@okaxis"), 500.0, "  "),
        )
    }
    @Test fun bankChainEmbedsIfscAccountAmount() {
        assertEquals(
            "*99*1*5*HDFC0000798*50100078635437*100*1#",
            UssdChain.pay(Payee(account = "50100078635437", ifsc = "HDFC0000798"), 100.0, ""),
        )
    }
    @Test fun remarkSanitizesUssdChars() {
        // * and # would break the chain - stripped; spaces collapsed; capped at 40 chars.
        assertEquals(
            "*99*1*1*9876543210*10*hello world#",
            UssdChain.pay(Payee(mobile = "9876543210"), 10.0, "hello*#   world"),
        )
    }
    @Test fun emptyPayeeFallsBackToMenu() {
        assertEquals("*99#", UssdChain.pay(Payee(), 10.0, ""))
    }
    @Test fun balanceChain() {
        assertEquals("*99*3#", UssdChain.balance())
    }
    @Test fun amountTokens() {
        assertEquals("1", UssdChain.amountToken(1.0))
        assertEquals("250", UssdChain.amountToken(250.0))
        assertEquals("99.50", UssdChain.amountToken(99.5))
    }
}

class DtmfTest {
    private val delays = Dtmf.Delays(6, 4, 4)

    @Test
    fun mobilePayeeBuildsFullSequence() {
        val p = Dtmf.pay(Payee(mobile = "9810012345", name = "Rohan"), 250.0, "1", delays)
        assertTrue(p.possible)
        assertTrue(p.dialString.startsWith("08045163666,"))
        assertTrue(p.dialString.contains(",1,"))   // language Hindi
        assertTrue(p.dialString.contains("9810012345"))
        assertTrue(p.dialString.contains("250"))
        assertTrue(p.dialString.endsWith(",1"))    // final continue
    }

    @Test
    fun vpaOnlyPayeeIsBlocked() {
        val p = Dtmf.pay(Payee(vpa = "priya@okhdfc"), 100.0, "1", delays)
        assertTrue(!p.possible)
        assertTrue(p.blockedReason.contains("mobile number"))
    }

    @Test
    fun accountPayeeUsesAccountPath() {
        val p = Dtmf.pay(Payee(account = "12345678901", ifsc = "SBIN0001234"), 500.0, "2", delays)
        assertTrue(p.possible)
        assertTrue(p.dialString.contains(",2,"))   // language English + pay-via-account=2
        assertTrue(p.dialString.contains("12345678901"))
    }

    @Test
    fun manualStepsKeepPinManual() {
        val p = Dtmf.pay(Payee(mobile = "9810012345"), 10.0, "1", delays)
        assertTrue(p.manualSummary.any { it.contains("UPI PIN") })
        assertTrue(p.manualSummary.any { it.contains("callback") || it.contains("calls you back") })
    }

    @Test
    fun delaysScaleCommas() {
        val fast = Dtmf.pay(Payee(mobile = "9810012345"), 10.0, "1", Dtmf.Delays(2, 2, 2))
        val slow = Dtmf.pay(Payee(mobile = "9810012345"), 10.0, "1", Dtmf.Delays(14, 10, 10))
        assertTrue(slow.dialString.length > fast.dialString.length)
    }
}
