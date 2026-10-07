package cz.kralicekgamer.netnote.collect

import android.net.Network
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import android.os.SystemClock
import cz.kralicekgamer.netnote.Config
import cz.kralicekgamer.netnote.logic.parsePublicIp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import kotlin.random.Random

data class PingResult(val medianMs: Double?, val minMs: Double?, val attempts: Int, val failures: Int, val error: String?)

data class IpResult(val ip: String?, val error: String?)

data class Transfer(val bps: Long?, val bytes: Long, val ms: Long, val error: String?)

/** Všechna měření jdou přes konkrétní [Network], aby se zaručeně měřila ta síť, kterou zapisujeme. */
object Probes {
    private fun describe(e: Exception) = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")

    /** ICMP appka bez roota neodešle, takže „ping“ = doba navázání TCP spojení. */
    suspend fun ping(network: Network, host: String, port: Int): PingResult = withContext(Dispatchers.IO) {
        val address = try {
            // Jméno se překládá přes měřenou síť; u IP adresy se na DNS nesahá. Do času se nepočítá.
            InetSocketAddress(network.getByName(host), port)
        } catch (e: IOException) {
            return@withContext PingResult(null, null, 0, 0, describe(e))
        }
        val times = mutableListOf<Double>()
        var error: String? = null
        repeat(Config.PING_ATTEMPTS) { i ->
            if (i > 0) delay(200)
            try {
                network.socketFactory.createSocket().use { socket ->
                    val t0 = System.nanoTime()
                    socket.connect(address, Config.PING_TIMEOUT_MS)
                    times += (System.nanoTime() - t0) / 1e6
                }
            } catch (e: IOException) {
                error = describe(e)
            }
        }
        times.sort()
        PingResult(
            medianMs = times.getOrNull((times.size - 1) / 2),
            minMs = times.firstOrNull(),
            attempts = Config.PING_ATTEMPTS,
            failures = Config.PING_ATTEMPTS - times.size,
            error = error,
        )
    }

    suspend fun publicIp(network: Network, url: String): IpResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = open(network, url)
            val code = conn.responseCode
            if (code != 200) return@withContext IpResult(null, "HTTP $code")
            val buf = ByteArray(4096)
            var len = 0
            conn.inputStream.use { input ->
                while (len < buf.size) {
                    val n = input.read(buf, len, buf.size - len)
                    if (n < 0) break
                    len += n
                }
            }
            val ip = parsePublicIp(String(buf, 0, len, Charsets.UTF_8))
            if (ip != null) IpResult(ip, null) else IpResult(null, "odpověď neobsahuje IP adresu")
        } catch (e: IOException) {
            IpResult(null, describe(e))
        } finally {
            conn?.disconnect()
        }
    }

    /** Čas se měří od prvního bajtu těla, navázání spojení se do rychlosti nepočítá. */
    suspend fun download(network: Network, urlTemplate: String, maxBytes: Long, maxMs: Long): Transfer =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            var bytes = 0L
            var t0 = 0L
            try {
                conn = open(network, urlTemplate.replace("{bytes}", maxBytes.toString()))
                val code = conn.responseCode
                if (code != 200) return@withContext Transfer(null, 0, 0, "HTTP $code")
                val input = conn.inputStream
                val buf = ByteArray(64 * 1024)
                t0 = System.nanoTime()
                val deadline = t0 + maxMs * 1_000_000
                while (bytes < maxBytes && System.nanoTime() < deadline) {
                    val n = input.read(buf)
                    if (n < 0) break
                    bytes += n
                }
                transfer(bytes, t0, null)
            } catch (e: IOException) {
                transfer(bytes, t0, describe(e))
            } finally {
                conn?.disconnect()
            }
        }

    /** Čas běží až do odpovědi serveru, tedy dokud data opravdu nedorazila. */
    suspend fun upload(network: Network, url: String, maxBytes: Long, maxMs: Long): Transfer =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            var bytes = 0L
            var t0 = 0L
            try {
                conn = open(network, url).apply {
                    requestMethod = "POST"
                    doOutput = true
                    // Chunked, aby šlo odesílání ukončit po vypršení času.
                    setChunkedStreamingMode(16 * 1024)
                    setRequestProperty("Content-Type", "application/octet-stream")
                }
                conn.connect()
                val buf = Random.nextBytes(16 * 1024)
                t0 = System.nanoTime()
                val deadline = t0 + maxMs * 1_000_000
                conn.outputStream.use { out ->
                    while (bytes < maxBytes && System.nanoTime() < deadline) {
                        val n = minOf(buf.size.toLong(), maxBytes - bytes).toInt()
                        out.write(buf, 0, n)
                        bytes += n
                    }
                }
                val code = conn.responseCode
                transfer(bytes, t0, if (code in 200..299) null else "HTTP $code")
            } catch (e: IOException) {
                transfer(bytes, t0, describe(e))
            } finally {
                conn?.disconnect()
            }
        }

    private fun transfer(bytes: Long, t0: Long, error: String?): Transfer {
        val nanos = if (t0 == 0L) 0L else System.nanoTime() - t0
        val bps = if (error == null && bytes > 0 && nanos > 0) (bytes * 8.0 * 1e9 / nanos).toLong() else null
        return Transfer(bps, bytes, nanos / 1_000_000, error)
    }

    private fun open(network: Network, url: String): HttpURLConnection =
        (network.openConnection(URL(url)) as HttpURLConnection).apply {
            connectTimeout = Config.HTTP_TIMEOUT_MS
            readTimeout = Config.HTTP_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "netnote")
        }
}

