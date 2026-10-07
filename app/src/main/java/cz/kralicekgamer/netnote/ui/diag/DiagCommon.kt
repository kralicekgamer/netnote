package cz.kralicekgamer.netnote.ui.diag

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.kralicekgamer.netnote.data.ConnState
import cz.kralicekgamer.netnote.data.EventType
import cz.kralicekgamer.netnote.ui.theme.Geist
import cz.kralicekgamer.netnote.ui.theme.Mono
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Fmt {
    private val CZ = Locale.forLanguageTag("cs-CZ")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val DATE_TIME = DateTimeFormatter.ofPattern("d.M. HH:mm:ss")
    private val FILE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    private fun zoned(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

    fun time(ms: Long): String = TIME.format(zoned(ms))
    fun dateTime(ms: Long): String = DATE_TIME.format(zoned(ms))
    fun fileStamp(ms: Long): String = FILE.format(zoned(ms))

    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min ${s % 60} s"
            else -> "${s / 3600} h ${s % 3600 / 60} min"
        }
    }

    fun ago(nowMs: Long, thenMs: Long): String = "před " + duration((nowMs - thenMs).coerceAtLeast(0))

    fun bytes(b: Long): String = when {
        b < 1_000 -> "$b B"
        b < 1_000_000 -> String.format(CZ, "%.1f kB", b / 1e3)
        b < 1_000_000_000 -> String.format(CZ, "%.1f MB", b / 1e6)
        else -> String.format(CZ, "%.2f GB", b / 1e9)
    }

    fun bps(v: Number?): String {
        val b = v?.toDouble() ?: return "–"
        return if (b < 1e6) String.format(CZ, "%.0f kb/s", b / 1e3) else String.format(CZ, "%.1f Mb/s", b / 1e6)
    }

    fun ms(v: Double?): String = if (v == null) "–" else String.format(CZ, "%.1f ms", v)

    fun percent(part: Long, total: Long): String =
        if (total <= 0) "–" else String.format(CZ, "%.1f %%", part * 100.0 / total)

    fun state(s: ConnState): String = when (s) {
        ConnState.WIFI -> "Wi‑Fi"
        ConnState.MOBILE -> "Data"
        ConnState.SEARCHING -> "Hledání"
        ConnState.OFFLINE -> "Offline"
        ConnState.PHONE_OFF -> "Telefon vypnutý"
        ConnState.UNKNOWN -> "Neznámo"
        ConnState.OTHER -> "Jiné připojení"
    }

    /** Hodnota události, která může být názvem stavu. */
    fun stateOrRaw(v: String?): String? =
        v?.let { raw -> ConnState.entries.firstOrNull { it.name == raw }?.let(::state) ?: raw }

    fun event(t: EventType): String = when (t) {
        EventType.STATE_CHANGE -> "Změna stavu"
        EventType.WIFI_NETWORK_CHANGE -> "Jiná Wi‑Fi síť"
        EventType.AP_CHANGE -> "Změna AP"
        EventType.CELL_CHANGE -> "Změna buňky"
        EventType.TECH_CHANGE -> "Změna technologie"
        EventType.DATA_SIM_CHANGE -> "Změna datové SIM"
        EventType.OPERATOR_CHANGE -> "Změna operátora"
        EventType.ROAMING_CHANGE -> "Roaming"
        EventType.IP_CONFIG_CHANGE -> "Změna IP / brány / DNS"
        EventType.PUBLIC_IP_CHANGE -> "Změna veřejné IP"
        EventType.SERVICE_START -> "Start služby"
        EventType.SERVICE_STOP -> "Stop služby"
        EventType.GAP -> "Díra v datech"
        EventType.BOOT -> "Telefon byl vypnutý"
        EventType.SHUTDOWN -> "Vypínání telefonu"
        EventType.LOCATION_OFF -> "Systémová poloha vypnuta"
        EventType.LOCATION_ON -> "Systémová poloha zapnuta"
        EventType.ERROR -> "Chyba"
    }
}

/** Barvy a písma bílé karty Diagnostiky. Jsou pevné, na barvách zbytku appky nezávisí. */
object Paper {
    val Background = Color.White
    val Ink = Color(0xFF1E1E2E)
    val Label = Color(0xFF5C5F77)
    val Line = Color(0xFFDCE0E8)

    val Title = TextStyle(fontFamily = Geist, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    val Text = TextStyle(fontFamily = Geist, fontSize = 13.sp, color = Ink)
    val Small = TextStyle(fontFamily = Geist, fontSize = 12.sp, lineHeight = 17.sp, color = Label)
    val Value = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = Ink)
}

@Composable
fun Section(title: String, first: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 20.dp)) {
        Text(title, style = Paper.Title)
        HorizontalDivider(Modifier.padding(top = 4.dp, bottom = 4.dp), color = Paper.Line)
        content()
    }
}

/** Řádek „název: hodnota“. Chybějící hodnota se ukáže jako pomlčka, aby bylo vidět, co se nesbírá. */
@Composable
fun KeyValue(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(0.42f).padding(end = 8.dp), style = Paper.Text, color = Paper.Label)
        Text(value?.ifEmpty { null } ?: "–", modifier = Modifier.weight(0.58f), style = Paper.Value)
    }
}

fun yesNo(v: Boolean?): String? = v?.let { if (it) "ano" else "ne" }
