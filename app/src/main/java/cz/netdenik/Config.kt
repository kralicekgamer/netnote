package cz.netdenik

object Config {
    const val TICK_INTERVAL_MS = 60_000L

    /**
     * Delší výpadek heartbeatu než tohle se bere jako díra v datech. Se zhasnutým displejem
     * telefon (realme 8) pouští tick jen zhruba jednou za 5 minut, nejdéle jsme viděli 5:48,
     * a to díra není. Kratší práh by z každého restartu služby udělal falešné „neznámo“.
     */
    const val GAP_THRESHOLD_MS = 7 * 60_000L

    /** Po ztrátě sítě chvíli počkáme, jestli hned nenaskočí jiná (Wi‑Fi → data bez mezistavu). */
    const val LOSS_DEBOUNCE_MS = 3_000L

    const val DEFAULT_PING_HOST = "1.1.1.1"
    const val DEFAULT_PING_PORT = 443
    const val PING_TIMEOUT_MS = 3_000
    const val PING_ATTEMPTS = 3

    /** Tick chodí dál každou minutu; delší interval jen znamená, že se pinguje při každém N‑tém. */
    const val DEFAULT_PING_INTERVAL_MS = 60_000L
    val PING_INTERVALS_MS = listOf(1, 2, 5, 10, 15).map { it * 60_000L }

    /** Tick, který přijde o chvíli dřív než celý interval, se ještě počítá jako „je čas“. */
    const val DUE_SLACK_MS = TICK_INTERVAL_MS / 2

    const val PUBLIC_IP_INTERVAL_MS = 10 * 60_000L

    /**
     * Speedtest na Wi‑Fi. Při testu každých 10 minut s 10 MB dolů a 5 MB nahoru dělaly vlastní
     * testy 70 % veškerého provozu telefonu (126 ze 180 MB za 2 h 20 min, přes 2 GB denně).
     * Velikost stahování zůstává, aby výsledek šel srovnat se staršími daty a na rychlé lince
     * nebyl zkreslený rozjezdem TCP; šetří se intervalem a menším odesíláním.
     * Při výchozím intervalu nejvýš 48 × 12 MB, tedy kolem 600 MB denně.
     * Interval 0 = automatické testy vypnuté, měří se jen ručně.
     */
    const val DEFAULT_WIFI_SPEED_INTERVAL_MS = 30 * 60_000L
    val WIFI_SPEED_INTERVALS_MS = listOf(10, 15, 30, 60, 120, 360, 0).map { it * 60_000L }
    const val WIFI_DOWN_MAX_BYTES = 10_000_000L
    const val WIFI_DOWN_MAX_MS = 3_000L
    const val WIFI_UP_MAX_BYTES = 2_000_000L
    const val WIFI_UP_MAX_MS = 2_000L

    /** Platí pro mobilní data a pro měřenou Wi‑Fi (typicky hotspot z jiného telefonu). */
    const val DEFAULT_MOBILE_SPEED_INTERVAL_MS = 60 * 60_000L
    val MOBILE_SPEED_INTERVALS_MS = listOf(30, 60, 120, 180, 360, 0).map { it * 60_000L }
    const val MOBILE_SPEED_MIN_GAP_MS = 10 * 60_000L
    const val MOBILE_DOWN_BYTES = 1_000_000L
    const val MOBILE_UP_BYTES = 256_000L
    const val MOBILE_SPEED_MAX_MS = 5_000L

    /** Kolik smí automatické testy na mobilních datech stáhnout a odeslat za den; 0 = bez limitu. */
    const val DEFAULT_MOBILE_DAILY_LIMIT_BYTES = 50_000_000L
    val MOBILE_DAILY_LIMITS_BYTES = listOf(10, 25, 50, 100, 250, 0).map { it * 1_000_000L }

    /** Starší záznamy se mažou; 0 = nemazat nic. */
    const val DEFAULT_RETENTION_DAYS = 90
    val RETENTION_DAYS = listOf(30, 90, 180, 365, 0)
    const val PRUNE_INTERVAL_MS = 24 * 3_600_000L

    const val HTTP_TIMEOUT_MS = 5_000

    const val DEFAULT_IP_URL = "https://1.1.1.1/cdn-cgi/trace"
    const val DEFAULT_DOWN_URL = "https://speed.cloudflare.com/__down?bytes={bytes}"
    const val DEFAULT_UP_URL = "https://speed.cloudflare.com/__up"
}
