package cz.netdenik.logic

import cz.netdenik.data.ConnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GapDetectorTest {
    private val min = 60_000L
    private val threshold = 3 * min
    private val t0 = 1_000_000_000L

    private fun input(
        last: LastAlive? = LastAlive(t0, bootCount = 5),
        now: Long,
        bootCount: Int = 5,
        bootTime: Long = t0 - 500 * min,
        cleanShutdownAt: Long? = null,
        stoppedByUserAt: Long? = null,
    ) = GapInput(last, now, bootCount, bootTime, cleanShutdownAt, stoppedByUserAt, threshold)

    /** Poslední úsek + díry + nový úsek musí pokrýt čas bez mezer a překryvů. */
    private fun assertTiles(lastEnd: Long, r: GapResult) {
        var cursor = lastEnd
        for (s in r.segments) {
            assertEquals(cursor, s.startMs)
            assertTrue(s.endMs > s.startMs)
            cursor = s.endMs
        }
        assertEquals(cursor, r.resumeStartMs)
    }

    @Test
    fun firstStartHasNoGap() {
        val r = detectGap(input(last = null, now = t0))
        assertTrue(r.segments.isEmpty())
        assertEquals(t0, r.resumeStartMs)
        assertFalse(r.killed)
    }

    @Test
    fun shortPauseIsAbsorbed() {
        val r = detectGap(input(now = t0 + 2 * min))
        assertTrue(r.segments.isEmpty())
        assertEquals(t0, r.resumeStartMs)
        assertFalse(r.killed)
    }

    @Test
    fun longPauseWithoutRebootIsUnknown() {
        val r = detectGap(input(now = t0 + 45 * min))
        assertEquals(listOf(ConnState.UNKNOWN), r.segments.map { it.state })
        assertTrue(r.killed)
        assertFalse(r.rebooted)
        assertTiles(t0, r)
    }

    @Test
    fun userStopIsUnknownButNotCountedAsKill() {
        val r = detectGap(input(now = t0 + 45 * min, stoppedByUserAt = t0 + 100))
        assertEquals(listOf(ConnState.UNKNOWN), r.segments.map { it.state })
        assertFalse(r.killed)
    }

    @Test
    fun rebootWithCleanShutdownIsPhoneOff() {
        val now = t0 + 60 * min
        val r = detectGap(input(now = now, bootCount = 6, bootTime = now - min, cleanShutdownAt = t0 + 50))
        assertEquals(listOf(ConnState.PHONE_OFF), r.segments.map { it.state })
        assertEquals("čisté vypnutí", r.segments[0].detail)
        // Minuta bootování se počítá ještě k vypnutému telefonu.
        assertEquals(now, r.segments[0].endMs)
        assertEquals(now, r.resumeStartMs)
        assertTrue(r.rebooted)
        assertFalse(r.killed)
        assertTiles(t0, r)
    }

    @Test
    fun rebootWithoutMarkerIsPhoneOffButFlagged() {
        val now = t0 + 60 * min
        val r = detectGap(input(now = now, bootCount = 6, bootTime = now - min))
        assertEquals(ConnState.PHONE_OFF, r.segments.single().state)
        assertTrue(r.segments.single().detail.startsWith("bez značky"))
    }

    @Test
    fun serviceNotStartedAfterBootIsUnknown() {
        val now = t0 + 120 * min
        val boot = t0 + 30 * min
        val r = detectGap(input(now = now, bootCount = 6, bootTime = boot, cleanShutdownAt = t0))
        assertEquals(listOf(ConnState.PHONE_OFF, ConnState.UNKNOWN), r.segments.map { it.state })
        assertEquals(boot, r.segments[0].endMs)
        assertTrue(r.killed)
        assertTiles(t0, r)
    }

    @Test
    fun severalRebootsAreUnknown() {
        val now = t0 + 60 * min
        val r = detectGap(input(now = now, bootCount = 8, bootTime = now - min, cleanShutdownAt = t0))
        assertEquals(ConnState.UNKNOWN, r.segments.single().state)
        assertTiles(t0, r)
    }

    @Test
    fun quickRebootStillRecordsPhoneOff() {
        val now = t0 + 2 * min
        val r = detectGap(input(now = now, bootCount = 6, bootTime = now - 30_000, cleanShutdownAt = t0))
        assertEquals(ConnState.PHONE_OFF, r.segments.single().state)
        assertTiles(t0, r)
    }

    @Test
    fun clockMovedBackProducesNothing() {
        val r = detectGap(input(now = t0 - 10 * min))
        assertTrue(r.segments.isEmpty())
        assertEquals(t0 - 10 * min, r.resumeStartMs)
    }

    @Test
    fun bootTimeOutsideGapIsClamped() {
        val now = t0 + 60 * min
        val r = detectGap(input(now = now, bootCount = 6, bootTime = t0 - 999 * min, cleanShutdownAt = t0))
        assertEquals(ConnState.UNKNOWN, r.segments.single().state)
        assertTiles(t0, r)
    }
}
