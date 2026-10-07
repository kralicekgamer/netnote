package cz.netdenik.logic

import cz.netdenik.data.ConnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StateResolverTest {
    private val base = RadioInputs(
        hasNetwork = false, wifiTransport = false, cellTransport = false,
        airplaneMode = false, wifiEnabled = true, mobileDataEnabled = true, simReady = true,
    )

    @Test
    fun connectedStates() {
        assertEquals(ConnState.WIFI, resolveState(base.copy(hasNetwork = true, wifiTransport = true)))
        assertEquals(ConnState.MOBILE, resolveState(base.copy(hasNetwork = true, cellTransport = true)))
        assertEquals(ConnState.OTHER, resolveState(base.copy(hasNetwork = true)))
        // VPN nad Wi‑Fi hlásí oba transporty podkladové sítě; Wi‑Fi má přednost.
        assertEquals(
            ConnState.WIFI,
            resolveState(base.copy(hasNetwork = true, wifiTransport = true, cellTransport = true)),
        )
    }

    @Test
    fun searchingWhenSomethingIsSwitchedOn() {
        assertEquals(ConnState.SEARCHING, resolveState(base))
        assertEquals(ConnState.SEARCHING, resolveState(base.copy(wifiEnabled = false)))
        assertEquals(ConnState.SEARCHING, resolveState(base.copy(mobileDataEnabled = false)))
        assertEquals(ConnState.SEARCHING, resolveState(base.copy(airplaneMode = true)))
    }

    @Test
    fun offlineWhenUserSwitchedEverythingOff() {
        assertEquals(ConnState.OFFLINE, resolveState(base.copy(airplaneMode = true, wifiEnabled = false)))
        assertEquals(ConnState.OFFLINE, resolveState(base.copy(wifiEnabled = false, mobileDataEnabled = false)))
        assertEquals(ConnState.OFFLINE, resolveState(base.copy(wifiEnabled = false, simReady = false)))
    }
}

class IpClassifierTest {
    @Test
    fun parsesIpv4() {
        assertEquals(0x0A000001L, parseIpv4("10.0.0.1"))
        assertEquals(0xFFFFFFFFL, parseIpv4("255.255.255.255"))
        assertNull(parseIpv4("256.1.1.1"))
        assertNull(parseIpv4("1.2.3"))
        assertNull(parseIpv4("2a00:1028::1"))
        assertNull(parseIpv4(null))
    }

    @Test
    fun classifiesNat() {
        assertEquals(NatType.CGNAT, classifyNat("100.64.0.1", "85.160.1.2"))
        assertEquals(NatType.CGNAT, classifyNat("100.127.255.254", null))
        assertEquals(NatType.NAT, classifyNat("100.128.0.1", "85.160.1.2"))
        assertEquals(NatType.NAT, classifyNat("10.20.30.40", "85.160.1.2"))
        assertEquals(NatType.NAT, classifyNat("192.168.1.5", null))
        assertEquals(NatType.NAT, classifyNat("172.31.0.1", "85.160.1.2"))
        assertEquals(NatType.PUBLIC, classifyNat("85.160.1.2", "85.160.1.2"))
        assertEquals(NatType.XLAT464, classifyNat("192.0.0.4", "85.160.1.2"))
        assertEquals(NatType.UNKNOWN, classifyNat(null, "85.160.1.2"))
        assertEquals(NatType.UNKNOWN, classifyNat("85.160.1.2", null))
        assertEquals(NatType.UNKNOWN, classifyNat("85.160.1.2", "2a00:1028::1"))
        // Na mobilní síti je i privátní rozsah NAT operátora.
        assertEquals(NatType.CGNAT, classifyNat("10.20.30.40", "37.48.1.2", carrier = true))
        assertEquals(NatType.CGNAT, classifyNat("10.20.30.40", null, carrier = true))
        assertEquals(NatType.PUBLIC, classifyNat("37.48.1.2", "37.48.1.2", carrier = true))
    }

