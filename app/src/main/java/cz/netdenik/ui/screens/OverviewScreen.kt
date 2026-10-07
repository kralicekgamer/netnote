package cz.netdenik.ui.screens

import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.netdenik.collect.MonitorService
import cz.netdenik.data.AppDatabase
import cz.netdenik.data.ConnState
import cz.netdenik.data.Prefs
import cz.netdenik.logic.Switches
import cz.netdenik.logic.TransportStats
import cz.netdenik.logic.switches
import cz.netdenik.logic.transportStats
import cz.netdenik.logic.weekHeatmap
import cz.netdenik.ui.Fmt
import cz.netdenik.ui.Period
import cz.netdenik.ui.PeriodData
import cz.netdenik.ui.charts.AxisLabels
import cz.netdenik.ui.charts.DonutChart
import cz.netdenik.ui.charts.ShareBar
import cz.netdenik.ui.charts.StateTimeline
import cz.netdenik.ui.charts.WeekHeatmap
import cz.netdenik.ui.components.CardFootnote
import cz.netdenik.ui.components.CardTitle
import cz.netdenik.ui.components.Dot
import cz.netdenik.ui.components.LinkButton
import cz.netdenik.ui.components.MetricTile
import cz.netdenik.ui.components.NdCard
import cz.netdenik.ui.components.ScreenHeader
import cz.netdenik.ui.components.StateLegend
import cz.netdenik.ui.components.StateSwatch
import cz.netdenik.ui.theme.Nd
import cz.netdenik.ui.theme.NdIcons
import cz.netdenik.ui.theme.NdType
import cz.netdenik.ui.theme.stateName
import cz.netdenik.ui.theme.stateShort

@Composable
fun OverviewScreen(
    data: PeriodData?,
    period: Period,
    onPeriod: (Period) -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onPing: () -> Unit,
    onSpeed: () -> Unit,
) {
    val ctx = LocalContext.current
    val dao = remember { AppDatabase.get(ctx).dao() }
    val running by MonitorService.running.collectAsStateWithLifecycle()
    val tick by remember { dao.latestTick() }.collectAsStateWithLifecycle(null)
    var problem by remember { mutableStateOf<String?>(null) }
    val start = rememberStartMeasurement { problem = it }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(
            "Síťový deník", periodSubtitle(period, data),
            actionIcon = NdIcons.Settings, actionLabel = "Nastavení", onAction = onSettings,
        )
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Dot(if (running) Nd.Success else Nd.Muted)
            Text(
                when {
                    !running -> "Měření neběží"
                    tick != null -> "Měření běží · poslední tik ${Fmt.time(tick!!.ts)}"
                    else -> "Měření běží"
                },
                style = NdType.SmallVariant,
            )
        }
        problem?.let {
            Text(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp), style = NdType.Small, color = Nd.Error)
        }
        PeriodSwitch(period, onPeriod)

        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GapNotice(running, onSettings)
            when {
                data == null || data.period != period -> Unit
                !data.hasData -> NoDataCard(running, start)
                period == Period.DAY -> {
                    if (!running) StoppedCard(start)
                    DayCards(data, onHistory, onPing, onSpeed)
                }
                else -> {
                    if (!running) StoppedCard(start)
                    WeekCards(data)
                }
            }
        }
    }
}

/** Měření stojí, ale starší data jsou: krátká výzva nad grafy. */
@Composable
private fun StoppedCard(onStart: () -> Unit) {
    NdCard(spacing = 4.dp) {
        Text("Měření je vypnuté, data se nezapisují.", style = NdType.Small, color = Nd.TextVariant)
        LinkButton("Spustit měření", onStart)
    }
}

/**
 * Výjimka z optimalizace baterie se nabízí až po skutečné díře v datech (telefon appku zastavil).
 * Řidší měření se zhasnutým displejem díra není.
 */
@Composable
private fun GapNotice(running: Boolean, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val gaps = remember(running) { Prefs(ctx).gapCount }
    val exempt = remember(running) {
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }
    if (gaps == 0 || exempt) return
    NdCard(spacing = 4.dp) {
        Text(
            "Telefon appku na pozadí ${gaps}× zastavil a v datech je díra.",
            style = NdType.Small, color = Nd.Mobile,
        )
        LinkButton("Vyřešit v Nastavení", onSettings, chevron = true)
    }
}

// ---------------------------------------------------------------- 24 h

