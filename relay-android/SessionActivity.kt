package app.relay

import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import app.relay.databinding.ActivitySessionBinding

class SessionActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionBinding
    private var countDownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sessionId = intent.getStringExtra("session_id") ?: ""
        val expiresAt = intent.getLongExtra("expires_at", 0)
        val hostName = intent.getStringExtra("host_name") ?: "Relay network"

        binding.tvConnectedTo.text = "Connected to $hostName"
        binding.tvSessionNote.text = "Internet will cut off automatically when time runs out.\nNo password was shared — just leave when done."

        startCountdown(expiresAt)
    }

    private fun startCountdown(expiresAt: Long) {
        val remaining = expiresAt - System.currentTimeMillis()
        if (remaining <= 0) {
            showExpired()
            return
        }

        countDownTimer = object : CountDownTimer(remaining, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val totalSec = millisUntilFinished / 1000
                val hours = totalSec / 3600
                val minutes = (totalSec % 3600) / 60
                val seconds = totalSec % 60

                binding.tvCountdown.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)

                // Warning color when under 5 minutes
                if (totalSec < 300) {
                    binding.tvCountdown.setTextColor(getColor(R.color.amber))
                    binding.tvWarning.visibility = View.VISIBLE
                }
            }

            override fun onFinish() {
                showExpired()
            }
        }.start()
    }

    private fun showExpired() {
        binding.tvCountdown.text = "00:00:00"
        binding.tvCountdown.setTextColor(getColor(R.color.grey_dim))
        binding.tvConnectedTo.text = "Session ended"
        binding.tvSessionNote.text = "Your time ran out. Tap below to buy more."
        binding.btnBuyMore.visibility = View.VISIBLE
        binding.btnBuyMore.setOnClickListener {
            finish() // goes back to ConnectActivity
        }
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        super.onDestroy()
    }
}
