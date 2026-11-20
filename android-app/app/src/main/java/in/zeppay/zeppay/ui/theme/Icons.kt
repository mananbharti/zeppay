package `in`.zeppay.zeppay.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** ZepPay's own icon set: 24-grid, 2.4 stroke, round caps. Drawn for this identity. */
object ZIcon {
    private fun icon(name: String, vararg d: String, stroke: Float = 2.4f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            d.forEach { addPath(addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = stroke, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
        }.build()

    val Scan = icon("scan", "M4 9V5.5A1.5 1.5 0 0 1 5.5 4H9", "M15 4h3.5A1.5 1.5 0 0 1 20 5.5V9", "M20 15v3.5a1.5 1.5 0 0 1-1.5 1.5H15", "M9 20H5.5A1.5 1.5 0 0 1 4 18.5V15", "M8 12h8")
    val Phone = icon("phone", "M7.5 2.5h9a1.5 1.5 0 0 1 1.5 1.5v16a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 6 20V4a1.5 1.5 0 0 1 1.5-1.5z", "M10.5 18h3")
    val At = icon("at", "M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0z", "M16 12v1.5a2.5 2.5 0 0 0 5 0V12a9 9 0 1 0-3.6 7.2")
    val Bank = icon("bank", "M3 9.5 12 4l9 5.5", "M5 10v7", "M9.7 10v7", "M14.3 10v7", "M19 10v7", "M3 20.5h18")
    val Wallet = icon("wallet", "M4 7.5A2.5 2.5 0 0 1 6.5 5H18v3", "M4 7.5v10A2.5 2.5 0 0 0 6.5 20H20V8H6.5A2.5 2.5 0 0 1 4 5.5", "M16 14h.5")
    val Split = icon("split", "M12 3v6", "M12 9l-6 6v6", "M12 9l6 6v6", "M9 6l3-3 3 3")
    val Chart = icon("chart", "M4 20h16", "M7 16v-4", "M12 16V6", "M17 16v-7")
    val More = icon("more", "M4 7h16", "M4 12h16", "M4 17h10")
    val Back = icon("back", "M15 5l-7 7 7 7")
    val Close = icon("close", "M6 6l12 12", "M18 6 6 18")
    val Check = icon("check", "M4.5 12.5l5 5L19.5 7", stroke = 3f)
    val Cross = icon("cross", "M6.5 6.5l11 11", "M17.5 6.5l-11 11", stroke = 3f)
    val Question = icon("question", "M9 9a3 3 0 1 1 4.2 2.75c-.8.35-1.2 1-1.2 1.85V14", "M12 18h.01", stroke = 3f)
    val Clock = icon("clock", "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z", "M12 7v5l3 2")
    val Contacts = icon("contacts", "M15.5 8a3.5 3.5 0 1 1-7 0 3.5 3.5 0 0 1 7 0z", "M5 20c.8-3.5 3.6-5.5 7-5.5s6.2 2 7 5.5")
    val Gear = icon("gear", "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z", "M12 2.5v2.2", "M12 19.3v2.2", "M2.5 12h2.2", "M19.3 12h2.2", "M5.3 5.3l1.5 1.5", "M17.2 17.2l1.5 1.5", "M5.3 18.7l1.5-1.5", "M17.2 6.8l1.5-1.5")
    val Help = icon("help", "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z", "M9.5 9.5a2.5 2.5 0 1 1 3.4 2.3c-.6.3-.9.8-.9 1.5V14", "M12 17.2h.01")
    val Update = icon("update", "M12 3v12", "M7 10l5 5 5-5", "M4 20h16")
    val Shield = icon("shield", "M12 3l7.5 3v5.5c0 4.6-3.1 8-7.5 9.5-4.4-1.5-7.5-4.9-7.5-9.5V6z", "M9 12l2.2 2.2L15.5 10")
    val Torch = icon("torch", "M13 2.5 5 13.5h6l-1 8 8-11h-6z")
    val Image = icon("image", "M4.5 4h15A.5.5 0 0 1 20 4.5v15a.5.5 0 0 1-.5.5h-15a.5.5 0 0 1-.5-.5v-15A.5.5 0 0 1 4.5 4z", "M4 16l5-5 4 4 2-2 5 5", "M15.5 8.5h.01")
    val Copy = icon("copy", "M9 9h11v11H9z", "M5 15H4V4h11v1")
    val Share = icon("share", "M12 15V3", "M7.5 7.5 12 3l4.5 4.5", "M5 12v7.5a.5.5 0 0 0 .5.5h13a.5.5 0 0 0 .5-.5V12")
    val Plus = icon("plus", "M12 5v14", "M5 12h14")
    val Trash = icon("trash", "M4 7h16", "M9.5 7V4.5h5V7", "M6.5 7l1 13h9l1-13")
    val Sim = icon("sim", "M7 3h7l4 4v13.5a.5.5 0 0 1-.5.5h-10.5a.5.5 0 0 1-.5-.5V3.5A.5.5 0 0 1 7 3z", "M9.5 12h5v5h-5z")
    val List = icon("list", "M9 6h11", "M9 12h11", "M9 18h11", "M4.5 6h.01", "M4.5 12h.01", "M4.5 18h.01", stroke = 2.6f)
    val Arrow = icon("arrow", "M5 12h14", "M13 6l6 6-6 6")
    val Call = icon("call", "M5 4h3.5l1.8 4.5-2.3 1.4a11 11 0 0 0 6.1 6.1l1.4-2.3 4.5 1.8V19a1.5 1.5 0 0 1-1.6 1.5C10.6 20 4 13.4 3.5 5.6A1.5 1.5 0 0 1 5 4z")
    val Signal = icon("signal", "M5 19v-3", "M10 19v-7", "M15 19V8", "M20 19V4", stroke = 3f)
    val Accessibility = icon("a11y", "M13.5 4.5a1.5 1.5 0 1 1-3 0 1.5 1.5 0 0 1 3 0z", "M5 8.5l7 1.5 7-1.5", "M12 10v4.5l-3 6", "M12 14.5l3 6")
    val Layers = icon("layers", "M12 3 3 8l9 5 9-5z", "M3 13l9 5 9-5")
}
