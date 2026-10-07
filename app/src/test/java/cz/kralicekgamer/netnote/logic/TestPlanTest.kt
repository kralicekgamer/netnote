package cz.kralicekgamer.netnote.logic

import cz.kralicekgamer.netnote.ui.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

private const val MIN = 60_000L

class TestPlanTest {
    /** Neměřená Wi‑Fi, 5 minut po posledním testu, výchozí nastavení. */
    private val wifi = SpeedPlan(
        manual = false, validated = true, mobile = false, meteredWifi = false, roaming = false,
        sinceLastMs = 5 * MIN, cellChanged = false,
        wifiIntervalMs = 30 * MIN, mobileIntervalMs = 60 * MIN, cellChangeMinGapMs = 10 * MIN,
        skipMeteredWifi = true, usedTodayBytes = 0, dailyLimitBytes = 50_000_000, testBytes = 1_256_000,
        slackMs = 30_000,
    )
    private val mobile = wifi.copy(mobile = true)

    @Test
    fun intervalElapsedToleratesEarlyTick() {
        assertFalse(intervalElapsed(4 * MIN, 5 * MIN, 30_000))
        // Tick o vteřinu dřív se ještě počítá, jinak by se kolo posunulo o celou minutu.
        assertTrue(intervalElapsed(5 * MIN - 1_000, 5 * MIN, 30_000))
        assertTrue(intervalElapsed(5 * MIN, 5 * MIN, 30_000))
        // Každou minutu = při každém ticku, i když přišel o něco dřív.
        assertTrue(intervalElapsed(45_000, MIN, 30_000))
        assertFalse(intervalElapsed(20_000, MIN, 30_000))
    }

    @Test
    fun intervalElapsedEdgeCases() {
        // Hodiny se posunuly zpět: nečekat věčně.
        assertTrue(intervalElapsed(-5_000, 30 * MIN, 30_000))
        // Interval 0 = vypnuto, nikdy.
        assertFalse(intervalElapsed(Long.MAX_VALUE, 0, 30_000))
    }

    @Test
    fun manualAlwaysRuns() {
        val blocked = mobile.copy(
            manual = true, validated = false, roaming = true, mobileIntervalMs = 0,
            usedTodayBytes = 60_000_000, sinceLastMs = 1_000,
        )
        assertEquals("MANUAL", speedTestReason(blocked))
        assertEquals("MANUAL", speedTestReason(wifi.copy(manual = true, wifiIntervalMs = 0)))
        assertEquals("MANUAL", speedTestReason(wifi.copy(manual = true, meteredWifi = true)))
    }

    @Test
    fun wifiFollowsItsInterval() {
        assertNull(speedTestReason(wifi))
        assertEquals("PERIODIC", speedTestReason(wifi.copy(sinceLastMs = 30 * MIN)))
        assertEquals("PERIODIC", speedTestReason(wifi.copy(sinceLastMs = 10 * MIN, wifiIntervalMs = 10 * MIN)))
        assertNull(speedTestReason(wifi.copy(sinceLastMs = 30 * MIN, wifiIntervalMs = 60 * MIN)))
        // Síť bez ověřeného internetu (captive portál) se neměří.
        assertNull(speedTestReason(wifi.copy(sinceLastMs = 30 * MIN, validated = false)))
    }

    @Test
    fun wifiSwitchedOff() {
        assertNull(speedTestReason(wifi.copy(sinceLastMs = 100 * 60 * MIN, wifiIntervalMs = 0)))
    }

    @Test
    fun wifiIgnoresMobileSettings() {
        val spent = wifi.copy(sinceLastMs = 30 * MIN, usedTodayBytes = 60_000_000, mobileIntervalMs = 0, roaming = true)
        assertEquals("PERIODIC", speedTestReason(spent))
    }

    @Test
    fun meteredWifiRespectsSwitch() {
        val hotspot = wifi.copy(meteredWifi = true, sinceLastMs = 60 * MIN)
        assertNull(speedTestReason(hotspot))
        assertEquals("PERIODIC", speedTestReason(hotspot.copy(skipMeteredWifi = false)))
        // Na hotspotu platí šetrný interval pro data, ne ten pro Wi‑Fi.
        assertNull(speedTestReason(hotspot.copy(skipMeteredWifi = false, sinceLastMs = 30 * MIN)))
        // Denní limit je jen pro mobilní data, hotspot má vlastní vypínač.
        assertEquals("PERIODIC", speedTestReason(hotspot.copy(skipMeteredWifi = false, usedTodayBytes = 60_000_000)))
    }

    @Test
    fun mobilePeriodicAndCellChange() {
        assertNull(speedTestReason(mobile))
        assertEquals("PERIODIC", speedTestReason(mobile.copy(sinceLastMs = 60 * MIN)))
        // Změna buňky test spustí dřív, ale ne hned po předchozím.
        assertEquals("CELL_CHANGE", speedTestReason(mobile.copy(sinceLastMs = 12 * MIN, cellChanged = true)))
        assertNull(speedTestReason(mobile.copy(sinceLastMs = 5 * MIN, cellChanged = true)))
        assertEquals("PERIODIC", speedTestReason(mobile.copy(sinceLastMs = 30 * MIN, mobileIntervalMs = 30 * MIN)))
    }

