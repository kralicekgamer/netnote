package cz.kralicekgamer.netnote.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.kralicekgamer.netnote.data.ConnState
import cz.kralicekgamer.netnote.logic.Bucket
import cz.kralicekgamer.netnote.logic.HeatDay
import cz.kralicekgamer.netnote.logic.Slice
import cz.kralicekgamer.netnote.logic.StateShare
import cz.kralicekgamer.netnote.logic.gridLines
import cz.kralicekgamer.netnote.ui.Fmt
import cz.kralicekgamer.netnote.ui.theme.Mono
import cz.kralicekgamer.netnote.ui.theme.Nn
import cz.kralicekgamer.netnote.ui.theme.NnType
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Vyplní obdélník barvou stavu. Neznámo (výpadek měření) se nikdy nekreslí plnou barvou,
 * ale šrafou 45°; vypnutý telefon má navíc obrys, aby se nepletl s offline.
 *
 * @param behind barva plochy pod šrafou (pozadí karty, na které graf leží)
 */
fun DrawScope.fillState(
    state: ConnState,
    topLeft: Offset,
    size: Size,
    radius: Float = 0f,
    behind: Color = Nn.Surface,
) {
    if (size.width <= 0f || size.height <= 0f) return
    val corner = CornerRadius(radius)
    when (state) {
        ConnState.UNKNOWN -> {
            val clip = Path().apply { addRoundRect(RoundRect(Rect(topLeft, size), corner)) }
            clipPath(clip) {
                drawRect(behind, topLeft, size)
                val stripe = 2.dp.toPx()
                val period = 5.dp.toPx()
                var x = topLeft.x - size.height
                while (x < topLeft.x + size.width + stripe) {
                    drawLine(
                        Nn.Unknown,
                        Offset(x, topLeft.y + size.height),
                        Offset(x + size.height, topLeft.y),
                        strokeWidth = stripe,
                    )
                    x += period
                }
            }
        }
        ConnState.PHONE_OFF -> {
            drawRoundRect(Nn.PhoneOff, topLeft, size, corner)
            val w = 1.dp.toPx()
            if (size.width > 2 * w && size.height > 2 * w) {
                drawRoundRect(
                    Nn.PhoneOffOutline,
                    Offset(topLeft.x + w / 2, topLeft.y + w / 2),
                    Size(size.width - w, size.height - w),
                    corner,
                    style = Stroke(w),
                )
            }
        }
        else -> drawRoundRect(Nn.state(state), topLeft, size, corner)
    }
}

fun Modifier.stateBackground(state: ConnState, radius: Dp = 0.dp, behind: Color = Nn.Surface): Modifier =
    drawBehind { fillState(state, Offset.Zero, size, radius.toPx(), behind) }

/** Koláč podílů stavů: 136 dp, prstenec 20 dp, uprostřed volitelný obsah (% Wi‑Fi). */
@Composable
fun DonutChart(shares: List<StateShare>, modifier: Modifier = Modifier, center: @Composable () -> Unit) {
    Box(modifier.size(136.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 20.dp.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arc = Size(size.width - stroke, size.height - stroke)
            if (shares.all { it.ms == 0L }) {
                drawArc(Nn.Background, 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
                return@Canvas
            }
            var start = -90f
            for (s in shares) {
                val sweep = (s.fraction * 360).toFloat()
                if (sweep <= 0f) continue
                // Výpadek měření: radiální proužky místo plné barvy.
                val style = if (s.state == ConnState.UNKNOWN) {
                    Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())))
                } else {
                    Stroke(stroke)
                }
                drawArc(Nn.state(s.state), start, sweep, false, topLeft, arc, style = style)
                start += sweep
            }
        }
        center()
    }
}

/**
 * Vodorovný pruh úseků podle délky. Doba bez záznamu zůstává tmavá.
 *
 * @param markers časy změn AP; kreslí se jako tenké svislé zářezy
 */
@Composable
fun StateTimeline(
    slices: List<Slice>,
    fromMs: Long,
    toMs: Long,
    modifier: Modifier = Modifier,
    radius: Dp = 7.dp,
    markers: List<Long> = emptyList(),
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(Nn.Background),
    ) {
        val span = (toMs - fromMs).toFloat().coerceAtLeast(1f)
        for (s in slices) {
            val x0 = floor((s.startMs - fromMs) / span * size.width)
            val x1 = ceil((s.endMs - fromMs) / span * size.width)
            fillState(s.state, Offset(x0, 0f), Size(max(x1 - x0, 1f), size.height))
        }
        val inset = 6.dp.toPx()
        for (m in markers) {
            if (m < fromMs || m > toMs) continue
            val x = (m - fromMs) / span * size.width
            drawLine(
                Nn.Background, Offset(x, inset), Offset(x, size.height - inset),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            )
        }
    }
}

/** Popisky pod grafem, rovnoměrně od kraje ke kraji. */
@Composable
fun AxisLabels(labels: List<String>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEach { Text(it, style = NnType.Axis) }
    }
}

