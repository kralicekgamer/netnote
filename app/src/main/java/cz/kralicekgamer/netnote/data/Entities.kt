package cz.kralicekgamer.netnote.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ConnState { WIFI, MOBILE, SEARCHING, OFFLINE, PHONE_OFF, UNKNOWN, OTHER }

enum class EventType {
    STATE_CHANGE,
    WIFI_NETWORK_CHANGE,
    AP_CHANGE,
    CELL_CHANGE,
    TECH_CHANGE,
    DATA_SIM_CHANGE,
    OPERATOR_CHANGE,
    ROAMING_CHANGE,
    IP_CONFIG_CHANGE,
    PUBLIC_IP_CHANGE,
    SERVICE_START,
    SERVICE_STOP,
    GAP,
    BOOT,
    SHUTDOWN,
    LOCATION_OFF,
    LOCATION_ON,
    ERROR,
}

/**
 * Souvislý úsek jednoho stavu. Úseky na sebe navazují (endMs jednoho = startMs dalšího),
 * takže se časy stavů sčítají do 100 %. endMs otevřeného úseku slouží zároveň jako heartbeat.
 */
@Entity(tableName = "state_segment", indices = [Index("startMs")])
data class StateSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long,
    val state: ConnState,
    val bootCount: Int,
    val detail: String? = null,
)

@Entity(tableName = "event", indices = [Index("ts")])
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val type: EventType,
    val fromValue: String? = null,
    val toValue: String? = null,
    val detail: String? = null,
)

@Entity(tableName = "wifi_sample", indices = [Index("ts")])
data class WifiSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val ssid: String?,
    val bssid: String?,
    val frequencyMhz: Int?,
    val channel: Int?,
    val band: String?,
    val wifiStandard: String?,
    val security: String?,
    val rssiDbm: Int?,
    val txLinkMbps: Int?,
    val rxLinkMbps: Int?,
    val ip: String?,
    val prefixLen: Int?,
    val ipv6: String?,
    val gateway: String?,
    val dns: String?,
    val dhcpServer: String?,
    val publicIp: String?,
    val validated: Boolean?,
    val metered: Boolean?,
    val vpn: Boolean,
)

@Entity(tableName = "cell_sample", indices = [Index("ts")])
data class CellSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val operatorName: String?,
    val networkMccMnc: String?,
    val simOperatorName: String?,
    val simMccMnc: String?,
    val roaming: Boolean?,
    val dataSubId: Int?,
    val simSlot: Int?,
    val activeSimCount: Int?,
    val networkType: String?,
    val generation: String?,
    val overrideType: String?,
    val cellId: Long?,
    val pci: Int?,
    val tac: Int?,
    val arfcn: Int?,
    val bands: String?,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val rssi: Int?,
    val level: Int?,
    val ip: String?,
    val ipv6: String?,
    val publicIp: String?,
    val natType: String?,
    val validated: Boolean?,
    val vpn: Boolean,
)

@Entity(tableName = "ping_sample", indices = [Index("ts")])
data class PingSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val transport: ConnState,
    val target: String,
    /** Medián úspěšných pokusů; null = žádný neprošel. */
    val rttMs: Double?,
    val minMs: Double?,
    val attempts: Int,
    val failures: Int,
    val error: String?,
)

@Entity(tableName = "speed_sample", indices = [Index("ts")])
data class SpeedSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val transport: ConnState,
    val trigger: String,
    val downBps: Long?,
    val upBps: Long?,
    val downBytes: Long,
    val upBytes: Long,
    val downMs: Long,
    val upMs: Long,
    val error: String?,
)

/** Rozdíl počítadel TrafficStats od minulého ticku. own* = spotřeba téhle appky (ping, speedtest). */
@Entity(tableName = "traffic_sample", indices = [Index("ts")])
data class TrafficSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val intervalMs: Long,
    val state: ConnState,
    val wifiRx: Long,
    val wifiTx: Long,
    val mobileRx: Long,
    val mobileTx: Long,
    val ownRx: Long,
    val ownTx: Long,
    /** Odkud je číslo pro Wi‑Fi: "iface:wlan0" nebo "total-mobile". */
    val wifiSource: String,
    /** Odkud je číslo pro mobilní data: "iface:ccmni0", "rebaseline" (nové rozhraní, interval vynechán) nebo "mobile-total". */
    val mobileSource: String,
)

@Entity(tableName = "tick", indices = [Index("ts")])
data class Tick(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    /** O kolik později než naplánováno tick přišel. */
    val latenessMs: Long,
    val workMs: Long,
    val state: ConnState,
    val trigger: String,
    val exactAlarm: Boolean,
    val deviceIdle: Boolean,
    val screenOn: Boolean,
    val batteryOptIgnored: Boolean,
    /** Telefon je na nabíječce / USB. Úsporné zdržování budíků se pak obvykle neuplatní. */
    val plugged: Boolean,
    val batteryPct: Int?,
)