@Composable
private fun DayCards(data: PeriodData, onHistory: () -> Unit, onPing: () -> Unit, onSpeed: () -> Unit) {
    val coverage = data.coverage
    val sw = remember(data) { switches(data.segments, data.events, data.fromMs, data.toMs) }
    val wifi = remember(data) { transportStats(ConnState.WIFI, data.pings, data.speeds, data.traffic) }
    val mobile = remember(data) { transportStats(ConnState.MOBILE, data.pings, data.speeds, data.traffic) }

    NdCard(spacing = 16.dp) {
        CardTitle("Připojení")
        if (coverage.coveredMs < coverage.windowMs * 0.95) {
            Text(
                "Záznam pokrývá ${Fmt.duration(coverage.coveredMs)} z posledních 24 h. Podíly se počítají jen z něj.",
                style = NdType.Caption,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            DonutChart(coverage.shares) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Fmt.percentWhole(coverage.fraction(ConnState.WIFI)), style = NdType.number(24))
                    Text("Wi‑Fi", style = NdType.Caption, color = Nd.TextVariant)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                for (share in coverage.shares) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StateSwatch(share.state, Modifier.padding(top = 4.dp))
                        Column {
                            Text(stateName(share.state), style = NdType.Small)
                            Text(
                                "${Fmt.duration(share.ms)} · ${Fmt.percent(share.fraction)}",
                                style = NdType.Axis,
                            )
                        }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StateTimeline(data.slices, data.fromMs, data.toMs, Modifier.height(22.dp))
            AxisLabels(dayAxisLabels(data))
        }
        LinkButton("Celá historie změn", onHistory, chevron = true)
    }

    SwitchesCard("Přepnutí", sw)

    NdCard(onClick = onPing) {
        CardTitle("Ping · medián", chevron = true)
        TwoColumns(
            left = { m -> PingSummary(wifi, NdIcons.Wifi, Nd.Wifi, "Wi‑Fi", m) },
            right = { m -> PingSummary(mobile, NdIcons.Cell, Nd.Mobile, "Mobilní data", m) },
        )
    }

    NdCard(onClick = onSpeed) {
        CardTitle("Rychlost · medián", chevron = true)
        TwoColumns(
            left = { m -> SpeedSummary(wifi, NdIcons.Wifi, Nd.Wifi, "Wi‑Fi", small = false, m) },
            right = { m -> SpeedSummary(mobile, NdIcons.Cell, Nd.Mobile, "Mobilní data", small = true, m) },
        )
    }

    NdCard {
        CardTitle("Objem dat")
        val wifiTotal = wifi.rxBytes + wifi.txBytes
        val mobileTotal = mobile.rxBytes + mobile.txBytes
        ShareBar(
            listOf(ConnState.WIFI to wifiTotal.toFloat(), ConnState.MOBILE to mobileTotal.toFloat()),
            Modifier.height(10.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VolumeRow(ConnState.WIFI, "Wi‑Fi", wifi)
            VolumeRow(ConnState.MOBILE, "Mobilní data", mobile)
        }
        val ownWifi = wifi.ownRxBytes + wifi.ownTxBytes
        val ownMobile = mobile.ownRxBytes + mobile.ownTxBytes
        if (ownWifi + ownMobile > 0) {
            CardFootnote("Z toho vlastní měření: ${Fmt.bytes(ownWifi)} na Wi‑Fi a ${Fmt.bytes(ownMobile)} na mobilu.")
        }
    }
}

@Composable
private fun SwitchesCard(title: String, sw: Switches) {
    NdCard {
        CardTitle(title)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TwoColumns(
                spacing = 8,
                left = { m -> MetricTile(Fmt.count(sw.wifiMobile), "Wi‑Fi ↔ data", m) },
                right = { m -> MetricTile(Fmt.count(sw.apChanges), "Změny AP", m) },
            )
            TwoColumns(
                spacing = 8,
                left = { m -> MetricTile(Fmt.count(sw.cellChanges), "Změny buňky", m) },
                right = { m ->
                    val label = when (sw.gaps) {
                        0 -> "Výpadky měření"
                        1 -> "Výpadek měření · ${Fmt.duration(sw.gapMs)}"
                        else -> "Výpadky měření · ${Fmt.duration(sw.gapMs)}"
                    }
                    MetricTile(Fmt.count(sw.gaps), label, m)
                },
            )
        }
    }
}

@Composable
private fun NetworkLabel(icon: ImageVector, tint: Color, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
        Text(name, style = NdType.SmallVariant)
    }
}

