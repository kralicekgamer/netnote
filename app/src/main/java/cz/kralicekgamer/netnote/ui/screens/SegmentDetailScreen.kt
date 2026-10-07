package cz.kralicekgamer.netnote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.kralicekgamer.netnote.Config
import cz.kralicekgamer.netnote.collect.MonitorService
import cz.kralicekgamer.netnote.data.AppDatabase
import cz.kralicekgamer.netnote.data.ConnState
import cz.kralicekgamer.netnote.data.NetDao
import cz.kralicekgamer.netnote.data.Prefs
import cz.kralicekgamer.netnote.logic.SegmentDetail
import cz.kralicekgamer.netnote.logic.bandLong
import cz.kralicekgamer.netnote.logic.evenly
import cz.kralicekgamer.netnote.logic.mccMncSpaced
import cz.kralicekgamer.netnote.logic.mobileName
import cz.kralicekgamer.netnote.logic.natLabel
import cz.kralicekgamer.netnote.logic.segmentDetail
import cz.kralicekgamer.netnote.logic.techLong
import cz.kralicekgamer.netnote.logic.wifiSignalBars
import cz.kralicekgamer.netnote.logic.wifiStandardLabel
import cz.kralicekgamer.netnote.ui.Fmt
import cz.kralicekgamer.netnote.ui.charts.AxisLabels
import cz.kralicekgamer.netnote.ui.charts.RsrpBars
import cz.kralicekgamer.netnote.ui.charts.SignalBars
import cz.kralicekgamer.netnote.ui.components.CardTitle
import cz.kralicekgamer.netnote.ui.components.DetailRow
import cz.kralicekgamer.netnote.ui.components.NnCard
import cz.kralicekgamer.netnote.ui.components.StatusChip
import cz.kralicekgamer.netnote.ui.components.SubpageHeader
import cz.kralicekgamer.netnote.ui.components.Tag
import cz.kralicekgamer.netnote.ui.theme.Nn
import cz.kralicekgamer.netnote.ui.theme.NnIcons
import cz.kralicekgamer.netnote.ui.theme.NnType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class Loaded(val detail: SegmentDetail, val nowMs: Long, val apName: String?)

private suspend fun load(dao: NetDao, prefs: Prefs, id: Long, running: Boolean): Loaded? = withContext(Dispatchers.IO) {
    val stored = dao.segmentById(id) ?: return@withContext null
    val now = System.currentTimeMillis()
    // Běžící úsek končí v databázi posledním heartbeatem; pro zobrazení ho protáhneme do teď.
    val ongoing = running && dao.lastSegment()?.id == id && now - stored.endMs < Config.GAP_THRESHOLD_MS
    val segment = if (ongoing) stored.copy(endMs = now) else stored
    val from = segment.startMs
    val to = segment.endMs + 1
    val detail = segmentDetail(
        segment, ongoing,
        pings = dao.pingsBetween(from, to),
        speeds = dao.speedsBetween(from, to),
        traffic = dao.trafficBetween(from, to),
        wifi = dao.wifiBetween(from, to),
        cells = dao.cellsBetween(from, to),
        events = dao.eventsBetween(from, to),
    )
    Loaded(detail, now, detail.wifi?.bssid?.let(prefs::apName))
}

@Composable
fun SegmentDetailScreen(segmentId: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDatabase.get(ctx).dao() }
    val prefs = remember { Prefs(ctx) }
    val running by MonitorService.running.collectAsStateWithLifecycle()
    val tick by remember { dao.latestTick() }.collectAsStateWithLifecycle(null)
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var missing by remember { mutableStateOf(false) }

    LaunchedEffect(segmentId, running, tick?.id) {
        val result = load(dao, prefs, segmentId, running)
        loaded = result
        missing = result == null
    }

    val l = loaded
    val wifi = l?.detail?.segment?.state != ConnState.MOBILE
    Column(Modifier.fillMaxSize()) {
        SubpageHeader(if (wifi) "Úsek · Wi‑Fi" else "Úsek · mobilní data", onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                missing -> MessageCard("Úsek už neexistuje", "Data byla mezitím smazána.")
                l == null -> Unit
                wifi -> WifiDetail(l)
                else -> MobileDetail(l)
            }
        }
    }
}

