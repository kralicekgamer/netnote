package cz.netdenik.collect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.telephony.CellInfo
import android.util.Log
import androidx.core.content.ContextCompat
import cz.netdenik.Config
import cz.netdenik.data.AppDatabase
import cz.netdenik.data.ConnState
import cz.netdenik.data.Event
import cz.netdenik.data.EventType
import cz.netdenik.data.PingSample
import cz.netdenik.data.Prefs
import cz.netdenik.data.Retention
import cz.netdenik.data.SpeedSample
import cz.netdenik.data.StateSegment
import cz.netdenik.data.Tick
import cz.netdenik.data.TrafficSample
import cz.netdenik.logic.GapInput
import cz.netdenik.logic.HostPort
import cz.netdenik.logic.LastAlive
import cz.netdenik.logic.RadioInputs
import cz.netdenik.logic.SpeedPlan
import cz.netdenik.logic.classifyNat
import cz.netdenik.logic.detectGap
import cz.netdenik.logic.intervalElapsed
import cz.netdenik.logic.ipConfigDiff
import cz.netdenik.logic.resolveState
import cz.netdenik.logic.speedTestReason
import cz.netdenik.logic.startOfDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Srdce sběru. Změny sítě přicházejí callbacky a hned se zapisují jako události a úseky stavu;
 * jednou za minutu přijde tick, který zapíše heartbeat, vzorek a provede měření.
 */
