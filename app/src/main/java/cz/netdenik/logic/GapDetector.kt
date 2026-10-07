package cz.netdenik.logic

import cz.netdenik.data.ConnState
import kotlin.math.abs

/** Poslední zapsaný heartbeat (konec posledního úseku v databázi). */
data class LastAlive(val endMs: Long, val bootCount: Int)

data class GapInput(
    val last: LastAlive?,
    val nowMs: Long,
    val bootCount: Int,
    /** Okamžik startu telefonu: teď − elapsedRealtime. */
    val bootTimeMs: Long,
    val cleanShutdownAtMs: Long?,
    val stoppedByUserAtMs: Long?,
    val thresholdMs: Long,
)

data class GapSegment(val startMs: Long, val endMs: Long, val state: ConnState, val detail: String)

data class GapResult(
    val segments: List<GapSegment>,
    /** Od kdy začíná nový živý úsek, aby navázal bez mezery. */
    val resumeStartMs: Long,
    /** Služba neběžela, ačkoli měla (zabití systémem, nenaběhla po startu telefonu). */
    val killed: Boolean,
    val rebooted: Boolean,
)

/**
 * Volá se jen při startu služby. Rozhoduje, co se dělo mezi posledním heartbeatem a teď:
 * změnil se BOOT_COUNT → telefon byl vypnutý; nezměnil → appka neběžela a stav neznáme.
 * Opožděný tick v živém procesu sem vůbec nedojde, takže se jako díra nepočítá.
 */
fun detectGap(i: GapInput): GapResult {
    val last = i.last ?: return GapResult(emptyList(), i.nowMs, killed = false, rebooted = false)
    val rebooted = i.bootCount != last.bootCount
    // Hodiny se posunuly zpět: nic nedopočítáváme, jen začneme nový úsek.
    if (i.nowMs <= last.endMs) return GapResult(emptyList(), i.nowMs, killed = false, rebooted = rebooted)

    fun near(t: Long?) = t != null && abs(t - last.endMs) <= i.thresholdMs
    val userStopped = near(i.stoppedByUserAtMs)

    if (!rebooted) {
        if (i.nowMs - last.endMs <= i.thresholdMs) {
            return GapResult(emptyList(), last.endMs, killed = false, rebooted = false)
        }
        val detail = if (userStopped) "služba zastavena uživatelem" else "appka neběžela (zabil ji systém?)"
        return GapResult(
            listOf(GapSegment(last.endMs, i.nowMs, ConnState.UNKNOWN, detail)),
            i.nowMs, killed = !userStopped, rebooted = false,
        )
    }

    val bootTime = i.bootTimeMs.coerceIn(last.endMs, i.nowMs)
    val reboots = i.bootCount - last.bootCount
    // Služba naběhla brzy po startu telefonu: dobu bootování počítáme ještě k vypnutému telefonu.
    val startedSoon = i.nowMs - bootTime <= i.thresholdMs
    val offEnd = if (startedSoon) i.nowMs else bootTime
    val segments = mutableListOf<GapSegment>()
    if (offEnd > last.endMs) {
        segments += when {
            userStopped ->
                GapSegment(last.endMs, offEnd, ConnState.UNKNOWN, "služba zastavena uživatelem, pak restart telefonu")
            reboots != 1 ->
                GapSegment(last.endMs, offEnd, ConnState.UNKNOWN, "více restartů telefonu ($reboots), mezi nimi appka neběžela")
            near(i.cleanShutdownAtMs) ->
                GapSegment(last.endMs, offEnd, ConnState.PHONE_OFF, "čisté vypnutí")
            else ->
                GapSegment(
                    last.endMs, offEnd, ConnState.PHONE_OFF,
                    "bez značky vypnutí (vybitá baterie, pád, nebo appka neběžela už před vypnutím)",
                )
        }
    }
    if (startedSoon) return GapResult(segments, i.nowMs, killed = false, rebooted = true)
    segments += GapSegment(bootTime, i.nowMs, ConnState.UNKNOWN, "po startu telefonu služba neběžela")
    return GapResult(segments, i.nowMs, killed = !userStopped, rebooted = true)
}
