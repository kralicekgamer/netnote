package cz.kralicekgamer.netnote.logic

import cz.kralicekgamer.netnote.data.CellSample
import cz.kralicekgamer.netnote.data.ConnState
import cz.kralicekgamer.netnote.data.Event
import cz.kralicekgamer.netnote.data.EventType
import cz.kralicekgamer.netnote.data.PingSample
import cz.kralicekgamer.netnote.data.SpeedSample
import cz.kralicekgamer.netnote.data.StateSegment
import cz.kralicekgamer.netnote.data.TrafficSample
import cz.kralicekgamer.netnote.data.WifiSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

private const val MIN = 60_000L
private const val HOUR = 60 * MIN

private fun seg(id: Long, start: Long, end: Long, state: ConnState, detail: String? = null) =
    StateSegment(id = id, startMs = start, endMs = end, state = state, bootCount = 1, detail = detail)

private fun ping(ts: Long, transport: ConnState, rtt: Double?) = PingSample(
    ts = ts, transport = transport, target = "x", rttMs = rtt, minMs = rtt, attempts = 3,
    failures = if (rtt == null) 3 else 0, error = null,
)

private fun speed(ts: Long, transport: ConnState, down: Long?, up: Long?, trigger: String = "PERIODIC") = SpeedSample(
    ts = ts, transport = transport, trigger = trigger, downBps = down, upBps = up,
    downBytes = 1, upBytes = 1, downMs = 1, upMs = 1, error = null,
)

private fun traffic(ts: Long, state: ConnState, wifiRx: Long = 0, mobileRx: Long = 0, ownRx: Long = 0) = TrafficSample(
    ts = ts, intervalMs = MIN, state = state, wifiRx = wifiRx, wifiTx = wifiRx / 10, mobileRx = mobileRx,
    mobileTx = mobileRx / 10, ownRx = ownRx, ownTx = ownRx / 10, wifiSource = "iface:wlan0", mobileSource = "iface:ccmni1",
)

private fun wifi(ts: Long, ssid: String?, bssid: String? = "02:11:22:33:72:52") = WifiSample(
    ts = ts, ssid = ssid, bssid = bssid, frequencyMhz = 5200, channel = 40, band = "5 GHz", wifiStandard = "5",
    security = "WPA/WPA2-Enterprise", rssiDbm = -52, txLinkMbps = 180, rxLinkMbps = 52, ip = "192.168.10.23",
    prefixLen = 22, ipv6 = null, gateway = "192.168.10.1", dns = "192.168.10.2", dhcpServer = "192.168.10.2",
    publicIp = null, validated = true, metered = false, vpn = false,
)

private fun cell(ts: Long, type: String = "LTE", gen: String = "4G", cellId: Long = 123456789, rsrp: Int? = -85) = CellSample(
    ts = ts, operatorName = "T‑Mobile CZ", networkMccMnc = "23001", simOperatorName = "T‑Mobile CZ", simMccMnc = "23001",
    roaming = false, dataSubId = 1, simSlot = 0, activeSimCount = 1, networkType = type, generation = gen,
    overrideType = null, cellId = cellId, pci = 463, tac = 14408, arfcn = 1579, bands = "3", rsrp = rsrp, rsrq = -13,
    sinr = 20, rssi = null, level = 4, ip = "10.1.2.3", ipv6 = null, publicIp = null, natType = "CGNAT",
    validated = true, vpn = false,
)

class DistTest {
    @Test
    fun medianOfOddAndEvenCounts() {
        assertEquals(3.0, median(listOf(5.0, 1.0, 3.0))!!, 0.0)
        assertEquals(2.5, median(listOf(4.0, 1.0, 2.0, 3.0))!!, 0.0)
        assertNull(median(emptyList()))
    }

    @Test
    fun percentileUsesNearestRank() {
        val values = (1..20).map { it.toDouble() }
        assertEquals(19.0, percentile(values, 95.0)!!, 0.0)
        assertEquals(20.0, percentile(values, 100.0)!!, 0.0)
        assertEquals(7.0, percentile(listOf(7.0), 95.0)!!, 0.0)
    }

    @Test
    fun distSummarises() {
        val d = dist(listOf(12.0, 7.0, 118.0, 13.0))
        assertEquals(4, d.count)
        assertEquals(12.5, d.median!!, 0.0)
        assertEquals(7.0, d.min!!, 0.0)
        assertEquals(118.0, d.max!!, 0.0)
        assertEquals(Dist(0, null, null, null, null), dist(emptyList()))
    }
}

