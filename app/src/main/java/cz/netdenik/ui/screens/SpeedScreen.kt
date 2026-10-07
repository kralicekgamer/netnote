package cz.netdenik.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.netdenik.collect.MonitorService
import cz.netdenik.data.AppDatabase
import cz.netdenik.data.ConnState
import cz.netdenik.data.Prefs
import cz.netdenik.data.SpeedSample
import cz.netdenik.logic.Bucket
import cz.netdenik.logic.TransportStats
import cz.netdenik.logic.bandShort
import cz.netdenik.logic.bucketMedians
import cz.netdenik.logic.speedByNetwork
import cz.netdenik.logic.techLabel
import cz.netdenik.logic.transportStats
import cz.netdenik.ui.Fmt
import cz.netdenik.ui.Period
import cz.netdenik.ui.PeriodData
import cz.netdenik.ui.charts.AxisLabels
import cz.netdenik.ui.charts.BarChart
import cz.netdenik.ui.components.CardFootnote
import cz.netdenik.ui.components.CardTitle
import cz.netdenik.ui.components.Dot
import cz.netdenik.ui.components.NdCard
import cz.netdenik.ui.components.PrimaryPillButton
import cz.netdenik.ui.components.ScreenHeader
import cz.netdenik.ui.theme.Nd
import cz.netdenik.ui.theme.NdIcons
import cz.netdenik.ui.theme.NdType
import kotlinx.coroutines.delay

@Composable
fun SpeedScreen(data: PeriodData?, period: Period, onPeriod: (Period) -> Unit) {
    val ctx = LocalContext.current
    val subtitle = remember {
        val prefs = Prefs(ctx)
        "Wi‑Fi ${everyOrManual(prefs.wifiSpeedIntervalMs)} · data ${everyOrManual(prefs.mobileSpeedIntervalMs)}"
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader("Rychlost", subtitle)
        PeriodSwitch(period, onPeriod)
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (data != null && data.period == period) SpeedCards(data)
        }
    }
}

@Composable
private fun SpeedCards(data: PeriodData) {
    val wifi = remember(data) { transportStats(ConnState.WIFI, data.pings, data.speeds, data.traffic) }
    val mobile = remember(data) { transportStats(ConnState.MOBILE, data.pings, data.speeds, data.traffic) }
    val day = data.period == Period.DAY
    // Den po hodinách, týden po 6 hodinách. Hodnoty rovnou v Mb/s kvůli popiskům osy.
    val count = if (day) 24 else 28
    val down = remember(data) { speedBuckets(data, count) { it.downBps } }
    val up = remember(data) { speedBuckets(data, count) { it.upBps } }
    val byNetwork = remember(data) { speedByNetwork(data.speeds, data.index) }
    val mobileTests = remember(data) {
        data.speeds.filter { it.transport == ConnState.MOBILE }.sortedByDescending { it.ts }.take(if (day) 6 else 10)
    }

    NdCard(spacing = 14.dp) {
        TwoColumns(
            left = { m -> SpeedColumn(ConnState.WIFI, "Wi‑Fi · medián", wifi, small = false, m) },
            right = { m -> SpeedColumn(ConnState.MOBILE, "Data · medián", mobile, small = true, m) },
        )
        MeasureNowButton()
    }

    if (data.speeds.isEmpty()) {
        MessageCard("Zatím žádný test", "Rychlost se měří sama po připojení k síti, nebo hned tlačítkem „Změřit teď“.")
        return
    }

    NdCard(spacing = 10.dp) {
        CardTitle("Stahování", note = if (day) "medián za hodinu · Mb/s" else "medián za 6 h · Mb/s")
        BarChart(down, Modifier.height(104.dp), gap = 3.dp)
        SpeedAxis(data)
        HorizontalDivider(color = Nd.SurfaceHigh)
        CardTitle("Odesílání", note = if (day) "medián za hodinu · Mb/s" else "medián za 6 h · Mb/s")
        BarChart(up, Modifier.height(84.dp), gap = 3.dp)
        SpeedAxis(data)
    }

    if (byNetwork.isNotEmpty()) {
        NdCard(spacing = 10.dp) {
            CardTitle("Podle sítě")
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                NetworkRow(null, "Síť · testů", null, "↓ Mb/s", "↑ Mb/s", header = true)
                for (n in byNetwork) {
                    NetworkRow(n.transport, n.name, n.tests, Fmt.mbps(n.downBps), Fmt.mbps(n.upBps), header = false)
                }
            }
        }
    }

    if (mobileTests.isNotEmpty()) {
        NdCard(spacing = 10.dp) {
            CardTitle("Testy na mobilních datech")
            Column {
                mobileTests.forEachIndexed { i, s ->
                    val cell = data.index.cellAt(s.ts)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            if (day) Fmt.time(s.ts) else "${Fmt.dayMonth(s.ts)} ${Fmt.time(s.ts)}",
                            Modifier.width(if (day) 46.dp else 94.dp),
                            style = NdType.MonoSmall, color = Nd.TextVariant,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(triggerLabel(s.trigger), style = NdType.Small)
                            Text(
                                cell?.let { listOfNotNull(techLabel(it), bandShort(it)).joinToString(" · ") }?.ifEmpty { null } ?: "síť neznámá",
                                style = NdType.Caption,
                            )
                        }
                        Text(
                            if (s.downBps == null && s.upBps == null) "nezdařilo se"
                            else "↓${Fmt.mbps(s.downBps?.toDouble())} ↑${Fmt.mbps(s.upBps?.toDouble())}",
                            style = NdType.MonoSmall, textAlign = TextAlign.End,
                        )
                    }
                    if (i < mobileTests.lastIndex) HorizontalDivider(color = Nd.Divider)
                }
            }
            CardFootnote("Malý test (1 MB) na rychlém LTE trvá zlomek sekundy, ber ho jako orientační.")
        }
    }
}

