package cz.kralicekgamer.netnote.ui.diag

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.kralicekgamer.netnote.collect.MonitorService
import cz.kralicekgamer.netnote.data.AppDatabase
import cz.kralicekgamer.netnote.data.Event
import cz.kralicekgamer.netnote.data.Tick
import kotlinx.coroutines.delay

private const val EVENT_LIMIT = 100

/**
 * Syrový výpis všeho, co appka sbírá: poslední hodnoty z každé tabulky, spolehlivost ticků
 * a poslední události. Jedna bílá karta bez ovládání; co tu chybí, to se nesbírá.
 */
@Composable
fun DiagnosticsScreen() {
    val ctx = LocalContext.current
    val dao = remember { AppDatabase.get(ctx).dao() }

    val running by MonitorService.running.collectAsStateWithLifecycle()
    val segment by remember { dao.lastSegmentFlow() }.collectAsStateWithLifecycle(null)
    val tick by remember { dao.latestTick() }.collectAsStateWithLifecycle(null)
    val wifi by remember { dao.latestWifi() }.collectAsStateWithLifecycle(null)
    val cell by remember { dao.latestCell() }.collectAsStateWithLifecycle(null)
    val ping by remember { dao.latestPing() }.collectAsStateWithLifecycle(null)
    val speed by remember { dao.latestSpeed() }.collectAsStateWithLifecycle(null)
    val traffic by remember { dao.latestTraffic() }.collectAsStateWithLifecycle(null)
    val events by remember { dao.recentEvents(EVENT_LIMIT) }.collectAsStateWithLifecycle(emptyList())

    // Statistika ticků se přepočítá s každým novým tickem.
    var ticks by remember { mutableStateOf<List<Tick>>(emptyList()) }
    LaunchedEffect(tick?.id) {
        val to = System.currentTimeMillis()
        ticks = dao.ticksBetween(to - 24 * 3_600_000L, to + 1)
    }

    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Paper.Background)
                .padding(16.dp),
        ) {
            Section("Služba", first = true) {
                KeyValue("Běží", yesNo(running))
                KeyValue("Poslední tick", tick?.let { "${Fmt.time(it.ts)} (${Fmt.ago(now, it.ts)})" })
                KeyValue("Spouštěč", tick?.trigger)
                KeyValue("Zpoždění ticku", tick?.let { "${it.latenessMs} ms" })
                KeyValue("Délka měření", tick?.let { "${it.workMs} ms" })
                KeyValue("Přesný budík", yesNo(tick?.exactAlarm))
                KeyValue("Doze při ticku", yesNo(tick?.deviceIdle))
                KeyValue("Displej při ticku", tick?.let { if (it.screenOn) "zapnutý" else "zhasnutý" })
                KeyValue("Výjimka z optimalizace baterie", yesNo(tick?.batteryOptIgnored))
                KeyValue("Napájení při ticku", tick?.let { if (it.plugged) "nabíječka / USB" else "baterie" })
                KeyValue("Baterie", tick?.batteryPct?.let { "$it %" })
            }

            Section(if (running) "Stav" else "Poslední zapsaný stav") {
                KeyValue("Stav", segment?.let { Fmt.state(it.state) })
                KeyValue("Od", segment?.let { Fmt.dateTime(it.startMs) })
                KeyValue("Trvá", segment?.let { Fmt.duration(it.endMs - it.startMs) })
                KeyValue("Heartbeat", segment?.let { "${Fmt.time(it.endMs)} (${Fmt.ago(now, it.endMs)})" })
                KeyValue("Počet startů telefonu", segment?.bootCount?.toString())
                KeyValue("Poznámka", segment?.detail)
            }

            Section("Wi‑Fi (poslední vzorek)") {
                val w = wifi
                KeyValue("Vzorek z", w?.let { "${Fmt.time(it.ts)} (${Fmt.ago(now, it.ts)})" })
                KeyValue("SSID", w?.ssid)
                KeyValue("BSSID", w?.bssid)
                KeyValue("Kanál", w?.channel?.let { "$it (${w.frequencyMhz} MHz)" })
                KeyValue("Pásmo", w?.band)
                KeyValue("Verze Wi‑Fi", w?.wifiStandard)
                KeyValue("Zabezpečení", w?.security)
                KeyValue("Signál", w?.rssiDbm?.let { "$it dBm" })
                KeyValue("Link speed ↑ / ↓", w?.let { "${it.txLinkMbps ?: "–"} / ${it.rxLinkMbps ?: "–"} Mb/s" })
                KeyValue("IP adresa", w?.ip?.let { "$it/${w.prefixLen}" })
                KeyValue("IPv6", w?.ipv6)
                KeyValue("Brána", w?.gateway)
                KeyValue("DNS", w?.dns)
                KeyValue("DHCP server", w?.dhcpServer)
                KeyValue("Veřejná IP", w?.publicIp)
                KeyValue("Internet ověřen", yesNo(w?.validated))
                KeyValue("Měřená síť", yesNo(w?.metered))
                KeyValue("VPN", yesNo(w?.vpn))
            }

            Section("Mobilní síť (poslední vzorek)") {
                val c = cell
                KeyValue("Vzorek z", c?.let { "${Fmt.time(it.ts)} (${Fmt.ago(now, it.ts)})" })
                KeyValue("Operátor", c?.operatorName?.let { "$it (${c.networkMccMnc})" })
                KeyValue("SIM", c?.simOperatorName?.let { "$it (${c.simMccMnc})" })
                KeyValue("Datová SIM", c?.let { "slot ${it.simSlot ?: "–"}, subId ${it.dataSubId ?: "–"}, aktivních ${it.activeSimCount ?: "–"}" })
                KeyValue("Roaming", yesNo(c?.roaming))
                KeyValue("Technologie", c?.generation?.let { "$it (${c.networkType})" })
                KeyValue("Zobrazený typ", c?.overrideType)
                KeyValue("ID buňky", c?.cellId?.toString())
                KeyValue("PCI / TAC", c?.let { "${it.pci ?: "–"} / ${it.tac ?: "–"}" })
                KeyValue("ARFCN / pásmo", c?.let { "${it.arfcn ?: "–"} / ${it.bands ?: "–"}" })
                KeyValue("RSRP", c?.rsrp?.let { "$it dBm" })
                KeyValue("RSRQ", c?.rsrq?.let { "$it dB" })
                KeyValue("SINR", c?.sinr?.let { "$it dB" })
                KeyValue("RSSI", c?.rssi?.let { "$it dBm" })
                KeyValue("Úroveň (0–4)", c?.level?.toString())
                KeyValue("IP adresa", c?.ip)
                KeyValue("IPv6", c?.ipv6)
                KeyValue("Veřejná IP", c?.publicIp)
                KeyValue("NAT", c?.natType)
                KeyValue("Internet ověřen", yesNo(c?.validated))
                KeyValue("VPN", yesNo(c?.vpn))
            }

            Section("Ping") {
                val p = ping
                KeyValue("Měřeno", p?.let { "${Fmt.time(it.ts)} přes ${Fmt.state(it.transport)}" })
                KeyValue("Cíl", p?.target)
                KeyValue("Medián / minimum", p?.let { "${Fmt.ms(it.rttMs)} / ${Fmt.ms(it.minMs)}" })
                KeyValue("Neúspěšných pokusů", p?.let { "${it.failures} z ${it.attempts}" })
                KeyValue("Chyba", p?.error)
            }

            Section("Rychlost") {
                val s = speed
                KeyValue("Měřeno", s?.let { "${Fmt.dateTime(it.ts)} přes ${Fmt.state(it.transport)}" })
                KeyValue("Spouštěč", s?.trigger)
                KeyValue("Stahování", s?.let { "${Fmt.bps(it.downBps)} (${Fmt.bytes(it.downBytes)} za ${it.downMs} ms)" })
                KeyValue("Odesílání", s?.let { "${Fmt.bps(it.upBps)} (${Fmt.bytes(it.upBytes)} za ${it.upMs} ms)" })
                KeyValue("Chyba", s?.error)
            }

            Section("Objem dat za poslední interval") {
                val t = traffic
                KeyValue("Interval", t?.let { "${Fmt.time(it.ts)}, ${Fmt.duration(it.intervalMs)}" })
                KeyValue("Wi‑Fi ↓ / ↑", t?.let { "${Fmt.bytes(it.wifiRx)} / ${Fmt.bytes(it.wifiTx)}" })
                KeyValue("Data ↓ / ↑", t?.let { "${Fmt.bytes(it.mobileRx)} / ${Fmt.bytes(it.mobileTx)}" })
                KeyValue("Tahle appka ↓ / ↑", t?.let { "${Fmt.bytes(it.ownRx)} / ${Fmt.bytes(it.ownTx)}" })
                KeyValue("Zdroj čísla pro Wi‑Fi", t?.wifiSource)
                KeyValue("Zdroj čísla pro data", t?.mobileSource)
            }

            TickStats(ticks)
            EventLog(events)
        }
    }
}