class CoverageTest {
    private val segments = listOf(
        seg(1, -5 * HOUR, 6 * HOUR, ConnState.WIFI),
        seg(2, 6 * HOUR, 8 * HOUR, ConnState.MOBILE),
        seg(3, 8 * HOUR, 8 * HOUR + 10_000, ConnState.SEARCHING), // jen přechod
        seg(4, 8 * HOUR + 10_000, 10 * HOUR, ConnState.WIFI),
        seg(5, 10 * HOUR, 10 * HOUR + 14 * MIN, ConnState.UNKNOWN),
        seg(6, 10 * HOUR + 14 * MIN, 30 * HOUR, ConnState.WIFI),
    )

    @Test
    fun sharesAddUpToOneAndAreSorted() {
        val c = coverage(segments, 0, 24 * HOUR)
        assertEquals(24 * HOUR, c.coveredMs)
        assertEquals(1.0, c.shares.sumOf { it.fraction }, 1e-9)
        assertEquals(ConnState.WIFI, c.shares.first().state)
        assertEquals(6, c.shares.size) // „jiné připojení“ nenastalo, takže se neukáže
        assertEquals(c.shares.sortedByDescending { it.ms }, c.shares)
    }

    @Test
    fun timeBeforeFirstRecordIsNotCounted() {
        val c = coverage(listOf(seg(1, 20 * HOUR, 23 * HOUR, ConnState.WIFI)), 0, 24 * HOUR)
        assertEquals(3 * HOUR, c.coveredMs)
        assertEquals(24 * HOUR, c.windowMs)
        assertEquals(1.0, c.fraction(ConnState.WIFI), 1e-9)
    }

    @Test
    fun switchesIgnoreBriefSearching() {
        val events = listOf(
            Event(ts = HOUR, type = EventType.AP_CHANGE),
            Event(ts = 2 * HOUR, type = EventType.AP_CHANGE),
            Event(ts = 7 * HOUR, type = EventType.CELL_CHANGE),
            Event(ts = 40 * HOUR, type = EventType.CELL_CHANGE), // mimo období
        )
        val s = switches(segments, events, 0, 24 * HOUR)
        assertEquals(2, s.wifiMobile) // Wi‑Fi → data a data → (krátké hledání) → Wi‑Fi
        assertEquals(2, s.apChanges)
        assertEquals(1, s.cellChanges)
        assertEquals(1, s.gaps)
        assertEquals(14 * MIN, s.gapMs)
    }

    @Test
    fun dominantStateOfInterval() {
        assertEquals(ConnState.MOBILE, dominantState(segments, 6 * HOUR, 7 * HOUR))
        assertEquals(ConnState.WIFI, dominantState(segments, 5 * HOUR + 30 * MIN, 6 * HOUR + 29 * MIN))
        assertNull(dominantState(segments, 40 * HOUR, 41 * HOUR))
    }
}

class TransportStatsTest {
    @Test
    fun wifiAndMobileAreNeverMixed() {
        val pings = listOf(
            ping(1, ConnState.WIFI, 10.0), ping(2, ConnState.WIFI, 20.0), ping(3, ConnState.WIFI, null),
            ping(4, ConnState.MOBILE, 46.0),
        )
        val speeds = listOf(
            speed(1, ConnState.WIFI, 40_000_000, 90_000_000), speed(2, ConnState.WIFI, 50_000_000, null),
            speed(3, ConnState.MOBILE, 80_000_000, 9_500_000),
        )
        val traffic = listOf(
            traffic(1, ConnState.WIFI, wifiRx = 1000, mobileRx = 5, ownRx = 700),
            traffic(2, ConnState.MOBILE, mobileRx = 50, ownRx = 30),
        )
        val w = transportStats(ConnState.WIFI, pings, speeds, traffic)
        val m = transportStats(ConnState.MOBILE, pings, speeds, traffic)
        assertEquals(15.0, w.ping.median!!, 0.0)
        assertEquals(1, w.failedPings)
        assertEquals(45_000_000.0, w.downBps.median!!, 0.0)
        assertEquals(90_000_000.0, w.upBps.median!!, 0.0)
        assertEquals(2, w.tests)
        assertEquals(1000, w.rxBytes)
        assertEquals(700, w.ownRxBytes)
        assertEquals(46.0, m.ping.median!!, 0.0)
        assertEquals(55, m.rxBytes)
        assertEquals(30, m.ownRxBytes)
    }
}

