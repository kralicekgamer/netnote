package cz.kralicekgamer.netnote.logic

import cz.kralicekgamer.netnote.data.ConnState
import cz.kralicekgamer.netnote.data.StateSegment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Jeden sloupec grafu. [value] je medián měření jedné sítě v daném úseku; když měření chybí,
 * je null a [state] říká, co se v té době dělo (offline, hledání, výpadek), nebo null bez záznamu.
 */
data class Bucket(val startMs: Long, val endMs: Long, val value: Double?, val state: ConnState?)

/**
 * Rozdělí období na [count] stejných úseků. Když jsou v úseku měření z Wi‑Fi i z mobilních dat,
 * sloupec ukáže tu síť, která jich má víc, a medián jen z jejích hodnot: obě sítě se nikdy nemíchají.
 */
fun <T> bucketMedians(
    fromMs: Long,
    toMs: Long,
    count: Int,
    segments: List<StateSegment>,
    items: List<T>,
    ts: (T) -> Long,
    value: (T) -> Double?,
    transport: (T) -> ConnState,
): List<Bucket> {
    val width = (toMs - fromMs).toDouble() / count
    val grouped = items.filter { ts(it) in fromMs until toMs }
        .groupBy { ((ts(it) - fromMs) / width).toInt().coerceIn(0, count - 1) }
    return List(count) { i ->
        val start = fromMs + (i * width).toLong()
        val end = fromMs + ((i + 1) * width).toLong()
        val measured = grouped[i].orEmpty().filter { value(it) != null }
        if (measured.isEmpty()) {
            Bucket(start, end, null, dominantState(segments, start, end))
        } else {
            val (net, ofNet) = measured.groupBy(transport).maxByOrNull { it.value.size }!!
            Bucket(start, end, median(ofNet.mapNotNull(value)), net)
        }
    }
}

/**
 * Vodicí čáry grafu: násobky „hezkého“ kroku (1 · 2 · 5 × 10ⁿ) zvoleného tak, aby se jich
 * pod největší hodnotu vešlo jedna až tři. Pro 118 ms vyjde 50 a 100, pro 236 Mb/s 100 a 200.
 */
fun gridLines(maxValue: Double): List<Double> {
    if (maxValue <= 0) return emptyList()
    val target = maxValue / 3.5
    var magnitude = 1.0
    while (magnitude * 10 <= target) magnitude *= 10
    while (magnitude > target) magnitude /= 10
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= target }
    return generateSequence(step) { it + step }.takeWhile { it <= maxValue * 1.0001 }.toList()
}

/** Jeden řádek týdenní heatmapy: 24 hodin jednoho dne. */
data class HeatDay(
    val date: LocalDate,
    /** Převažující stav každé hodiny; null = pro tu hodinu není záznam (budoucnost, před instalací). */
    val hours: List<ConnState?>,
    /** Podíl Wi‑Fi ze zaznamenaného času toho dne; null bez záznamu. */
    val wifiFraction: Double?,
)

fun weekHeatmap(segments: List<StateSegment>, lastDay: LocalDate, zone: ZoneId, days: Int = 7): List<HeatDay> =
    (days - 1 downTo 0).map { back ->
        val date = lastDay.minusDays(back.toLong())
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val hours = (0 until 24).map { h ->
            val start = date.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
            dominantState(segments, start, start + 3_600_000L)
        }
        val day = coverage(segments, dayStart, dayEnd)
        HeatDay(date, hours, if (day.coveredMs > 0) day.fraction(ConnState.WIFI) else null)
    }

fun startOfDay(ms: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
