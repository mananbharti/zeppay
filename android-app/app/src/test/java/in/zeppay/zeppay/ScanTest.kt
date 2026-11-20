package `in`.zeppay.zeppay

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import `in`.zeppay.zeppay.data.UpiParser
import `in`.zeppay.zeppay.scan.Yuv
import `in`.zeppay.zeppay.scan.decodePixels
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanTest {

    private val payload = "upi://pay?pa=priya@okhdfc&pn=Priya%20Sharma&am=250&tn=Chai"

    private fun qrPixels(text: String, scale: Int = 8): Triple<Int, Int, IntArray> {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0)
        val w = m.width * scale
        val h = m.height * scale
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            px[y * w + x] = if (m.get(x / scale, y / scale)) 0xFF111213.toInt() else 0xFFFFFFFF.toInt()
        }
        return Triple(w, h, px)
    }

    @Test
    fun decodesUpiQr() {
        val (w, h, px) = qrPixels(payload)
        val text = decodePixels(w, h, px)
        assertEquals(payload, text)
        val p = UpiParser.parse(text)
        assertNotNull(p)
        assertEquals("priya@okhdfc", p!!.vpa)
        assertEquals("Priya Sharma", p.name)
        assertEquals("250", p.amount)
    }

    @Test
    fun decodesRotatedQr() {
        val (w, h, px) = qrPixels(payload)
        // rotate 90 degrees: QR must decode in any camera orientation
        val rot = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            rot[x * h + (h - 1 - y)] = px[y * w + x]
        }
        assertEquals(payload, decodePixels(h, w, rot))
    }

    @Test
    fun yuvPlanarTight() {
        // 4x4 I420, pixelStride 1, no padding
        val y = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16))
        val u = ByteBuffer.wrap(byteArrayOf(100, 101, 102, 103))
        val v = ByteBuffer.wrap(byteArrayOf(110, 111, 112, 113))
        val out = Yuv.toNv21(
            4, 4,
            Yuv.Plane(y, 4, 1), Yuv.Plane(u, 2, 1), Yuv.Plane(v, 2, 1),
        )
        val expected = byteArrayOf(
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            110, 100, 111, 101, 112, 102, 113, 103,
        )
        assertArrayEquals(expected, out)
    }

    @Test
    fun yuvSemiPlanarNv21RoundTrip() {
        // NV21 semi-planar: VU interleaved chroma, pixelStride 2, like real camera output
        val w = 8; val h = 4
        val yBytes = ByteArray(w * h) { (it * 3).toByte() }
        val chroma = ByteArray(w * h / 2) { (200 - it).toByte() } // V,U,V,U...
        val nv21 = yBytes + chroma
        val yBuf = ByteBuffer.wrap(yBytes)
        // V plane starts at chroma[0], U plane at chroma[1], both pixelStride 2
        val vBuf = ByteBuffer.wrap(chroma, 0, chroma.size - 1).slice()
        val uBuf = ByteBuffer.wrap(chroma, 1, chroma.size - 1).slice()
        val out = Yuv.toNv21(
            w, h,
            Yuv.Plane(yBuf, w, 1),
            Yuv.Plane(uBuf, w, 2),
            Yuv.Plane(vBuf, w, 2),
        )
        assertArrayEquals(nv21, out)
    }

    @Test
    fun yuvRowPadding() {
        // Y plane with rowStride 6 for width 4 (2 bytes padding per row)
        val w = 4; val h = 2; val stride = 6
        val yRaw = byteArrayOf(1, 2, 3, 4, 99, 99, 5, 6, 7, 8, 99, 99)
        val y = ByteBuffer.wrap(yRaw)
        val u = ByteBuffer.wrap(byteArrayOf(50, 51))
        val v = ByteBuffer.wrap(byteArrayOf(60, 61))
        val out = Yuv.toNv21(w, h, Yuv.Plane(y, stride, 1), Yuv.Plane(u, 2, 1), Yuv.Plane(v, 2, 1))
        val expected = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 60, 50, 61, 51)
        assertArrayEquals(expected, out)
    }

    @Test
    fun rejectsNonUpiQr() {
        val (w, h, px) = qrPixels("https://example.com")
        val text = decodePixels(w, h, px)
        assertEquals("https://example.com", text)
        assertTrue(UpiParser.parse(text) == null)
    }
}
