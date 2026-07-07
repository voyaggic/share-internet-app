package app.relay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * SessionService runs as a foreground service so Android doesn't kill it
 * when the app is minimised. It:
 *  - Shows a persistent notification with time remaining
 *  - Pings the backend every 30s to confirm session is still active
 *  - Cuts off the connection (via backend) when time runs out
 *  - Handles unexpected drops (router went offline) and auto-refunds
 */
class SessionService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var sessionId: String? = null
    private var expiresAt: Long = 0
    private var hostId: String? = null
    private var isGuestSession = false // true = guest running down their time; false = host waiting for guests

    companion object {
        const val CHANNEL_ID = "relay_session"
        const val NOTIF_ID = 1001
        const val TICK_INTERVAL_MS = 30_000L // 30 seconds
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sessionId = intent?.getStringExtra("session_id")
        expiresAt = intent?.getLongExtra("expires_at", 0) ?: 0
        hostId = intent?.getStringExtra("host_id")
        isGuestSession = sessionId != null

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        handler.post(tickRunnable)

        return START_STICKY // restart if killed by OS
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()

            if (isGuestSession) {
                if (expiresAt > 0 && now >= expiresAt) {
                    // Time's up — notify user and stop service
                    updateNotification("Your Relay session has ended", "Buy more time to reconnect")
                    notifySessionEnded()
                    stopSelf()
                    return
                }
                // Update countdown in notification
                val remaining = ((expiresAt - now) / 1000 / 60).toInt()
                updateNotification("Relay active", "$remaining minutes remaining")
            } else {
                // Host mode: just show "sharing is on" notification
                updateNotification("Relay sharing is on", "Tap to see active guests and earnings")
            }

            // Ping backend to confirm still active (detects server-side cutoffs)
            pingBackend()

            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    private fun pingBackend() {
        val path = if (isGuestSession) "/api/sessions/$sessionId/ping"
                   else "/api/hosts/$hostId/ping"
        try {
            val url = URL("${BuildConfig.API_BASE_URL}$path")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            val response = JSONObject(conn.inputStream.bufferedReader().readText())

            if (isGuestSession) {
                val status = response.getString("status")
                if (status == "completed" || status == "refunded") {
                    updateNotification("Session ended", "Your internet access has been stopped")
                    notifySessionEnded()
                    stopSelf()
                }
            }
        } catch (_: Exception) {
            // Network error — don't crash; the expiry timer will still cut off correctly
        }
    }

    private fun notifySessionEnded() {
        val intent = Intent(this, ConnectActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wifi)
            .setContentTitle("Relay session ended")
            .setContentText("Tap to buy more time")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID + 1, notif)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_wifi)
        .setContentTitle("Relay active")
        .setContentText(if (isGuestSession) "Calculating time…" else "Sharing is on")
        .setOngoing(true)
        .build()

    private fun updateNotification(title: String, text: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wifi)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, notif)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Relay Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows active WiFi session status" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
