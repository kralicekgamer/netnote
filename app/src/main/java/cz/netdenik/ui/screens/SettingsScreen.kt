package cz.netdenik.ui.screens

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.netdenik.Config
import cz.netdenik.collect.MonitorService
import cz.netdenik.collect.Perms
import cz.netdenik.data.AppDatabase
import cz.netdenik.data.KnownAp
import cz.netdenik.data.Prefs
import cz.netdenik.data.Retention
import cz.netdenik.logic.HostPort
import cz.netdenik.logic.dailyTestBytes
import cz.netdenik.logic.limitAllows
import cz.netdenik.logic.parseHostPort
import cz.netdenik.logic.retentionCutoff
import cz.netdenik.logic.startOfDay
import cz.netdenik.ui.Fmt
import cz.netdenik.ui.components.LinkButton
import cz.netdenik.ui.components.NdCard
import cz.netdenik.ui.components.OutlinedPillButton
import cz.netdenik.ui.components.SettingRow
import cz.netdenik.ui.components.SubpageHeader
import cz.netdenik.ui.theme.Nd
import cz.netdenik.ui.theme.NdIcons
import cz.netdenik.ui.theme.NdType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

private class Status(
    val fineLocation: Boolean,
    val backgroundLocation: Boolean,
    val phoneState: Boolean,
    val notifications: Boolean,
    val locationOn: Boolean,
    val exactAlarm: Boolean,
    val batteryIgnored: Boolean,
    val gapCount: Int,
)

private fun readStatus(ctx: Context) = Status(
    fineLocation = Perms.fineLocation(ctx),
    backgroundLocation = Perms.backgroundLocation(ctx),
    phoneState = Perms.phoneState(ctx),
    notifications = Perms.notifications(ctx),
    locationOn = ctx.getSystemService(LocationManager::class.java).isLocationEnabled,
    exactAlarm = Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
    batteryIgnored = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
    gapCount = Prefs(ctx).gapCount,
)

/** Údaje z databáze, které Nastavení jen ukazuje. */
private class Stored(
    val startedAt: Long?,
    val firstRecord: Long?,
    val bytes: Long,
    val accessPoints: List<KnownAp>,
    /** Kolik dnes přenesly speedtesty na mobilních datech. */
    val mobileTestBytesToday: Long,
)

/** Hodnoty z Nastavení, které řídí měření. Čtou se znovu po každé změně. */
private class Chosen(prefs: Prefs) {
    val pingIntervalMs = prefs.pingIntervalMs
    val wifiIntervalMs = prefs.wifiSpeedIntervalMs
    val mobileIntervalMs = prefs.mobileSpeedIntervalMs
    val dailyLimitBytes = prefs.mobileDailyLimitBytes
    val skipMeteredWifi = prefs.skipMeteredWifi
    val pingTarget = HostPort(prefs.pingHost, prefs.pingPort)
    val retentionDays = prefs.retentionDays
}

private suspend fun loadStored(ctx: Context): Stored = withContext(Dispatchers.IO) {
    val dao = AppDatabase.get(ctx).dao()
    val db = ctx.getDatabasePath("netdenik.db").path
    Stored(
        startedAt = dao.lastServiceStart(),
        firstRecord = dao.firstSegmentStart(),
        bytes = listOf("", "-wal", "-shm").sumOf { File(db + it).length() },
        accessPoints = dao.knownAccessPoints(),
        mobileTestBytesToday = dao.mobileTestBytesSince(startOfDay(System.currentTimeMillis(), ZoneId.systemDefault())),
    )
}

private sealed interface Dialog {
    data object PingInterval : Dialog
    data object WifiInterval : Dialog
    data object MobileInterval : Dialog
    data object DailyLimit : Dialog
    data object PingTarget : Dialog
    data object RetentionDays : Dialog
    data class RetentionConfirm(val days: Int, val cutoff: Long) : Dialog
    data object PublicIp : Dialog
    data object Speedtest : Dialog
    data class AccessPoint(val ap: KnownAp) : Dialog
    data object Autostart : Dialog
    data object Delete : Dialog
}

