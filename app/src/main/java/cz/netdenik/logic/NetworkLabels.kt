package cz.netdenik.logic

import cz.netdenik.data.CellSample
import cz.netdenik.data.ConnState
import cz.netdenik.data.PingSample
import cz.netdenik.data.SpeedSample
import cz.netdenik.data.WifiSample
import kotlin.math.abs

/**
 * Měření si nepamatují, na jaké síti proběhla; jen čas a typ připojení. Síť se dohledá
 * podle vzorku Wi‑Fi nebo buňky, který je časově nejblíž (vzorek se zapisuje ve stejném ticku).
 */
class SampleIndex(wifi: List<WifiSample>, cells: List<CellSample>) {
    private val wifi = wifi.sortedBy { it.ts }
    private val cells = cells.sortedBy { it.ts }

    fun wifiAt(ts: Long): WifiSample? = nearest(wifi, ts) { it.ts }
    fun cellAt(ts: Long): CellSample? = nearest(cells, ts) { it.ts }

    /** Jméno sítě pro souhrny „podle sítě“: SSID, nebo „operátor · technologie“. */
    fun networkName(transport: ConnState, ts: Long): String? = when (transport) {
        ConnState.WIFI -> wifiAt(ts)?.let { it.ssid ?: "Wi‑Fi bez názvu" }
        ConnState.MOBILE -> cellAt(ts)?.let(::mobileName)
        else -> null
    }

    private fun <T> nearest(sorted: List<T>, ts: Long, time: (T) -> Long): T? {
        if (sorted.isEmpty()) return null
        var lo = 0
        var hi = sorted.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (time(sorted[mid]) < ts) lo = mid + 1 else hi = mid
        }
        val best = listOfNotNull(sorted.getOrNull(lo - 1), sorted[lo]).minByOrNull { abs(time(it) - ts) }!!
        return best.takeIf { abs(time(it) - ts) <= MAX_DISTANCE_MS }
    }

    private companion object {
        /** Dál od měření už vzorek nejspíš patří k jiné síti. */
        const val MAX_DISTANCE_MS = 10 * 60_000L
    }
}

fun mobileName(cell: CellSample): String =
    listOfNotNull(cell.operatorName ?: "Mobilní síť", techLabel(cell)).joinToString(" · ")

data class NetworkPing(val name: String, val transport: ConnState, val medianMs: Double, val count: Int)

/** Medián pingu pro každou síť, od nejrychlejší. */
fun pingByNetwork(pings: List<PingSample>, index: SampleIndex): List<NetworkPing> =
    pings.filter { it.rttMs != null }
        .groupBy { (index.networkName(it.transport, it.ts) ?: return@groupBy null) to it.transport }
        .filterKeys { it != null }
        .map { (key, list) -> NetworkPing(key!!.first, key.second, median(list.mapNotNull { it.rttMs })!!, list.size) }
        .sortedBy { it.medianMs }

data class PingPeak(val ts: Long, val network: String?, val rttMs: Double)

fun pingPeaks(pings: List<PingSample>, index: SampleIndex, count: Int = 4): List<PingPeak> =
    pings.filter { it.rttMs != null }
        .sortedByDescending { it.rttMs }
        .take(count)
        .map { PingPeak(it.ts, index.networkName(it.transport, it.ts), it.rttMs!!) }

data class NetworkSpeed(
    val name: String,
    val transport: ConnState,
    val tests: Int,
    val downBps: Double?,
    val upBps: Double?,
)

/** Medián rychlosti pro každou síť; nejdřív Wi‑Fi, uvnitř podle počtu testů. */
fun speedByNetwork(speeds: List<SpeedSample>, index: SampleIndex): List<NetworkSpeed> =
    speeds.groupBy { (index.networkName(it.transport, it.ts) ?: return@groupBy null) to it.transport }
        .filterKeys { it != null }
        .map { (key, list) ->
            NetworkSpeed(
                key!!.first, key.second, list.size,
                median(list.mapNotNull { it.downBps?.toDouble() }),
                median(list.mapNotNull { it.upBps?.toDouble() }),
            )
        }
        .sortedWith(compareBy<NetworkSpeed> { it.transport != ConnState.WIFI }.thenByDescending { it.tests })
