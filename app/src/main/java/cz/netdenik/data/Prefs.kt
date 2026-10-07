package cz.netdenik.data

import android.content.Context
import cz.netdenik.Config

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("netdenik", Context.MODE_PRIVATE)

    var ipUrl: String
        get() = sp.getString("ipUrl", null) ?: Config.DEFAULT_IP_URL
        set(v) = sp.edit().putString("ipUrl", v).apply()

    var downUrl: String
        get() = sp.getString("downUrl", null) ?: Config.DEFAULT_DOWN_URL
        set(v) = sp.edit().putString("downUrl", v).apply()

    var upUrl: String
        get() = sp.getString("upUrl", null) ?: Config.DEFAULT_UP_URL
        set(v) = sp.edit().putString("upUrl", v).apply()

    var pingHost: String
        get() = sp.getString("pingHost", null) ?: Config.DEFAULT_PING_HOST
        set(v) = sp.edit().putString("pingHost", v).apply()

    var pingPort: Int
        get() = sp.getInt("pingPort", Config.DEFAULT_PING_PORT)
        set(v) = sp.edit().putInt("pingPort", v).apply()

    var pingIntervalMs: Long
        get() = sp.getLong("pingIntervalMs", Config.DEFAULT_PING_INTERVAL_MS)
        set(v) = sp.edit().putLong("pingIntervalMs", v).apply()

    /** 0 = automatické testy na Wi‑Fi vypnuté. */
    var wifiSpeedIntervalMs: Long
        get() = sp.getLong("wifiSpeedIntervalMs", Config.DEFAULT_WIFI_SPEED_INTERVAL_MS)
        set(v) = sp.edit().putLong("wifiSpeedIntervalMs", v).apply()

    /** 0 = automatické testy na mobilních datech vypnuté. */
    var mobileSpeedIntervalMs: Long
        get() = sp.getLong("mobileSpeedIntervalMs", Config.DEFAULT_MOBILE_SPEED_INTERVAL_MS)
        set(v) = sp.edit().putLong("mobileSpeedIntervalMs", v).apply()

    /** 0 = bez limitu. */
    var mobileDailyLimitBytes: Long
        get() = sp.getLong("mobileDailyLimitBytes", Config.DEFAULT_MOBILE_DAILY_LIMIT_BYTES)
        set(v) = sp.edit().putLong("mobileDailyLimitBytes", v).apply()

    /** Na měřené Wi‑Fi (hotspot z jiného telefonu) rychlost samy neměříme. */
    var skipMeteredWifi: Boolean
        get() = sp.getBoolean("skipMeteredWifi", true)
        set(v) = sp.edit().putBoolean("skipMeteredWifi", v).apply()

    /** 0 = neomezeně. */
    var retentionDays: Int
        get() = sp.getInt("retentionDays", Config.DEFAULT_RETENTION_DAYS)
        set(v) = sp.edit().putInt("retentionDays", v).apply()

    var lastPruneAt: Long
        get() = sp.getLong("lastPruneAt", 0)
        set(v) = sp.edit().putLong("lastPruneAt", v).apply()

    /** Uživatel chce, aby služba běžela (po rebootu se spustí sama). */
    var serviceWanted: Boolean
        get() = sp.getBoolean("serviceWanted", false)
        set(v) = sp.edit().putBoolean("serviceWanted", v).apply()

    /** Čas, kdy služba zachytila ACTION_SHUTDOWN. Zapisuje se synchronně, telefon se právě vypíná. */
    var cleanShutdownAt: Long
        get() = sp.getLong("cleanShutdownAt", 0)
        set(v) {
            sp.edit().putLong("cleanShutdownAt", v).commit()
        }

    var stoppedByUserAt: Long
        get() = sp.getLong("stoppedByUserAt", 0)
        set(v) = sp.edit().putLong("stoppedByUserAt", v).apply()

    /** Počet děr způsobených zabitím appky. Od první díry nabízíme vypnutí optimalizace baterie. */
    var gapCount: Int
        get() = sp.getInt("gapCount", 0)
        set(v) = sp.edit().putInt("gapCount", v).apply()

    var lastWifiSpeedAt: Long
        get() = sp.getLong("lastWifiSpeedAt", 0)
        set(v) = sp.edit().putLong("lastWifiSpeedAt", v).apply()

    var lastMobileSpeedAt: Long
        get() = sp.getLong("lastMobileSpeedAt", 0)
        set(v) = sp.edit().putLong("lastMobileSpeedAt", v).apply()

    /** Jména síťových rozhraní, aby se objem dat počítal správně hned po restartu služby. */
    var wifiIface: String?
        get() = sp.getString("wifiIface", null)
        set(v) = sp.edit().putString("wifiIface", v).apply()

    var mobileIface: String?
        get() = sp.getString("mobileIface", null)
        set(v) = sp.edit().putString("mobileIface", v).apply()

    /** Uživatelská jména přístupových bodů podle BSSID (Android název AP nezná). */
    fun apName(bssid: String): String? = sp.getString(AP_PREFIX + bssid.lowercase(), null)

    fun setApName(bssid: String, name: String?) {
        val key = AP_PREFIX + bssid.lowercase()
        if (name.isNullOrBlank()) sp.edit().remove(key).apply() else sp.edit().putString(key, name.trim()).apply()
    }

    fun apNames(): Map<String, String> = sp.all
        .filterKeys { it.startsWith(AP_PREFIX) }
        .mapNotNull { (k, v) -> (v as? String)?.let { k.removePrefix(AP_PREFIX) to it } }
        .toMap()

    fun resetCounters() {
        sp.edit().remove("gapCount").remove("lastWifiSpeedAt").remove("lastMobileSpeedAt").apply()
    }

    private companion object {
        const val AP_PREFIX = "ap:"
    }
}
