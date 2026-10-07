package cz.netdenik.ui.screens

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
import cz.netdenik.Config
import cz.netdenik.collect.MonitorService
import cz.netdenik.data.AppDatabase
import cz.netdenik.data.ConnState
import cz.netdenik.data.NetDao
import cz.netdenik.data.Prefs
import cz.netdenik.logic.SegmentDetail
import cz.netdenik.logic.bandLong
import cz.netdenik.logic.evenly
import cz.netdenik.logic.mccMncSpaced
import cz.netdenik.logic.mobileName
import cz.netdenik.logic.natLabel
import cz.netdenik.logic.segmentDetail
import cz.netdenik.logic.techLong
import cz.netdenik.logic.wifiSignalBars
import cz.netdenik.logic.wifiStandardLabel
import cz.netdenik.ui.Fmt
import cz.netdenik.ui.charts.AxisLabels
import cz.netdenik.ui.charts.RsrpBars
import cz.netdenik.ui.charts.SignalBars
import cz.netdenik.ui.components.CardTitle
import cz.netdenik.ui.components.DetailRow
import cz.netdenik.ui.components.NdCard
import cz.netdenik.ui.components.StatusChip
import cz.netdenik.ui.components.SubpageHeader
import cz.netdenik.ui.components.Tag
import cz.netdenik.ui.theme.Nd
import cz.netdenik.ui.theme.NdIcons
import cz.netdenik.ui.theme.NdType
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
    NdCard(spacing = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(24.dp), tint = tint)
            }
            Column {
                Text(title, style = NdType.CardTitle.copy(fontSize = NdType.SubpageTitle.fontSize * 17 / 18))
                Text(
                    listOfNotNull(
                        "${Fmt.timeRelative(s.startMs, l.nowMs)} – ${Fmt.time(s.endMs)}",
                        Fmt.duration(s.endMs - s.startMs),
                        "probíhá".takeIf { d.ongoing },
                    ).joinToString(" · "),
                    style = NdType.MonoSmall, color = Nd.TextVariant,
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
                Text("Přeneseno", Modifier.weight(1f), style = NdType.SmallVariant)
                Text("↓ ${Fmt.bytes(d.rxBytes)}  ↑ ${Fmt.bytes(d.txBytes)}", style = NdType.MonoSmall)
            }
            if (d.ownRxBytes + d.ownTxBytes > 0) {
                Text("z toho vlastní měření ↓ ${Fmt.bytes(d.ownRxBytes)} ↑ ${Fmt.bytes(d.ownTxBytes)}", style = NdType.Caption)
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
        if (before > 0) Box(Modifier.weight(before).fillMaxHeight().background(Nd.SurfaceHigh))
        Box(Modifier.weight(during).fillMaxHeight().background(color))
        if (after > 0) Box(Modifier.weight(after).fillMaxHeight().background(Nd.SurfaceHigh))
    }
}

/** Karta s nadpisem a řádky „název – hodnota“. */
@Composable
private fun RowsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    NdCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), spacing = 0.dp) {
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
    Summary(l, NdIcons.Wifi, Nd.Wifi, Nd.PrimaryContainer, w?.ssid ?: "Wi‑Fi bez názvu", positionBar = true)

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
            if (rssi == null) Text("–", style = NdType.MonoSmall) else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SignalBars(wifiSignalBars(rssi), Nd.Wifi)
                    Text("${Fmt.signed(rssi)} dBm", style = NdType.MonoSmall)
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
            if (w.validated == true) StatusChip("Internet ověřen", dot = Nd.Success) else StatusChip("Internet neověřen", dot = Nd.Mobile)
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
    Summary(l, NdIcons.Cell, Nd.Mobile, Nd.MobileContainer, c?.let(::mobileName) ?: "Mobilní data", positionBar = false)

    NdCard(spacing = 10.dp) {
        CardTitle("Signál během úseku", note = "RSRP dBm")
        if (d.rsrp.isEmpty()) {
            Text("Síla signálu se v tomhle úseku nezaznamenala.", style = NdType.Caption)
        } else {
            val shown = evenly(d.rsrp, 8)
            RsrpBars(shown.map { it.second }, Modifier.fillMaxWidth())
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HorizontalDivider(color = Nd.Outline)
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
            Tag(natLabel(c?.natType), Nd.Mobile, Nd.MobileContainer)
        }
        if (c?.ipv6 != null) {
            Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("IPv6", style = NdType.SmallVariant)
                Text(c.ipv6, style = NdType.MonoCaption, color = Nd.Text)
            }
        }
    }
}
