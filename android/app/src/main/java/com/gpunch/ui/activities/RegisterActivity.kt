package com.gpunch.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.gpunch.api.GpunchApiService
import com.gpunch.api.RetrofitClient
import com.gpunch.databinding.ActivityRegisterBinding
import com.gpunch.ui.viewmodels.AuthState
import com.gpunch.ui.viewmodels.AuthViewModel
import com.gpunch.utils.GeofenceUtils
import com.gpunch.utils.SessionManager

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var viewModel: AuthViewModel
    private lateinit var sessionManager: SessionManager
    private lateinit var androidId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
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
        binding.btnRegister.setOnClickListener {
            val name = binding.etName.text.toString().trim()
            val email = binding.etEmail.text.toString().trim()

            var valid = true
            if (name.isBlank()) {
                binding.tilName.error = "Name is required"; valid = false
            } else binding.tilName.error = null

            if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                binding.tilEmail.error = "Valid institutional email required"; valid = false
            } else binding.tilEmail.error = null

            if (!valid) return@setOnClickListener

            sessionManager.setPendingEmail(email)
            viewModel.register(name, email, androidId)
        }

        binding.tvAlreadyHaveAccount.setOnClickListener {
            finish() // go back to LoginActivity
        }
    }

    private fun observeState() {
        viewModel.authState.observe(this) { state ->
            when (state) {
                is AuthState.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.btnRegister.isEnabled = false
                }
                is AuthState.OtpSent -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnRegister.isEnabled = true
                    Toast.makeText(this, "OTP sent to ${state.email}", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this, OtpActivity::class.java).apply {
                        putExtra(OtpActivity.EXTRA_EMAIL, state.email)
                        putExtra(OtpActivity.EXTRA_FLOW, OtpActivity.FLOW_REGISTER)
                    }
                    startActivity(intent)
                    viewModel.resetState()
                }
                is AuthState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnRegister.isEnabled = true
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                    viewModel.resetState()
                }
                else -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnRegister.isEnabled = true
                }
            }
        }
    }
}
