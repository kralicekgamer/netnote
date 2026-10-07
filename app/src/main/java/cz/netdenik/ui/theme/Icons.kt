package cz.netdenik.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Ikony z návrhu: obrysové, zaoblené konce, mřížka 24 × 24. Cesty jsou převzaté z SVG
 * v design/obrazovky, takže vypadají stejně jako v předloze. Barvu určuje až [androidx.compose.material3.Icon].
 */
object NdIcons {
    // Návrh tu má kolečko s paprsky, které vypadá jako přepínač motivu. Tohle je ozubené kolo:
    // 8 zubů, stejná tloušťka obrysu jako ostatní ikony.
    val Settings = icon(
        "settings", 1.75f,
        circle(12f, 12f, 2.8f),
        "M10.53 5.87L10.72 2.89L13.28 2.89L13.47 5.87A6.3 6.3 0 0 1 15.29 6.63L17.54 4.65L19.35 6.46L17.37 8.71A6.3 6.3 0 0 1 18.13 10.53L21.11 10.72L21.11 13.28L18.13 13.47A6.3 6.3 0 0 1 17.37 15.29L19.35 17.54L17.54 19.35L15.29 17.37A6.3 6.3 0 0 1 13.47 18.13L13.28 21.11L10.72 21.11L10.53 18.13A6.3 6.3 0 0 1 8.71 17.37L6.46 19.35L4.65 17.54L6.63 15.29A6.3 6.3 0 0 1 5.87 13.47L2.89 13.28L2.89 10.72L5.87 10.53A6.3 6.3 0 0 1 6.63 8.71L4.65 6.46L6.46 4.65L8.71 6.63A6.3 6.3 0 0 1 10.53 5.87Z",
    )
    val Export = icon("export", 1.75f, "M12 4v11M7.5 10.5L12 15l4.5-4.5M5 19h14")
    val Back = icon("back", 2f, "M15 6l-6 6 6 6")
    val Chevron = icon("chevron", 2f, "M9 6l6 6-6 6")
    val Wifi = icon("wifi", 2f, "M2 9a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0", circle(12f, 19.5f, 1f))
    val Cell = icon("cell", 2f, "M5 19v-3M10 19v-7M15 19V8M20 19V4")
    val Overview = icon("overview", 1.75f, circle(12f, 12f, 8f), "M12 4v8l5.7 5.7")
    val History = icon("history", 1.75f, "M4 6h16M4 12h11M4 18h7")
    val Ping = icon("ping", 1.75f, "M3 12h4l2-6 4 12 2-6h6")
    val Speed = icon("speed", 1.75f, "M4.5 17.5a8.5 8.5 0 1 1 15 0", "M12 13.5l4-4.5")
    val Info = icon("info", 2f, circle(12f, 12f, 9f), "M12 8v5M12 16.5v.5")
    val Edit = icon("edit", 1.75f, "M4 20h4L19 9l-4-4L4 16v4z")
    val Check = icon("check", 2.25f, "M5 12.5l4.5 4.5L19 7.5")

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun icon(name: String, strokeWidth: Float, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) {
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = strokeWidth,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