/** Jak spolehlivě telefon pouští minutové ticky. */
@Composable
private fun TickStats(ticks: List<Tick>) {
    Section("Ticky za posledních 24 h") {
        val planned = ticks.filter { it.trigger == "ALARM" || it.trigger == "WATCHDOG" }
        KeyValue("Ticků celkem", ticks.size.toString())
        KeyValue("Průměrné zpoždění", planned.takeIf { it.isNotEmpty() }?.let { p -> "${p.sumOf { it.latenessMs } / p.size} ms" })
        KeyValue("Největší zpoždění", planned.maxOfOrNull { it.latenessMs }?.let { Fmt.duration(it) + " ($it ms)" })
        KeyValue("Opožděných o víc než 10 s", planned.count { it.latenessMs > 10_000 }.toString())
        KeyValue("Rozestup zhasnuto, bez výjimky", sleepSpacing(ticks, exempt = false))
        KeyValue("Rozestup zhasnuto, s výjimkou", sleepSpacing(ticks, exempt = true))
        KeyValue("Ticků na nabíječce", ticks.count { it.plugged }.toString())
        KeyValue("Ticků v Doze", ticks.count { it.deviceIdle }.toString())
        KeyValue("Ticků bez přesného budíku", ticks.count { !it.exactAlarm }.toString())
        Text(
            "Rozestup = průměrná doba mezi ticky se zhasnutým displejem na baterii. Telefon tu běžně povolí jen kolem 5 minut.",
            Modifier.padding(top = 4.dp), style = Paper.Small,
        )
    }
}

