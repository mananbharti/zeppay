package `in`.zeppay.zeppay

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import `in`.zeppay.zeppay.data.UpiParser
import `in`.zeppay.zeppay.scan.decodePixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** QR shapes seen in the wild: Google Pay links, BharatQR/EMVCo merchant codes, logos, non-ASCII names. */
class QrCompatTest {

    /** Builds one EMVCo field: two-digit id, two-digit length, value. */
    private fun f(id: String, v: String) = id + v.length.toString().padStart(2, '0') + v

    private fun qr(text: String, ec: ErrorCorrectionLevel = ErrorCorrectionLevel.M, scale: Int = 6, logo: Double = 0.0, invert: Boolean = false): Triple<Int, Int, IntArray> {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ec, EncodeHintType.CHARACTER_SET to "UTF-8"))
        val w = m.width * scale; val h = m.height * scale
        val dark = if (invert) 0xFFFFFFFF.toInt() else 0xFF111111.toInt()
        val light = if (invert) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
        val px = IntArray(w * h) { i -> if (m.get((i % w) / scale, (i / w) / scale)) dark else light }
        if (logo > 0) { // a solid logo box over the middle, like the brand mark on payment QRs
            val s = (w * logo).toInt(); val o = (w - s) / 2
            for (y in o until o + s) for (x in o until o + s) px[y * w + x] = 0xFF1A73E8.toInt()
        }
        return Triple(w, h, px)
    }

    @Test fun googlePayStyleLinkWithExtraFields() {
        val p = UpiParser.parse("upi://pay?pa=asha.k@okaxis&pn=Asha%20Kumar&aid=uGICAgIDvh0NNUA&mode=02&purpose=00&orgid=159761&sign=MEUCIQDx+abc/de==")!!
        assertEquals("asha.k@okaxis", p.vpa)
        assertEquals("Asha Kumar", p.name)
    }

    @Test fun merchantLinkWithAmount() {
        val p = UpiParser.parse("UPI://PAY?ver=01&mode=01&pa=gpay-11223344@okbizaxis&pn=Sharma%20Tea%20Stall&mc=5814&tr=ABC123&am=40.00&cu=INR")!!
        assertEquals("gpay-11223344@okbizaxis", p.vpa)
        assertEquals("Sharma Tea Stall", p.name)
        assertEquals("40", p.amount)
    }

    @Test fun bharatQrEmvcoMerchantCode() {
        val upi = f("00", "A000000677010111") + f("01", "sharmastall@okbizaxis") + f("02", "SHARMA0001")
        val text = f("00", "01") + f("01", "12") + f("26", upi) + f("52", "5814") + f("53", "356") + f("54", "120.00") +
            f("58", "IN") + f("59", "SHARMA TEA STALL") + f("60", "PUNE") + f("63", "A1B2")
        val p = UpiParser.parse(text)!!
        assertEquals("sharmastall@okbizaxis", p.vpa)
        assertEquals("SHARMA TEA STALL", p.name)
        assertEquals("120", p.amount)
    }

    @Test fun bharatQrWithLinkInsideAndNoAmount() {
        val text = f("00", "01") + f("01", "11") + f("26", f("00", "upi://pay?pa=ravi@ybl&pn=Ravi")) + f("53", "356") + f("58", "IN") + f("59", "RAVI STORES")
        val p = UpiParser.parse(text)!!
        assertEquals("ravi@ybl", p.vpa)
        assertEquals("RAVI STORES", p.name)
        assertEquals("", p.amount)
    }

    @Test fun emvcoWithoutUpiIsRejected() {
        val card = f("00", "01") + f("01", "11") + f("04", "4111111111111111") + f("59", "SOME SHOP")
        assertNull(UpiParser.parse(card))
    }

    @Test fun linkEmbeddedInText() {
        assertEquals("ravi@ybl", UpiParser.parse("Pay with any UPI app upi://pay?pa=ravi@ybl&pn=Ravi")!!.vpa)
    }

    @Test fun hindiNameSurvivesDecoding() {
        val text = "upi://pay?pa=ram@okaxis&pn=राम कुमार"
        val (w, h, px) = qr(text)
        val decoded = decodePixels(w, h, px)
        assertEquals(text, decoded)
        assertEquals("राम कुमार", UpiParser.parse(decoded)!!.name)
    }

    @Test fun denseQrWithLogoDecodes() {
        val text = "upi://pay?pa=gpay-11223344@okbizaxis&pn=Sharma%20Tea%20Stall&mc=5814&aid=uGICAgIDvh0NNUA&tr=ABCDEF123456&tn=Payment&cu=INR&mode=02&purpose=00"
        val (w, h, px) = qr(text, ErrorCorrectionLevel.H, scale = 4, logo = 0.18)
        assertNotNull("logo QR should decode", decodePixels(w, h, px))
        assertEquals("Sharma Tea Stall", UpiParser.parse(decodePixels(w, h, px))!!.name)
    }

    @Test fun invertedQrDecodes() {
        val text = "upi://pay?pa=asha.k@okaxis&pn=Asha"
        val (w, h, px) = qr(text, invert = true)
        assertEquals(text, decodePixels(w, h, px))
    }
}