class BucketsTest {
    private val segments = listOf(
        seg(1, 0, 2 * HOUR, ConnState.WIFI),
        seg(2, 2 * HOUR, 3 * HOUR, ConnState.OFFLINE),
        seg(3, 3 * HOUR, 4 * HOUR, ConnState.MOBILE),
    )

    @Test
    fun barShowsTheNetworkWithMostSamples() {
        val pings = listOf(
            ping(10 * MIN, ConnState.WIFI, 10.0), ping(20 * MIN, ConnState.WIFI, 14.0),
            ping(30 * MIN, ConnState.MOBILE, 500.0), // menšina, do mediánu nesmí
            ping(3 * HOUR + 5 * MIN, ConnState.MOBILE, 40.0),
            ping(3 * HOUR + 6 * MIN, ConnState.MOBILE, null), // neúspěšný ping není měření
        )
        val b = bucketMedians(0, 5 * HOUR, 5, segments, pings, { it.ts }, { it.rttMs }, { it.transport })
        assertEquals(5, b.size)
        assertEquals(Bucket(0, HOUR, 12.0, ConnState.WIFI), b[0])
        assertEquals(Bucket(HOUR, 2 * HOUR, null, ConnState.WIFI), b[1]) // bez měření, ale Wi‑Fi byla
        assertEquals(ConnState.OFFLINE, b[2].state)
        assertNull(b[2].value)
        assertEquals(40.0, b[3].value!!, 0.0)
        assertEquals(ConnState.MOBILE, b[3].state)
        assertNull(b[4].state) // pro tuhle hodinu není žádný záznam
    }

    @Test
    fun gridLinesAreEvenNiceSteps() {
        assertEquals(listOf(50.0, 100.0), gridLines(118.0))
        assertEquals(listOf(100.0, 200.0), gridLines(236.0))
        assertEquals(listOf(20.0, 40.0), gridLines(46.0))
        assertEquals(listOf(20.0, 40.0, 60.0), gridLines(60.0))
        assertEquals(listOf(5.0), gridLines(9.0))
        assertEquals(listOf(0.2, 0.4, 0.6), gridLines(0.7).map { Math.round(it * 10) / 10.0 })
        assertTrue(gridLines(0.0).isEmpty())
    }

    @Test
    fun heatmapHasOneRowPerDay() {
        val zone = ZoneId.of("Europe/Prague")
        val day = LocalDate.of(2026, 10, 6)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = weekHeatmap(
            listOf(
                seg(1, start, start + 6 * HOUR, ConnState.OFFLINE),
                seg(2, start + 6 * HOUR, start + 18 * HOUR, ConnState.WIFI),
            ),
            lastDay = day, zone = zone,
        )
        assertEquals(7, rows.size)
        assertEquals(day, rows.last().date)
        assertEquals(day.minusDays(6), rows.first().date)
        assertNull(rows.first().wifiFraction)
        assertTrue(rows.first().hours.all { it == null })
        val today = rows.last()
        assertEquals(ConnState.OFFLINE, today.hours[0])
        assertEquals(ConnState.WIFI, today.hours[6])
        assertNull(today.hours[20])
        assertEquals(12.0 / 18.0, today.wifiFraction!!, 1e-9)
    }
}

class NetworkLabelsTest {
    private val index = SampleIndex(
        wifi = listOf(wifi(10 * MIN, "Domov_5G"), wifi(2 * HOUR, "Skola")),
        cells = listOf(cell(4 * HOUR), cell(5 * HOUR, gen = "5G NSA")),
    )

    @Test
    fun measurementGetsTheNearestSample() {
        assertEquals("Domov_5G", index.networkName(ConnState.WIFI, 12 * MIN))
        assertEquals("Skola", index.networkName(ConnState.WIFI, 2 * HOUR - MIN))
        assertEquals("T‑Mobile CZ · LTE", index.networkName(ConnState.MOBILE, 4 * HOUR + MIN))
        assertEquals("T‑Mobile CZ · 5G NSA", index.networkName(ConnState.MOBILE, 5 * HOUR))
        assertNull(index.networkName(ConnState.WIFI, HOUR)) // nejbližší vzorek je dál než 10 minut
        assertNull(index.networkName(ConnState.OFFLINE, 10 * MIN))
    }

