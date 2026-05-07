package com.gpunch.ui.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
import com.gpunch.api.GpunchApiService
import com.gpunch.models.LoginRequest
import com.gpunch.models.RegisterRequest
import com.gpunch.models.ResendOtpRequest
import com.gpunch.models.VerifyOtpRequest
import kotlinx.coroutines.launch
import retrofit2.Response

sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    data class OtpSent(val email: String) : AuthState()
    data class Verified(val token: String, val userId: String, val name: String, val email: String, val role: String, val androidId: String) : AuthState()
    data class Error(val message: String) : AuthState()
}

class AuthViewModel(private val apiService: GpunchApiService) : ViewModel() {

    private val _authState = MutableLiveData<AuthState>(AuthState.Idle)
    val authState: LiveData<AuthState> = _authState

    fun register(name: String, email: String, androidId: String) {
        _authState.value = AuthState.Loading
        viewModelScope.launch {
            try {
                val response = apiService.register(RegisterRequest(name, email, androidId))
                if (response.isSuccessful && response.body()?.success == true) {
                    _authState.value = AuthState.OtpSent(email)
                } else {
                    val msg = response.messageFromBody() ?: "Registration failed (${response.code()})"
                    _authState.value = AuthState.Error(msg)
                }
            } catch (e: Exception) {
                _authState.value = AuthState.Error("Network error: ${e.message}")
            }
        }
    }

    fun login(email: String, androidId: String) {
        _authState.value = AuthState.Loading
        viewModelScope.launch {
            try {
                val response = apiService.login(LoginRequest(email, androidId))
                if (response.isSuccessful && response.body()?.success == true) {
                    _authState.value = AuthState.OtpSent(email)
                } else {
                    val msg = response.messageFromBody() ?: "Login failed (${response.code()})"
                    _authState.value = AuthState.Error(msg)
                }
            } catch (e: Exception) {
                _authState.value = AuthState.Error("Network error: ${e.message}")
            }
        }
    }

    fun verifyOtp(email: String, otp: String, androidId: String) {
        _authState.value = AuthState.Loading
        viewModelScope.launch {
            try {
                val response = apiService.verifyOtp(VerifyOtpRequest(email, otp, androidId))
                val body = response.body()
                if (response.isSuccessful && body?.success == true && body.token != null && body.user != null) {
                    val u = body.user
                    _authState.value = AuthState.Verified(
                        token = body.token,
                        userId = u.id,
                        name = u.name,
                        email = u.email,
                        role = u.role,
                        androidId = u.androidId ?: androidId
                    )
                } else {
                    val msg = response.messageFromBody() ?: body?.message ?: "OTP verification failed (${response.code()})"
                    _authState.value = AuthState.Error(msg)
                }
            } catch (e: Exception) {
                _authState.value = AuthState.Error("Network error: ${e.message}")
            }
        }
    }

    fun resendOtp(email: String) {
        viewModelScope.launch {
            try {
                apiService.resendOtp(ResendOtpRequest(email))
            } catch (_: Exception) { /* silent */ }
        }
    }

    fun resetState() { _authState.value = AuthState.Idle }

    private fun Response<*>.messageFromBody(): String? {
        body()?.let { value ->
            val field = value.javaClass.methods.firstOrNull { it.name == "getMessage" }
            (field?.invoke(value) as? String)?.let { return it }
        }
        return try {
            val raw = errorBody()?.string() ?: return null
            JsonParser.parseString(raw).asJsonObject.get("message")?.asString
        } catch (_: Exception) {
            null
        }
    }
}
