package app.echoes.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.echoes.Graph
import app.echoes.R
import app.echoes.ui.MainActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the process alive while the queue has work, e.g. after sharing from the YouTube app. */
class DownloadService : Service() {
    private val scope = MainScope()
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, notification("Preparando descarga…", null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!watching) {
            watching = true
            scope.launch {
                Graph.downloads.jobs.collect { jobs ->
                    val pending = jobs.filterNot { it.finished }
                    if (pending.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collect
                    }
                    val current = pending.firstOrNull { it.state is DlState.Working } ?: pending.first()
                    val done = jobs.size - pending.size
                    val fraction = (current.state as? DlState.Working)?.fraction
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIF_ID,
                        notification("${current.item.title} (${done + 1} de ${jobs.size})", fraction),
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String, fraction: Float?): Notification {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Descargas", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_echoes)
            .setContentTitle("Descargando")
            .setContentText(text)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 43

        /** Best effort: Android may refuse from the background, and the queue still runs in-process. */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, DownloadService::class.java)) }
        }
    }
}
