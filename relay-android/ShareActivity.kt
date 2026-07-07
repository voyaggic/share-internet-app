package app.relay

import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.relay.databinding.ActivityShareBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ShareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareBinding
    private lateinit var prefs: android.content.SharedPreferences
    private var hostId: String? = null
    private var isSharing = false
    private var pricePerMin = 2.0    // default KSh 2/min, host can change
    private var pendingEarnings = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("relay_host", MODE_PRIVATE)
        hostId = prefs.getString("host_id", null)

        binding.btnBack.setOnClickListener { finish() }

        if (hostId == null) {
            showSetupFlow()
        } else {
            showDashboard()
        }
    }

    // ---------------------------------------------------------------
    // First-time setup: register host with backend
    // ---------------------------------------------------------------
    private fun showSetupFlow() {
        binding.layoutSetup.visibility = View.VISIBLE
        binding.layoutDashboard.visibility = View.GONE

        binding.btnRegister.setOnClickListener {
            val payoutNumber = binding.etPayoutNumber.text.toString().trim()
            val payoutProvider = if (binding.radioMpesa.isChecked) "mpesa"
                                 else if (binding.radioAirtel.isChecked) "airtel_money"
                                 else "card"
            val priceInput = binding.etPrice.text.toString().toDoubleOrNull()

            if (payoutNumber.isEmpty()) {
                Toast.makeText(this, "Enter your mobile money number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (priceInput == null || priceInput <= 0) {
                Toast.makeText(this, "Enter a valid price per minute", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            pricePerMin = priceInput
            registerHost(payoutNumber, payoutProvider, priceInput)
        }
    }

    private fun registerHost(payoutNumber: String, provider: String, price: Double) {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo

        // Read the host's own router SSID and BSSID — this is what gets registered
        // as the verified network in the backend (the anti-spoofing anchor)
        val ssid = wifiInfo.ssid?.trim('"') ?: "Unknown"
        val bssid = wifiInfo.bssid ?: run {
            Toast.makeText(this, "Connect to your WiFi router first", Toast.LENGTH_LONG).show()
            return
        }

        binding.btnRegister.isEnabled = false
        binding.tvSetupStatus.text = "Registering your network…"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("display_name", binding.etDisplayName.text.toString().ifEmpty { ssid })
                    put("ssid", ssid)
                    put("bssid", bssid)
                    put("router_type", "captive_portal_only") // default; OpenWRT mode if compatible router detected
                    put("price_per_min", price)
                    put("payout_number", payoutNumber)
                    put("payout_provider", provider)
                }
                val response = apiPost("/api/hosts", body)
                val newHostId = response.getString("host_id")

                prefs.edit().putString("host_id", newHostId).apply()
                withContext(Dispatchers.Main) {
                    hostId = newHostId
                    showDashboard()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnRegister.isEnabled = true
                    binding.tvSetupStatus.text = "Registration failed: ${e.message}"
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Dashboard: earnings stats, sharing toggle, active sessions, withdraw
    // ---------------------------------------------------------------
    private fun showDashboard() {
        binding.layoutSetup.visibility = View.GONE
        binding.layoutDashboard.visibility = View.VISIBLE

        loadStats()

        // Sharing toggle
        binding.switchSharing.setOnCheckedChangeListener { _, checked ->
            isSharing = checked
            toggleSharing(checked)
            binding.tvSharingStatus.text = if (checked) "Guests can connect" else "Sharing paused"
        }

        // Price editor
        binding.tvEditPrice.setOnClickListener {
            // In production: show a dialog or bottom sheet to update price
            Toast.makeText(this, "Price editing coming soon", Toast.LENGTH_SHORT).show()
        }

        // Withdraw earnings
        binding.btnWithdraw.setOnClickListener {
            if (pendingEarnings <= 0) {
                Toast.makeText(this, "No earnings to withdraw yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            withdrawEarnings()
        }
    }

    private fun loadStats() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = apiGet("/api/hosts/$hostId/stats")
                withContext(Dispatchers.Main) {
                    pendingEarnings = response.getDouble("pending_earnings")
                    binding.tvEarningsToday.text = "KSh ${response.getDouble("earnings_today").toInt()}"
                    binding.tvActiveGuests.text = "${response.getInt("active_guests")}"
                    binding.tvMinutesSold.text = "${response.getInt("minutes_sold_today")}"
                    binding.tvWithdrawAmount.text = "Withdraw KSh ${pendingEarnings.toInt()} to M-Pesa"
                    isSharing = response.getBoolean("is_sharing")
                    binding.switchSharing.isChecked = isSharing
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvEarningsToday.text = "—"
                }
            }
        }
    }

    private fun toggleSharing(enabled: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                apiPost("/api/hosts/$hostId/sharing", JSONObject().put("enabled", enabled))
                if (enabled) {
                    // Start the foreground service so sessions keep running if app is backgrounded
                    startService(Intent(this@ShareActivity, SessionService::class.java)
                        .putExtra("host_id", hostId))
                } else {
                    stopService(Intent(this@ShareActivity, SessionService::class.java))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ShareActivity, "Failed to toggle sharing", Toast.LENGTH_SHORT).show()
                    binding.switchSharing.isChecked = !enabled // revert
                }
            }
        }
    }

    private fun withdrawEarnings() {
        binding.btnWithdraw.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = apiPost("/api/hosts/$hostId/withdraw", JSONObject())
                withContext(Dispatchers.Main) {
                    val amount = response.getDouble("amount")
                    Toast.makeText(this@ShareActivity, "KSh ${amount.toInt()} sent to your M-Pesa!", Toast.LENGTH_LONG).show()
                    pendingEarnings = 0.0
                    binding.tvWithdrawAmount.text = "Withdraw KSh 0 to M-Pesa"
                    binding.btnWithdraw.isEnabled = true
                    loadStats()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ShareActivity, "Withdrawal failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    binding.btnWithdraw.isEnabled = true
                }
            }
        }
    }

    private fun apiPost(path: String, body: JSONObject): JSONObject {
        val url = URL("${BuildConfig.API_BASE_URL}$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.write(body.toString().toByteArray())
        return JSONObject(conn.inputStream.bufferedReader().readText())
    }

    private fun apiGet(path: String): JSONObject {
        val url = URL("${BuildConfig.API_BASE_URL}$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        return JSONObject(conn.inputStream.bufferedReader().readText())
    }
}