// ---------------------------------------------------------------- společné části

@Composable
private fun Summary(
    l: Loaded,
    icon: ImageVector,
    tint: Color,
    container: Color,
    title: String,
    positionBar: Boolean,
) {
    val d = l.detail
    val s = d.segment
    NnCard(spacing = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(24.dp), tint = tint)
            }
            Column {
                Text(title, style = NnType.CardTitle.copy(fontSize = NnType.SubpageTitle.fontSize * 17 / 18))
                Text(
                    listOfNotNull(
                        "${Fmt.timeRelative(s.startMs, l.nowMs)} – ${Fmt.time(s.endMs)}",
                        Fmt.duration(s.endMs - s.startMs),
                        "probíhá".takeIf { d.ongoing },
                    ).joinToString(" · "),
                    style = NnType.MonoSmall, color = Nn.TextVariant,
                )
            }
        }
        val windowFrom = l.nowMs - 24 * 3_600_000L
        if (positionBar && s.endMs > windowFrom) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PositionBar(windowFrom, l.nowMs, maxOf(s.startMs, windowFrom), s.endMs, tint)
                AxisLabels(listOf(Fmt.timeRelative(windowFrom, l.nowMs), "teď"))
            }
        }
        TwoColumns(
            spacing = 8,
            left = { m -> StatTile("Ping · medián", Fmt.msFine(d.ping.median), Fmt.measurements(d.ping.count), m) },
            right = { m ->
                StatTile(
                    "Rychlost · medián",
                    if (d.tests == 0) "–" else "↓${Fmt.mbps(d.downBps.median)} ↑${Fmt.mbps(d.upBps.median)}",
                    if (d.tests == 0) "bez testu" else "Mb/s · ${Fmt.tests(d.tests)}",
                    m,
                )
            },
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Přeneseno", Modifier.weight(1f), style = NnType.SmallVariant)
                Text("↓ ${Fmt.bytes(d.rxBytes)}  ↑ ${Fmt.bytes(d.txBytes)}", style = NnType.MonoSmall)
            }
            if (d.ownRxBytes + d.ownTxBytes > 0) {
                Text("z toho vlastní měření ↓ ${Fmt.bytes(d.ownRxBytes)} ↑ ${Fmt.bytes(d.ownTxBytes)}", style = NnType.Caption)
            }
        }
    }
}

/** Kde úsek leží v posledních 24 hodinách. */
@Composable
private fun PositionBar(fromMs: Long, toMs: Long, startMs: Long, endMs: Long, color: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp)),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        val before = (startMs - fromMs).toFloat()
        val during = (endMs - startMs).toFloat().coerceAtLeast((toMs - fromMs) / 200f)
        val after = (toMs - endMs).toFloat()
        if (before > 0) Box(Modifier.weight(before).fillMaxHeight().background(Nn.SurfaceHigh))
        Box(Modifier.weight(during).fillMaxHeight().background(color))
        if (after > 0) Box(Modifier.weight(after).fillMaxHeight().background(Nn.SurfaceHigh))
    }
}

/** Karta s nadpisem a řádky „název – hodnota“. */
@Composable
private fun RowsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    NnCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), spacing = 0.dp) {
        Box(Modifier.padding(top = 10.dp, bottom = 6.dp)) { CardTitle(title) }
        content()
    }
}

