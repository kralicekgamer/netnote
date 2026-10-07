package cz.kralicekgamer.netnote.collect

import android.annotation.SuppressLint
import android.content.Context
import android.net.LinkProperties
import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import cz.kralicekgamer.netnote.data.CellSample
import cz.kralicekgamer.netnote.logic.IpInfo
import cz.kralicekgamer.netnote.logic.generationOf
import cz.kralicekgamer.netnote.logic.networkTypeName
import cz.kralicekgamer.netnote.logic.overrideTypeName
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume

data class CellSnapshot(
    val operatorName: String?,
    val networkMccMnc: String?,
    val simOperatorName: String?,
    val simMccMnc: String?,
    val roaming: Boolean?,
    val dataSubId: Int?,
    val simSlot: Int?,
    val activeSimCount: Int?,
    val networkType: String?,
    val generation: String?,
    val overrideType: String?,
    val cellId: Long?,
    val pci: Int?,
    val tac: Int?,
    val arfcn: Int?,
    val bands: String?,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val rssi: Int?,
    val level: Int?,
    val ip: IpInfo,
    val validated: Boolean,
    val vpn: Boolean,
) {
    fun toSample(ts: Long, publicIp: String?, natType: String?) = CellSample(
        ts = ts, operatorName = operatorName, networkMccMnc = networkMccMnc,
        simOperatorName = simOperatorName, simMccMnc = simMccMnc, roaming = roaming,
        dataSubId = dataSubId, simSlot = simSlot, activeSimCount = activeSimCount,
        networkType = networkType, generation = generation, overrideType = overrideType,
        cellId = cellId, pci = pci, tac = tac, arfcn = arfcn, bands = bands,
        rsrp = rsrp, rsrq = rsrq, sinr = sinr, rssi = rssi, level = level,
        ip = ip.v4, ipv6 = ip.v6, publicIp = publicIp, natType = natType,
        validated = validated, vpn = vpn,
    )
}

/** Údaje jedné registrované buňky. U 3G je v [rssi] RSCP, u 2G RSSI. */
private data class CellFields(
    val rat: String,
    val mccMnc: String?,
    val cellId: Long?,
    val pci: Int?,
    val tac: Int?,
    val arfcn: Int?,
    val bands: String?,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val rssi: Int?,
    val level: Int?,
)

@SuppressLint("MissingPermission")
class CellReader(context: Context, private val onChange: () -> Unit) {
    private val baseTm = context.getSystemService(TelephonyManager::class.java)
    private val sm = context.getSystemService(SubscriptionManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var displayOverride: Int? = null
    private var boundSubId: Int? = null
    private var unregister: (() -> Unit)? = null

    /** Proč se nepodařilo zaregistrovat posluchače (chybí oprávnění); null = v pořádku. */
    @Volatile
    var listenError: String? = null
        private set

    val mobileDataEnabled: Boolean get() = safe { tm().isDataEnabled } ?: false
    val simReady: Boolean get() = safe { tm().simState == TelephonyManager.SIM_STATE_READY } ?: false

    fun start() = rebind()

    @Synchronized
    fun stop() {
        unregister?.let { runCatching(it) }
        unregister = null
        boundSubId = null
        executor.shutdown()
    }

    /** SIM, přes kterou právě jdou data (u dual SIM se může měnit). */
    private fun dataSubId(): Int {
        val active = SubscriptionManager.getActiveDataSubscriptionId()
        return if (active != SubscriptionManager.INVALID_SUBSCRIPTION_ID) active
        else SubscriptionManager.getDefaultDataSubscriptionId()
    }

    private fun tm(subId: Int = dataSubId()): TelephonyManager =
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) baseTm.createForSubscriptionId(subId) else baseTm

    /** Posluchač visí na konkrétní SIM, takže se po přepnutí datové SIM musí přehlásit. */
    @Synchronized
    private fun rebind() {
        val subId = dataSubId()
        if (subId == boundSubId && unregister != null) return
        unregister?.let { runCatching(it) }
        unregister = null
        boundSubId = subId
        displayOverride = null
        val t = tm(subId)
        try {
            unregister = if (Build.VERSION.SDK_INT >= 31) registerModern(t) else registerLegacy(t)
            listenError = null
        } catch (e: RuntimeException) {
            listenError = "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun onDataSubChanged() {
        if (!executor.isShutdown) rebind()
        onChange()
    }

    @RequiresApi(31)
    private fun registerModern(t: TelephonyManager): () -> Unit {
        val cb = ModernCallback(
            onDisplay = { displayOverride = it },
            onDataSub = ::onDataSubChanged,
            onChange = onChange,
        )
        t.registerTelephonyCallback(executor, cb)
        return { t.unregisterTelephonyCallback(cb) }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private fun registerLegacy(t: TelephonyManager): () -> Unit {
        val listener = object : PhoneStateListener(executor) {
            override fun onServiceStateChanged(serviceState: ServiceState?) = onChange()
            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) = onChange()
            override fun onUserMobileDataStateChanged(enabled: Boolean) = onChange()
            override fun onActiveDataSubscriptionIdChanged(subId: Int) = onDataSubChanged()
            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                displayOverride = info.overrideNetworkType
                onChange()
            }
        }
        t.listen(
            listener,
            PhoneStateListener.LISTEN_SERVICE_STATE or
                PhoneStateListener.LISTEN_CELL_INFO or
                PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED or
                PhoneStateListener.LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE or
                PhoneStateListener.LISTEN_USER_MOBILE_DATA_STATE,
        )
        return { t.listen(listener, PhoneStateListener.LISTEN_NONE) }
    }

