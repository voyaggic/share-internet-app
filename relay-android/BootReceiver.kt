package app.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("relay_host", Context.MODE_PRIVATE)
        val hostId = prefs.getString("host_id", null) ?: return
        val wasSharing = prefs.getBoolean("was_sharing", false)

        // If host was sharing before phone restarted, resume automatically
        if (wasSharing) {
            context.startService(
                Intent(context, SessionService::class.java)
                    .putExtra("host_id", hostId)
            )
        }
    }
}
