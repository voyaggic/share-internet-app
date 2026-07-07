package app.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import app.relay.databinding.ActivityConnectBinding
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class RelayNetwork(
    val hostId: String,
    val displayName: String,
    val ssid: String,
    val bssid: String,          // hardware address — the anti-spoof anchor
    val pricePerMin: Double,    // host's set price
    val isVerified: Boolean,    // matched in backend BSSID registry
    val distanceLabel: String,  // estimated from signal strength
    val activeGuests: Int
)

class ConnectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectBinding
    private lateinit var wifiManager: WifiManager
    private var selectedNetwork: RelayNetwork? = null
    private var selectedMinutes: Int = 60

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (success) processScanResults()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        binding.btnBack.setOnClickListener { finish() }
        binding.btnScan.setOnClickListener { startWifiScan() }

        setupPricingSlider()
        startWifiScan()
    }

    // ---------------------------------------------------------------
    // WiFi scan → extract SSIDs + BSSIDs → verify against backend
    // ---------------------------------------------------------------
    private fun startWifiScan() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "Scanning nearby networks…"

        registerReceiver(
            wifiScanReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )
        val started = wifiManager.startScan()
        if (!started) processScanResults() // use cached results if scan fails
    }

    private fun processScanResults() {
        val scanResults: List<ScanResult> = wifiManager.scanResults

        // Build the list we send to backend for BSSID verification
        val scanned = scanResults.map {
            mapOf("ssid" to it.SSID, "bssid" to it.BSSID, "level" to it.level)
        }

        lifecycleScope.launch {
            verifyNetworksWithBackend(scanned, scanResults)
        }
    }

    private suspend fun verifyNetworksWithBackend(
        scanned: List<Map<String, Any>>,
        rawResults: List<ScanResult>
    ) {
        try {
            val body = JSONObject().put("scanned", JSONArray(scanned.map { JSONObject(it) }))
            val response = apiPost("/api/networks/verify", body)
            val verified = response.getJSONArray("verified")

            val networks = mutableListOf<RelayNetwork>()

            // Add verified Relay networks first (green badges)
            for (i in 0 until verified.length()) {
                val v = verified.getJSONObject(i)
                val raw = rawResults.find { it.BSSID == v.getString("bssid") }
                networks.add(
                    RelayNetwork(
                        hostId = v.getString("id"),
                        displayName = v.getString("display_name"),
                        ssid = v.getString("ssid"),
                        bssid = v.getString("bssid"),
                        pricePerMin = v.getDouble("price_per_min"),
                        isVerified = true,
                        distanceLabel = estimateDistance(raw?.level ?: -80),
                        activeGuests = v.optInt("active_guests", 0)
                    )
                )
            }

            // Unverified networks shown greyed out — user can see them but can't pay
            rawResults
                .filter { r -> networks.none { it.bssid == r.BSSID } }
                .take(5)
                .forEach { r ->
                    networks.add(
                        RelayNetwork(
                            hostId = "",
                            displayName = r.SSID.ifEmpty { "Hidden network" },
                            ssid = r.SSID,
                            bssid = r.BSSID,
                            pricePerMin = 0.0,
                            isVerified = false,
                            distanceLabel = estimateDistance(r.level),
                            activeGuests = 0
                        )
                    )
                }

            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.text = "${networks.count { it.isVerified }} verified Relay network(s) nearby"
                setupNetworkList(networks)
            }
        } catch (e: Exception) {
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.text = "Scan failed — check internet connection"
                Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun estimateDistance(level: Int): String = when {
        level >= -50 -> "Very close"
        level >= -65 -> "Nearby"
        level >= -75 -> "~50m away"
        else -> "Weak signal"
    }

    // ---------------------------------------------------------------
    // Network list — verified ones in cyan, unverified greyed
    // ---------------------------------------------------------------
    private fun setupNetworkList(networks: List<RelayNetwork>) {
        binding.recyclerNetworks.layoutManager = LinearLayoutManager(this)
        binding.recyclerNetworks.adapter = NetworkAdapter(networks) { selected ->
            selectedNetwork = selected
            updatePricingCard(selected)
        }
    }

    // ---------------------------------------------------------------
    // Pricing calculator — slider 15 min to 24 hours
    // ---------------------------------------------------------------
    private fun setupPricingSlider() {
        binding.slider.valueFrom = 15f
        binding.slider.valueTo = 1440f
        binding.slider.value = 60f
        binding.slider.stepSize = 1f

        // Snap points
        binding.btn15m.setOnClickListener { binding.slider.value = 15f }
        binding.btn30m.setOnClickListener { binding.slider.value = 30f }
        binding.btn1h.setOnClickListener  { binding.slider.value = 60f }
        binding.btn3h.setOnClickListener  { binding.slider.value = 180f }
        binding.btn24h.setOnClickListener { binding.slider.value = 1440f }

        binding.slider.addOnChangeListener { _, value, _ ->
            selectedMinutes = value.toInt()
            updatePriceDisplay()
        }

        updatePriceDisplay()
    }

    private fun updatePricingCard(network: RelayNetwork) {
        binding.tvNetworkSelected.text = "Buying time on: ${network.displayName}"
        binding.layoutPricing.visibility = View.VISIBLE
        updatePriceDisplay()
    }

    private fun updatePriceDisplay() {
        val net = selectedNetwork ?: return
        val total = net.pricePerMin * selectedMinutes
        binding.tvDuration.text = formatDuration(selectedMinutes)
        binding.tvTotal.text = "KSh ${String.format("%.0f", total)}"
        binding.btnPay.isEnabled = true
        binding.btnPay.setOnClickListener { initiatePayment(net, selectedMinutes, total) }
    }

    private fun formatDuration(min: Int): String = when {
        min < 60 -> "$min minutes"
        min == 1440 -> "24 hours"
        min % 60 == 0 -> "${min / 60} hour${if (min / 60 > 1) "s" else ""}"
        else -> "${min / 60}h ${min % 60}m"
    }

    // ---------------------------------------------------------------
    // Payment — open PaymentActivity with session details
    // ---------------------------------------------------------------
    private fun initiatePayment(network: RelayNetwork, durationMin: Int, total: Double) {
        val intent = Intent(this, PaymentActivity::class.java).apply {
            putExtra("host_id", network.hostId)
            putExtra("host_name", network.displayName)
            putExtra("duration_min", durationMin)
            putExtra("amount_total", total)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(wifiScanReceiver) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------
    // Simple API helper (runs on IO thread via coroutine)
    // ---------------------------------------------------------------
    private fun apiPost(path: String, body: JSONObject): JSONObject {
        val url = URL("${BuildConfig.API_BASE_URL}$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.write(body.toString().toByteArray())
        val response = conn.inputStream.bufferedReader().readText()
        return JSONObject(response)
    }
}