/** Heatmapa 7 dní × 24 hodin; buňka = převažující stav hodiny, vpravo podíl Wi‑Fi za den. */
@Composable
fun WeekHeatmap(days: List<HeatDay>, today: LocalDate, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (day in days) {
            val isToday = day.date == today
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    Fmt.dayName(day.date),
                    Modifier.width(22.dp),
                    style = NnType.Caption,
                    color = if (isToday) Nn.Text else Nn.TextVariant,
                    fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                )
                Canvas(
                    Modifier
                        .weight(1f)
                        .height(18.dp),
                ) {
                    val gap = 2.dp.toPx()
                    val w = (size.width - 23 * gap) / 24
                    val r = 3.dp.toPx()
                    day.hours.forEachIndexed { h, state ->
                        val topLeft = Offset(h * (w + gap), 0f)
                        val cell = Size(w, size.height)
                        if (state == null) drawRoundRect(Nn.Background, topLeft, cell, CornerRadius(r))
                        else fillState(state, topLeft, cell, r)
                    }
                }
                Text(
                    day.wifiFraction?.let(Fmt::percentWhole) ?: "–",
                    Modifier.width(34.dp),
                    style = NnType.Axis,
                    color = Nn.TextVariant,
                    textAlign = TextAlign.End,
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 40.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf("0", "6", "12", "18", "24").forEach { Text(it, style = NnType.Axis) }
        }
    }
}

/**
 * Sloupcový graf: barva sloupce = síť, na které se měřilo. Úsek bez měření má jen tenkou
 * čárku v barvě toho, co se dělo (šedá, žlutá pro hledání, šrafa pro výpadek).
 */
@Composable
fun BarChart(buckets: List<Bucket>, modifier: Modifier = Modifier, gap: Dp = 1.dp) {
    val measurer = rememberTextMeasurer()
    val labelStyle = NnType.Axis.copy(fontFamily = Mono, fontSize = 10.sp)
    Canvas(modifier.fillMaxWidth()) {
        val gutter = 28.dp.toPx()
        val plotW = size.width - gutter
        val plotH = size.height - 10.dp.toPx()
        val maxValue = buckets.mapNotNull { it.value }.maxOrNull() ?: 0.0
        val chartMax = if (maxValue > 0) maxValue else 1.0

        for (g in gridLines(maxValue)) {
            val y = size.height - (g / chartMax * plotH).toFloat()
            drawLine(Nn.Divider, Offset(0f, y), Offset(plotW, y), 1.dp.toPx())
            val text = measurer.measure(Fmt.axis(g), labelStyle)
            drawText(text, topLeft = Offset(size.width - text.size.width, y - text.size.height / 2f))
        }

        val n = buckets.size
        if (n > 0) {
            val gapPx = gap.toPx()
            val w = ((plotW - gapPx * (n - 1)) / n).coerceAtLeast(1f)
            val tick = 3.dp.toPx()
            buckets.forEachIndexed { i, b ->
                val x = i * (w + gapPx)
                val state = b.state ?: return@forEachIndexed
                if (b.value != null) {
                    val h = max(tick, (b.value / chartMax * plotH).toFloat())
                    drawRect(Nn.state(state), Offset(x, size.height - h), Size(w, h))
                } else {
                    val topLeft = Offset(x, size.height - tick)
                    when (state) {
                        ConnState.UNKNOWN -> fillState(state, topLeft, Size(w, tick))
                        ConnState.SEARCHING -> drawRect(Nn.Searching, topLeft, Size(w, tick))
                        else -> drawRect(Nn.Offline, topLeft, Size(w, tick))
                    }
                }
            }
        }
        drawLine(Nn.Outline, Offset(0f, size.height), Offset(plotW, size.height), 1.dp.toPx())
    }
}

/** Skládaný pruh podílů (objem dat, podíl za týden). */
@Composable
fun ShareBar(parts: List<Pair<ConnState, Float>>, modifier: Modifier = Modifier, radius: Dp = 5.dp) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(Nn.Background),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((state, share) in parts) {
            if (share > 0f) Box(Modifier.weight(share).fillMaxHeight().stateBackground(state))
        }
    }
}

/** Tenký vodorovný sloupec, třeba ping jedné sítě vůči nejpomalejší. */
@Composable
fun HBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Nn.Background),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
    }
}

/** Čtyři čárky síly signálu. */
@Composable
fun SignalBars(level: Int, color: Color, modifier: Modifier = Modifier) {
    Row(modifier.height(14.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(5, 8, 11, 14).forEachIndexed { i, h ->
            Box(
                Modifier
                    .width(4.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i < level) color else Nn.Outline),
            )
        }
    }
}

/** Průběh RSRP během úseku: vyšší sloupec = silnější signál, nad sloupcem hodnota. */
@Composable
fun RsrpBars(values: List<Int>, modifier: Modifier = Modifier) {
    Row(modifier.height(64.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (v in values) {
            // −100 dBm a horší je prakticky nula, −70 dBm plná výška.
            val h = ((v + 100) * 2).coerceIn(6, 46)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Fmt.signed(v), style = NnType.Axis.copy(fontSize = 10.sp), color = Nn.TextVariant, maxLines = 1)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(h.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Nn.Mobile),
                )
            }
        }
    }
}
