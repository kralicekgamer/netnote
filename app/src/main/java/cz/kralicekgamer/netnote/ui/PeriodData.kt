package cz.kralicekgamer.netnote.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.kralicekgamer.netnote.Config
import cz.kralicekgamer.netnote.collect.MonitorService
import cz.kralicekgamer.netnote.data.AppDatabase
import cz.kralicekgamer.netnote.data.CellSample
import cz.kralicekgamer.netnote.data.Event
import cz.kralicekgamer.netnote.data.NetDao
import cz.kralicekgamer.netnote.data.PingSample
import cz.kralicekgamer.netnote.data.SpeedSample
import cz.kralicekgamer.netnote.data.StateSegment
import cz.kralicekgamer.netnote.data.TrafficSample
import cz.kralicekgamer.netnote.data.WifiSample
import cz.kralicekgamer.netnote.logic.SampleIndex
import cz.kralicekgamer.netnote.logic.coverage
import cz.kralicekgamer.netnote.logic.slices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class Period(val label: String) { DAY("24 h"), WEEK("7 dní") }

/**
 * Všechno z databáze pro jedno zobrazované období. 24 h je klouzavé okno do teď,
 * týden je posledních 7 kalendářních dní včetně dneška (heatmapa má řádky po dnech).
 */
class PeriodData(
    val period: Period,
    val fromMs: Long,
    val toMs: Long,
    val segments: List<StateSegment>,
    /** Úsek těsně před prvním v období, aby i první řádek historie věděl, odkud se přešlo. */
    val previous: StateSegment?,
    /** Úsek, který právě běží; null, když měření stojí. */
    val openSegmentId: Long?,
    val events: List<Event>,
    val pings: List<PingSample>,
    val speeds: List<SpeedSample>,
    val traffic: List<TrafficSample>,
    val wifi: List<WifiSample>,
    val cells: List<CellSample>,
) {
    val zone: ZoneId = ZoneId.systemDefault()
    val today: LocalDate get() = Instant.ofEpochMilli(toMs).atZone(zone).toLocalDate()
    val hasData get() = segments.isNotEmpty()

    val index by lazy { SampleIndex(wifi, cells) }
    val slices by lazy { slices(segments, fromMs, toMs) }
    val coverage by lazy { coverage(segments, fromMs, toMs) }

    /** Konec grafů: u dne „teď“, u týdne půlnoc po dnešku, aby sloupce seděly na dny. */
    val chartToMs: Long
        get() = if (period == Period.DAY) toMs else today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun wifiIn(s: StateSegment): List<WifiSample> = between(wifi, s.startMs, s.endMs) { it.ts }
    fun cellsIn(s: StateSegment): List<CellSample> = between(cells, s.startMs, s.endMs) { it.ts }

    /** Vzorky jsou seřazené podle času, takže stačí najít oba konce půlením. */
    private fun <T> between(sorted: List<T>, from: Long, to: Long, ts: (T) -> Long): List<T> {
        fun lowerBound(target: Long): Int {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (ts(sorted[mid]) < target) lo = mid + 1 else hi = mid
            }
            return lo
        }
        return sorted.subList(lowerBound(from), lowerBound(to + 1))
    }
}

suspend fun loadPeriodData(dao: NetDao, period: Period, running: Boolean): PeriodData = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val from = when (period) {
        Period.DAY -> now - 24 * 3_600_000L
        Period.WEEK -> Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(6)
            .atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val last = dao.lastSegment()
    // Běžící úsek končí v databázi posledním heartbeatem; dokud měření běží, protáhneme ho do teď,
    // aby na pravém okraji grafů nebyla falešná mezera.
    val alive = running && last != null && now - last.endMs < Config.GAP_THRESHOLD_MS
    val segments = dao.segmentsBetween(from, now)
        .map { if (alive && it.id == last?.id) it.copy(endMs = now) else it }
    PeriodData(
        period = period,
        fromMs = from,
        toMs = now,
        segments = segments,
        previous = segments.firstOrNull()?.let { dao.segmentBefore(it.id) },
        openSegmentId = last?.id.takeIf { alive },
        events = dao.eventsBetween(from, now + 1),
        pings = dao.pingsBetween(from, now + 1),
        speeds = dao.speedsBetween(from, now + 1),
        traffic = dao.trafficBetween(from, now + 1),
        wifi = dao.wifiBetween(from, now + 1),
        cells = dao.cellsBetween(from, now + 1),
    )
}

/** Načte období a přepočítá ho při každém novém ticku, změně stavu nebo přepnutí období. */
@Composable
fun rememberPeriodData(period: Period): PeriodData? {
    val ctx = LocalContext.current
    val dao = remember { AppDatabase.get(ctx).dao() }
    val running by MonitorService.running.collectAsStateWithLifecycle()
    val tick by remember { dao.latestTick() }.collectAsStateWithLifecycle(null)
    val segment by remember { dao.lastSegmentFlow() }.collectAsStateWithLifecycle(null)
    var data by remember { mutableStateOf<PeriodData?>(null) }
    LaunchedEffect(period, running, tick?.id, segment?.id) {
        data = loadPeriodData(dao, period, running)
    }
    return data
}
