package cz.netdenik.logic

import java.time.Instant
import java.time.ZoneId

/** Všechno, podle čeho se při ticku rozhoduje o měření rychlosti. Časy v ms, objemy v bajtech. */
data class SpeedPlan(
    val manual: Boolean,
    /** Android ověřil, že síť vede do internetu. */
    val validated: Boolean,
    val mobile: Boolean,
    /** Wi‑Fi označená jako měřená, typicky hotspot z jiného telefonu. Testuje se malým testem jako data. */
    val meteredWifi: Boolean,
    val roaming: Boolean,
    /** Od posledního testu stejného druhu (velký na Wi‑Fi, malý na měřené síti). */
    val sinceLastMs: Long,
    val cellChanged: Boolean,
    /** 0 = automatické testy vypnuté. */
    val wifiIntervalMs: Long,
    val mobileIntervalMs: Long,
    val cellChangeMinGapMs: Long,
    val skipMeteredWifi: Boolean,
    /** Co dnes testy na mobilních datech přenesly, včetně ručních. */
    val usedTodayBytes: Long,
    /** 0 = bez limitu. */
    val dailyLimitBytes: Long,
    val testBytes: Long,
    val slackMs: Long,
)

/**
 * Důvod, proč teď změřit rychlost ("MANUAL", "PERIODIC", "CELL_CHANGE"), nebo null = neměřit.
 * Ruční měření projde vždy; denní limit hlídá jen automatické testy na mobilních datech.
 */
fun speedTestReason(p: SpeedPlan): String? {
    if (p.manual) return "MANUAL"
    if (!p.validated) return null
    if (!p.mobile && !p.meteredWifi) {
        return if (intervalElapsed(p.sinceLastMs, p.wifiIntervalMs, p.slackMs)) "PERIODIC" else null
    }
    if (!p.mobile && p.skipMeteredWifi) return null
    // V roamingu se rychlost neměří, mohlo by to stát peníze.
    if (p.mobile && p.roaming) return null
    if (p.mobileIntervalMs <= 0) return null
    if (p.mobile && !limitAllows(p.usedTodayBytes, p.testBytes, p.dailyLimitBytes)) return null
    return when {
        intervalElapsed(p.sinceLastMs, p.mobileIntervalMs, p.slackMs) -> "PERIODIC"
        p.cellChanged && (p.sinceLastMs < 0 || p.sinceLastMs >= p.cellChangeMinGapMs) -> "CELL_CHANGE"
        else -> null
    }
}

/** Vejde se další test do denního limitu? Limit 0 = bez limitu. */
fun limitAllows(usedBytes: Long, testBytes: Long, limitBytes: Long): Boolean =
    limitBytes <= 0 || usedBytes + testBytes <= limitBytes

/** Kolik nejvýš za den přenesou pravidelné testy při daném intervalu; 0 při vypnutých testech. */
fun dailyTestBytes(intervalMs: Long, bytesPerTest: Long): Long =
    if (intervalMs <= 0) 0 else 86_400_000L / intervalMs * bytesPerTest

/**
 * Hranice uchovávání: půlnoc před [days] dny. Maže se po celých dnech, aby z nejstaršího
 * dne nezůstal jen kus. null = nemazat (0 dní znamená neomezeně).
 */
fun retentionCutoff(nowMs: Long, days: Int, zone: ZoneId): Long? =
    if (days <= 0) null
    else Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().minusDays(days.toLong())
        .atStartOfDay(zone).toInstant().toEpochMilli()

data class HostPort(val host: String, val port: Int) {
    /** IPv6 adresa se od portu odděluje hranatými závorkami. */
    val label: String get() = if (':' in host) "[$host]:$port" else "$host:$port"
}

private val HOST_NAME = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")
private val IPV6 = Regex("^[0-9A-Fa-f:.]+$")

/** Cíl pingu z textu: `host`, `host:port`, `[IPv6]:port` nebo holá IPv6. null = nedá se použít. */
fun parseHostPort(text: String, defaultPort: Int = 443): HostPort? {
    val t = text.trim()
    val host: String
    val portText: String?
    when {
        t.startsWith("[") -> {
            val end = t.indexOf(']')
            if (end < 0) return null
            host = t.substring(1, end)
            val rest = t.substring(end + 1)
            portText = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1)
                else -> return null
            }
            if (!isIpv6(host)) return null
        }
        t.count { it == ':' } >= 2 -> {
            host = t
            portText = null
            if (!isIpv6(host)) return null
        }
        else -> {
            host = t.substringBefore(':')
            portText = if (':' in t) t.substringAfter(':') else null
            if (host.length > 253 || ".." in host || !HOST_NAME.matches(host)) return null
        }
    }
    val port = when {
        portText == null -> defaultPort
        portText.length in 1..5 && portText.all { it in '0'..'9' } -> portText.toInt()
        else -> return null
    }
    return if (port in 1..65535) HostPort(host, port) else null
}

private fun isIpv6(host: String) = host.count { it == ':' } >= 2 && IPV6.matches(host)