    @Test
    fun mobileBlockers() {
        val due = mobile.copy(sinceLastMs = 60 * MIN, cellChanged = true)
        assertNull(speedTestReason(due.copy(roaming = true)))
        assertNull(speedTestReason(due.copy(validated = false)))
        // Vypnuto = žádné automatické testy, ani po změně buňky.
        assertNull(speedTestReason(due.copy(mobileIntervalMs = 0)))
        // „Netestovat na měřené Wi‑Fi“ se mobilních dat netýká.
        assertEquals("PERIODIC", speedTestReason(due.copy(skipMeteredWifi = true)))
    }

    @Test
    fun mobileDailyLimit() {
        val due = mobile.copy(sinceLastMs = 60 * MIN)
        // Do limitu se musí vejít celý další test.
        assertEquals("PERIODIC", speedTestReason(due.copy(usedTodayBytes = 48_744_000)))
        assertNull(speedTestReason(due.copy(usedTodayBytes = 48_744_001)))
        assertNull(speedTestReason(due.copy(usedTodayBytes = 60_000_000, cellChanged = true)))
        assertEquals("PERIODIC", speedTestReason(due.copy(usedTodayBytes = 60_000_000, dailyLimitBytes = 0)))
    }

    @Test
    fun limitAllowsWholeTest() {
        assertTrue(limitAllows(0, 1_256_000, 10_000_000))
        assertTrue(limitAllows(8_744_000, 1_256_000, 10_000_000))
        assertFalse(limitAllows(9_000_000, 1_256_000, 10_000_000))
        assertTrue(limitAllows(999_000_000, 1_256_000, 0))
    }

    @Test
    fun dailyEstimate() {
        // 48 testů po 12 MB.
        assertEquals(576_000_000L, dailyTestBytes(30 * MIN, 12_000_000))
        assertEquals(1_728_000_000L, dailyTestBytes(10 * MIN, 12_000_000))
        assertEquals(48_000_000L, dailyTestBytes(6 * 60 * MIN, 12_000_000))
        assertEquals(0L, dailyTestBytes(0, 12_000_000))
    }

    @Test
    fun retentionCutsAtMidnight() {
        val zone = ZoneId.of("Europe/Prague")
        fun ms(y: Int, m: Int, d: Int, h: Int, min: Int) = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

        assertEquals(ms(2026, 9, 7, 0, 0), retentionCutoff(ms(2026, 10, 7, 15, 30), 30, zone))
        assertEquals(ms(2026, 7, 9, 0, 0), retentionCutoff(ms(2026, 10, 7, 0, 0), 90, zone))
        // Přes změnu času (25. 10. 2026) pořád půlnoc, ne o hodinu vedle.
        assertEquals(ms(2026, 10, 1, 0, 0), retentionCutoff(ms(2026, 10, 31, 12, 0), 30, zone))
        assertNull(retentionCutoff(ms(2026, 10, 7, 15, 30), 0, zone))
    }

    @Test
    fun parsesPingTargets() {
        assertEquals(HostPort("1.1.1.1", 443), parseHostPort("1.1.1.1:443"))
        assertEquals(HostPort("8.8.8.8", 53), parseHostPort("  8.8.8.8:53 "))
        assertEquals(HostPort("example.com", 443), parseHostPort("example.com"))
        assertEquals(HostPort("my-router.lan", 80), parseHostPort("my-router.lan:80"))
        assertEquals(HostPort("2606:4700:4700::1111", 443), parseHostPort("[2606:4700:4700::1111]:443"))
        assertEquals(HostPort("2606:4700:4700::1111", 443), parseHostPort("2606:4700:4700::1111"))
        assertEquals(HostPort("::1", 8080), parseHostPort("[::1]:8080"))
    }

    @Test
    fun rejectsBadPingTargets() {
        for (bad in listOf(
            "", "   ", ":443", "1.1.1.1:", "1.1.1.1:0", "1.1.1.1:65536", "1.1.1.1:abc", "1.1.1.1:-1",
            "https://1.1.1.1", "host name:80", "a..b:80", "-host:80", "[1.1.1.1]:80", "[::1", "[::1]x", "[::1]:99999",
            "1.1.1.1/24",
        )) {
            assertNull("„$bad“ nemá projít", parseHostPort(bad))
        }
    }

    @Test
    fun hostPortLabel() {
        assertEquals("1.1.1.1:443", HostPort("1.1.1.1", 443).label)
        assertEquals("[2606:4700:4700::1111]:443", HostPort("2606:4700:4700::1111", 443).label)
        // Co se uloží, musí jít znovu načíst (dialog předvyplňuje právě label).
        assertEquals(HostPort("::1", 80), parseHostPort(HostPort("::1", 80).label))
    }

    @Test
    fun everyLabels() {
        assertEquals("každou minutu", Fmt.every(MIN))
        assertEquals("každé 2 min", Fmt.every(2 * MIN))
        assertEquals("každých 5 min", Fmt.every(5 * MIN))
        assertEquals("každých 15 min", Fmt.every(15 * MIN))
        assertEquals("každých 30 min", Fmt.every(30 * MIN))
        assertEquals("každou hodinu", Fmt.every(60 * MIN))
        assertEquals("každé 2 h", Fmt.every(120 * MIN))
        assertEquals("každé 3 h", Fmt.every(180 * MIN))
        assertEquals("každých 6 h", Fmt.every(360 * MIN))
    }

    @Test
    fun retentionLabels() {
        assertEquals("30 dní", Fmt.retention(30))
        assertEquals("90 dní", Fmt.retention(90))
        assertEquals("1 rok", Fmt.retention(365))
        assertEquals("Neomezeně", Fmt.retention(0))
    }
}
