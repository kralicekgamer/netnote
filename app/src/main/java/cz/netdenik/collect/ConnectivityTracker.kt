package cz.netdenik.collect

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import cz.netdenik.logic.IpInfo
import java.net.Inet4Address
import java.net.Inet6Address

class NetSnapshot(val network: Network, val caps: NetworkCapabilities, val link: LinkProperties?)

/** Sleduje výchozí síť, tedy to, přes co telefon právě posílá data. */
class ConnectivityTracker(context: Context, private val onChange: () -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Volatile private var network: Network? = null
    @Volatile private var caps: NetworkCapabilities? = null
    @Volatile private var link: LinkProperties? = null

    /** Kdy výchozí síť zmizela; null, dokud nějaká je. */
    @Volatile
    var lostAtMs: Long? = null
        private set

    private val callback = makeCallback()

    fun start() {
        cm.activeNetwork?.let(::available)
        cm.registerDefaultNetworkCallback(callback)
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    fun snapshot(): NetSnapshot? {
        val n = network
        val c = caps
        return if (n != null && c != null) NetSnapshot(n, c, link) else null
    }

    /** Pod VPN: skutečná Wi‑Fi / mobilní síť, ze které chceme IP, bránu a DNS. */
    @Suppress("DEPRECATION")
    fun underlying(transport: Int): Pair<NetworkCapabilities, LinkProperties?>? {
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(transport) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return c to cm.getLinkProperties(n)
            }
        }
        return null
    }

    private fun available(n: Network) {
        if (n != network) {
            network = n
            // Než dorazí první callbacky s plnými údaji, vezmeme aspoň to, co systém vydá hned.
            caps = cm.getNetworkCapabilities(n)
            link = cm.getLinkProperties(n)
        }
        lostAtMs = null
    }

    private fun capsChanged(n: Network, c: NetworkCapabilities) {
        if (n != network) available(n)
        caps = c
        onChange()
    }

    private fun linkChanged(n: Network, l: LinkProperties) {
        if (n != network) available(n)
        link = l
        onChange()
    }

    private fun lost(n: Network) {
        if (n != network) return
        network = null
        caps = null
        link = null
        lostAtMs = System.currentTimeMillis()
        onChange()
    }

    // Od Androidu 12 je SSID/BSSID ve WifiInfo jen s příznakem FLAG_INCLUDE_LOCATION_INFO.
    private fun makeCallback(): ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(n: Network) = available(n)
                override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = capsChanged(n, c)
                override fun onLinkPropertiesChanged(n: Network, l: LinkProperties) = linkChanged(n, l)
                override fun onLost(n: Network) = lost(n)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) = available(n)
                override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = capsChanged(n, c)
                override fun onLinkPropertiesChanged(n: Network, l: LinkProperties) = linkChanged(n, l)
                override fun onLost(n: Network) = lost(n)
            }
        }
}

fun ipInfo(link: LinkProperties?): IpInfo {
    if (link == null) return IpInfo()
    val v4 = link.linkAddresses.firstOrNull { it.address is Inet4Address }
    val v6 = link.linkAddresses.firstOrNull { it.address is Inet6Address && !it.address.isLinkLocalAddress }
    val defaults = link.routes.filter { it.isDefaultRoute }
    val gateway = (defaults.firstOrNull { it.gateway is Inet4Address } ?: defaults.firstOrNull())
        ?.gateway?.takeIf { !it.isAnyLocalAddress }
    return IpInfo(
        v4 = v4?.address?.hostAddress,
        prefixLen = v4?.prefixLength,
        v6 = v6?.address?.hostAddress,
        gateway = gateway?.hostAddress,
        dns = link.dnsServers.mapNotNull { it.hostAddress },
        dhcpServer = link.dhcpServerAddress?.hostAddress,
        iface = link.interfaceName,
    )
}
