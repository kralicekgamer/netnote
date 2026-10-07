package cz.netdenik.logic

import cz.netdenik.data.ConnState
import cz.netdenik.data.Event
import cz.netdenik.data.EventType
import cz.netdenik.data.StateSegment

enum class HistoryKind { LINK, AP, CELL, GAP, OTHER }

enum class HistoryFilter(val label: String) {
    ALL("Vše"), LINK("Wi‑Fi ↔ data"), AP("AP"), CELL("Buňky"), GAP("Výpadky")
}

/** Jak vypadá značka řádku: plná barva stavu, dutý kroužek (změna AP / buňky), nebo šrafa (výpadek). */
enum class HistoryMark { STATE, RING, HATCH }

data class HistoryRow(
    val key: String,
    val ts: Long,
    val kind: HistoryKind,
    val mark: HistoryMark,
    /** Barva značky = barva tohoto stavu. */
    val state: ConnState,
    val title: String,
    val subtitle: String?,
    /** Podtitulek jsou identifikátory (BSSID, ID buněk): sází se neproporcionálním písmem. */
    val monoSubtitle: Boolean = false,
    /** Úsek Wi‑Fi nebo mobilních dat, jehož detail jde otevřít. */
    val segmentId: Long? = null,
    val highlighted: Boolean = false,
)

/** Co je potřeba vědět o úseku nad rámec databázového řádku; dodá volající ze vzorků. */
data class SegmentInfo(
    /** SSID, nebo „operátor · technologie“. */
    val network: String? = null,
    /** Jméno nebo zkrácené BSSID přístupového bodu, na kterém úsek začal. */
    val accessPoint: String? = null,
    val cellId: Long? = null,
    /** Kolik různých buněk se během úseku vystřídalo. */
    val cellCount: Int = 0,
)

/**
 * Řádky historie od nejnovějšího. V pohledu „Vše“ jsou úseky stavu a změny AP; změny buněk
 * jsou v něm schované v podtitulku mobilního úseku („3 buňky“) a samostatně se ukážou až ve filtru.
 *
 * @param previous úsek těsně před prvním z [segments], aby i první řádek věděl, odkud se přešlo
 * @param openSegmentId úsek, který ještě běží (místo délky se u něj píše, kde telefon právě je)
 * @param duration formát délky úseku, např. „2 h 18 m“
 * @param apName uživatelské jméno přístupového bodu podle BSSID
 */
fun historyRows(
    segments: List<StateSegment>,
    previous: StateSegment?,
    events: List<Event>,
    info: (StateSegment) -> SegmentInfo,
    openSegmentId: Long?,
    duration: (Long) -> String,
    apName: (String) -> String?,
): List<HistoryRow> {
    val rows = mutableListOf<HistoryRow>()
    var prev = previous
    for (s in segments.sortedBy { it.startMs }) {
        rows += segmentRow(s, prev, info(s), s.id == openSegmentId, duration)
        prev = s
    }
    for (e in events) {
        when (e.type) {
            EventType.AP_CHANGE -> rows += HistoryRow(
                key = "e${e.id}", ts = e.ts, kind = HistoryKind.AP, mark = HistoryMark.RING, state = ConnState.WIFI,
                title = "Změna AP",
                subtitle = listOfNotNull(
                    "${apLabel(e.fromValue, apName)} → ${apLabel(e.toValue, apName)}",
                    DBM.find(e.detail.orEmpty())?.let { "${minus(it.groupValues[1])} → ${minus(it.groupValues[2])} dBm" },
                ).joinToString(" · "),
                monoSubtitle = true,
            )
            EventType.WIFI_NETWORK_CHANGE -> rows += HistoryRow(
                key = "e${e.id}", ts = e.ts, kind = HistoryKind.AP, mark = HistoryMark.RING, state = ConnState.WIFI,
                title = "Jiná Wi‑Fi síť",
                subtitle = "${ssidOnly(e.fromValue)} → ${ssidOnly(e.toValue)}",
            )
            EventType.CELL_CHANGE -> rows += HistoryRow(
                key = "e${e.id}", ts = e.ts, kind = HistoryKind.CELL, mark = HistoryMark.RING, state = ConnState.MOBILE,
                title = "Změna buňky",
                subtitle = "${e.fromValue} → ${e.toValue}",
                monoSubtitle = true,
            )
            else -> Unit
        }
    }
    return rows.sortedWith(compareByDescending<HistoryRow> { it.ts }.thenByDescending { it.key })
}

