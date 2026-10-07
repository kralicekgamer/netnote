package cz.kralicekgamer.netnote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.kralicekgamer.netnote.data.ConnState
import cz.kralicekgamer.netnote.data.Prefs
import cz.kralicekgamer.netnote.logic.HostPort
import cz.kralicekgamer.netnote.logic.TransportStats
import cz.kralicekgamer.netnote.logic.bucketMedians
import cz.kralicekgamer.netnote.logic.pingByNetwork
import cz.kralicekgamer.netnote.logic.pingPeaks
import cz.kralicekgamer.netnote.logic.transportStats
import cz.kralicekgamer.netnote.ui.Fmt
import cz.kralicekgamer.netnote.ui.Period
import cz.kralicekgamer.netnote.ui.PeriodData
import cz.kralicekgamer.netnote.ui.charts.AxisLabels
import cz.kralicekgamer.netnote.ui.charts.BarChart
import cz.kralicekgamer.netnote.ui.charts.HBar
import cz.kralicekgamer.netnote.ui.components.CardFootnote
import cz.kralicekgamer.netnote.ui.components.CardTitle
import cz.kralicekgamer.netnote.ui.components.NnCard
import cz.kralicekgamer.netnote.ui.components.ScreenHeader
import cz.kralicekgamer.netnote.ui.components.StateSwatch
import cz.kralicekgamer.netnote.ui.theme.Nn
import cz.kralicekgamer.netnote.ui.theme.NnType

@Composable
fun PingScreen(data: PeriodData?, period: Period, onPeriod: (Period) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val interval = remember { prefs.pingIntervalMs }
    val target = remember { HostPort(prefs.pingHost, prefs.pingPort).label }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader("Ping", "Doba TCP spojení na $target · ${Fmt.every(interval)}")
        PeriodSwitch(period, onPeriod)
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                data == null || data.period != period -> Unit
                data.pings.isEmpty() -> MessageCard(
                    "Zatím žádné měření",
                    "Ping se měří ${Fmt.every(interval)}, když je telefon připojený. Se zhasnutým displejem řidčeji.",
                )
                else -> PingCards(data, interval)
            }
        }
    }
}

@Composable
private fun PingCards(data: PeriodData, intervalMs: Long) {
    val wifi = remember(data) { transportStats(ConnState.WIFI, data.pings, data.speeds, data.traffic) }
    val mobile = remember(data) { transportStats(ConnState.MOBILE, data.pings, data.speeds, data.traffic) }
    val day = data.period == Period.DAY
    // Při řidším měření by čtvrthodinové sloupce byly napůl prázdné.
    val halfHours = day && intervalMs > 5 * 60_000L
    val buckets = remember(data, halfHours) {
        // Den po 15 nebo 30 minutách, týden po 2 hodinách.
        bucketMedians(
            data.fromMs, data.chartToMs, if (!day) 84 else if (halfHours) 48 else 96, data.segments, data.pings,
            ts = { it.ts }, value = { it.rttMs }, transport = { it.transport },
        )
    }
    val byNetwork = remember(data) { pingByNetwork(data.pings, data.index) }
    val peaks = remember(data) { pingPeaks(data.pings, data.index) }

    NnCard {
        TwoColumns(
            left = { m -> PingColumn(ConnState.WIFI, "Wi‑Fi · medián", wifi, m) },
            right = { m -> PingColumn(ConnState.MOBILE, "Data · medián", mobile, m) },
        )
    }

    NnCard(spacing = 10.dp) {
        CardTitle("Průběh", note = if (!day) "medián za 2 h" else if (halfHours) "medián za 30 min" else "medián za 15 min")
        BarChart(buckets, Modifier.height(122.dp), gap = if (halfHours) 2.dp else 1.dp)
        if (day) AxisLabels(dayAxisLabels(data), Modifier.padding(end = 28.dp)) else WeekAxis(data)
        ChartLegend()
    }

    if (byNetwork.isNotEmpty()) {
        NnCard {
            CardTitle("Podle sítě")
            val slowest = byNetwork.maxOf { it.medianMs }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (n in byNetwork) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(n.name, Modifier.weight(1f), style = NnType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${Fmt.ms(n.medianMs)} ms", style = NnType.MonoSmall)
                        }
                        HBar((n.medianMs / (slowest * 1.25)).toFloat(), Nn.state(n.transport))
                    }
                }
            }
        }
    }

    NnCard(spacing = 10.dp) {
        CardTitle("Největší špičky")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in peaks) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (day) Fmt.time(p.ts) else "${Fmt.dayMonth(p.ts)} ${Fmt.time(p.ts)}",
                        Modifier.width(if (day) 48.dp else 96.dp),
                        style = NnType.MonoSmall, color = Nn.TextVariant,
                    )
                    Text(p.network ?: "neznámá síť", Modifier.weight(1f), style = NnType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${Fmt.ms(p.rttMs)} ms", style = NnType.MonoSmall)
                }
            }
        }
        CardFootnote("Neúspěšná měření: ${Fmt.count(wifi.failedPings + mobile.failedPings)} z ${Fmt.count(data.pings.size)}")
    }
}

@Composable
private fun PingColumn(state: ConnState, label: String, t: TransportStats, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TransportLabel(state, label)
        Text(
            buildAnnotatedString {
                append(Fmt.ms(t.ping.median))
                withStyle(SpanStyle(fontSize = 16.sp, color = Nn.TextVariant)) { append(" ms") }
            },
            style = NnType.number(34),
        )
        Text(
            if (t.ping.count == 0) "bez měření"
            else "p95 ${Fmt.ms(t.ping.p95)} ms\nmin ${Fmt.ms(t.ping.min)} · max ${Fmt.ms(t.ping.max)} ms",
            style = NnType.MonoCaption, lineHeight = 19.sp,
        )
    }
}

/** Osa týdenního grafu: zkratka dne pod každou sedminou. */
@Composable
fun WeekAxis(data: PeriodData) {
    Row(Modifier.fillMaxWidth().padding(end = 28.dp)) {
        for (back in 6 downTo 0) {
            Text(
                Fmt.dayName(data.today.minusDays(back.toLong())),
                Modifier.weight(1f), style = NnType.Axis, textAlign = TextAlign.Center,
            )
        }
    }
}

/** Legenda sloupcových grafů: obě sítě a čárka pro úsek bez měření. */
@Composable
fun ChartLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((state, name) in listOf(ConnState.WIFI to "Wi‑Fi", ConnState.MOBILE to "Data")) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                StateSwatch(state)
                Text(name, style = NnType.Caption, color = Nn.TextVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.width(10.dp).height(3.dp).background(Nn.Offline))
            Text("Bez měření", style = NnType.Caption, color = Nn.TextVariant)
        }
    }
}
