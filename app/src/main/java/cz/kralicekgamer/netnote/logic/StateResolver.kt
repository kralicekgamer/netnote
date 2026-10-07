package cz.kralicekgamer.netnote.logic

import cz.kralicekgamer.netnote.data.ConnState

data class RadioInputs(
    /** Existuje výchozí síť (to, přes co telefon právě posílá data). */
    val hasNetwork: Boolean,
    val wifiTransport: Boolean,
    val cellTransport: Boolean,
    val airplaneMode: Boolean,
    val wifiEnabled: Boolean,
    val mobileDataEnabled: Boolean,
    val simReady: Boolean,
)

/**
 * Stavy jsou navzájem výlučné. Bez sítě rozhoduje, jestli telefon online být chce
 * (HLEDÁNÍ), nebo to uživatel vypnul (OFFLINE). Wi‑Fi jde zapnout i v režimu letadlo.
 */
fun resolveState(i: RadioInputs): ConnState = when {
    i.hasNetwork && i.wifiTransport -> ConnState.WIFI
    i.hasNetwork && i.cellTransport -> ConnState.MOBILE
    i.hasNetwork -> ConnState.OTHER
    i.wifiEnabled -> ConnState.SEARCHING
    !i.airplaneMode && i.mobileDataEnabled && i.simReady -> ConnState.SEARCHING
    else -> ConnState.OFFLINE
}
