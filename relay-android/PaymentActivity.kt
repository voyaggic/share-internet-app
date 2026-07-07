package app.relay

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.relay.databinding.ActivityPaymentBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class PaymentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPaymentBinding
    private var sessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaymentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val hostId = intent.getStringExtra("host_id") ?: return finish()
        val hostName = intent.getStringExtra("host_name") ?: ""
        val durationMin = intent.getIntExtra("duration_min", 60)
        val amountTotal = intent.getDoubleExtra("amount_total", 0.0)

        binding.tvPaymentTitle.text = "Pay for $hostName"
        binding.tvPaymentSummary.text = "${formatDuration(durationMin)} · KSh ${amountTotal.toInt()}"
        binding.btnBack.setOnClickListener { finish() }

        setupWebView()
        createSession(hostId, durationMin)
    }

    private fun createSession(hostId: String, durationMin: Int) {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Create session record in backend
                val sessionBody = JSONObject().apply {
                    put("host_id", hostId)
                    put("guest_mac", getDeviceMac())
                    put("duration_min", durationMin)
                    put("payment_method", "mobilemoneykenya") // default; let user pick in v2
                }
                val sessionRes = apiPost("/api/sessions", sessionBody)
                sessionId = sessionRes.getString("session_id")
                val checkoutUrl = sessionRes.optString("checkout_url", "")

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    if (checkoutUrl.isNotEmpty()) {
                        // Load Flutterwave checkout in WebView
                        binding.webView.loadUrl(checkoutUrl)
                        binding.webView.visibility = View.VISIBLE
                    } else {
                        // Fallback: payment aggregator returned a direct USSD push
                        binding.tvStatus.text = "Check your phone for an M-Pesa prompt"
                        binding.tvStatus.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = "Payment failed to start: ${e.message}"
                    binding.tvStatus.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false

                // Flutterwave redirects to this URL after payment
                // We set it as the redirect_url in payment.js
                if (url.contains("relay.app/payment-complete")) {
                    val status = request.url?.getQueryParameter("status") ?: "failed"
                    handlePaymentReturn(status)
                    return true
                }
                return false
            }
        }
    }

    private fun handlePaymentReturn(status: String) {
        binding.webView.visibility = View.GONE

        if (status == "successful") {
            binding.tvStatus.text = "Payment confirmed! Connecting you…"
            binding.tvStatus.visibility = View.VISIBLE

            // Start the guest session countdown service
            val expiresAt = System.currentTimeMillis() +
                    (intent.getIntExtra("duration_min", 60) * 60 * 1000L)

            startService(
                Intent(this, SessionService::class.java).apply {
                    putExtra("session_id", sessionId)
                    putExtra("expires_at", expiresAt)
                }
            )

            // Go to session countdown screen
            startActivity(
                Intent(this, SessionActivity::class.java).apply {
                    putExtra("session_id", sessionId)
                    putExtra("expires_at", expiresAt)
                    putExtra("host_name", intent.getStringExtra("host_name"))
                }
            )
            finish()
        } else {
            binding.tvStatus.text = "Payment not completed. Try again."
            binding.tvStatus.visibility = View.VISIBLE
        }
    }

    private fun getDeviceMac(): String {
        // Note: Android 10+ blocks reading real MAC address from app code.
        // For enforcement, the backend uses the MAC the router sees when the
        // device connects to the WiFi — not self-reported by the app.
        // Here we send a session token that maps to the device during checkout.
        return android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID
        )
    }

    private fun formatDuration(min: Int): String = when {
        min < 60 -> "$min minutes"
        min == 1440 -> "24 hours"
        min % 60 == 0 -> "${min / 60} hour${if (min / 60 > 1) "s" else ""}"
        else -> "${min / 60}h ${min % 60}m"
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
}