class Collector(
    context: Context,
    private val scope: CoroutineScope,
    private val onStatus: (String) -> Unit,
) {
    private val ctx = context.applicationContext
    private val dao = AppDatabase.get(ctx).dao()
    private val prefs = Prefs(ctx)
    private val power = ctx.getSystemService(PowerManager::class.java)
    private val location = ctx.getSystemService(LocationManager::class.java)

    /** Callbacky chodí ve shlucích; kanál je slije do jednoho vyhodnocení. */
    private val trigger = Channel<Unit>(Channel.CONFLATED)

    /** Hlídá úseky stavu a poslední snapshoty; tick a callbacky se o ně jinak perou. */
    private val stateMutex = Mutex()
    private val tickMutex = Mutex()

    private val tracker = ConnectivityTracker(ctx) { trigger.trySend(Unit) }
    private val wifiReader = WifiReader(ctx)
    private val cellReader = CellReader(ctx) { trigger.trySend(Unit) }
    private val traffic = TrafficCounter()

    private var started = false
    @Volatile private var state = ConnState.UNKNOWN
    private var openSegmentId = 0L
    private var openSegmentStart = 0L
    @Volatile private var lastWifi: WifiSnapshot? = null
    @Volatile private var lastCell: CellSnapshot? = null

    /** Poslední Wi‑Fi, o které víme SSID i BSSID. Přežívá výpadek, aby šla poznat změna AP po znovupřipojení. */
    private var knownWifi: WifiSnapshot? = null

    @Volatile private var publicIp: String? = null
    private var publicIpKey: String? = null
    private var publicIpAt = 0L
    private var needIpRefresh = true

    private var cellChangedSinceSpeed = false
    private var locationOn: Boolean? = null
    @Volatile private var lastPingMs: Double? = null

    /** elapsedRealtime posledního pingu; 0 = v tomhle připojení se ještě neměřilo. */
    @Volatile private var lastPingAt = 0L
    private var recheck: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SHUTDOWN) onShutdown() else trigger.trySend(Unit)
        }
    }

    fun start() {
        tracker.start()
        cellReader.start()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SHUTDOWN)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        scope.launch {
            guarded("start") { stateMutex.withLock { resume() } }
            launch {
                for (signal in trigger) {
                    delay(300)
                    guarded("evaluate") { evaluate() }
                    // Pojistka: kdyby budík nepřišel (systém ho zahodil), tick spustí první změna sítě.
                    val planned = TickScheduler.scheduledAt
                    if (planned > 0 && SystemClock.elapsedRealtime() - planned > 2 * Config.TICK_INTERVAL_MS) {
                        runTick("WATCHDOG")
                    }
                }
            }
            runTick("START")
        }
    }

    /** Zastavení na přání uživatele: zapíše konec, aby další start věděl, že nešlo o zabití. */
    suspend fun stop() {
        stateMutex.withLock {
            if (started) {
                val now = System.currentTimeMillis()
                dao.setSegmentEnd(openSegmentId, now)
                dao.insert(Event(ts = now, type = EventType.SERVICE_STOP, detail = "zastaveno uživatelem"))
                prefs.stoppedByUserAt = now
            }
            started = false
        }
        release()
    }

    fun release() {
        runCatching { ctx.unregisterReceiver(receiver) }
        tracker.stop()
        cellReader.stop()
        trigger.close()
    }

    fun onTickAlarm() {
        scope.launch { runTick("ALARM") }
    }

    fun measureNow() {
        scope.launch { runTick("MANUAL") }
    }

    // ---- start služby: díry a první úsek ----

    private suspend fun resume() {
        val now = System.currentTimeMillis()
        val bootCount = bootCount()
        val last = dao.lastSegment()
        val gap = detectGap(
            GapInput(
                last = last?.let { LastAlive(it.endMs, it.bootCount) },
                nowMs = now,
                bootCount = bootCount,
                bootTimeMs = now - SystemClock.elapsedRealtime(),
                cleanShutdownAtMs = prefs.cleanShutdownAt.takeIf { it > 0 },
                stoppedByUserAtMs = prefs.stoppedByUserAt.takeIf { it > 0 },
                thresholdMs = Config.GAP_THRESHOLD_MS,
            ),
        )
        for (g in gap.segments) {
            dao.insert(StateSegment(startMs = g.startMs, endMs = g.endMs, state = g.state, bootCount = bootCount, detail = g.detail))
            dao.insert(
                Event(
                    ts = g.endMs,
                    type = if (g.state == ConnState.PHONE_OFF) EventType.BOOT else EventType.GAP,
                    fromValue = last?.state?.name,
                    toValue = g.state.name,
                    detail = "${span(g.startMs, g.endMs)}: ${g.detail}",
                ),
            )
        }
        if (gap.killed) prefs.gapCount += 1
        prefs.cleanShutdownAt = 0
        prefs.stoppedByUserAt = 0

        state = resolveState(radioInputs(tracker.snapshot()))
        val continues = last != null && gap.segments.isEmpty() && gap.resumeStartMs == last.endMs &&
            last.state == state && last.bootCount == bootCount
        if (continues) {
            // Krátká pauza ve stejném stavu: pokračujeme v posledním úseku, ať se historie netříští.
            openSegmentId = last.id
            openSegmentStart = last.startMs
            dao.setSegmentEnd(openSegmentId, now)
        } else {
            openSegmentStart = gap.resumeStartMs
            openSegmentId = dao.insert(
                StateSegment(startMs = openSegmentStart, endMs = now, state = state, bootCount = bootCount),
            )
        }
        dao.insert(
            Event(
                ts = now, type = EventType.SERVICE_START, toValue = state.name,
                detail = listOfNotNull(
                    "boot č. $bootCount",
                    cellReader.listenError?.let { "telefonie: $it" },
                ).joinToString(", "),
            ),
        )
        started = true
    }

    private fun bootCount() = Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, 0)

    private fun onShutdown() {
        val now = System.currentTimeMillis()
        prefs.cleanShutdownAt = now
        // Telefon se vypíná, na asynchronní zápis nemusí zbýt čas.
        runBlocking {
            withTimeoutOrNull(1_500) {
                stateMutex.withLock {
                    if (!started) return@withLock
                    dao.setSegmentEnd(openSegmentId, now)
                    dao.insert(Event(ts = now, type = EventType.SHUTDOWN, fromValue = state.name))
                }
            }
        }
    }

    // ---- vyhodnocení stavu a změn ----

    private fun radioInputs(net: NetSnapshot?) = RadioInputs(
        hasNetwork = net != null,
        wifiTransport = net?.caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
        cellTransport = net?.caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
        airplaneMode = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0,
        wifiEnabled = wifiReader.wifiEnabled,
        mobileDataEnabled = cellReader.mobileDataEnabled,
        simReady = cellReader.simReady,
    )

    private fun ConnState.isConnected() =
        this == ConnState.WIFI || this == ConnState.MOBILE || this == ConnState.OTHER

    /**
     * @param cells čerstvé buňky (jen z ticku)
     * @param forceSample zapsat vzorek, i když se nic nezměnilo (minutový vzorek)
     */
    private suspend fun evaluate(cells: List<CellInfo>? = null, forceSample: Boolean = false) {
        stateMutex.withLock {
            if (!started) return@withLock
            val now = System.currentTimeMillis()
            checkLocationSetting(now)

            val net = tracker.snapshot()
            val resolved = resolveState(radioInputs(net))
            var changeAt = now
            if (net == null && state.isConnected()) {
                // Při přechodu Wi‑Fi → data se síť na okamžik ztratí; nechceme z toho dělat „hledání“.
                val lostAt = tracker.lostAtMs ?: now
                val waited = now - lostAt
                if (waited < Config.LOSS_DEBOUNCE_MS) {
                    recheck?.cancel()
                    recheck = scope.launch {
                        delay(Config.LOSS_DEBOUNCE_MS - waited + 100)
                        trigger.trySend(Unit)
                    }
                    return@withLock
                }
                changeAt = lostAt
            }
            if (resolved != state) switchState(resolved, changeAt)

            when {
                net != null && resolved == ConnState.WIFI -> onWifi(net, now, forceSample)
                net != null && resolved == ConnState.MOBILE -> onMobile(net, now, cells, forceSample)
                else -> {
                    lastWifi = null
                    lastCell = null
                }
            }
        }
    }

    private suspend fun switchState(newState: ConnState, ts: Long) {
        val at = maxOf(ts, openSegmentStart)
        dao.setSegmentEnd(openSegmentId, at)
        openSegmentId = dao.insert(
            StateSegment(
                startMs = at, endMs = maxOf(at, System.currentTimeMillis()),
                state = newState, bootCount = bootCount(),
            ),
        )
        openSegmentStart = at
        dao.insert(Event(ts = at, type = EventType.STATE_CHANGE, fromValue = state.name, toValue = newState.name))
        state = newState
        lastWifi = null
        lastCell = null
        publicIp = null
        needIpRefresh = true
        lastPingMs = null
        // Nové připojení dostane ping hned při dalším ticku, i když je interval delší.
        lastPingAt = 0
        onStatus(statusText())
    }

    private suspend fun onWifi(net: NetSnapshot, now: Long, forceSample: Boolean) {
        val vpn = net.caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val under = if (vpn) tracker.underlying(NetworkCapabilities.TRANSPORT_WIFI) else null
        val cur = wifiReader.read(
            caps = under?.first ?: net.caps,
            link = if (under != null) under.second else net.link,
            validated = net.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            vpn = vpn,
        )
        val prev = lastWifi
        lastWifi = cur
        var changed = prev == null

        // Porovnává se s poslední známou Wi‑Fi, ne jen s minulým vzorkem: telefon často přejde
        // na jiný AP přes pár vteřin bez sítě a taková změna by jinak v logu chyběla.
        val known = knownWifi
        if (cur.ssid != null && cur.bssid != null) {
            if (known != null && known.ssid != cur.ssid) {
                log(
                    now, EventType.WIFI_NETWORK_CHANGE, "${known.ssid} (${known.bssid})", "${cur.ssid} (${cur.bssid})",
                    if (prev == null) "po přerušení" else null,
                )
                publicIp = null
                needIpRefresh = true
                changed = true
            } else if (known != null && known.bssid != cur.bssid) {
                log(
                    now, EventType.AP_CHANGE, known.bssid, cur.bssid,
                    "${cur.ssid}: ${known.frequencyMhz} → ${cur.frequencyMhz} MHz, " +
                        "${known.rssiDbm} → ${cur.rssiDbm} dBm" + if (prev == null) " (po přerušení)" else "",
                )
                changed = true
            }
            knownWifi = cur
        }
        if (prev != null) {
            ipConfigDiff(prev.ip, cur.ip)?.let {
                log(now, EventType.IP_CONFIG_CHANGE, detail = "Wi‑Fi ${cur.ssid}: $it")
                needIpRefresh = true
                changed = true
            }
        }
        if (changed || forceSample) dao.insert(cur.toSample(now, publicIp))
    }

    private suspend fun onMobile(net: NetSnapshot, now: Long, cells: List<CellInfo>?, forceSample: Boolean) {
        val vpn = net.caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val under = if (vpn) tracker.underlying(NetworkCapabilities.TRANSPORT_CELLULAR) else null
        val cur = cellReader.read(
            link = if (under != null) under.second else net.link,
            validated = net.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            vpn = vpn,
            cells = cells,
        )
        val prev = lastCell
        lastCell = cur
        var changed = prev == null
        if (prev != null) {
            if (prev.dataSubId != null && cur.dataSubId != null && prev.dataSubId != cur.dataSubId) {
                log(
                    now, EventType.DATA_SIM_CHANGE,
                    "SIM ${prev.simSlot} (${prev.simOperatorName})", "SIM ${cur.simSlot} (${cur.simOperatorName})",
                )
                publicIp = null
                needIpRefresh = true
                changed = true
            }
            if (prev.networkMccMnc != null && cur.networkMccMnc != null && prev.networkMccMnc != cur.networkMccMnc) {
                log(
                    now, EventType.OPERATOR_CHANGE,
                    "${prev.operatorName} (${prev.networkMccMnc})", "${cur.operatorName} (${cur.networkMccMnc})",
                )
                changed = true
            }
            if (prev.cellId != null && cur.cellId != null && prev.cellId != cur.cellId) {
                log(
                    now, EventType.CELL_CHANGE, prev.cellId.toString(), cur.cellId.toString(),
                    "PCI ${prev.pci} → ${cur.pci}, TAC ${prev.tac} → ${cur.tac}, ${cur.networkType}",
                )
                cellChangedSinceSpeed = true
                changed = true
            }
            if (prev.generation != null && cur.generation != null &&
                (prev.generation != cur.generation || prev.networkType != cur.networkType)
            ) {
                log(
                    now, EventType.TECH_CHANGE,
                    "${prev.generation} (${prev.networkType})", "${cur.generation} (${cur.networkType})",
                )
                changed = true
            }
            if (prev.roaming != null && cur.roaming != null && prev.roaming != cur.roaming) {
                log(now, EventType.ROAMING_CHANGE, prev.roaming.toString(), cur.roaming.toString(), cur.operatorName)
                changed = true
            }
            ipConfigDiff(prev.ip, cur.ip)?.let {
                log(now, EventType.IP_CONFIG_CHANGE, detail = "data: $it")
                needIpRefresh = true
                changed = true
            }
        }
        if (changed || forceSample) {
            dao.insert(cur.toSample(now, publicIp, classifyNat(cur.ip.v4, publicIp, carrier = true).name))
        }
    }

    /** Bez zapnuté systémové polohy Android SSID ani buňku nevydá; ať je v logu vidět proč. */
    private suspend fun checkLocationSetting(now: Long) {
        val on = location.isLocationEnabled
        if (on != locationOn && (locationOn != null || !on)) {
            log(now, if (on) EventType.LOCATION_ON else EventType.LOCATION_OFF)
        }
        locationOn = on
    }

    private suspend fun log(ts: Long, type: EventType, from: String? = null, to: String? = null, detail: String? = null) {
        dao.insert(Event(ts = ts, type = type, fromValue = from, toValue = to, detail = detail))
    }

    // ---- minutový tick ----

    private suspend fun runTick(trigger: String) {
        val startElapsed = SystemClock.elapsedRealtime()
        val scheduledAt = TickScheduler.scheduledAt
        val planned = trigger == "ALARM" || trigger == "WATCHDOG"
        val lateness = if (planned && scheduledAt > 0) startElapsed - scheduledAt else 0
        // Další budík se plánuje dřív než cokoli jiného, aby řetěz ticků nepřetrhla chyba ani vynechání.
        if (trigger != "MANUAL") TickScheduler.scheduleNext(ctx)
        // Předchozí tick ještě měří (pomalá síť); tenhle vynecháme, wakelock drží ten běžící.
        if (!tickMutex.tryLock()) return
        try {
            guarded("tick") {
                val cells = if (state == ConnState.MOBILE) cellReader.freshCells() else null
                evaluate(cells)

                // Heartbeat „jsem naživu“.
                val now = System.currentTimeMillis()
                stateMutex.withLock { if (started) dao.setSegmentEnd(openSegmentId, now) }

                // Před měřením, aby první vzorek po startu služby nezahodil spotřebu vlastního speedtestu.
                sampleTraffic()
                val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

                val net = tracker.snapshot()
                if (net != null && state.isConnected()) {
                    val manual = trigger == "MANUAL"
                    refreshPublicIp(net)
                    evaluate(cells, forceSample = true)
                    if (manual || pingDue()) measurePing(net)
                    measureSpeed(net, manual)
                }
                dao.insert(
                    Tick(
                        ts = now,
                        latenessMs = lateness,
                        workMs = SystemClock.elapsedRealtime() - startElapsed,
                        state = state,
                        trigger = trigger,
                        exactAlarm = TickScheduler.exact,
                        deviceIdle = power.isDeviceIdleMode,
                        screenOn = power.isInteractive,
                        batteryOptIgnored = power.isIgnoringBatteryOptimizations(ctx.packageName),
                        plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
                        batteryPct = batteryPercent(battery),
                    ),
                )
                onStatus(statusText())

                val sincePrune = now - prefs.lastPruneAt
                if (sincePrune < 0 || sincePrune >= Config.PRUNE_INTERVAL_MS) Retention.prune(ctx)
            }
        } finally {
            tickMutex.unlock()
            if (trigger != "MANUAL") TickWakeLock.release()
        }
    }

    private suspend fun refreshPublicIp(net: NetSnapshot) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val fresh = publicIp != null && nowElapsed - publicIpAt < Config.PUBLIC_IP_INTERVAL_MS
        if (fresh && !needIpRefresh) return

        val key = stateMutex.withLock {
            when (state) {
                ConnState.WIFI -> "wifi:${lastWifi?.ssid}"
                ConnState.MOBILE -> "mobile:${lastCell?.dataSubId}:${lastCell?.networkMccMnc}"
                else -> state.name
            }
        }
        val result = Probes.publicIp(net.network, prefs.ipUrl)
        stateMutex.withLock {
            publicIpAt = nowElapsed
            if (result.ip == null) {
                publicIp = null
                return@withLock
            }
            // Změna se hlásí jen v rámci téže sítě; mezi sítěmi je jiná veřejná IP samozřejmost.
            val old = publicIp
            if (old != null && key == publicIpKey && old != result.ip) {
                log(System.currentTimeMillis(), EventType.PUBLIC_IP_CHANGE, old, result.ip)
            }
            publicIp = result.ip
            publicIpKey = key
            needIpRefresh = false
        }
    }

    private fun pingDue(): Boolean {
        val last = lastPingAt
        return last == 0L ||
            intervalElapsed(SystemClock.elapsedRealtime() - last, prefs.pingIntervalMs, Config.DUE_SLACK_MS)
    }

    private suspend fun measurePing(net: NetSnapshot) {
        val transport = state
        val target = HostPort(prefs.pingHost, prefs.pingPort)
        lastPingAt = SystemClock.elapsedRealtime()
        val r = Probes.ping(net.network, target.host, target.port)
        lastPingMs = r.medianMs
        dao.insert(
            PingSample(
                ts = System.currentTimeMillis(), transport = transport,
                target = target.label,
                rttMs = r.medianMs, minMs = r.minMs, attempts = r.attempts, failures = r.failures, error = r.error,
            ),
        )
    }

    private suspend fun measureSpeed(net: NetSnapshot, manual: Boolean) {
        val transport = state
        if (transport != ConnState.WIFI && transport != ConnState.MOBILE) return
        val now = System.currentTimeMillis()
        val mobile = transport == ConnState.MOBILE
        // Měřená Wi‑Fi je typicky hotspot z jiného telefonu: šetříme ji stejně jako mobilní data.
        val metered = mobile || lastWifi?.metered == true
        val testBytes = Config.MOBILE_DOWN_BYTES + Config.MOBILE_UP_BYTES
        val limit = prefs.mobileDailyLimitBytes

        val reason = speedTestReason(
            SpeedPlan(
                manual = manual,
                validated = net.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                mobile = mobile,
                meteredWifi = metered && !mobile,
                roaming = lastCell?.roaming == true,
                sinceLastMs = now - if (metered) prefs.lastMobileSpeedAt else prefs.lastWifiSpeedAt,
                cellChanged = cellChangedSinceSpeed,
                wifiIntervalMs = prefs.wifiSpeedIntervalMs,
                mobileIntervalMs = prefs.mobileSpeedIntervalMs,
                cellChangeMinGapMs = Config.MOBILE_SPEED_MIN_GAP_MS,
                skipMeteredWifi = prefs.skipMeteredWifi,
                // Dotaz jen tehdy, když na výsledku záleží.
                usedTodayBytes = if (mobile && !manual && limit > 0) {
                    dao.mobileTestBytesSince(startOfDay(now, ZoneId.systemDefault()))
                } else 0,
                dailyLimitBytes = limit,
                testBytes = testBytes,
                slackMs = Config.DUE_SLACK_MS,
            ),
        ) ?: return

        val down: Transfer
        val up: Transfer
        if (metered) {
            prefs.lastMobileSpeedAt = now
            cellChangedSinceSpeed = false
            down = Probes.download(net.network, prefs.downUrl, Config.MOBILE_DOWN_BYTES, Config.MOBILE_SPEED_MAX_MS)
            up = Probes.upload(net.network, prefs.upUrl, Config.MOBILE_UP_BYTES, Config.MOBILE_SPEED_MAX_MS)
        } else {
            prefs.lastWifiSpeedAt = now
            down = Probes.download(net.network, prefs.downUrl, Config.WIFI_DOWN_MAX_BYTES, Config.WIFI_DOWN_MAX_MS)
            up = Probes.upload(net.network, prefs.upUrl, Config.WIFI_UP_MAX_BYTES, Config.WIFI_UP_MAX_MS)
        }
        dao.insert(
            SpeedSample(
                ts = now, transport = transport, trigger = reason,
                downBps = down.bps, upBps = up.bps,
                downBytes = down.bytes, upBytes = up.bytes, downMs = down.ms, upMs = up.ms,
                error = listOfNotNull(down.error?.let { "down: $it" }, up.error?.let { "up: $it" })
                    .joinToString("; ").ifEmpty { null },
            ),
        )
    }

    private suspend fun sampleTraffic() {
        val wifiIface = lastWifi?.takeIf { !it.vpn }?.ip?.iface
        val mobileIface = lastCell?.takeIf { !it.vpn }?.ip?.iface
        if (wifiIface != null && wifiIface != prefs.wifiIface) prefs.wifiIface = wifiIface
        if (mobileIface != null && mobileIface != prefs.mobileIface) prefs.mobileIface = mobileIface
        val d = traffic.sample(wifiIface ?: prefs.wifiIface, mobileIface ?: prefs.mobileIface) ?: return
        dao.insert(
            TrafficSample(
                ts = System.currentTimeMillis(), intervalMs = d.intervalMs, state = state,
                wifiRx = d.wifiRx, wifiTx = d.wifiTx, mobileRx = d.mobileRx, mobileTx = d.mobileTx,
                ownRx = d.ownRx, ownTx = d.ownTx, wifiSource = d.wifiSource, mobileSource = d.mobileSource,
            ),
        )
    }

    private fun batteryPercent(battery: Intent?): Int? {
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }

    private fun statusText(): String {
        val where = when (state) {
            ConnState.WIFI -> "Wi‑Fi" + (lastWifi?.ssid?.let { " · $it" } ?: "")
            ConnState.MOBILE -> "Data" + (lastCell?.let { " · ${it.operatorName ?: "?"} ${it.generation ?: ""}" } ?: "")
            ConnState.SEARCHING -> "Hledání sítě"
            ConnState.OFFLINE -> "Offline"
            else -> state.name
        }
        return where + (lastPingMs?.let { " · %.0f ms".format(it) } ?: "")
    }

    /** „6.10. 19:57–20:01 (4 min)“; datum u konce jen když díra přesáhla půlnoc. */
    private fun span(startMs: Long, endMs: Long): String {
        val start = Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault())
        val end = Instant.ofEpochMilli(endMs).atZone(ZoneId.systemDefault())
        val endFormat = if (start.toLocalDate() == end.toLocalDate()) TIME else DATE_TIME
        val seconds = (endMs - startMs) / 1000
        val length = if (seconds < 120) "$seconds s" else "${seconds / 60} min"
        return "${DATE_TIME.format(start)}–${endFormat.format(end)} ($length)"
    }

    /** Jedna chyba (třeba odebrané oprávnění) nesmí shodit sběr; zapíše se do logu událostí. */
    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$what failed", e)
            runCatching {
                dao.insert(
                    Event(
                        ts = System.currentTimeMillis(), type = EventType.ERROR,
                        detail = "$what: ${e.javaClass.simpleName}: ${e.message}",
                    ),
                )
            }
        }
    }

    private companion object {
        const val TAG = "Collector"
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M. HH:mm")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