@Composable
private fun PingSummary(t: TransportStats, icon: ImageVector, tint: Color, name: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        NetworkLabel(icon, tint, name)
        Text(
            buildAnnotatedString {
                append(Fmt.ms(t.ping.median))
                withStyle(SpanStyle(fontSize = 15.sp, color = Nd.TextVariant)) { append(" ms") }
            },
            style = NdType.number(28),
        )
        Text(
            if (t.ping.count == 0) "bez měření"
            else "${Fmt.ms(t.ping.min)}–${Fmt.ms(t.ping.max)} ms · ${Fmt.measurements(t.ping.count)}",
            style = NdType.Caption,
        )
    }
}

@Composable
private fun SpeedSummary(t: TransportStats, icon: ImageVector, tint: Color, name: String, small: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        NetworkLabel(icon, tint, name)
        Text(
            buildAnnotatedString {
                append("↓${Fmt.mbps(t.downBps.median)}")
                withStyle(SpanStyle(fontSize = 15.sp, color = Nd.TextVariant)) { append(" ↑${Fmt.mbps(t.upBps.median)}") }
            },
            style = NdType.number(28),
        )
        Text(if (t.tests == 0) "bez testu" else "Mb/s · ${Fmt.tests(t.tests, small)}", style = NdType.Caption)
    }
}

@Composable
private fun VolumeRow(state: ConnState, name: String, t: TransportStats) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StateSwatch(state)
            Text(name, style = NdType.Body)
        }
        Text("↓ ${Fmt.bytes(t.rxBytes)}  ↑ ${Fmt.bytes(t.txBytes)}", style = NdType.MonoSmall)
    }
}

// ---------------------------------------------------------------- 7 dní

@Composable
private fun WeekCards(data: PeriodData) {
    val coverage = data.coverage
    val days = remember(data) { weekHeatmap(data.segments, data.today, data.zone) }
    val sw = remember(data) { switches(data.segments, data.events, data.fromMs, data.toMs) }
    val wifi = remember(data) { transportStats(ConnState.WIFI, data.pings, data.speeds, data.traffic) }
    val mobile = remember(data) { transportStats(ConnState.MOBILE, data.pings, data.speeds, data.traffic) }

    NdCard {
        CardTitle("Připojení po hodinách", note = "Wi‑Fi za den")
        WeekHeatmap(days, data.today)
        StateLegend(coverage.shares.map { it.state }.sortedBy { it.ordinal })
    }

    NdCard {
        CardTitle("Podíl za týden")
        ShareBar(coverage.shares.map { it.state to it.fraction.toFloat() }, Modifier.height(14.dp), radius = 6.dp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (pair in coverage.shares.chunked(2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (share in pair) {
                        Row(Modifier.weight(1f)) {
                            Text(stateShort(share.state).let { if (share.state == ConnState.MOBILE) "Mobilní data" else it }, Modifier.weight(1f), style = NdType.Small)
                            Text(Fmt.percent(share.fraction), style = NdType.MonoSmall, color = Nd.TextVariant)
                        }
                    }
                    if (pair.size == 1) Row(Modifier.weight(1f)) {}
                }
            }
        }
    }

    SwitchesCard("Přepnutí za týden", sw)

    NdCard(spacing = 10.dp) {
        CardTitle("Týden v číslech")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WeekRow("", "Wi‑Fi", "Data", header = true)
            WeekRow("Ping · medián", "${Fmt.ms(wifi.ping.median)} ms", "${Fmt.ms(mobile.ping.median)} ms")
            WeekRow("Stahování · medián", "${Fmt.mbps(wifi.downBps.median)} Mb/s", "${Fmt.mbps(mobile.downBps.median)} Mb/s")
            WeekRow("Odesílání · medián", "${Fmt.mbps(wifi.upBps.median)} Mb/s", "${Fmt.mbps(mobile.upBps.median)} Mb/s")
            WeekRow("Staženo", Fmt.bytes(wifi.rxBytes), Fmt.bytes(mobile.rxBytes))
            WeekRow("Odesláno", Fmt.bytes(wifi.txBytes), Fmt.bytes(mobile.txBytes))
        }
    }
}

@Composable
private fun WeekRow(label: String, wifi: String, mobile: String, header: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = NdType.Small)
        Text(
            wifi, Modifier.width(92.dp), textAlign = TextAlign.End,
            style = if (header) NdType.Caption else NdType.MonoSmall, color = if (header) Nd.Wifi else Nd.Text,
        )
        Text(
            mobile, Modifier.width(92.dp), textAlign = TextAlign.End,
            style = if (header) NdType.Caption else NdType.MonoSmall, color = if (header) Nd.Mobile else Nd.Text,
        )
    }
}
