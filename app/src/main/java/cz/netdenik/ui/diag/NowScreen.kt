package cz.netdenik.ui.diag

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.netdenik.collect.MonitorService
import cz.netdenik.collect.Perms
import cz.netdenik.data.AppDatabase
import cz.netdenik.data.Prefs
import kotlinx.coroutines.delay

/** Výpis toho, co se naposledy zapsalo do databáze. Co tu chybí, to se nesbírá. */
@Composable
fun NowScreen() {
    val ctx = LocalContext.current
    val dao = remember { AppDatabase.get(ctx).dao() }
    val prefs = remember { Prefs(ctx) }

    val running by MonitorService.running.collectAsStateWithLifecycle()
    val segment by remember { dao.lastSegmentFlow() }.collectAsStateWithLifecycle(null)
    val tick by remember { dao.latestTick() }.collectAsStateWithLifecycle(null)
    val wifi by remember { dao.latestWifi() }.collectAsStateWithLifecycle(null)
    val cell by remember { dao.latestCell() }.collectAsStateWithLifecycle(null)
    val ping by remember { dao.latestPing() }.collectAsStateWithLifecycle(null)
    val speed by remember { dao.latestSpeed() }.collectAsStateWithLifecycle(null)
    val traffic by remember { dao.latestTraffic() }.collectAsStateWithLifecycle(null)

    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    var problem by remember { mutableStateOf<String?>(null) }

    var gapCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(running, tick?.id) { gapCount = prefs.gapCount }

    fun start() {
        prefs.serviceWanted = true
        problem = if (MonitorService.start(ctx)) null else "Službu se nepodařilo spustit. Zkontroluj oprávnění v Nastavení."
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (Perms.canStart(ctx)) start()
        else problem = "Bez přesné polohy a stavu telefonu Android nevydá SSID ani buňku. Povol je v Nastavení."
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                Button(onClick = { MonitorService.instance?.measureNow() }) { Text("Změřit teď") }
                OutlinedButton(onClick = { MonitorService.stop(ctx) }) { Text("Zastavit sběr") }
            } else {
                Button(onClick = {
                    if (Perms.canStart(ctx)) start() else permissionLauncher.launch(Perms.initialRequest())
                }) { Text("Spustit sběr") }
            }
        }
        problem?.let {
            Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
        }
        if (running && !Perms.backgroundLocation(ctx)) {
            Text(
                "Poloha není povolená „vždy“. Po restartu telefonu služba nedostane SSID ani buňku. " +
                    "Nastav to v záložce Nastavení.",
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Návrh říká: nejdřív zjistit díru v datech a teprve pak nabídnout výjimku.
        // Řidší ticky se zhasnutým displejem díra nejsou, s těmi se počítá.
        if (gapCount > 0 && tick?.batteryOptIgnored == false) {
            Text(
                "Telefon appku na pozadí zastavil ($gapCount×) a v datech je díra.",
                Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.fromParts("package", ctx.packageName, null),
                            ),
                        )
                    }
                },
                modifier = Modifier.padding(top = 4.dp),
            ) { Text("Vypnout optimalizaci baterie") }
        }

        Section("Služba") {
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
            KeyValue("Počet startů telefonu", segment?.bootCount)
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
            KeyValue("ID buňky", c?.cellId)
            KeyValue("PCI / TAC", c?.let { "${it.pci ?: "–"} / ${it.tac ?: "–"}" })
            KeyValue("ARFCN / pásmo", c?.let { "${it.arfcn ?: "–"} / ${it.bands ?: "–"}" })
            KeyValue("RSRP", c?.rsrp?.let { "$it dBm" })
            KeyValue("RSRQ", c?.rsrq?.let { "$it dB" })
            KeyValue("SINR", c?.sinr?.let { "$it dB" })
            KeyValue("RSSI", c?.rssi?.let { "$it dBm" })
            KeyValue("Úroveň (0–4)", c?.level)
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
            KeyValue("Z toho tahle appka ↓ / ↑", t?.let { "${Fmt.bytes(it.ownRx)} / ${Fmt.bytes(it.ownTx)}" })
            KeyValue("Zdroj čísla pro Wi‑Fi", t?.wifiSource)
        }
    }
}
