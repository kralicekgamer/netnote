package cz.netdenik.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/** Formáty podle návrhu: desetinná čárka, mezera před jednotkou, časy a čísla pro české oko. */
object Fmt {
    private val CZ = Locale.forLanguageTag("cs-CZ")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val HOUR = DateTimeFormatter.ofPattern("HH")
    private val FILE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    private val DAYS = listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne")

    private fun zoned(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

    fun time(ms: Long): String = TIME.format(zoned(ms))
    fun hour(ms: Long): String = HOUR.format(zoned(ms))
    fun fileStamp(ms: Long): String = FILE.format(zoned(ms))

    fun dayName(date: LocalDate): String = DAYS[date.dayOfWeek.value - 1]

    /** „6. 10.“ */
    fun dayMonth(date: LocalDate): String = "${date.dayOfMonth}. ${date.monthValue}."
    fun dayMonth(ms: Long): String = dayMonth(zoned(ms).toLocalDate())

    /** „Út 6. 10.“ */
    fun weekday(ms: Long): String = zoned(ms).toLocalDate().let { "${dayName(it)} ${dayMonth(it)}" }

    /** Čas na ose a v detailu: dnešní jen „23:25“, včerejší „včera 23:25“, starší s datem. */
    fun timeRelative(ms: Long, nowMs: Long): String {
        val day = zoned(ms).toLocalDate()
        val today = zoned(nowMs).toLocalDate()
        return when (day) {
            today -> time(ms)
            today.minusDays(1) -> "včera ${time(ms)}"
            else -> "${dayMonth(day)} ${time(ms)}"
        }
    }

    /** „16 h 33 m“, „14 m“, „45 s“. */
    fun duration(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 500) / 1000
        return when {
            s == 0L -> "0 m"
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} m"
            s < 86_400 -> "${s / 3600} h ${s % 3600 / 60} m"
            else -> "${s / 86_400} d ${s % 86_400 / 3600} h"
        }
    }

    /** Interval měření: „každou minutu“, „každé 2 min“, „každých 30 min“, „každou hodinu“, „každých 6 h“. */
    fun every(ms: Long): String {
        val min = ms / 60_000
        val hours = min / 60
        return when {
            min == 1L -> "každou minutu"
            min == 60L -> "každou hodinu"
            min % 60 == 0L -> "${everyWord(hours)} $hours h"
            else -> "${everyWord(min)} $min min"
        }
    }

    private fun everyWord(n: Long) = if (n in 2..4) "každé" else "každých"

    /** Doba uchovávání: „30 dní“, „1 rok“; 0 = „Neomezeně“. */
    fun retention(days: Int): String = when {
        days <= 0 -> "Neomezeně"
        days == 365 -> "1 rok"
        days == 1 -> "1 den"
        days in 2..4 -> "$days dny"
        else -> "$days dní"
    }

    /** „69,0 %“; nula jako „0 %“. */
    fun percent(fraction: Double): String =
        if (fraction <= 0.0) "0 %" else String.format(CZ, "%.1f %%", fraction * 100)

    fun percentWhole(fraction: Double): String = "${(fraction * 100).roundToLong()} %"

    /** „2,84 GB“, „17,9 GB“, „612 MB“, „7,5 MB“, „340 kB“. */
    fun bytes(b: Long): String {
        val gb = b / 1e9
        val mb = b / 1e6
        return when {
            gb >= 10 -> String.format(CZ, "%.1f GB", gb)
            gb >= 1 -> String.format(CZ, "%.2f GB", gb)
            mb >= 10 -> String.format(CZ, "%.0f MB", mb)
            mb >= 0.1 -> String.format(CZ, "%.1f MB", mb)
            b >= 1000 -> String.format(CZ, "%.0f kB", b / 1e3)
            else -> "$b B"
        }
    }

    /** Rychlost v Mb/s bez jednotky: „47“, „9,5“; bez měření pomlčka. */
    fun mbps(bps: Double?): String {
        val v = (bps ?: return "–") / 1e6
        return if (v < 10) String.format(CZ, "%.1f", v) else String.format(CZ, "%.0f", v)
    }

    /** Ping bez jednotky, celé milisekundy: „12“. */
    fun ms(v: Double?): String = if (v == null) "–" else String.format(CZ, "%.0f", v)

    /** Ping v detailu: pod 20 ms s jedním desetinným místem. */
    fun msFine(v: Double?): String = when {
        v == null -> "–"
        v < 20 -> String.format(CZ, "%.1f ms", v)
        else -> String.format(CZ, "%.0f ms", v)
    }

    /** „1 072“. */
    fun count(n: Int): String = String.format(CZ, "%,d", n).replace(' ', ' ').replace(' ', ' ')

    /** Popisek vodicí čáry grafu: „100“, „12,5“. */
    fun axis(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(CZ, "%.1f", v)

    /** Záporná čísla s typografickým minus: „−52 dBm“. */
    fun signed(v: Int): String = if (v < 0) "−${-v}" else v.toString()

    /** „99 testů“, „1 test“, „2 testy“. */
    fun tests(n: Int, small: Boolean = false): String {
        val adj = if (!small) "" else when {
            n == 1 -> "malý "
            n in 2..4 -> "malé "
            else -> "malých "
        }
        val noun = when {
            n == 1 -> "test"
            n in 2..4 -> "testy"
            else -> "testů"
        }
        return "${count(n)} $adj$noun"
    }

    fun measurements(n: Int): String = "${count(n)} měření"

    fun changes(n: Int, what: String): String = when {
        n == 1 -> "1 změna $what"
        n in 2..4 -> "$n změny $what"
        else -> "$n změn $what"
    }
}
