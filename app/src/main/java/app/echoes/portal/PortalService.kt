package app.echoes.portal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import app.echoes.R
import app.echoes.playback.PlayerConnection
import app.echoes.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/** Keeps the Portal reachable with the screen off, so a big upload from the PC isn't cut short. */
class PortalService : Service() {
    private lateinit var connection: PlayerConnection
    private lateinit var server: PortalServer
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        connection = PlayerConnection(this).also { it.connect() }
        server = PortalServer(this, connection)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = "http://${localIp() ?: "?"}:$PORT"
        startForeground(NOTIF_ID, notification(url), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        server.start(PORT)
        wifiLock = wifiLock ?: (getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "echoes:portal").also { it.acquire() }
        address.value = url
        return START_STICKY
    }

    override fun onDestroy() {
        server.stop()
        connection.release()
        wifiLock?.release()
        address.value = null
        super.onDestroy()
    }

    private fun notification(url: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Portal", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_echoes)
            .setContentTitle("Portal abierto")
            .setContentText(url)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val PORT = 8484
        private const val CHANNEL = "portal"
        private const val NOTIF_ID = 42
        val address = MutableStateFlow<String?>(null)

        fun localIp(): String? = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress

        fun setRunning(context: Context, on: Boolean) {
            val intent = Intent(context, PortalService::class.java)
            if (on) context.startForegroundService(intent) else context.stopService(intent)
        }
    }
}
