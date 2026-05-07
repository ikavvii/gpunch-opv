package com.gpunch.ui.viewmodels

import android.app.Application
import android.os.Environment
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gpunch.api.RetrofitClient
import com.gpunch.models.*
import com.gpunch.utils.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class AdminUiEvent {
    data class Success(val message: String) : AdminUiEvent()
    data class Error(val message: String) : AdminUiEvent()
}

class AdminViewModel(application: Application) : AndroidViewModel(application) {

    private val api = RetrofitClient.getInstance(SessionManager(application))
        .create(com.gpunch.api.GpunchApiService::class.java)

    // ─── Geofence ─────────────────────────────────────────────────────

    private val _geofenceConfig = MutableLiveData<GeofenceConfigDto?>()
    val geofenceConfig: LiveData<GeofenceConfigDto?> = _geofenceConfig

    // ─── Users ────────────────────────────────────────────────────────

    private val _users = MutableLiveData<List<UserItemDto>>()
    val users: LiveData<List<UserItemDto>> = _users

    private val _usersTotal = MutableLiveData<Int>()
    val usersTotal: LiveData<Int> = _usersTotal

    // ─── Audit Logs ───────────────────────────────────────────────────

    private val _auditLogs = MutableLiveData<List<AuditLogItemDto>>()
    val auditLogs: LiveData<List<AuditLogItemDto>> = _auditLogs

    private val _auditTotal = MutableLiveData<Int>()
    val auditTotal: LiveData<Int> = _auditTotal

    // ─── Attendance Report ───────────────────────────────────────────

    private val _attendanceRecords = MutableLiveData<List<AttendanceItemDto>>()
    val attendanceRecords: LiveData<List<AttendanceItemDto>> = _attendanceRecords

    private val _attendanceTotal = MutableLiveData<Int>()
    val attendanceTotal: LiveData<Int> = _attendanceTotal

    private val _attendanceSummary = MutableLiveData<AttendanceSummaryResponse?>()
    val attendanceSummary: LiveData<AttendanceSummaryResponse?> = _attendanceSummary

    private val _attendanceAbsentees = MutableLiveData<List<UserItemDto>>(emptyList())
    val attendanceAbsentees: LiveData<List<UserItemDto>> = _attendanceAbsentees

    private val _attendanceAbsenteesTotal = MutableLiveData(0)
    val attendanceAbsenteesTotal: LiveData<Int> = _attendanceAbsenteesTotal

    // ─── Loading / Event ──────────────────────────────────────────────

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

    fun updateGeofenceConfig(lat: Double, lng: Double, radius: Int, domainsText: String, isActive: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val domains = domainsText.split(',')
                    .map { it.trim().removePrefix("@").lowercase(Locale.US) }
                    .filter { it.isNotEmpty() }
                    .distinct()
                val resp = api.updateGeofenceConfig(
                    UpdateGeofenceRequest(lat, lng, radius, domains.firstOrNull().orEmpty(), domains, isActive)
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

    // ─── Attendance Report operations ──────────────────────────────

    fun loadAttendanceReport(
        page: Int = 1,
        date: String? = null,
        startDate: String? = null,
        endDate: String? = null,
        search: String? = null
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val resp = api.getAttendanceReport(
                    page = page,
                    date = date,
                    startDate = startDate,
                    endDate = endDate,
                    search = search
                )
                if (resp.isSuccessful) {
                    val body = resp.body()
                    _attendanceRecords.value = body?.records ?: emptyList()
                    _attendanceTotal.value = body?.total ?: 0
                } else {
                    _event.value = AdminUiEvent.Error("Failed to load attendance report")
                }
            } catch (e: Exception) {
                _event.value = AdminUiEvent.Error(e.message ?: "Network error")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadAttendanceSummary() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val resp = api.getAttendanceSummary()
                if (resp.isSuccessful) {
                    _attendanceSummary.value = resp.body()
                } else {
                    _event.value = AdminUiEvent.Error("Failed to load attendance summary")
                }
            } catch (e: Exception) {
                _event.value = AdminUiEvent.Error(e.message ?: "Network error")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadAttendanceAbsentees(date: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val resp = api.getAttendanceAbsentees(date)
                if (resp.isSuccessful) {
                    val body = resp.body()
                    _attendanceAbsentees.value = body?.users ?: emptyList()
                    _attendanceAbsenteesTotal.value = body?.total ?: 0
                } else {
                    _event.value = AdminUiEvent.Error("Failed to load absentees")
                }
            } catch (e: Exception) {
                _event.value = AdminUiEvent.Error(e.message ?: "Network error")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun exportCsv(
        date: String? = null,
        startDate: String? = null,
        endDate: String? = null,
        callback: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val response = api.exportCsv(date = date, startDate = startDate, endDate = endDate)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    val path = withContext(Dispatchers.IO) {
                        val dir = getApplication<Application>()
                            .getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                            ?: getApplication<Application>().cacheDir
                        if (!dir.exists()) dir.mkdirs()
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                        val file = File(dir, "gpunch-attendance-$stamp.csv")
                        file.outputStream().use { out -> body.byteStream().copyTo(out) }
                        file.absolutePath
                    }
                    callback(true, "CSV exported to $path")
                } else {
                    callback(false, "Export failed (${response.code()})")
                }
            } catch (e: Exception) {
                callback(false, e.message ?: "Export error")
            }
        }
    }

    fun clearEvent() {
        _event.value = null
    }
}
