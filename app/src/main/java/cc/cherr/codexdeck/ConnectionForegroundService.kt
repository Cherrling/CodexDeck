package cc.cherr.codexdeck

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class ConnectionForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Remote Codex connection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the SSH, Codex app-server, and terminal connection alive"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DISCONNECT) {
            (application as CodexDeckApplication).runtime.disconnectAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "CodexDeck"
        val detail = intent?.getStringExtra(EXTRA_DETAIL) ?: "远端连接正在运行"
        startForeground(NOTIFICATION_ID, notification(title, detail))
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        (application as CodexDeckApplication).runtime.disconnectAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(title: String, detail: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ConnectionForegroundService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "断开", disconnectIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "remote-connection"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_STOP = "cc.cherr.codexdeck.action.STOP_CONNECTION"
        private const val ACTION_DISCONNECT = "cc.cherr.codexdeck.action.DISCONNECT"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_DETAIL = "detail"

        fun start(context: Context, title: String, detail: String) {
            val intent = Intent(context, ConnectionForegroundService::class.java)
                .putExtra(EXTRA_TITLE, title.take(120))
                .putExtra(EXTRA_DETAIL, detail.take(180))
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionForegroundService::class.java))
        }
    }
}