/** Průměrný rozestup ticků, které přišly se zhasnutým displejem a na baterii. */
private fun sleepSpacing(ticks: List<Tick>, exempt: Boolean): String? {
    val gaps = ticks.zipWithNext()
        .filter { (_, t) -> !t.screenOn && !t.plugged && t.batteryOptIgnored == exempt && t.trigger != "START" }
        .map { (prev, t) -> t.ts - prev.ts }
    if (gaps.isEmpty()) return null
    return "${Fmt.duration(gaps.average().toLong())} (${gaps.size} ticků, nejdelší ${Fmt.duration(gaps.max())})"
}

@Composable
private fun EventLog(events: List<Event>) {
    Section("Události (posledních $EVENT_LIMIT)") {
        if (events.isEmpty()) Text("Zatím žádné.", Modifier.padding(vertical = 2.dp), style = Paper.Small)
        events.forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(color = Paper.Line)
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Fmt.dateTime(e.ts), style = Paper.Value, color = Paper.Label)
                    Text(Fmt.event(e.type), style = Paper.Text, fontWeight = FontWeight.SemiBold)
                }
                val from = Fmt.stateOrRaw(e.fromValue)
                val to = Fmt.stateOrRaw(e.toValue)
                if (from != null || to != null) Text("${from ?: "–"} → ${to ?: "–"}", style = Paper.Value)
                e.detail?.takeIf { it.isNotEmpty() }?.let { Text(it, style = Paper.Small) }
            }
        }
    }
}
