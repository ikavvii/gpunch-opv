package com.gpunch.ui.activities

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.gpunch.api.GpunchApiService
import com.gpunch.api.RetrofitClient
import com.gpunch.databinding.ActivityOtpBinding
import com.gpunch.ui.viewmodels.AuthState
import com.gpunch.ui.viewmodels.AuthViewModel
import com.gpunch.utils.GeofenceUtils
import com.gpunch.utils.SessionManager

class OtpActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EMAIL = "extra_email"
        const val EXTRA_FLOW = "extra_flow"
        const val FLOW_REGISTER = "register"
        const val FLOW_LOGIN = "login"
    }

    private lateinit var binding: ActivityOtpBinding
    private lateinit var viewModel: AuthViewModel
    private lateinit var sessionManager: SessionManager
    private lateinit var androidId: String
    private var email = ""
    private var flow = FLOW_LOGIN
    private var resendTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOtpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        email = intent.getStringExtra(EXTRA_EMAIL) ?: ""
        flow = intent.getStringExtra(EXTRA_FLOW) ?: FLOW_LOGIN
        sessionManager = SessionManager(this)
        androidId = GeofenceUtils.getAndroidId(this)

        binding.tvEmailHint.text = "Enter the OTP sent to $email"

        val apiService = RetrofitClient.getInstance()
            .create(GpunchApiService::class.java)
        viewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>) =
                    AuthViewModel(apiService) as T
            }
        ).get(AuthViewModel::class.java)

        observeState()
        setupListeners()
        startResendTimer()
    }

    private fun setupListeners() {
        binding.btnVerify.setOnClickListener {
            val otp = binding.etOtp.text.toString().trim()
            if (otp.length != 6) {
                binding.tilOtp.error = "Enter the 6-digit OTP"
                return@setOnClickListener
            }
            binding.tilOtp.error = null
            viewModel.verifyOtp(email, otp, androidId)
        }

        binding.tvResendOtp.setOnClickListener {
            viewModel.resendOtp(email)
            Toast.makeText(this, "OTP resent to $email", Toast.LENGTH_SHORT).show()
            startResendTimer()
        }
    }

    private fun observeState() {
        viewModel.authState.observe(this) { state ->
            when (state) {
                is AuthState.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.btnVerify.isEnabled = false
                }
                is AuthState.Verified -> {
                    binding.progressBar.visibility = View.GONE
                    sessionManager.saveSession(
                        token = state.token,
                        userId = state.userId,
                        name = state.name,
                        email = state.email,
                        role = state.role,
                        androidId = state.androidId
                    )
                    RetrofitClient.resetInstance()
                    startActivity(Intent(this, DashboardActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    viewModel.resetState()
                }
                is AuthState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnVerify.isEnabled = true
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                    viewModel.resetState()
                }
                else -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnVerify.isEnabled = true
                }
            }
        }
    }

    private fun startResendTimer() {
        binding.tvResendOtp.isEnabled = false
        resendTimer?.cancel()
        resendTimer = object : CountDownTimer(60_000, 1_000) {
            override fun onTick(ms: Long) {
                binding.tvResendOtp.text = "Resend OTP in ${ms / 1000}s"
            }
            override fun onFinish() {
                binding.tvResendOtp.isEnabled = true
                binding.tvResendOtp.text = getString(com.gpunch.R.string.btn_resend_otp)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        resendTimer?.cancel()
    }
}
