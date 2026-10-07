package cz.netdenik.collect

import android.annotation.SuppressLint
import android.content.Context
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import cz.netdenik.data.WifiSample
import cz.netdenik.logic.IpInfo
import cz.netdenik.logic.channelOf
import cz.netdenik.logic.securityFromScanCapabilities
import cz.netdenik.logic.wifiSecurityName
import cz.netdenik.logic.wifiStandardName

data class WifiSnapshot(
    val ssid: String?,
    val bssid: String?,
    val frequencyMhz: Int?,
    val rssiDbm: Int?,
    val txLinkMbps: Int?,
    val rxLinkMbps: Int?,
    val wifiStandard: String?,
    val security: String?,
    val ip: IpInfo,
    val validated: Boolean,
    val metered: Boolean,
    val vpn: Boolean,
) {
    fun toSample(ts: Long, publicIp: String?): WifiSample {
        val ch = frequencyMhz?.let(::channelOf)
        return WifiSample(
            ts = ts, ssid = ssid, bssid = bssid, frequencyMhz = frequencyMhz,
            channel = ch?.channel, band = ch?.band, wifiStandard = wifiStandard, security = security,
            rssiDbm = rssiDbm, txLinkMbps = txLinkMbps, rxLinkMbps = rxLinkMbps,
            ip = ip.v4, prefixLen = ip.prefixLen, ipv6 = ip.v6, gateway = ip.gateway,
            dns = ip.dns.joinToString(";").ifEmpty { null }, dhcpServer = ip.dhcpServer,
            publicIp = publicIp, validated = validated, metered = metered, vpn = vpn,
        )
    }
}

class WifiReader(context: Context) {
    private val wm = context.applicationContext.getSystemService(WifiManager::class.java)

    val wifiEnabled: Boolean get() = wm.isWifiEnabled

    /**
     * @param caps kapability Wi‑Fi sítě (pod VPN té podkladové)
     * @param validated jestli má výchozí síť ověřený přístup na internet
     */
    @SuppressLint("MissingPermission")
    fun read(caps: NetworkCapabilities, link: LinkProperties?, validated: Boolean, vpn: Boolean): WifiSnapshot {
        // WifiInfo v kapabilitách je snímek z poslední změny sítě: signál a link speed v něm stojí
        // (na realme 8 bylo 40 vzorků za dvě hodiny do puntíku stejných). Živé hodnoty má jen
        // WifiManager; snímek z kapabilit zůstává jako záloha pro SSID/BSSID a typ zabezpečení.
        val fromCaps = caps.transportInfo as? WifiInfo
        @Suppress("DEPRECATION")
        val live: WifiInfo? = runCatching { wm.connectionInfo }.getOrNull()
            ?.takeIf { it.supplicantState == SupplicantState.COMPLETED }
        val info: WifiInfo? = live ?: fromCaps

        val bssid = validBssid(live?.bssid) ?: validBssid(fromCaps?.bssid)
        return WifiSnapshot(
            ssid = cleanSsid(live?.ssid) ?: cleanSsid(fromCaps?.ssid),
            bssid = bssid,
            frequencyMhz = info?.frequency?.takeIf { it > 0 },
            rssiDbm = info?.rssi?.takeIf { it in -126..-1 },
            txLinkMbps = info?.txLinkSpeedMbps?.takeIf { it > 0 } ?: info?.linkSpeed?.takeIf { it > 0 },
            rxLinkMbps = info?.rxLinkSpeedMbps?.takeIf { it > 0 },
            wifiStandard = info?.let { wifiStandardName(it.wifiStandard) },
            security = security(live, null) ?: security(fromCaps, bssid),
            ip = ipInfo(link),
            validated = validated,
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            vpn = vpn,
        )
    }

    @SuppressLint("MissingPermission")
    private fun security(info: WifiInfo?, bssid: String?): String? {
        if (info == null) return null
        if (Build.VERSION.SDK_INT >= 31) {
            wifiSecurityName(info.currentSecurityType)?.let { return it }
        }
        if (bssid == null) return null
        return runCatching {
            wm.scanResults.firstOrNull { it.BSSID.equals(bssid, ignoreCase = true) }
                ?.capabilities?.let(::securityFromScanCapabilities)
        }.getOrNull()
    }

    private fun validBssid(raw: String?): String? = raw?.takeIf { it.isNotEmpty() && it != REDACTED_BSSID }

    private fun cleanSsid(raw: String?): String? {
        if (raw.isNullOrEmpty() || raw == WifiManager.UNKNOWN_SSID) return null
        return if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) raw.substring(1, raw.length - 1) else raw
    }

    private companion object {
        /** Tohle Android vrací místo BSSID, když chybí oprávnění k poloze. */
        const val REDACTED_BSSID = "02:00:00:00:00:00"
    }
}