/** Značky s firmwarem od Oplus (ColorOS / realme UI / OxygenOS); automatické spouštění se u nich povoluje ručně. */
private val OPLUS_BRANDS = setOf("realme", "oppo", "oneplus")

@Composable
fun SettingsScreen(onBack: () -> Unit, onDiagnostics: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(ctx) }
    val running by MonitorService.running.collectAsStateWithLifecycle()

    // Oprávnění se mění mimo appku (systémové dialogy, nastavení), proto se čtou znovu po návratu.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val status = remember(refresh, running) { readStatus(ctx) }
    val chosen = remember(refresh) { Chosen(prefs) }
    var stored by remember { mutableStateOf<Stored?>(null) }
    LaunchedEffect(refresh, running) { stored = loadStored(ctx) }

    var problem by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var allAccessPoints by remember { mutableStateOf(false) }

    val start = rememberStartMeasurement {
        problem = it
        refresh++
    }
    val export = rememberExport { message = it }
    val packageUri = Uri.fromParts("package", ctx.packageName, null)

    fun open(intent: Intent) {
        runCatching { ctx.startActivity(intent) }
            .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    val requestMany = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val requestBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Po dvojím odmítnutí už Android dialog neukáže; zbývá nastavení appky.
        if (!granted) open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        refresh++
    }

    Column(Modifier.fillMaxSize()) {
        SubpageHeader("Nastavení", onBack, large = true)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Group("Měření", bottom = 12) {
                SettingRow(
                    "Měření na pozadí",
                    subtitle = when {
                        !running -> "Vypnuto"
                        stored?.startedAt != null -> "Běží od ${Fmt.timeRelative(stored!!.startedAt!!, System.currentTimeMillis())} · tik každou minutu"
                        else -> "Běží · tik každou minutu"
                    },
                ) {
                    NdSwitch(running) { on -> if (on) start() else MonitorService.stop(ctx) }
                }
                problem?.let { Text(it, Modifier.padding(vertical = 6.dp), style = NdType.Caption, color = Nd.Error) }
                SettingRow(
                    "Ping", subtitle = "Doba TCP spojení",
                    value = intervalLabel(chosen.pingIntervalMs), chevron = true,
                    onClick = { dialog = Dialog.PingInterval },
                )
                SettingRow(
                    "Speedtest na Wi‑Fi",
                    subtitle = "Max ${Config.WIFI_DOWN_MAX_MS / 1000} s nebo ${Config.WIFI_DOWN_MAX_BYTES / 1_000_000} MB",
                    value = intervalLabel(chosen.wifiIntervalMs), chevron = true, divider = false,
                    onClick = { dialog = Dialog.WifiInterval },
                )
                Note(wifiTestNote(chosen.wifiIntervalMs))
                HorizontalDivider(Modifier.padding(top = 4.dp), color = Nd.Divider)
                SettingRow(
                    "Speedtest na datech",
                    subtitle = if (chosen.mobileIntervalMs > 0) "Malý test ${Config.MOBILE_DOWN_BYTES / 1_000_000} MB · i při změně buňky"
                    else "Jen ručně tlačítkem Změřit teď",
                    value = intervalLabel(chosen.mobileIntervalMs), chevron = true,
                    onClick = { dialog = Dialog.MobileInterval },
                )
                val used = stored?.mobileTestBytesToday ?: 0
                val spent = !limitAllows(used, MOBILE_TEST_BYTES, chosen.dailyLimitBytes)
                SettingRow(
                    "Denní limit testů na datech",
                    subtitle = when {
                        used == 0L -> "Dnes zatím nic"
                        spent -> "Dnes použito ${Fmt.bytes(used)} · další test až zítra"
                        else -> "Dnes použito ${Fmt.bytes(used)}"
                    },
                    subtitleColor = if (spent) Nd.Mobile else Nd.Muted,
                    value = limitLabel(chosen.dailyLimitBytes), monoValue = chosen.dailyLimitBytes > 0, chevron = true,
                    onClick = { dialog = Dialog.DailyLimit },
                )
                SettingRow("Netestovat na měřené Wi‑Fi", subtitle = "Třeba hotspot z jiného telefonu", divider = false) {
                    NdSwitch(chosen.skipMeteredWifi) {
                        prefs.skipMeteredWifi = it
                        refresh++
                    }
                }
            }

            Group("Servery") {
                SettingRow("Cíl pingu", subtitle = chosen.pingTarget.label, monoSubtitle = true, onClick = { dialog = Dialog.PingTarget }) { EditIcon() }
                SettingRow("Veřejná IP", subtitle = prefs.ipUrl, monoSubtitle = true, onClick = { dialog = Dialog.PublicIp }) { EditIcon() }
                SettingRow("Speedtest", subtitle = hostOf(prefs.downUrl), monoSubtitle = true, onClick = { dialog = Dialog.Speedtest }) { EditIcon() }
                LinkButton("Obnovit výchozí servery", {
                    prefs.pingHost = Config.DEFAULT_PING_HOST
                    prefs.pingPort = Config.DEFAULT_PING_PORT
                    prefs.ipUrl = Config.DEFAULT_IP_URL
                    prefs.downUrl = Config.DEFAULT_DOWN_URL
                    prefs.upUrl = Config.DEFAULT_UP_URL
                    refresh++
                })
            }

            Group("Přístupové body") {
                Text(
                    "Android název AP nezná, tak si je pojmenuj podle místa. Jména se pak ukážou v historii.",
                    Modifier.padding(bottom = 6.dp), style = NdType.Caption,
                )
                val aps = stored?.accessPoints.orEmpty()
                if (aps.isEmpty()) {
                    Text("Zatím žádný. Objeví se po připojení k Wi‑Fi.", Modifier.padding(vertical = 10.dp), style = NdType.Small, color = Nd.TextVariant)
                }
                val shown = if (allAccessPoints) aps else aps.take(3)
                for (ap in shown) {
                    // refresh v klíči: po přejmenování se řádek překreslí
                    val name = remember(ap.bssid, refresh) { prefs.apName(ap.bssid) }
                    SettingRow(
                        title = name ?: "Bez jména",
                        subtitle = "${ap.ssid ?: "bez názvu sítě"} · ${ap.bssid}",
                        titleStyle = if (name != null) NdType.Row else NdType.Row.copy(color = Nd.TextVariant, fontStyle = FontStyle.Italic),
                        monoSubtitle = true, minHeight = 56.dp,
                        divider = ap != shown.last() || aps.size > 3,
                        onClick = { dialog = Dialog.AccessPoint(ap) },
                    ) { EditIcon() }
                }
                if (aps.size > 3) {
                    LinkButton(if (allAccessPoints) "Zobrazit jen poslední 3" else "Zobrazit všech ${aps.size} AP", { allAccessPoints = !allAccessPoints })
                }
            }

            Group("Oprávnění") {
                PermissionRow("Přesná poloha", status.fineLocation) { requestMany.launch(Perms.initialRequest()) }
                PermissionRow("Poloha na pozadí", status.backgroundLocation, okText = "Vždy", enabled = status.fineLocation) {
                    requestBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                PermissionRow("Stav telefonu", status.phoneState) { requestMany.launch(Perms.initialRequest()) }
                PermissionRow("Notifikace", status.notifications) { requestMany.launch(Perms.initialRequest()) }
                if (!status.locationOn) {
                    PermissionRow("Poloha v telefonu", false, action = "Zapnout") { open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                }
                if (!status.exactAlarm && Build.VERSION.SDK_INT >= 31) {
                    PermissionRow("Přesné budíky", false) { open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)) }
                }
                // Výjimka z optimalizace se zvýrazní až po skutečné díře v datech.
                val stopped = status.gapCount > 0
                SettingRow(
                    "Optimalizace baterie",
                    subtitle = when {
                        status.batteryIgnored -> "Pro tuhle appku vypnutá"
                        stopped -> "Zapnutá. Telefon appku ${status.gapCount}× zastavil."
                        else -> "Zapnutá. Měření zatím nepřerušila."
                    },
                    subtitleColor = if (stopped && !status.batteryIgnored) Nd.Mobile else Nd.Muted,
                    minHeight = 56.dp,
                ) {
                    if (status.batteryIgnored) Granted("Vypnuto") else {
                        OutlinedPillButton(
                            "Vypnout",
                            { open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) },
                            color = if (stopped) Nd.Mobile else Nd.Text,
                            borderColor = if (stopped) Nd.Mobile else Nd.Outline,
                        )
                    }
                }
                SettingRow(
                    "Automatické spouštění",
                    subtitle = if (Build.MANUFACTURER.lowercase() in OPLUS_BRANDS) "Tenhle telefon ho musí povolit ručně v nastavení"
                    else "Některé telefony ho musí povolit ručně v nastavení",
                    minHeight = 56.dp, divider = false,
                ) { OutlinedPillButton("Návod", { dialog = Dialog.Autostart }) }
            }

            Group("Data", bottom = 16) {
                SettingRow("Uloženo", value = stored?.let { storedLabel(it) } ?: "–", monoValue = true, minHeight = 52.dp)
                SettingRow(
                    "Uchovávat data", subtitle = if (chosen.retentionDays > 0) "Starší záznamy se mažou samy" else "Nic se nemaže",
                    value = Fmt.retention(chosen.retentionDays), chevron = true, minHeight = 56.dp, divider = false,
                    onClick = { dialog = Dialog.RetentionDays },
                )
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedPillButton("Exportovat CSV (ZIP)", export, large = true, icon = NdIcons.Export)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !running, role = Role.Button) { dialog = Dialog.Delete },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Smazat všechna data…", style = NdType.Row, fontWeight = FontWeight.Medium, color = if (running) Nd.Muted else Nd.Error)
                    }
                    if (running) Text("Smazat jde jen při vypnutém měření.", Modifier.fillMaxWidth(), style = NdType.Caption, textAlign = TextAlign.Center)
                    message?.let { Text(it, style = NdType.Caption, color = Nd.TextVariant) }
                }
            }

            NdCard(padding = PaddingValues(horizontal = 16.dp, vertical = 2.dp), spacing = 0.dp) {
                SettingRow(
                    "Diagnostika", subtitle = "Syrové hodnoty, log událostí, spolehlivost ticků",
                    chevron = true, divider = false, onClick = onDiagnostics,
                )
            }

            Text(
                "Síťový deník ${versionName(ctx)}\nData zůstávají jen v tomhle telefonu.",
                Modifier.fillMaxWidth().padding(top = 4.dp),
                style = NdType.Caption, lineHeight = NdType.Caption.fontSize * 1.6, textAlign = TextAlign.Center,
            )
        }
    }

    fun close() {
        dialog = null
        refresh++
    }

    when (val d = dialog) {
        null -> Unit
        Dialog.PingInterval -> ChoiceDialog(
            title = "Jak často měřit ping",
            options = Config.PING_INTERVALS_MS.map { Choice(it, intervalLabel(it)) },
            selected = chosen.pingIntervalMs,
            hint = "Se zhasnutým displejem telefon měří nejčastěji zhruba jednou za 5 minut.",
            onDismiss = { dialog = null },
        ) {
            prefs.pingIntervalMs = it
            close()
        }
        Dialog.WifiInterval -> ChoiceDialog(
            title = "Speedtest na Wi‑Fi",
            options = Config.WIFI_SPEED_INTERVALS_MS.map { intervalChoice(it, WIFI_TEST_BYTES) },
            selected = chosen.wifiIntervalMs,
            hint = "Jeden test stáhne nejvýš ${Config.WIFI_DOWN_MAX_BYTES / 1_000_000} MB a odešle ${Config.WIFI_UP_MAX_BYTES / 1_000_000} MB. " +
                "Na pomalé lince skončí dřív a přenese míň.",
            onDismiss = { dialog = null },
        ) {
            prefs.wifiSpeedIntervalMs = it
            close()
        }
        Dialog.MobileInterval -> ChoiceDialog(
            title = "Speedtest na datech",
            options = Config.MOBILE_SPEED_INTERVALS_MS.map { intervalChoice(it, MOBILE_TEST_BYTES) },
            selected = chosen.mobileIntervalMs,
            hint = "K tomu test po změně buňky, nejdřív ${Config.MOBILE_SPEED_MIN_GAP_MS / 60_000} min po předchozím. " +
                "Celkovou spotřebu hlídá denní limit. V roamingu se neměří.",
            onDismiss = { dialog = null },
        ) {
            prefs.mobileSpeedIntervalMs = it
            close()
        }
        Dialog.DailyLimit -> ChoiceDialog(
            title = "Denní limit testů na datech",
            options = Config.MOBILE_DAILY_LIMITS_BYTES.map { Choice(it, limitLabel(it)) },
            selected = chosen.dailyLimitBytes,
            hint = "Po vyčerpání se rychlost na mobilních datech do půlnoci sama neměří. " +
                "Ruční test limit nezastaví, ale započítá se do něj.",
            onDismiss = { dialog = null },
        ) {
            prefs.mobileDailyLimitBytes = it
            close()
        }
        Dialog.PingTarget -> EditDialog(
            title = "Cíl pingu",
            fields = listOf("Adresa a port" to chosen.pingTarget.label),
            hint = "IP adresa nebo jméno serveru a port, třeba ${Config.DEFAULT_PING_HOST}:${Config.DEFAULT_PING_PORT}. " +
                "Měří se doba navázání TCP spojení.",
            validate = { if (parseHostPort(it[0]) == null) "Tohle není adresa serveru s portem 1–65535." else null },
            onDismiss = { dialog = null },
        ) {
            parseHostPort(it[0])?.let { target ->
                prefs.pingHost = target.host
                prefs.pingPort = target.port
            }
            close()
        }
        Dialog.RetentionDays -> ChoiceDialog(
            title = "Uchovávat data",
            options = Config.RETENTION_DAYS.map { Choice(it, Fmt.retention(it)) },
            selected = chosen.retentionDays,
            hint = "Starší záznamy se mažou jednou denně. Export je potřeba udělat dřív, než zmizí.",
            onDismiss = { dialog = null },
        ) { days ->
            val cutoff = retentionCutoff(System.currentTimeMillis(), days, ZoneId.systemDefault())
            val first = stored?.firstRecord
            if (cutoff != null && first != null && first < cutoff) {
                // Kratší doba by smazala data, která už v telefonu jsou: nejdřív se zeptat.
                dialog = Dialog.RetentionConfirm(days, cutoff)
            } else {
                prefs.retentionDays = days
                close()
            }
        }
        is Dialog.RetentionConfirm -> InfoDialog(
            title = "Smazat data starší než ${Fmt.retention(d.days)}?",
            text = "Záznamy z doby před ${Fmt.dayMonth(d.cutoff)} se smažou hned. Nejde to vrátit; export si udělej předem.",
            confirm = "Smazat starší data",
            confirmColor = Nd.Error,
            onConfirm = {
                dialog = null
                prefs.retentionDays = d.days
                scope.launch {
                    val rows = Retention.prune(ctx)
                    message = "Smazáno ${Fmt.count(rows)} starších záznamů."
                    refresh++
                }
            },
            dismiss = "Ponechat",
            onDismiss = { dialog = null },
        )
        Dialog.PublicIp -> EditDialog(
            title = "Adresa pro veřejnou IP",
            fields = listOf("Adresa" to prefs.ipUrl),
            hint = "Odpověď musí obsahovat IP adresu: samotnou, v řádku ip=…, nebo v JSONu jako \"ip\".",
            validate = ::httpsOnly,
            onDismiss = { dialog = null },
        ) {
            prefs.ipUrl = it[0]
            dialog = null
            refresh++
        }
        Dialog.Speedtest -> EditDialog(
            title = "Server pro speedtest",
            fields = listOf("Stahování" to prefs.downUrl, "Odesílání (POST)" to prefs.upUrl),
            hint = "{bytes} v adrese pro stahování se nahradí počtem bajtů.",
            validate = ::httpsOnly,
            onDismiss = { dialog = null },
        ) {
            prefs.downUrl = it[0]
            prefs.upUrl = it[1]
            dialog = null
            refresh++
        }
        is Dialog.AccessPoint -> EditDialog(
            title = "Jméno přístupového bodu",
            fields = listOf("Jméno" to prefs.apName(d.ap.bssid).orEmpty()),
            hint = "${d.ap.ssid ?: "bez názvu sítě"} · ${d.ap.bssid}\nPrázdné pole jméno smaže.",
            validate = { null },
            onDismiss = { dialog = null },
            mono = false,
        ) {
            prefs.setApName(d.ap.bssid, it[0])
            dialog = null
            refresh++
        }
        Dialog.Autostart -> InfoDialog(
            title = "Aby měření samo naběhlo",
            text = "1. Nastavení telefonu → Aplikace → Automatické spouštění → zapni Síťový deník.\n\n" +
                "2. V přehledu spuštěných aplikací appku zamkni (podrž kartu nebo ťukni na ⋮ → Zamknout).\n\n" +
                "3. Appku z přehledu neodsouvej. realme ji tím natvrdo zastaví a měření stojí, dokud ji znovu neotevřeš.",
            confirm = "Otevřít nastavení appky",
            onConfirm = {
                dialog = null
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
            },
            dismiss = "Zavřít",
            onDismiss = { dialog = null },
        )
        Dialog.Delete -> InfoDialog(
            title = "Smazat všechna naměřená data?",
            text = "Smažou se stavy, události, vzorky i měření. Nejde to vrátit; export si udělej předem. Jména přístupových bodů zůstanou.",
            confirm = "Smazat data",
            confirmColor = Nd.Error,
            onConfirm = {
                dialog = null
                scope.launch {
                    withContext(Dispatchers.IO) { AppDatabase.get(ctx).clearAllTables() }
                    prefs.resetCounters()
                    message = "Data smazána."
                    refresh++
                }
            },
            dismiss = "Ponechat",
            onDismiss = { dialog = null },
        )
    }
}

