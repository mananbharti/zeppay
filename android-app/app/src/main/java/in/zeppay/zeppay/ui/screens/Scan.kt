package `in`.zeppay.zeppay.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import `in`.zeppay.zeppay.ZepViewModel
import `in`.zeppay.zeppay.data.Payee
import `in`.zeppay.zeppay.data.UpiParser
import `in`.zeppay.zeppay.scan.CameraPreview
import `in`.zeppay.zeppay.scan.decodeBitmap
import `in`.zeppay.zeppay.ui.Screen
import `in`.zeppay.zeppay.ui.kit.*
import `in`.zeppay.zeppay.ui.theme.ZIcon
import `in`.zeppay.zeppay.ui.theme.ZType
import `in`.zeppay.zeppay.ui.theme.Zep

/** QR scan: decoded on the phone (CameraX + ZXing). No image leaves the device. */
@Composable
fun ScanScreen(vm: ZepViewModel) {
    val c = Zep.c
    val ctx = LocalContext.current
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    LaunchedEffect(Unit) { if (!permitted) perm.launch(android.Manifest.permission.CAMERA) }
    var control by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }
    var torch by remember { mutableStateOf(false) }
    var camErr by remember { mutableStateOf("") }
    var paste by remember { mutableStateOf("") }

    fun use(text: String): Boolean {
        val p = UpiParser.parse(text) ?: return false
        vm.replace(Screen.Amount(Payee(vpa = p.vpa, name = p.name), amount = p.amount, note = p.note, lockAmount = p.amount.isNotEmpty()))
        return true
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = try { ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }?.let { decodeBitmap(it) } } catch (_: Exception) { null }
        if (text == null || !use(text)) vm.showToast("No UPI QR found in that picture")
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (permitted) CameraPreview(onControl = { control = it }, onError = { camErr = it }, onFound = { use(it) }, onBad = { vm.showToast("That QR isn't a UPI payment QR") })
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(ZIcon.Close, "Close") { vm.back() }
                Text("Scan to pay", style = ZType.title, color = Color.White, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                RoundIcon(ZIcon.Torch, if (torch) "Torch off" else "Torch on", on = torch) {
                    val cc = control
                    if (cc == null) vm.showToast("Torch isn't available") else { torch = !torch; cc.enableTorch(torch) }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                // Viewfinder: four lime corner brackets. The only thing that moves here is the camera.
                Canvas(Modifier.size(260.dp)) {
                    val l = 54.dp.toPx(); val w = 9.dp.toPx(); val s = size.width
                    listOf(Offset(0f, 0f) to Offset(1f, 1f), Offset(s, 0f) to Offset(-1f, 1f), Offset(0f, s) to Offset(1f, -1f), Offset(s, s) to Offset(-1f, -1f)).forEach { (o, d) ->
                        drawLine(c.primary, o, Offset(o.x + d.x * l, o.y), w, StrokeCap.Round)
                        drawLine(c.primary, o, Offset(o.x, o.y + d.y * l), w, StrokeCap.Round)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.bg).navigationBarsPadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when {
                    !permitted -> "Camera is off. Allow it, or pick a QR picture."
                    camErr.isNotEmpty() -> camErr
                    else -> "Hold the QR inside the corners."
                }, style = ZType.bodyBold, color = c.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                if (!permitted) ActionButton("Allow camera", { perm.launch(android.Manifest.permission.CAMERA) }, Modifier.fillMaxWidth(), icon = ZIcon.Scan)
                PlainButton("Pick a QR picture", { pick.launch("image/*") }, Modifier.fillMaxWidth(), icon = ZIcon.Image)
                Field(paste, { paste = it }, "Or paste a UPI ID / link", placeholder = "name@bank or upi://…", onDone = { if (!use(paste)) vm.showToast("That isn't a UPI ID or link") })
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean = false, onClick: () -> Unit) {
    val c = Zep.c
    Box(Modifier.size(56.dp).clip(CircleShape).background(if (on) c.primary else Color.Black.copy(alpha = 0.55f)).pressable(label = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = if (on) c.onPrimary else Color.White, modifier = Modifier.size(26.dp))
    }
    Spacer(Modifier.width(0.dp))
}