    @Test
    fun pingsAndSpeedsGroupByNetwork() {
        val pings = listOf(
            ping(11 * MIN, ConnState.WIFI, 9.0), ping(12 * MIN, ConnState.WIFI, 11.0),
            ping(2 * HOUR, ConnState.WIFI, 13.0), ping(4 * HOUR, ConnState.MOBILE, 46.0),
            ping(HOUR, ConnState.WIFI, 999.0), // síť nejde určit, do „podle sítě“ nepatří
        )
        val byNet = pingByNetwork(pings, index)
        assertEquals(listOf("Domov_5G", "Skola", "T‑Mobile CZ · LTE"), byNet.map { it.name })
        assertEquals(10.0, byNet[0].medianMs, 0.0)
        assertEquals(ConnState.MOBILE, byNet[2].transport)

        val peaks = pingPeaks(pings, index, count = 2)
        assertEquals(listOf(999.0, 46.0), peaks.map { it.rttMs })
        assertNull(peaks[0].network)

        val speeds = listOf(
            speed(4 * HOUR, ConnState.MOBILE, 74_000_000, 9_400_000),
            speed(2 * HOUR, ConnState.WIFI, 42_000_000, 73_000_000),
            speed(2 * HOUR + MIN, ConnState.WIFI, 44_000_000, null),
        )
        val bySpeed = speedByNetwork(speeds, index)
        assertEquals(listOf("Skola", "T‑Mobile CZ · LTE"), bySpeed.map { it.name })
        assertEquals(2, bySpeed[0].tests)
        assertEquals(43_000_000.0, bySpeed[0].downBps!!, 0.0)
        assertEquals(73_000_000.0, bySpeed[0].upBps!!, 0.0)
    }
}

class HistoryTest {
    private fun minutes(ms: Long) = "${ms / MIN} m"

    private val segments = listOf(
        seg(2, 1 * HOUR, 2 * HOUR, ConnState.MOBILE),
        seg(3, 2 * HOUR, 2 * HOUR + 3 * MIN, ConnState.SEARCHING),
        seg(4, 2 * HOUR + 3 * MIN, 3 * HOUR, ConnState.MOBILE),
        seg(5, 3 * HOUR, 4 * HOUR, ConnState.WIFI),
        seg(6, 4 * HOUR, 4 * HOUR + 14 * MIN, ConnState.UNKNOWN, "appka neběžela (zabil ji systém?)"),
        seg(7, 4 * HOUR + 14 * MIN, 5 * HOUR, ConnState.WIFI),
        seg(8, 5 * HOUR, 5 * HOUR + 6 * MIN, ConnState.MOBILE),
    )
    private val previous = seg(1, 0, HOUR, ConnState.WIFI)

    private fun info(s: StateSegment) = when (s.state) {
        ConnState.WIFI -> SegmentInfo(network = "Skola", accessPoint = "…72:52")
        ConnState.MOBILE -> SegmentInfo(network = "T‑Mobile CZ · LTE", cellId = 123456789, cellCount = if (s.id == 2L) 3 else 1)
        else -> SegmentInfo()
    }

    private fun rows(events: List<Event> = emptyList(), names: Map<String, String> = emptyMap()) =
        historyRows(segments, previous, events, ::info, openSegmentId = 8, duration = ::minutes, apName = { names[it] })

    @Test
    fun rowsAreNewestFirstWithTitlesFromTransitions() {
        val r = rows()
        assertEquals(
            listOf(
                "Wi‑Fi → mobilní data", "Měření obnoveno", "Výpadek měření · 14 m", "Data → Wi‑Fi",
                "Signál nalezen", "Hledání signálu · 3 m", "Wi‑Fi → mobilní data",
            ),
            r.map { it.title },
        )
        // Běžící úsek ukazuje, kde telefon je, ne jak dlouho.
        assertEquals("T‑Mobile CZ · LTE · buňka 123456789", r[0].subtitle)
        assertEquals(8L, r[0].segmentId)
        assertEquals("Skola · AP …72:52 · 46 m", r[1].subtitle)
        assertEquals("Systém ukončil aplikaci, data chybí", r[2].subtitle)
        assertTrue(r[2].highlighted)
        assertEquals(HistoryMark.HATCH, r[2].mark)
        assertNull(r[2].segmentId)
        assertEquals("T‑Mobile CZ · LTE · 60 m · 3 buňky", r.last().subtitle)
    }

