package cz.kralicekgamer.netnote.logic

/** Hodnoty TelephonyManager.NETWORK_TYPE_*. */
fun networkTypeName(type: Int): String? = when (type) {
    1 -> "GPRS"
    2 -> "EDGE"
    3 -> "UMTS"
    4 -> "CDMA"
    5 -> "EVDO_0"
    6 -> "EVDO_A"
    7 -> "1xRTT"
    8 -> "HSDPA"
    9 -> "HSUPA"
    10 -> "HSPA"
    11 -> "iDEN"
    12 -> "EVDO_B"
    13 -> "LTE"
    14 -> "eHRPD"
    15 -> "HSPA+"
    16 -> "GSM"
    17 -> "TD-SCDMA"
    18 -> "IWLAN"
    19 -> "LTE_CA"
    20 -> "NR"
    else -> null
}

/** Hodnoty TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_*. */
fun overrideTypeName(override: Int?): String? = when (override) {
    1 -> "LTE_CA"
    2 -> "LTE_ADVANCED_PRO"
    3 -> "NR_NSA"
    4 -> "NR_NSA_MMWAVE"
    5 -> "NR_ADVANCED"
    else -> null
}

/** 5G NSA se hlásí jako LTE s override typem NR, proto se generace určuje z obojího. */
fun generationOf(type: Int, override: Int?): String? = when (type) {
    1, 2, 4, 7, 11, 16 -> "2G"
    3, 5, 6, 8, 9, 10, 12, 14, 15, 17 -> "3G"
    13, 19 -> if (override in 3..5) "5G NSA" else "4G"
    20 -> "5G"
    18 -> "IWLAN"
    else -> null
}
