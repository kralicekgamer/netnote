package cz.netdenik.logic

import cz.netdenik.data.ConnState
import cz.netdenik.data.Event
import cz.netdenik.data.EventType
import cz.netdenik.data.PingSample
import cz.netdenik.data.SpeedSample
import cz.netdenik.data.StateSegment
import cz.netdenik.data.TrafficSample
import kotlin.math.ceil

/** Souhrn jedné sady měření. Návrh chce medián, rozsah a p95 jen jako doplněk. */
data class Dist(
    val count: Int,
    val median: Double?,
    val p95: Double?,
    val min: Double?,
    val max: Double?,
)

fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val s = values.sorted()
    val mid = s.size / 2
    return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
}

/** Percentil metodou nejbližšího pořadí: p95 z 20 hodnot je devatenáctá nejmenší. */
fun percentile(values: List<Double>, p: Double): Double? {
    if (values.isEmpty()) return null
    val s = values.sorted()
    val rank = ceil(p / 100.0 * s.size).toInt().coerceIn(1, s.size)
    return s[rank - 1]
}

fun dist(values: List<Double>): Dist =
    Dist(values.size, median(values), percentile(values, 95.0), values.minOrNull(), values.maxOrNull())

/** Kus úseku stavu oříznutý na zobrazované období. */
data class Slice(val startMs: Long, val endMs: Long, val state: ConnState, val segmentId: Long) {
    val durationMs get() = endMs - startMs
}

fun slices(segments: List<StateSegment>, fromMs: Long, toMs: Long): List<Slice> =
    segments.mapNotNull { s ->
        val start = maxOf(s.startMs, fromMs)
        val end = minOf(s.endMs, toMs)
        if (end > start) Slice(start, end, s.state, s.id) else null
    }

data class StateShare(val state: ConnState, val ms: Long, val fraction: Double)

data class Coverage(
    /** Seřazeno od největšího podílu. Vždy všech šest základních stavů, „jiné“ jen když nastalo. */
    val shares: List<StateShare>,
    /** Součet všech stavů. Doba bez záznamu (před prvním spuštěním) do něj nepatří. */
    val coveredMs: Long,
    val windowMs: Long,
) {
    fun fraction(state: ConnState) = shares.firstOrNull { it.state == state }?.fraction ?: 0.0
}

private val BASE_STATES = listOf(
    ConnState.WIFI, ConnState.MOBILE, ConnState.SEARCHING,
    ConnState.OFFLINE, ConnState.PHONE_OFF, ConnState.UNKNOWN,
)

fun coverage(segments: List<StateSegment>, fromMs: Long, toMs: Long): Coverage {
    val ms = mutableMapOf<ConnState, Long>()
    for (s in slices(segments, fromMs, toMs)) ms[s.state] = (ms[s.state] ?: 0) + s.durationMs
    val covered = ms.values.sum()
    val states = BASE_STATES + listOfNotNull(ConnState.OTHER.takeIf { (ms[it] ?: 0) > 0 })
    val shares = states
        .map { StateShare(it, ms[it] ?: 0, if (covered > 0) (ms[it] ?: 0).toDouble() / covered else 0.0) }
        .sortedByDescending { it.ms }
    return Coverage(shares, covered, toMs - fromMs)
}

/** Stav, který v daném intervalu trval nejdéle; null, když pro interval není žádný záznam. */
fun dominantState(segments: List<StateSegment>, fromMs: Long, toMs: Long): ConnState? {
    val ms = mutableMapOf<ConnState, Long>()
    for (s in slices(segments, fromMs, toMs)) ms[s.state] = (ms[s.state] ?: 0) + s.durationMs
    return ms.maxByOrNull { it.value }?.key
}

data class Switches(
    val wifiMobile: Int,
    val apChanges: Int,
    val cellChanges: Int,
    val gaps: Int,
    val gapMs: Long,
)

/** Kratší hledání signálu mezi Wi‑Fi a daty je jen přechod, ne samostatný stav. */
private const val BRIEF_SEARCH_MS = 30_000L

fun switches(segments: List<StateSegment>, events: List<Event>, fromMs: Long, toMs: Long): Switches {
    val inWindow = slices(segments, fromMs, toMs)
    val links = inWindow.filterNot { it.state == ConnState.SEARCHING && it.durationMs < BRIEF_SEARCH_MS }
    val wifiMobile = links.zipWithNext().count { (a, b) ->
        (a.state == ConnState.WIFI && b.state == ConnState.MOBILE) ||
            (a.state == ConnState.MOBILE && b.state == ConnState.WIFI)
    }
    val gaps = inWindow.filter { it.state == ConnState.UNKNOWN }
    val recent = events.filter { it.ts in fromMs until toMs }
    return Switches(
        wifiMobile = wifiMobile,
        apChanges = recent.count { it.type == EventType.AP_CHANGE },
        cellChanges = recent.count { it.type == EventType.CELL_CHANGE },
        gaps = gaps.size,
        gapMs = gaps.sumOf { it.durationMs },
    )
}

/** Ping, rychlost a objem dat jedné sítě. Wi‑Fi a mobilní data se nikdy neslučují. */
data class TransportStats(
    val transport: ConnState,
    val ping: Dist,
    val failedPings: Int,
    val downBps: Dist,
    val upBps: Dist,
    val tests: Int,
    val rxBytes: Long,
    val txBytes: Long,
    /** Kolik z toho spotřebovala měření téhle appky; přiřazeno podle stavu v daném intervalu. */
    val ownRxBytes: Long,
    val ownTxBytes: Long,
)

fun transportStats(
    transport: ConnState,
    pings: List<PingSample>,
    speeds: List<SpeedSample>,
    traffic: List<TrafficSample>,
): TransportStats {
    val p = pings.filter { it.transport == transport }
    val s = speeds.filter { it.transport == transport }
    val own = traffic.filter { it.state == transport }
    val wifi = transport == ConnState.WIFI
    return TransportStats(
        transport = transport,
        ping = dist(p.mapNotNull { it.rttMs }),
        failedPings = p.count { it.rttMs == null },
        downBps = dist(s.mapNotNull { it.downBps?.toDouble() }),
        upBps = dist(s.mapNotNull { it.upBps?.toDouble() }),
        tests = s.size,
        rxBytes = traffic.sumOf { if (wifi) it.wifiRx else it.mobileRx },
        txBytes = traffic.sumOf { if (wifi) it.wifiTx else it.mobileTx },
        ownRxBytes = own.sumOf { it.ownRx },
        ownTxBytes = own.sumOf { it.ownTx },
    )
}