private fun speedBuckets(data: PeriodData, count: Int, bps: (SpeedSample) -> Long?): List<Bucket> =
    bucketMedians(
        data.fromMs, data.chartToMs, count, data.segments, data.speeds,
        ts = { it.ts }, value = { s -> bps(s)?.let { it / 1e6 } }, transport = { it.transport },
    )

/** Interval do podtitulku; 0 = automatické testy jsou vypnuté. */
private fun everyOrManual(intervalMs: Long) = if (intervalMs > 0) Fmt.every(intervalMs) else "jen ručně"

private fun triggerLabel(trigger: String) = when (trigger) {
    "MANUAL" -> "Ručně"
    "CELL_CHANGE" -> "Změna buňky"
    else -> "Pravidelný test"
}

/** Osa hodinového grafu: jen hodiny („23 05 11 17 23“); u týdne zkratky dnů. */
@Composable
private fun SpeedAxis(data: PeriodData) {
    if (data.period == Period.WEEK) {
        WeekAxis(data)
    } else {
        val step = (data.toMs - data.fromMs) / 4
        AxisLabels((0..4).map { Fmt.hour(data.fromMs + it * step) }, Modifier.padding(end = 28.dp))
    }
}

@Composable
private fun SpeedColumn(state: ConnState, label: String, t: TransportStats, small: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TransportLabel(state, label)
        Text("↓${Fmt.mbps(t.downBps.median)}", style = NdType.number(30))
        Text("↑${Fmt.mbps(t.upBps.median)} Mb/s", style = NdType.number(16), color = Nd.TextVariant)
        Text(if (t.tests == 0) "bez testu" else Fmt.tests(t.tests, small), style = NdType.Caption)
    }
}

@Composable
private fun NetworkRow(transport: ConnState?, name: String, tests: Int?, down: String, up: String, header: Boolean) {
    val valueStyle = if (header) NdType.Caption else NdType.MonoSmall
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (transport != null) Dot(Nd.state(transport))
            Text(name, Modifier.weight(1f, fill = false), style = if (header) NdType.Caption else NdType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (tests != null) Text("· $tests", style = NdType.Small, color = Nd.Muted)
        }
        Text(down, Modifier.width(64.dp), style = valueStyle, textAlign = TextAlign.End)
        Text(up, Modifier.width(64.dp), style = valueStyle, textAlign = TextAlign.End)
    }
}

/**
 * Ruční měření pustí mimořádný tick včetně speedtestu. Tlačítko se odemkne, až dorazí výsledek,
 * nejpozději po 25 s (test se nemusí povést).
 */
@Composable
private fun MeasureNowButton() {
    val ctx = LocalContext.current
    val dao = remember { AppDatabase.get(ctx).dao() }
    val running by MonitorService.running.collectAsStateWithLifecycle()
    val latest by remember { dao.latestSpeed() }.collectAsStateWithLifecycle(null)
    var startedAt by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(startedAt) {
        if (startedAt != null) {
            delay(25_000)
            startedAt = null
        }
    }
    LaunchedEffect(latest?.id) {
        val since = startedAt
        if (since != null && (latest?.ts ?: 0) >= since - 1_000) startedAt = null
    }

    PrimaryPillButton(
        text = when {
            !running -> "Měření je vypnuté"
            startedAt != null -> "Měřím…"
            else -> "Změřit teď"
        },
        icon = NdIcons.Speed,
        enabled = running && startedAt == null,
        onClick = {
            startedAt = System.currentTimeMillis()
            MonitorService.instance?.measureNow()
        },
    )
}
