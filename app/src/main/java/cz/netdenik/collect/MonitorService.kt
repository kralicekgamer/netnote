package cz.netdenik.collect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import cz.netdenik.R
import cz.netdenik.data.Prefs
import cz.netdenik.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground služba typu location. Bez ní Android appku v kapse uspí a změny sítě nezachytí;
 * typ location je podmínka, aby na pozadí vydával SSID/BSSID a info o buňce.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collector: Collector? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Sběr dat", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, notification("Spouštím…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: RuntimeException) {
            // Typicky restart z pozadí na Androidu 14+ bez polohy „Povolit vždy“.
            Log.w(TAG, "startForeground refused", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (collector == null) {
            collector = Collector(this, scope, ::showStatus).also { it.start() }
            instance = this
            running.value = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Sem se dojde, i když službu ukončuje systém. Heartbeat je nejvýš minutu starý,
        // takže stačí uklidit posluchače; případnou díru dopočítá příští start.
        collector?.release()
        collector = null
        if (instance === this) {
            instance = null
            running.value = false
        }
        scope.cancel()
        super.onDestroy()
    }

    fun onTickAlarm() {
        collector?.onTickAlarm() ?: TickWakeLock.release()
    }

    fun measureNow() {
        collector?.measureNow()
    }

    private fun stopByUser() {
        val c = collector ?: return
        collector = null
        instance = null
        running.value = false
        TickScheduler.cancel(this)
        scope.launch {
            c.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun showStatus(text: String) {
        if (collector == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL = "monitor"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var instance: MonitorService? = null
            private set

        val running = MutableStateFlow(false)

        /** @return false, když chybí oprávnění nebo Android start z pozadí nedovolil */
        fun start(ctx: Context, fromBackground: Boolean = false): Boolean {
            val allowed = if (fromBackground) Perms.canStartFromBackground(ctx) else Perms.canStart(ctx)
            if (!allowed) {
                Log.w(TAG, "start skipped: missing permissions (fromBackground=$fromBackground)")
                return false
            }
            return try {
                ctx.startForegroundService(Intent(ctx, MonitorService::class.java))
                true
            } catch (e: RuntimeException) {
                Log.w(TAG, "start refused", e)
                false
            }
        }

        fun stop(ctx: Context) {
            Prefs(ctx).serviceWanted = false
            TickScheduler.cancel(ctx)
            instance?.stopByUser()
        }
    }
}