    /** Vynutí čerstvé info o buňkách datové SIM; allCellInfo jinak vrací i minutu staré údaje. */
    suspend fun freshCells(): List<CellInfo>? = withTimeoutOrNull(3_000) {
        suspendCancellableCoroutine { cont ->
            try {
                tm().requestCellInfoUpdate(
                    Executor { it.run() },
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            if (cont.isActive) cont.resume(cellInfo)
                        }

                        override fun onError(errorCode: Int, detail: Throwable?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            } catch (e: RuntimeException) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /**
     * @param cells čerstvé buňky z [freshCells]; bez nich se vezme, co má systém v cache
     */
    fun read(link: LinkProperties?, validated: Boolean, vpn: Boolean, cells: List<CellInfo>? = null): CellSnapshot {
        val subId = dataSubId()
        val t = tm(subId)
        val netType = safe { t.dataNetworkType } ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
        val override = displayOverride
        val networkOperator = safe { t.networkOperator }?.ifEmpty { null }
        val cell = pickCell(cells ?: safe { t.allCellInfo }.orEmpty(), networkOperator, networkTypeName(netType))

        // Dva zdroje signálu: SignalStrength (patří přímo téhle SIM, ale se zhasnutým displejem ho
        // systém neobnovuje) a buňky. Při ticku jsou buňky čerstvě vyžádané z modemu a mají přednost;
        // mimo tick jsou z cache, a tam je lepší SignalStrength.
        val signal = safe { t.signalStrength }
        val lte = signal?.getCellSignalStrengths(CellSignalStrengthLte::class.java)?.firstOrNull()
        val nr = signal?.getCellSignalStrengths(CellSignalStrengthNr::class.java)?.firstOrNull()
        val useNr = netType == TelephonyManager.NETWORK_TYPE_NR && nr != null
        val legacyDbm = signal?.getCellSignalStrengths(CellSignalStrengthWcdma::class.java)?.firstOrNull()?.dbm?.av()
            ?: signal?.getCellSignalStrengths(CellSignalStrengthGsm::class.java)?.firstOrNull()?.dbm?.av()
        val fresh = cells != null
        fun <T> pick(fromCell: T?, fromSignal: T?): T? = if (fresh) fromCell ?: fromSignal else fromSignal ?: fromCell

        return CellSnapshot(
            operatorName = safe { t.networkOperatorName }?.ifEmpty { null },
            networkMccMnc = networkOperator,
            simOperatorName = safe { t.simOperatorName }?.ifEmpty { null },
            simMccMnc = safe { t.simOperator }?.ifEmpty { null },
            roaming = safe { t.isNetworkRoaming },
            dataSubId = subId.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID },
            simSlot = safe { sm.getActiveSubscriptionInfo(subId)?.simSlotIndex },
            activeSimCount = safe { sm.activeSubscriptionInfoCount },
            networkType = networkTypeName(netType),
            generation = generationOf(netType, override),
            overrideType = overrideTypeName(override),
            cellId = cell?.cellId,
            pci = cell?.pci,
            tac = cell?.tac,
            arfcn = cell?.arfcn,
            bands = cell?.bands,
            rsrp = pick(cell?.rsrp, if (useNr) nr.ssRsrp.av() else lte?.rsrp?.av()),
            rsrq = pick(cell?.rsrq, if (useNr) nr.ssRsrq.av() else lte?.rsrq?.av()),
            sinr = pick(cell?.sinr, if (useNr) nr.ssSinr.av() else lte?.rssnr?.av()),
            rssi = pick(cell?.rssi, if (useNr) null else lte?.rssi?.av() ?: legacyDbm),
            level = pick(cell?.level, signal?.level),
            ip = ipInfo(link),
            validated = validated,
            vpn = vpn,
        )
    }

    /** U dual SIM vrací systém registrované buňky obou karet; vybereme tu od operátora datové SIM. */
    private fun pickCell(cells: List<CellInfo>, networkOperator: String?, rat: String?): CellFields? {
        val registered = cells.filter { it.isRegistered }.mapNotNull(::parseCell)
        return registered.firstOrNull { it.mccMnc != null && it.mccMnc == networkOperator && it.rat == rat }
            ?: registered.firstOrNull { it.mccMnc != null && it.mccMnc == networkOperator }
            ?: registered.firstOrNull()
    }

    private fun parseCell(info: CellInfo): CellFields? = when (info) {
        is CellInfoLte -> {
            val id = info.cellIdentity
            val s = info.cellSignalStrength
            CellFields(
                rat = "LTE", mccMnc = mccMnc(id.mccString, id.mncString),
                cellId = id.ci.av()?.toLong(), pci = id.pci.av(), tac = id.tac.av(), arfcn = id.earfcn.av(),
                bands = id.bands.joinToString(";").ifEmpty { null },
                rsrp = s.rsrp.av(), rsrq = s.rsrq.av(), sinr = s.rssnr.av(), rssi = s.rssi.av(), level = s.level,
            )
        }
        is CellInfoNr -> {
            val id = info.cellIdentity as CellIdentityNr
            val s = info.cellSignalStrength as CellSignalStrengthNr
            CellFields(
                rat = "NR", mccMnc = mccMnc(id.mccString, id.mncString),
                cellId = id.nci.takeIf { it != CellInfo.UNAVAILABLE_LONG }, pci = id.pci.av(), tac = id.tac.av(),
                arfcn = id.nrarfcn.av(), bands = id.bands.joinToString(";").ifEmpty { null },
                rsrp = s.ssRsrp.av(), rsrq = s.ssRsrq.av(), sinr = s.ssSinr.av(), rssi = null, level = s.level,
            )
        }
        is CellInfoWcdma -> {
            val id = info.cellIdentity
            val s = info.cellSignalStrength
            CellFields(
                rat = "UMTS", mccMnc = mccMnc(id.mccString, id.mncString),
                cellId = id.cid.av()?.toLong(), pci = id.psc.av(), tac = id.lac.av(), arfcn = id.uarfcn.av(),
                bands = null, rsrp = null, rsrq = null, sinr = null, rssi = s.dbm.av(), level = s.level,
            )
        }
        is CellInfoGsm -> {
            val id = info.cellIdentity
            val s = info.cellSignalStrength
            CellFields(
                rat = "GSM", mccMnc = mccMnc(id.mccString, id.mncString),
                cellId = id.cid.av()?.toLong(), pci = id.bsic.av(), tac = id.lac.av(), arfcn = id.arfcn.av(),
                bands = null, rsrp = null, rsrq = null, sinr = null, rssi = s.dbm.av(), level = s.level,
            )
        }
        is CellInfoTdscdma -> {
            val id = info.cellIdentity
            val s = info.cellSignalStrength
            CellFields(
                rat = "TD-SCDMA", mccMnc = mccMnc(id.mccString, id.mncString),
                cellId = id.cid.av()?.toLong(), pci = id.cpid.av(), tac = id.lac.av(), arfcn = id.uarfcn.av(),
                bands = null, rsrp = null, rsrq = null, sinr = null, rssi = s.dbm.av(), level = s.level,
            )
        }
        else -> null
    }

    private fun mccMnc(mcc: String?, mnc: String?) = if (mcc != null && mnc != null) mcc + mnc else null

    /** Telephony hlásí „nevím“ jako Integer.MAX_VALUE; do databáze patří NULL, ne nesmyslné číslo. */
    private fun Int.av(): Int? = takeIf { it != CellInfo.UNAVAILABLE }

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (e: RuntimeException) {
        null
    }
}

/** Ve vlastní třídě, aby se rozhraní z API 31 nenačítala na Androidu 11. */
@RequiresApi(31)
private class ModernCallback(
    private val onDisplay: (Int) -> Unit,
    private val onDataSub: () -> Unit,
    private val onChange: () -> Unit,
) : TelephonyCallback(),
    TelephonyCallback.ServiceStateListener,
    TelephonyCallback.CellInfoListener,
    TelephonyCallback.DisplayInfoListener,
    TelephonyCallback.ActiveDataSubscriptionIdListener,
    TelephonyCallback.UserMobileDataStateListener {

    override fun onServiceStateChanged(serviceState: ServiceState) = onChange()
    override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) = onChange()
    override fun onUserMobileDataStateChanged(enabled: Boolean) = onChange()
    override fun onActiveDataSubscriptionIdChanged(subId: Int) = onDataSub()
    override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
        onDisplay(telephonyDisplayInfo.overrideNetworkType)
        onChange()
    }
}