// ---------------------------------------------------------------- Wi‑Fi

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WifiDetail(l: Loaded) {
    val d = l.detail
    val w = d.wifi
    Summary(l, NnIcons.Wifi, Nn.Wifi, Nn.PrimaryContainer, w?.ssid ?: "Wi‑Fi bez názvu", positionBar = true)

    RowsCard("Přístupový bod") {
        if (l.apName != null) DetailRow("Jméno", l.apName, mono = false)
        DetailRow("BSSID", w?.bssid)
        DetailRow(
            "Pásmo a kanál",
            listOfNotNull(w?.band, w?.channel?.let { "ch $it" }, w?.frequencyMhz?.let { "$it MHz" }).joinToString(" · ").ifEmpty { null },
        )
        DetailRow("Standard", wifiStandardLabel(w?.wifiStandard))
        DetailRow("Zabezpečení", w?.security)
        DetailRow("Signál") {
            val rssi = w?.rssiDbm
            if (rssi == null) Text("–", style = NnType.MonoSmall) else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SignalBars(wifiSignalBars(rssi), Nn.Wifi)
                    Text("${Fmt.signed(rssi)} dBm", style = NnType.MonoSmall)
                }
            }
        }
        DetailRow(
            "Rychlost linky",
            if (w?.txLinkMbps == null && w?.rxLinkMbps == null) null else "tx ${w.txLinkMbps ?: "–"} · rx ${w.rxLinkMbps ?: "–"} Mb/s",
            divider = false,
        )
    }

    RowsCard("Adresy") {
        DetailRow("IP adresa", w?.ip?.let { ip -> w.prefixLen?.let { "$ip/$it" } ?: ip })
        DetailRow("Brána", w?.gateway)
        DetailRow("DNS", w?.dns?.replace(";", ", "))
        DetailRow("DHCP server", w?.dhcpServer)
        DetailRow("Veřejná IP", w?.publicIp, divider = false)
    }

    if (w != null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (w.validated == true) StatusChip("Internet ověřen", dot = Nn.Success) else StatusChip("Internet neověřen", dot = Nn.Mobile)
            StatusChip(if (w.metered == true) "Měřené připojení" else "Neměřené připojení")
            StatusChip(if (w.vpn) "Přes VPN" else "Bez VPN")
            StatusChip("${Fmt.changes(d.apChanges, "AP")} · ${Fmt.changes(d.ipChanges, "DHCP")}")
        }
    }
}

// ---------------------------------------------------------------- mobilní data

@Composable
private fun MobileDetail(l: Loaded) {
    val d = l.detail
    val c = d.cell
    Summary(l, NnIcons.Cell, Nn.Mobile, Nn.MobileContainer, c?.let(::mobileName) ?: "Mobilní data", positionBar = false)

    NnCard(spacing = 10.dp) {
        CardTitle("Signál během úseku", note = "RSRP dBm")
        if (d.rsrp.isEmpty()) {
            Text("Síla signálu se v tomhle úseku nezaznamenala.", style = NnType.Caption)
        } else {
            val shown = evenly(d.rsrp, 8)
            RsrpBars(shown.map { it.second }, Modifier.fillMaxWidth())
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HorizontalDivider(color = Nn.Outline)
                AxisLabels(listOf(Fmt.time(shown.first().first), Fmt.time(shown.last().first)))
            }
        }
    }

    RowsCard("Buňka") {
        DetailRow("Operátor", listOfNotNull(c?.operatorName, mccMncSpaced(c?.networkMccMnc)).joinToString(" · ").ifEmpty { null })
        DetailRow("Technologie", c?.let(::techLong))
        DetailRow("ID buňky", c?.cellId?.toString())
        DetailRow("PCI · TAC", if (c?.pci == null && c?.tac == null) null else "${c.pci ?: "–"} · ${c.tac ?: "–"}")
        DetailRow("Pásmo", c?.let(::bandLong))
        DetailRow(
            "RSRQ · SINR",
            if (c?.rsrq == null && c?.sinr == null) null
            else "${c.rsrq?.let { "${Fmt.signed(it)} dB" } ?: "–"} · ${c.sinr?.let { "${Fmt.signed(it)} dB" } ?: "–"}",
        )
        DetailRow(
            "Roaming · SIM",
            if (c == null) null else "${if (c.roaming == true) "ano" else "ne"} · ${c.simSlot?.let { "SIM ${it + 1}" } ?: "–"}",
            divider = false,
        )
    }

    RowsCard("Adresy") {
        DetailRow("IP v telefonu", c?.ip)
        DetailRow("Veřejná IP", c?.publicIp)
        DetailRow("Typ NAT", divider = c?.ipv6 != null) {
            Tag(natLabel(c?.natType), Nn.Mobile, Nn.MobileContainer)
        }
        if (c?.ipv6 != null) {
            Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("IPv6", style = NnType.SmallVariant)
                Text(c.ipv6, style = NnType.MonoCaption, color = Nn.Text)
            }
        }
    }
}