/**
 * Objem dat jako rozdíl počítadel TrafficStats mezi dvěma ticky.
 *
 * Na Androidu 12+ se Wi‑Fi i mobilní data čtou přímo z jejich síťového rozhraní. Souhrnné
 * getMobileRxBytes() se k tomu nehodí: sčítá jen právě aktivní mobilní rozhraní, takže při
 * přepnutí z Wi‑Fi na data vyskočí z nuly na všechno od startu telefonu (na realme 8 to
 * udělalo falešných 161 MB za jednu minutu). Na Androidu 11 zbývá jen souhrnné počítadlo;
 * skok z nuly se tam zahodí a Wi‑Fi se počítá jako „celkem − mobilní“.
 */
class TrafficCounter {
    data class Delta(
        val intervalMs: Long,
        val wifiRx: Long,
        val wifiTx: Long,
        val mobileRx: Long,
        val mobileTx: Long,
        val ownRx: Long,
        val ownTx: Long,
        val wifiSource: String,
        val mobileSource: String,
    )

    private class Iface(val name: String?, val rx: Long, val tx: Long)

    private class Counters(
        val elapsed: Long,
        val totalRx: Long, val totalTx: Long,
        val mobileRx: Long, val mobileTx: Long,
        val wifi: Iface, val mobile: Iface,
        val ownRx: Long, val ownTx: Long,
    )

    private var prev: Counters? = null
    private var wifiIface: String? = null
    private var mobileIface: String? = null

    /**
     * Jména rozhraní se předávají jen ve stavu, kdy je známe; pamatují se dál, aby se
     * dopočítal i provoz z intervalu, ve kterém telefon přepnul na jinou síť.
     */
    fun sample(currentWifiIface: String?, currentMobileIface: String?): Delta? {
        if (currentWifiIface != null) wifiIface = currentWifiIface
        if (currentMobileIface != null) mobileIface = currentMobileIface
        val uid = Process.myUid()
        val cur = Counters(
            elapsed = SystemClock.elapsedRealtime(),
            totalRx = TrafficStats.getTotalRxBytes(), totalTx = TrafficStats.getTotalTxBytes(),
            mobileRx = TrafficStats.getMobileRxBytes(), mobileTx = TrafficStats.getMobileTxBytes(),
            wifi = readIface(wifiIface), mobile = readIface(mobileIface),
            ownRx = TrafficStats.getUidRxBytes(uid), ownTx = TrafficStats.getUidTxBytes(uid),
        )
        val p = prev
        prev = cur
        if (p == null) return null

        val mobileByIface = comparable(cur.mobile, p.mobile)
        // Souhrnné počítadlo: z nuly „naskočí“ ve chvíli, kdy se mobilní rozhraní objeví.
        val mobileTotalValid = p.mobileRx > 0 || p.mobileTx > 0
        // Rozhraní známe teprve od tohoto vzorku: interval vynecháme, další už bude přesný.
        val mobileNewIface = !mobileByIface && cur.mobile.name != null
        val mobileRx = when {
            mobileByIface -> diff(cur.mobile.rx, p.mobile.rx)
            mobileNewIface || !mobileTotalValid -> 0
            else -> diff(cur.mobileRx, p.mobileRx)
        }
        val mobileTx = when {
            mobileByIface -> diff(cur.mobile.tx, p.mobile.tx)
            mobileNewIface || !mobileTotalValid -> 0
            else -> diff(cur.mobileTx, p.mobileTx)
        }
        val wifiByIface = comparable(cur.wifi, p.wifi)
        return Delta(
            intervalMs = cur.elapsed - p.elapsed,
            wifiRx = if (wifiByIface) diff(cur.wifi.rx, p.wifi.rx)
            else (diff(cur.totalRx, p.totalRx) - mobileRx).coerceAtLeast(0),
            wifiTx = if (wifiByIface) diff(cur.wifi.tx, p.wifi.tx)
            else (diff(cur.totalTx, p.totalTx) - mobileTx).coerceAtLeast(0),
            mobileRx = mobileRx,
            mobileTx = mobileTx,
            ownRx = diff(cur.ownRx, p.ownRx),
            ownTx = diff(cur.ownTx, p.ownTx),
            wifiSource = if (wifiByIface) "iface:${cur.wifi.name}" else "total-mobile",
            mobileSource = when {
                mobileByIface -> "iface:${cur.mobile.name}"
                mobileNewIface -> "rebaseline"
                else -> "mobile-total"
            },
        )
    }

    private fun readIface(name: String?): Iface =
        if (name != null && Build.VERSION.SDK_INT >= 31) {
            Iface(name, TrafficStats.getRxBytes(name), TrafficStats.getTxBytes(name))
        } else {
            Iface(null, -1, -1)
        }

    /** Stejné rozhraní v obou vzorcích a obě počítadla platná (-1 = zařízení je nepodporuje). */
    private fun comparable(a: Iface, b: Iface) =
        a.name != null && a.name == b.name && a.rx >= 0 && a.tx >= 0 && b.rx >= 0 && b.tx >= 0

    /** Záporný rozdíl = počítadlo se vynulovalo. */
    private fun diff(a: Long, b: Long) = if (a < 0 || b < 0) 0 else (a - b).coerceAtLeast(0)
}