    @Test
    fun filtersAndApRuns() {
        val events = (1L..6L).map {
            Event(
                id = it, ts = 3 * HOUR + it * MIN, type = EventType.AP_CHANGE,
                fromValue = "02:11:22:33:72:61", toValue = "02:11:22:33:72:52",
                detail = "Skola: 2412 → 5200 MHz, -71 → -55 dBm",
            )
        } + Event(id = 9, ts = HOUR + MIN, type = EventType.CELL_CHANGE, fromValue = "1", toValue = "2")
        val all = rows(events, names = mapOf("02:11:22:33:72:52" to "přízemí"))

        val ap = all.first { it.kind == HistoryKind.AP }
        assertEquals("…72:61 → přízemí · −71 → −55 dBm", ap.subtitle)
        assertTrue(ap.monoSubtitle)

        assertEquals(13, all.count { it.matches(HistoryFilter.ALL) }) // změna buňky do „Vše“ nepatří
        assertEquals(1, all.count { it.matches(HistoryFilter.CELL) })
        assertEquals(6, all.count { it.matches(HistoryFilter.AP) })
        assertEquals(1, all.count { it.matches(HistoryFilter.GAP) })
        assertEquals(5, all.count { it.matches(HistoryFilter.LINK) })

        val visible = all.filter { it.matches(HistoryFilter.ALL) }
        val collapsed = collapseApRuns(visible, expanded = emptySet())
        val more = collapsed.filterIsInstance<HistoryItem.More>().single()
        assertEquals(4, more.hidden)
        assertEquals(visible.size - 4 + 1, collapsed.size)
        assertEquals(visible.size, collapseApRuns(visible, expanded = setOf(more.runKey)).size)
    }

    @Test
    fun czechPlurals() {
        assertEquals("Zobrazit 1 další změnu AP", moreApChangesLabel(1))
        assertEquals("Zobrazit 4 další změny AP", moreApChangesLabel(4))
        assertEquals("Zobrazit 5 dalších změn AP", moreApChangesLabel(5))
        assertEquals("3 buňky", cellCountLabel(3))
        assertEquals("7 buněk", cellCountLabel(7))
    }
}

class SegmentDetailTest {
    @Test
    fun summarisesOnlyTheSegment() {
        val s = seg(5, HOUR, 2 * HOUR, ConnState.MOBILE)
        val d = segmentDetail(
            segment = s, ongoing = true,
            pings = listOf(ping(HOUR + MIN, ConnState.MOBILE, 44.0), ping(HOUR + 2 * MIN, ConnState.MOBILE, 46.0), ping(3 * HOUR, ConnState.MOBILE, 900.0)),
            speeds = listOf(speed(HOUR + MIN, ConnState.MOBILE, 87_000_000, 9_500_000)),
            traffic = listOf(
                traffic(HOUR, ConnState.WIFI, wifiRx = 999, mobileRx = 999), // interval před úsekem
                traffic(HOUR + MIN, ConnState.MOBILE, mobileRx = 1200, ownRx = 1000),
            ),
            wifi = emptyList(),
            cells = listOf(cell(HOUR + MIN, rsrp = -85), cell(HOUR + 2 * MIN, rsrp = null), cell(HOUR + 3 * MIN, rsrp = -78, cellId = 7)),
            events = listOf(Event(ts = HOUR + 2 * MIN, type = EventType.CELL_CHANGE), Event(ts = 5 * HOUR, type = EventType.CELL_CHANGE)),
        )
        assertEquals(45.0, d.ping.median!!, 0.0)
        assertEquals(2, d.ping.count)
        assertEquals(1, d.tests)
        assertEquals(1200, d.rxBytes)
        assertEquals(1000, d.ownRxBytes)
        assertEquals(7L, d.cell!!.cellId)
        assertEquals(1, d.cellChanges)
        assertEquals(listOf(-85, -78), d.rsrp.map { it.second })
    }

    @Test
    fun evenlyKeepsEnds() {
        assertEquals(listOf(1, 2, 3), evenly(listOf(1, 2, 3), 8))
        assertEquals(listOf(0, 3, 6, 9), evenly((0..9).toList(), 4))
    }
}

class RadioNamesTest {
    @Test
    fun labels() {
        assertEquals("Wi‑Fi 5 · 802.11ac", wifiStandardLabel("5"))
        assertEquals(3, wifiSignalBars(-52))
        assertEquals(1, wifiSignalBars(-80))
        assertEquals("B3", bandShort(cell(0)))
        assertEquals("B3 · 1800 MHz · EARFCN 1579", bandLong(cell(0)))
        assertEquals("LTE (4G)", techLong(cell(0)))
        assertEquals("5G NSA", techLabel(cell(0, gen = "5G NSA")))
        assertEquals("230 01", mccMncSpaced("23001"))
        assertEquals("NAT u operátora", natLabel("CGNAT"))
        assertEquals("…72:52", bssidShort("02:11:22:33:72:52"))
    }
}
