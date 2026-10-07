package cz.netdenik.logic

import cz.netdenik.data.CellSample
import cz.netdenik.data.ConnState
import cz.netdenik.data.Event
import cz.netdenik.data.EventType
import cz.netdenik.data.PingSample
import cz.netdenik.data.SpeedSample
import cz.netdenik.data.StateSegment
import cz.netdenik.data.TrafficSample
import cz.netdenik.data.WifiSample

/** Všechno, co ukazuje obrazovka „Úsek · Wi‑Fi“ nebo „Úsek · mobilní data“. */
data class SegmentDetail(
    val segment: StateSegment,
    val ongoing: Boolean,
    val ping: Dist,
    val downBps: Dist,
    val upBps: Dist,
    val tests: Int,
    val rxBytes: Long,
    val txBytes: Long,
    val ownRxBytes: Long,
    val ownTxBytes: Long,
    /** Poslední vzorek úseku; má nejúplnější údaje (veřejná IP přichází až po prvním ticku). */
    val wifi: WifiSample?,
    val cell: CellSample?,
    val apChanges: Int,
    val ipChanges: Int,
    val cellChanges: Int,
    /** Průběh síly signálu mobilní sítě (čas, RSRP v dBm). */
    val rsrp: List<Pair<Long, Int>>,
)

/** Vstupní seznamy stačí načíst pro dobu úseku; funkce si je stejně ořízne. */
fun segmentDetail(
    segment: StateSegment,
    ongoing: Boolean,
    pings: List<PingSample>,
    speeds: List<SpeedSample>,
    traffic: List<TrafficSample>,
    wifi: List<WifiSample>,
    cells: List<CellSample>,
    events: List<Event>,
): SegmentDetail {
    val range = segment.startMs..segment.endMs
    val p = pings.filter { it.ts in range && it.transport == segment.state }
    val s = speeds.filter { it.ts in range && it.transport == segment.state }
    // Objem dat se měří zpětně za uplynulý interval, proto vzorek přesně na začátku úseku patří předchozímu.
    val t = traffic.filter { it.ts > segment.startMs && it.ts <= segment.endMs }
    val e = events.filter { it.ts in range }
    val isWifi = segment.state == ConnState.WIFI
    val cellSamples = cells.filter { it.ts in range }
    return SegmentDetail(
        segment = segment,
        ongoing = ongoing,
        ping = dist(p.mapNotNull { it.rttMs }),
        downBps = dist(s.mapNotNull { it.downBps?.toDouble() }),
        upBps = dist(s.mapNotNull { it.upBps?.toDouble() }),
        tests = s.size,
        rxBytes = t.sumOf { if (isWifi) it.wifiRx else it.mobileRx },
        txBytes = t.sumOf { if (isWifi) it.wifiTx else it.mobileTx },
        ownRxBytes = t.filter { it.state == segment.state }.sumOf { it.ownRx },
        ownTxBytes = t.filter { it.state == segment.state }.sumOf { it.ownTx },
        wifi = wifi.lastOrNull { it.ts in range },
        cell = cellSamples.lastOrNull(),
        apChanges = e.count { it.type == EventType.AP_CHANGE },
        ipChanges = e.count { it.type == EventType.IP_CONFIG_CHANGE },
        cellChanges = e.count { it.type == EventType.CELL_CHANGE },
        rsrp = cellSamples.mapNotNull { c -> c.rsrp?.let { c.ts to it } },
    )
}

/** Vybere nejvýš [max] rovnoměrně rozložených položek, první a poslední vždy. */
fun <T> evenly(items: List<T>, max: Int): List<T> {
    if (items.size <= max) return items
    return List(max) { i -> items[(i.toLong() * (items.size - 1) / (max - 1)).toInt()] }
}