private const val WIFI_TEST_BYTES = Config.WIFI_DOWN_MAX_BYTES + Config.WIFI_UP_MAX_BYTES
private const val MOBILE_TEST_BYTES = Config.MOBILE_DOWN_BYTES + Config.MOBILE_UP_BYTES

/** Hodnota v řádku: „Každých 30 min“, u vypnutých testů „Vypnuto“. */
private fun intervalLabel(ms: Long): String =
    if (ms <= 0) "Vypnuto" else Fmt.every(ms).replaceFirstChar { it.uppercase() }

/** Volba v dialogu i s tím, kolik nejvýš za den přenese: „Každých 30 min“ a pod tím „do 576 MB denně“. */
private fun intervalChoice(ms: Long, bytesPerTest: Long): Choice<Long> =
    if (ms <= 0) Choice(ms, "Vypnuto", "Jen ručně tlačítkem Změřit teď")
    else Choice(ms, intervalLabel(ms), "do ${Fmt.bytes(dailyTestBytes(ms, bytesPerTest))} denně")

/** Objem s nedělitelnou mezerou, aby se číslo a jednotka nerozdělily na dva řádky. */
private fun bytesNoBreak(bytes: Long) = Fmt.bytes(bytes).replace(' ', '\u00A0')

private fun limitLabel(bytes: Long): String = if (bytes <= 0) "Bez limitu" else Fmt.bytes(bytes)