fun HistoryRow.matches(filter: HistoryFilter): Boolean = when (filter) {
    HistoryFilter.ALL -> kind != HistoryKind.CELL
    HistoryFilter.LINK -> kind == HistoryKind.LINK
    HistoryFilter.AP -> kind == HistoryKind.AP
    HistoryFilter.CELL -> kind == HistoryKind.CELL
    HistoryFilter.GAP -> kind == HistoryKind.GAP
}

/** Položka seznamu po sbalení: řádek, nebo tlačítko „Zobrazit N dalších změn AP“. */
sealed interface HistoryItem {
    data class Row(val row: HistoryRow) : HistoryItem
    data class More(val runKey: String, val hidden: Int) : HistoryItem
}

/** Série změn AP jdoucích po sobě se sbalí na první dvě; zbytek se rozbalí klepnutím. */
fun collapseApRuns(rows: List<HistoryRow>, expanded: Set<String>, keep: Int = 2): List<HistoryItem> {
    val out = mutableListOf<HistoryItem>()
    var i = 0
    while (i < rows.size) {
        if (rows[i].kind != HistoryKind.AP) {
            out += HistoryItem.Row(rows[i])
            i++
            continue
        }
        var j = i
        while (j < rows.size && rows[j].kind == HistoryKind.AP) j++
        val run = rows.subList(i, j)
        val runKey = run.first().key
        if (run.size <= keep + 1 || runKey in expanded) {
            run.forEach { out += HistoryItem.Row(it) }
        } else {
            run.take(keep).forEach { out += HistoryItem.Row(it) }
            out += HistoryItem.More(runKey, run.size - keep)
        }
        i = j
    }
    return out
}

/** „Zobrazit 4 další změny AP“ se správným českým tvarem. */
fun moreApChangesLabel(n: Int): String = when {
    n == 1 -> "Zobrazit 1 další změnu AP"
    n in 2..4 -> "Zobrazit $n další změny AP"
    else -> "Zobrazit $n dalších změn AP"
}

fun cellCountLabel(n: Int): String = when {
    n == 1 -> "1 buňka"
    n in 2..4 -> "$n buňky"
    else -> "$n buněk"
}

