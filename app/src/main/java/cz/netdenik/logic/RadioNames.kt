package cz.netdenik.logic

import cz.netdenik.data.CellSample

/** „Wi‑Fi 5 · 802.11ac“ z hodnoty sloupce wifiStandard. */
fun wifiStandardLabel(standard: String?): String? = when (standard) {
    null -> null
    "4" -> "Wi‑Fi 4 · 802.11n"
    "5" -> "Wi‑Fi 5 · 802.11ac"
    "6" -> "Wi‑Fi 6 · 802.11ax"
    "7" -> "Wi‑Fi 7 · 802.11be"
    else -> standard
}

/** Počet čárek signálu Wi‑Fi (1–4). */
fun wifiSignalBars(rssiDbm: Int): Int = when {
    rssiDbm >= -50 -> 4
    rssiDbm >= -60 -> 3
    rssiDbm >= -70 -> 2
    else -> 1
}

/** Obvyklé označení pásma v MHz pro pásma používaná v Evropě; jinak null. */
fun bandMhz(band: Int): Int? = when (band) {
    1 -> 2100
    3 -> 1800
    7 -> 2600
    8 -> 900
    20 -> 800
    28 -> 700
    32 -> 1500
    38 -> 2600
    40 -> 2300
    77 -> 3700
    78 -> 3500
    else -> null
}

/** „B3“ pro LTE, „n78“ pro 5G; z prvního pásma ve sloupci bands. */
fun bandShort(cell: CellSample): String? {
    val band = cell.bands?.split(';')?.firstOrNull()?.toIntOrNull() ?: return null
    return (if (cell.networkType == "NR") "n" else "B") + band
}

/** „B3 · 1800 MHz · EARFCN 1579“. */
fun bandLong(cell: CellSample): String? {
    val short = bandShort(cell)
    val mhz = cell.bands?.split(';')?.firstOrNull()?.toIntOrNull()?.let(::bandMhz)
    val arfcn = cell.arfcn?.let {
        when (cell.networkType) {
            "NR" -> "NR‑ARFCN $it"
            "LTE", "LTE_CA" -> "EARFCN $it"
            else -> "ARFCN $it"
        }
    }
    return listOfNotNull(short, mhz?.let { "$it MHz" }, arfcn).joinToString(" · ").ifEmpty { null }
}

/** Technologie tak, jak ji čeká uživatel: „LTE“, „5G NSA“, „5G“, „HSPA+“. */
fun techLabel(cell: CellSample): String? = when {
    cell.generation == "5G NSA" -> "5G NSA"
    cell.networkType == "NR" -> "5G"
    else -> cell.networkType ?: cell.generation
}

/** „LTE (4G)“ pro detail buňky. */
fun techLong(cell: CellSample): String? {
    val type = cell.networkType ?: return cell.generation
    val gen = cell.generation ?: return type
    return if (gen == type) type else "$type ($gen)"
}

/** „23001“ → „230 01“. */
fun mccMncSpaced(code: String?): String? =
    if (code != null && code.length >= 5) code.substring(0, 3) + " " + code.substring(3) else code

fun natLabel(natType: String?): String = when (natType) {
    "CGNAT" -> "NAT u operátora"
    "NAT" -> "NAT"
    "PUBLIC" -> "Veřejná adresa"
    "XLAT464" -> "Překlad z IPv6"
    else -> "Neznámý"
}

/** Poslední dva oktety BSSID: „…72:52“. */
fun bssidShort(bssid: String?): String? =
    bssid?.split(':')?.takeIf { it.size == 6 }?.let { "…${it[4]}:${it[5]}" } ?: bssid
