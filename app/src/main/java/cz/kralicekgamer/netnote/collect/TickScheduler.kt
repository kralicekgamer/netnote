package cz.kralicekgamer.netnote.collect

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import cz.kralicekgamer.netnote.Config
import cz.kralicekgamer.netnote.data.Prefs
import cz.kralicekgamer.netnote.logic.nextTickAt

/**
 * Minutový tick přes AlarmManager. Smyčka s delay() by při zhasnutém displeji spala
 * spolu s procesorem; budík telefon probudí. Každý tick si naplánuje další.
 */
object TickScheduler {
    /** elapsedRealtime, na kdy je naplánovaný další tick; 0 = žádný. */
    @Volatile
    var scheduledAt = 0L
        private set

    /** Jestli systém dovolil přesný budík (jinak tick chodí s rozptylem). */
    @Volatile
    var exact = false
        private set

    private fun pendingIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, TickReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun scheduleNext(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val now = SystemClock.elapsedRealtime()
        val at = nextTickAt(scheduledAt, now, Config.TICK_INTERVAL_MS)

        val pi = pendingIntent(ctx)
        exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } catch (e: SecurityException) {
                exact = false
            }
        }
        if (!exact) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        scheduledAt = at
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pendingIntent(ctx))
        scheduledAt = 0
    }
}

/** Drží procesor vzhůru od budíku do konce měření. Pojistka: sám se uvolní po minutě. */
object TickWakeLock {
    private var lock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(ctx: Context) {
        val l = lock ?: ctx.applicationContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "netnote:tick")
            .apply { setReferenceCounted(false) }
            .also { lock = it }
        l.acquire(60_000)
    }

    @Synchronized
    fun release() {
        lock?.let { if (it.isHeld) it.release() }
    }
}

class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        TickWakeLock.acquire(context)
        val service = MonitorService.instance
        if (service != null) {
            service.onTickAlarm()
        } else {
            // Proces přežil nebo ho budík znovu nahodil, ale služba neběží: zkusíme ji oživit.
            if (Prefs(context).serviceWanted) MonitorService.start(context, fromBackground = true)
            TickWakeLock.release()
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> if (Prefs(context).serviceWanted) MonitorService.start(context, fromBackground = true)
        }
    }
}