private fun segmentRow(
    s: StateSegment,
    prev: StateSegment?,
    info: SegmentInfo,
    open: Boolean,
    duration: (Long) -> String,
): HistoryRow {
    val length = duration(s.endMs - s.startMs)
    val key = "s${s.id}"
    return when (s.state) {
        ConnState.WIFI, ConnState.MOBILE -> {
            val wifi = s.state == ConnState.WIFI
            val place = if (wifi) info.accessPoint?.let { "AP $it" } else info.cellId?.let { "buňka $it" }
            HistoryRow(
                key = key, ts = s.startMs, kind = HistoryKind.LINK, mark = HistoryMark.STATE, state = s.state,
                title = arrivalTitle(prev?.state, s.state),
                subtitle = listOfNotNull(
                    info.network,
                    place.takeIf { wifi || open },
                    length.takeIf { !open },
                    cellCountLabel(info.cellCount).takeIf { !wifi && !open && info.cellCount > 1 },
                ).joinToString(" · ").ifEmpty { null },
                segmentId = s.id,
            )
        }
        ConnState.SEARCHING -> HistoryRow(
            key = key, ts = s.startMs, kind = HistoryKind.OTHER, mark = HistoryMark.STATE, state = s.state,
            title = "Hledání signálu · $length", subtitle = "Telefon chtěl být online, ale neměl síť",
        )
        ConnState.OFFLINE -> HistoryRow(
            key = key, ts = s.startMs, kind = HistoryKind.OTHER, mark = HistoryMark.STATE, state = s.state,
            title = arrivalTitle(prev?.state, s.state), subtitle = "Připojení vypnuté · $length",
        )
        ConnState.PHONE_OFF -> HistoryRow(
            key = key, ts = s.startMs, kind = HistoryKind.OTHER, mark = HistoryMark.STATE, state = s.state,
            title = "Telefon vypnutý · $length",
            subtitle = if (s.detail == "čisté vypnutí") "Vypnutý ručně" else "Vybitá baterie, pád, nebo neznámý důvod",
        )
        ConnState.UNKNOWN -> HistoryRow(
            key = key, ts = s.startMs, kind = HistoryKind.GAP, mark = HistoryMark.HATCH, state = s.state,
            title = "Výpadek měření · $length",
            subtitle = when {
                s.detail?.contains("zastavena uživatelem") == true -> "Měření bylo vypnuté ručně"
                s.detail?.contains("po startu telefonu") == true -> "Po zapnutí telefonu se měření nespustilo"
                else -> "Systém ukončil aplikaci, data chybí"
            },
            highlighted = true,
        )
        ConnState.OTHER -> HistoryRow(
            key = key, ts = s.startMs, kind = HistoryKind.OTHER, mark = HistoryMark.STATE, state = s.state,
            title = arrivalTitle(prev?.state, s.state), subtitle = "Ethernet nebo sdílené připojení · $length",
        )
    }
}

/** „Wi‑Fi → mobilní data“, „Měření obnoveno“, „Signál nalezen“… podle toho, odkud se přešlo. */
private fun arrivalTitle(from: ConnState?, to: ConnState): String {
    val connected = to == ConnState.WIFI || to == ConnState.MOBILE
    return when {
        from == null -> "Začátek měření"
        from == ConnState.UNKNOWN -> "Měření obnoveno"
        from == ConnState.PHONE_OFF -> "Telefon zapnutý"
        from == ConnState.SEARCHING && connected -> "Signál nalezen"
        else -> "${fromName(from)} → ${toName(to)}"
    }
}

private fun fromName(s: ConnState) = when (s) {
    ConnState.WIFI -> "Wi‑Fi"
    ConnState.MOBILE -> "Data"
    ConnState.OFFLINE -> "Offline"
    ConnState.SEARCHING -> "Hledání"
    ConnState.OTHER -> "Jiné připojení"
    ConnState.PHONE_OFF -> "Vypnutý"
    ConnState.UNKNOWN -> "Výpadek"
}

private fun toName(s: ConnState) = when (s) {
    ConnState.WIFI -> "Wi‑Fi"
    ConnState.MOBILE -> "mobilní data"
    ConnState.OFFLINE -> "offline"
    ConnState.SEARCHING -> "hledání signálu"
    ConnState.OTHER -> "jiné připojení"
    ConnState.PHONE_OFF -> "vypnutý telefon"
    ConnState.UNKNOWN -> "výpadek"
}

private val DBM = Regex("(-?\\d+) → (-?\\d+) dBm")

/** Typografické minus místo spojovníku. */
private fun minus(number: String) = number.replace("-", "−")

private fun apLabel(bssid: String?, apName: (String) -> String?): String =
    bssid?.let { apName(it) ?: bssidShort(it) } ?: "?"

/** Události sítě ukládají „SSID (BSSID)“; pro řádek historie stačí SSID. */
private fun ssidOnly(value: String?): String = value?.substringBeforeLast(" (") ?: "?"