    @Test
    fun parsesPublicIpFormats() {
        assertEquals("85.160.1.2", parsePublicIp("fl=1\nh=1.1.1.1\nip=85.160.1.2\nts=1\n"))
        assertEquals("2a00:1028:83a0::1", parsePublicIp("ip=2a00:1028:83a0::1\n"))
        assertEquals("85.160.1.2", parsePublicIp(" 85.160.1.2\n"))
        assertEquals("85.160.1.2", parsePublicIp("{\"ip\": \"85.160.1.2\"}"))
        assertNull(parsePublicIp("<html>error</html>"))
        assertNull(parsePublicIp("ip=nonsense"))
    }
}

class WifiChannelTest {
    @Test
    fun mapsFrequencies() {
        assertEquals(ChannelInfo(1, "2.4 GHz"), channelOf(2412))
        assertEquals(ChannelInfo(13, "2.4 GHz"), channelOf(2472))
        assertEquals(ChannelInfo(14, "2.4 GHz"), channelOf(2484))
        assertEquals(ChannelInfo(36, "5 GHz"), channelOf(5180))
        assertEquals(ChannelInfo(165, "5 GHz"), channelOf(5825))
        assertEquals(ChannelInfo(1, "6 GHz"), channelOf(5955))
        assertEquals(ChannelInfo(233, "6 GHz"), channelOf(7115))
        assertNull(channelOf(0))
    }
}

class CsvTest {
    @Test
    fun escapes() {
        assertEquals("a,,\"b,c\",\"say \"\"hi\"\"\",\"x\ny\"", csvLine(listOf("a", null, "b,c", "say \"hi\"", "x\ny")))
        assertEquals("\"\"", csvField(""))
        assertEquals("\" lead\"", csvField(" lead"))
    }
}

class NamesTest {
    @Test
    fun generations() {
        assertEquals("4G", generationOf(13, null))
        assertEquals("4G", generationOf(13, 1))
        assertEquals("5G NSA", generationOf(13, 3))
        assertEquals("5G", generationOf(20, null))
        assertEquals("3G", generationOf(15, null))
        assertEquals("2G", generationOf(2, null))
        assertNull(generationOf(0, null))
    }

    @Test
    fun securityFromScan() {
        assertEquals("WPA/WPA2-Personal", securityFromScanCapabilities("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"))
        assertEquals("WPA3-Personal", securityFromScanCapabilities("[RSN-SAE-CCMP][ESS]"))
        assertEquals("WPA2/WPA3-Personal", securityFromScanCapabilities("[RSN-PSK+SAE-CCMP][ESS]"))
        assertEquals("WPA/WPA2-Enterprise", securityFromScanCapabilities("[WPA2-EAP-CCMP][ESS]"))
        assertEquals("Open", securityFromScanCapabilities("[ESS]"))
    }

    @Test
    fun ipConfigChanges() {
        val a = IpInfo(v4 = "192.168.1.5", gateway = "192.168.1.1", dns = listOf("1.1.1.1", "8.8.8.8"))
        assertNull(ipConfigDiff(a, a.copy(dns = listOf("8.8.8.8", "1.1.1.1"), v6 = "2a00::1")))
        assertNull(ipConfigDiff(IpInfo(), a))
        assertEquals("IP 192.168.1.5 → 192.168.1.9", ipConfigDiff(a, a.copy(v4 = "192.168.1.9")))
        assertEquals(
            "brána 192.168.1.1 → 192.168.1.254, DNS 1.1.1.1;8.8.8.8 → 192.168.1.254",
            ipConfigDiff(a, a.copy(gateway = "192.168.1.254", dns = listOf("192.168.1.254"))),
        )
    }
}

class TickPlanTest {
    private val minute = 60_000L
    private val now = 10_000_000L

    @Test
    fun firstTickIsOneIntervalFromNow() {
        assertEquals(now + minute, nextTickAt(0, now, minute))
    }

    @Test
    fun keepsTheRhythmWhenOnTimeOrSlightlyLate() {
        assertEquals(now - 3 + minute, nextTickAt(now - 3, now, minute))
        assertEquals(now - 20_000 + minute, nextTickAt(now - 20_000, now, minute))
    }

    @Test
    fun restartsFromNowWhenVeryLate() {
        assertEquals(now + minute, nextTickAt(now - 58_000, now, minute))
        assertEquals(now + minute, nextTickAt(now - 200_000, now, minute))
    }

    @Test
    fun strayAlarmDoesNotPushAFuturePlan() {
        assertEquals(now + 59_000, nextTickAt(now + 59_000, now, minute))
    }
}
