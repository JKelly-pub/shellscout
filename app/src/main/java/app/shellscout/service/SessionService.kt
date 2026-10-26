package app.shellscout.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import app.shellscout.MainActivity
import app.shellscout.R
import app.shellscout.ShellScoutApp

/**
 * SE-02 / FT-06: a foreground service that keeps the process alive while any session is open or
 * any transfer is running, with a persistent notification. It stops itself when both are idle.
 * Connections themselves live in [app.shellscout.session.SessionManager].
 */
class SessionService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val app = application as ShellScoutApp
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(0, 0),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        lifecycleScope.launch {
            combine(app.container.sessions.tabs, app.container.transfers.activeCount) { tabs, active -> tabs.size to active }
                .collect { (tabs, active) ->
                    if (tabs == 0 && active == 0) {
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(tabs, active))
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_DISCONNECT_ALL) {
            (application as ShellScoutApp).container.sessions.closeAll()
        }
        return START_NOT_STICKY
    }

    private fun notification(tabs: Int, transfers: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_DISCONNECT_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = buildList {
            if (tabs > 0) add(if (tabs == 1) "1 session" else "$tabs sessions")
            if (transfers > 0) add(if (transfers == 1) "1 transfer running" else "$transfers transfers running")
        }.joinToString(" · ").ifEmpty { "Starting…" }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Disconnect all", disconnect)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Active sessions", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "sessions"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT_ALL = "app.shellscout.DISCONNECT_ALL"

        fun ensureRunning(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java))
        }
    }
}
