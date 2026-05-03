package com.gpunch.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.gpunch.api.GpunchApiService
import com.gpunch.api.RetrofitClient
import com.gpunch.databinding.ActivityLoginBinding
import com.gpunch.ui.viewmodels.AuthState
import com.gpunch.ui.viewmodels.AuthViewModel
import com.gpunch.utils.GeofenceUtils
import com.gpunch.utils.SessionManager

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var viewModel: AuthViewModel
    private lateinit var sessionManager: SessionManager
    private lateinit var androidId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)
        androidId = GeofenceUtils.getAndroidId(this)

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
    }

    private fun setupListeners() {
        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            if (email.isBlank()) {
                binding.tilEmail.error = "Email is required"
                return@setOnClickListener
            }
            binding.tilEmail.error = null
            viewModel.login(email, androidId)
            sessionManager.setPendingEmail(email)
        }

        binding.tvCreateAccount.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun observeState() {
        viewModel.authState.observe(this) { state ->
            when (state) {
                is AuthState.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.btnLogin.isEnabled = false
                }
                is AuthState.OtpSent -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnLogin.isEnabled = true
                    val intent = Intent(this, OtpActivity::class.java).apply {
                        putExtra(OtpActivity.EXTRA_EMAIL, state.email)
                        putExtra(OtpActivity.EXTRA_FLOW, OtpActivity.FLOW_LOGIN)
                    }
                    startActivity(intent)
                    viewModel.resetState()
                }
                is AuthState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnLogin.isEnabled = true
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                    viewModel.resetState()
                }
                else -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnLogin.isEnabled = true
                }
            }
        }
    }
}
