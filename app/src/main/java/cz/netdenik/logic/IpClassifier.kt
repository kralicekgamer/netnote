package cz.netdenik.logic

enum class NatType { PUBLIC, NAT, CGNAT, XLAT464, UNKNOWN }

/** IPv4 jako 32bitové číslo, nebo null, když to IPv4 adresa není. Schválně bez InetAddress (žádné DNS). */
fun parseIpv4(s: String?): Long? {
    val parts = s?.trim()?.split('.') ?: return null
    if (parts.size != 4) return null
    var out = 0L
    for (p in parts) {
        if (p.isEmpty() || p.length > 3 || !p.all { it.isDigit() }) return null
        val n = p.toInt()
        if (n > 255) return null
        out = (out shl 8) or n.toLong()
    }
    return out
}

private fun inRange(ip: Long, base: String, prefix: Int): Boolean {
    val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
    return (ip and mask) == (parseIpv4(base)!! and mask)
}

fun isPrivateV4(ip: Long): Boolean =
    inRange(ip, "10.0.0.0", 8) || inRange(ip, "172.16.0.0", 12) || inRange(ip, "192.168.0.0", 16)

/**
 * Porovná lokální IPv4 s tím, jak telefon vidí internet.
 * CGNAT = NAT u operátora. Pozná se podle adresy ze 100.64.0.0/10, která je pro něj vyhrazená,
 * a na mobilní síti i podle privátní adresy (T‑Mobile CZ přiděluje střídavě 100.x a 10.x).
 * Na Wi‑Fi je privátní adresa obyčejný NAT routeru. XLAT464 = síť je jen IPv6, IPv4 se překládá.
 *
 * @param carrier adresu přidělil mobilní operátor
 */
fun classifyNat(localV4: String?, publicIp: String?, carrier: Boolean = false): NatType {
    val local = parseIpv4(localV4) ?: return NatType.UNKNOWN
    if (inRange(local, "192.0.0.0", 29)) return NatType.XLAT464
    if (inRange(local, "100.64.0.0", 10)) return NatType.CGNAT
    val pub = parseIpv4(publicIp)
    if (pub != null && pub == local) return NatType.PUBLIC
    val nat = if (carrier) NatType.CGNAT else NatType.NAT
    if (isPrivateV4(local)) return nat
    if (pub != null) return nat
    return NatType.UNKNOWN
}

fun looksLikeIp(s: String): Boolean =
    parseIpv4(s) != null ||
        (s.length in 2..45 && s.contains(':') && s.all { it in "0123456789abcdefABCDEF:." })

/** Rozumí cdn-cgi/trace (řádek ip=…), holé adrese i JSONu s polem "ip". */
fun parsePublicIp(body: String): String? {
    body.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("ip=") }?.let { line ->
        return line.substring(3).trim().takeIf(::looksLikeIp)
    }
    val trimmed = body.trim()
    if (looksLikeIp(trimmed)) return trimmed
    return Regex("\"ip\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)?.takeIf(::looksLikeIp)
}

/** IP konfigurace jedné sítě, jak ji hlásí LinkProperties. */
data class IpInfo(
    val v4: String? = null,
    val prefixLen: Int? = null,
    val v6: String? = null,
    val gateway: String? = null,
    val dns: List<String> = emptyList(),
    val dhcpServer: String? = null,
    val iface: String? = null,
)

/**
 * Popis změny IPv4 / brány / DNS / DHCP serveru, nebo null, když se nic nezměnilo.
 * Přechod z „ještě nevím“ (null) na hodnotu změna není. IPv6 se schválně neporovnává,
 * dočasné adresy se mění samy.
 */
fun ipConfigDiff(prev: IpInfo, cur: IpInfo): String? {
    val parts = mutableListOf<String>()
    fun cmp(label: String, a: String?, b: String?) {
        if (a != null && b != null && a != b) parts += "$label $a → $b"
    }
    cmp("IP", prev.v4, cur.v4)
    cmp("brána", prev.gateway, cur.gateway)
    cmp("DHCP", prev.dhcpServer, cur.dhcpServer)
    if (prev.dns.isNotEmpty() && cur.dns.isNotEmpty() && prev.dns.sorted() != cur.dns.sorted()) {
        parts += "DNS ${prev.dns.joinToString(";")} → ${cur.dns.joinToString(";")}"
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}
