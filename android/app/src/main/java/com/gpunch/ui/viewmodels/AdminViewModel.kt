package com.gpunch.ui.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gpunch.api.RetrofitClient
import com.gpunch.models.*
import kotlinx.coroutines.launch

sealed class AdminUiEvent {
    data class Success(val message: String) : AdminUiEvent()
    data class Error(val message: String) : AdminUiEvent()
}

class AdminViewModel : ViewModel() {

    private val api = RetrofitClient.getInstance().create(com.gpunch.api.GpunchApiService::class.java)

    // ─── Geofence ─────────────────────────────────────────────────────────────

    private val _geofenceConfig = MutableLiveData<GeofenceConfigDto?>()
    val geofenceConfig: LiveData<GeofenceConfigDto?> = _geofenceConfig

    // ─── Users ────────────────────────────────────────────────────────────────

    private val _users = MutableLiveData<List<UserItemDto>>()
    val users: LiveData<List<UserItemDto>> = _users

    private val _usersTotal = MutableLiveData<Int>()
    val usersTotal: LiveData<Int> = _usersTotal

    // ─── Audit Logs ───────────────────────────────────────────────────────────

    private val _auditLogs = MutableLiveData<List<AuditLogItemDto>>()
    val auditLogs: LiveData<List<AuditLogItemDto>> = _auditLogs

    private val _auditTotal = MutableLiveData<Int>()
    val auditTotal: LiveData<Int> = _auditTotal

    // ─── Loading / Event ──────────────────────────────────────────────────────

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _event = MutableLiveData<AdminUiEvent?>()
    val event: LiveData<AdminUiEvent?> = _event

    // ─── Geofence operations ──────────────────────────────────────────────────

    fun loadGeofenceConfig() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val resp = api.getGeofenceConfig()
                if (resp.isSuccessful) {
                    _geofenceConfig.value = resp.body()?.config
                } else {
                    _event.value = AdminUiEvent.Error("Failed to load geofence config")
                }
            } catch (e: Exception) {
                _event.value = AdminUiEvent.Error(e.message ?: "Network error")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateGeofenceConfig(lat: Double, lng: Double, radius: Int, domain: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val resp = api.updateGeofenceConfig(
                    UpdateGeofenceRequest(lat, lng, radius, domain)
                )
                if (resp.isSuccessful && resp.body()?.success == true) {
                    _geofenceConfig.value = resp.body()?.config
                    _event.value = AdminUiEvent.Success("Geofence configuration updated.")
                } else {
                    _event.value = AdminUiEvent.Error("Update failed")
                }
            } catch (e: Exception) {
                _event.value = AdminUiEvent.Error(e.message ?: "Network error")
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ─── User operations ──────────────────────────────────────────────────────

    fun loadUsers(page: Int = 1) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val resp = api.getUsers(page = page)
                if (resp.isSuccessful) {
                    val body = resp.body()
                    _users.value = body?.users ?: emptyList()
                    _usersTotal.value = body?.total ?: 0
                } else {
                    _event.value = AdminUiEvent.Error("Failed to load users")
                }
            } catch (e: Exception) {
                _event.value = AdminUiEvent.Error(e.message ?: "Network error")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun resetDevice(userId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val resp = api.resetDevice(userId)
                if (resp.isSuccessful && resp.body()?.success == true) {
                    _event.value = AdminUiEvent.Success(
                        resp.body()?.message ?: "Device binding cleared."
                    )
                    loadUsers()
                } else {
                    _event.value = AdminUiEvent.Error(
                        resp.body()?.message ?: "Reset failed"
                    )
                }
            } catch (e: Exception) {
                _event.value = AdminUiEvent.Error(e.message ?: "Network error")
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ─── Audit Log operations ─────────────────────────────────────────────────

    fun loadAuditLogs(page: Int = 1, eventType: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val resp = api.getAuditLogs(page = page, eventType = eventType)
                if (resp.isSuccessful) {
                    val body = resp.body()
                    _auditLogs.value = body?.logs ?: emptyList()
                    _auditTotal.value = body?.total ?: 0
                } else {
                    _event.value = AdminUiEvent.Error("Failed to load audit logs")
                }
            } catch (e: Exception) {
                _event.value = AdminUiEvent.Error(e.message ?: "Network error")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearEvent() {
        _event.value = null
    }
}
