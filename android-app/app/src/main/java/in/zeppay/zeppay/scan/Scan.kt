package `in`.zeppay.zeppay.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import `in`.zeppay.zeppay.data.UpiPayload
import `in`.zeppay.zeppay.data.UpiParser
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

private fun newReader(): MultiFormatReader {
    val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        // Payee names can be non-ASCII (Hindi, Tamil...): read QR bytes as UTF-8, not Latin-1.
        DecodeHintType.CHARACTER_SET to "UTF-8",
        // Some printed and on-screen QRs are light-on-dark.
        DecodeHintType.ALSO_INVERTED to true,
    )
    return MultiFormatReader().apply { setHints(hints) }
}

/** Tries the local-contrast binarizer first (logos, shadows), then the global one (screens, glare). */
fun decodeLuminance(source: LuminanceSource): String? {
    val reader = newReader()
    for (bin in listOf<(LuminanceSource) -> com.google.zxing.Binarizer>({ HybridBinarizer(it) }, { GlobalHistogramBinarizer(it) })) {
        try { return reader.decodeWithState(BinaryBitmap(bin(source))).text } catch (_: Exception) { reader.reset() }
    }
    return null
}

fun decodePixels(width: Int, height: Int, pixels: IntArray): String? = decodeLuminance(RGBLuminanceSource(width, height, pixels))

/**
 * Decodes straight from the camera's brightness (Y) plane: no JPEG round trip, so the fine
 * modules of dense QRs (Google Pay, merchant stickers with a logo) stay sharp. Tries the whole
 * frame, then the middle 60%, where people hold the QR.
 */
fun decodeImage(image: ImageProxy): String? {
    if (image.format != ImageFormat.YUV_420_888) return null
    val w = image.width; val h = image.height
    val plane = image.planes[0]
    val buf = plane.buffer.duplicate().apply { rewind() }
    val y = ByteArray(w * h)
    val stride = plane.rowStride; val px = plane.pixelStride
    if (px == 1 && stride == w) buf.get(y, 0, minOf(y.size, buf.remaining()))
    else for (row in 0 until h) for (col in 0 until w) {
        val i = row * stride + col * px
        if (i < buf.limit()) y[row * w + col] = buf.get(i)
    }
    decodeLuminance(PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false))?.let { return it }
    val cw = (w * 0.6).toInt(); val ch = (h * 0.6).toInt()
    return decodeLuminance(PlanarYUVLuminanceSource(y, w, h, (w - cw) / 2, (h - ch) / 2, cw, ch, false))
}

fun decodeBitmap(src: Bitmap): String? {
    var bmp = src
    val maxSide = maxOf(bmp.width, bmp.height)
    if (maxSide > 1600) {
        val k = 1600f / maxSide
        bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
    }
    val w = bmp.width
    val h = bmp.height
    val pixels = IntArray(w * h)
    bmp.getPixels(pixels, 0, w, 0, 0, w, h)
    return decodePixels(w, h, pixels)
}

fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
    if (image.format != ImageFormat.YUV_420_888) return null
    return try {
        fun plane(i: Int) = image.planes[i].let {
            Yuv.Plane(it.buffer, it.rowStride, it.pixelStride)
        }
        val nv21 = Yuv.toNv21(image.width, image.height, plane(0), plane(1), plane(2))
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
        BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    } catch (e: Exception) {
        null
    }
}

@Composable
fun CameraPreview(
    onControl: (androidx.camera.core.CameraControl?) -> Unit,
    onError: (String) -> Unit,
    onFound: (String) -> Unit,
    onBad: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { analyzerExecutor.shutdown() }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                try {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1920, 1080),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                    ),
                                )
                                .build(),
                        )
                        .build()
                    var lastAt = 0L
                    var lastBadAt = 0L
                    var done = false
                    analysis.setAnalyzer(analyzerExecutor) { image ->
                        try {
                            if (done) { image.close(); return@setAnalyzer }
                            val now = System.currentTimeMillis()
                            if (now - lastAt < 250) { image.close(); return@setAnalyzer }
                            lastAt = now
                            val text = decodeImage(image)
                            image.close()
                            if (text != null) {
                                if (UpiParser.parse(text) != null) {
                                    done = true
                                    view.post { onFound(text) }
                                } else if (now - lastBadAt > 2500) {
                                    lastBadAt = now
                                    view.post { onBad() }
                                }
                            }
                        } catch (e: Exception) {
                            try { image.close() } catch (_: Exception) {}
                        }
                    }
                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    view.post { onControl(camera.cameraControl) }
                } catch (e: Exception) {
                    view.post { onError("Camera would not start. Upload a QR image below.") }
                }
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )
}
