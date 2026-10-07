package cz.netdenik.logic

data class ChannelInfo(val channel: Int, val band: String)

fun channelOf(freqMhz: Int): ChannelInfo? = when {
    freqMhz == 2484 -> ChannelInfo(14, "2.4 GHz")
    freqMhz in 2412..2472 -> ChannelInfo((freqMhz - 2407) / 5, "2.4 GHz")
    freqMhz in 5160..5885 -> ChannelInfo((freqMhz - 5000) / 5, "5 GHz")
    freqMhz == 5935 -> ChannelInfo(2, "6 GHz")
    freqMhz in 5955..7115 -> ChannelInfo((freqMhz - 5950) / 5, "6 GHz")
    freqMhz in 58320..70200 -> ChannelInfo((freqMhz - 56160) / 2160, "60 GHz")
    else -> null
}

/** Hodnoty ScanResult.WIFI_STANDARD_*. */
fun wifiStandardName(standard: Int): String? = when (standard) {
    1 -> "≤3 (802.11a/b/g)"
    4 -> "4"
    5 -> "5"
    6 -> "6"
    7 -> "802.11ad"
    8 -> "7"
    else -> null
}

/** Hodnoty WifiInfo.SECURITY_TYPE_* (Android 12+). */
fun wifiSecurityName(type: Int): String? = when (type) {
    0 -> "Open"
    1 -> "WEP"
    2 -> "WPA/WPA2-Personal"
    3 -> "WPA/WPA2-Enterprise"
    4 -> "WPA3-Personal"
    5 -> "WPA3-Enterprise 192-bit"
    6 -> "OWE"
    7 -> "WAPI-PSK"
    8 -> "WAPI-CERT"
    9 -> "WPA3-Enterprise"
    10 -> "OSEN"
    11 -> "Passpoint R1/R2"
    12 -> "Passpoint R3"
    13 -> "DPP"
    else -> null
}

/** Záloha pro Android 11: zabezpečení z řetězce ScanResult.capabilities, např. "[WPA2-PSK-CCMP][ESS]". */
fun securityFromScanCapabilities(c: String): String = when {
    c.contains("SAE") && c.contains("PSK") -> "WPA2/WPA3-Personal"
    c.contains("SAE") -> "WPA3-Personal"
    c.contains("SUITE_B_192") -> "WPA3-Enterprise 192-bit"
    c.contains("EAP") -> "WPA/WPA2-Enterprise"
    c.contains("PSK") -> "WPA/WPA2-Personal"
    c.contains("OWE") -> "OWE"
    c.contains("WEP") -> "WEP"
    else -> "Open"
}