/** Odhad spotřeby pod řádkem testu na Wi‑Fi; u kratších intervalů i to, kolik ušetří výchozí. */
private fun wifiTestNote(intervalMs: Long): String {
    if (intervalMs <= 0) return "Rychlost na Wi‑Fi se sama neměří. Změříš ji tlačítkem Změřit teď na záložce Rychlost."
    val daily = "Při tomhle intervalu spotřebují testy nejvýš kolem ${bytesNoBreak(dailyTestBytes(intervalMs, WIFI_TEST_BYTES))} denně."
    val default = Config.DEFAULT_WIFI_SPEED_INTERVAL_MS
    return if (intervalMs >= default) daily
    else "$daily ${intervalLabel(default)} to sníží na ${bytesNoBreak(dailyTestBytes(default, WIFI_TEST_BYTES))}."
}

private fun hostOf(url: String): String = runCatching { Uri.parse(url).host }.getOrNull() ?: url

private fun httpsOnly(values: List<String>): String? =
    if (values.any { !it.startsWith("https://") }) "Adresa musí začínat https://. Nešifrované HTTP Android nepovolí." else null

private fun versionName(ctx: Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""

/** „7 dní · 18,4 MB“. */
private fun storedLabel(s: Stored): String {
    val first = s.firstRecord ?: return "nic · ${Fmt.bytes(s.bytes)}"
    val hours = (System.currentTimeMillis() - first) / 3_600_000L
    val days = (hours + 23) / 24
    val span = when {
        hours < 24 -> "$hours h"
        days == 1L -> "1 den"
        days in 2..4 -> "$days dny"
        else -> "$days dní"
    }
    return "$span · ${Fmt.bytes(s.bytes)}"
}

// ---------------------------------------------------------------- stavební části

/** Karta nastavení s modrým nadpisem skupiny. */
@Composable
private fun Group(title: String, bottom: Int = 8, content: @Composable ColumnScope.() -> Unit) {
    NdCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = bottom.dp), spacing = 0.dp) {
        Text(title, Modifier.padding(top = 10.dp, bottom = 4.dp), style = NdType.SectionLabel)
        content()
    }
}

