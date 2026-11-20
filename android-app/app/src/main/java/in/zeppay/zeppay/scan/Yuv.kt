package `in`.zeppay.zeppay.scan

import java.nio.ByteBuffer

/**
 * Stride-aware YUV_420_888 -> NV21 conversion.
 *
 * CameraX ImageProxy planes often carry rowStride padding and pixelStride 2
 * chroma (semi-planar). Copying plane buffers raw (the old code) corrupts the
 * image on many real devices, so QR decoding silently never succeeds there.
 * This function reads sample-by-sample honoring both strides, which is correct
 * for every layout (I420 planar, NV21/NV12 semi-planar, padded rows).
 */
object Yuv {

    data class Plane(
        val buffer: ByteBuffer,
        val rowStride: Int,
        val pixelStride: Int,
    )

    fun toNv21(width: Int, height: Int, y: Plane, u: Plane, v: Plane): ByteArray {
        val out = ByteArray(width * height * 3 / 2)

        // Luma
        var pos = 0
        val yBuf = y.buffer.duplicate()
        for (row in 0 until height) {
            val rowStart = row * y.rowStride
            if (y.pixelStride == 1) {
                yBuf.position(rowStart)
                yBuf.get(out, pos, width)
                pos += width
            } else {
                for (col in 0 until width) {
                    out[pos++] = yBuf.get(rowStart + col * y.pixelStride)
                }
            }
        }

        // Chroma (subsampled 2x2), NV21 wants VU interleaved
        val cW = width / 2
        val cH = height / 2
        val uBuf = u.buffer.duplicate()
        val vBuf = v.buffer.duplicate()
        var cPos = width * height
        for (row in 0 until cH) {
            val uRow = row * u.rowStride
            val vRow = row * v.rowStride
            for (col in 0 until cW) {
                out[cPos++] = vBuf.get(vRow + col * v.pixelStride)
                out[cPos++] = uBuf.get(uRow + col * u.pixelStride)
            }
        }
        return out
    }
}