/** Přepínač v barvách návrhu. */
@Composable
private fun NdSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = Nd.Primary, checkedThumbColor = Nd.Background,
            uncheckedTrackColor = Nd.SurfaceHigh, uncheckedThumbColor = Nd.Muted, uncheckedBorderColor = Nd.Outline,
        ),
    )
}

@Composable
private fun EditIcon() {
    Icon(NdIcons.Edit, "Upravit", Modifier.size(16.dp), tint = Nd.Faint)
}

@Composable
private fun Granted(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(NdIcons.Check, null, Modifier.size(16.dp), tint = Nd.Success)
        Text(text, style = NdType.Small, color = Nd.Success)
    }
}

@Composable
private fun PermissionRow(
    label: String,
    ok: Boolean,
    okText: String = "Povoleno",
    action: String = "Povolit",
    enabled: Boolean = true,
    onGrant: () -> Unit,
) {
    SettingRow(label, minHeight = 52.dp) {
        if (ok) Granted(okText) else OutlinedPillButton(action, onGrant, enabled = enabled)
    }
}

/** Poznámka o spotřebě: žlutý text na tmavém podkladu, vlevo ikona. */
@Composable
private fun Note(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Nd.WarningContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(NdIcons.Info, null, Modifier.padding(top = 1.dp).size(16.dp), tint = Nd.Warning)
        Text(text, style = NdType.Caption, color = Nd.Warning)
    }
}
