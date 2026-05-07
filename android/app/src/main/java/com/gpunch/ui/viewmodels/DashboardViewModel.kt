package com.gpunch.ui.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
import com.gpunch.api.GpunchApiService
import com.gpunch.models.AuditRequest
import com.gpunch.models.AttendanceRecordDto
import com.gpunch.models.PunchRequest
import com.gpunch.models.UserHistoryItemDto
import kotlinx.coroutines.launch
import retrofit2.Response

sealed class PunchState {
    object Idle : PunchState()
    object Loading : PunchState()
    data class ClockedIn(
        val record: AttendanceRecordDto,
        val distance: Int,
        val serverRadius: Int = 0,
        val serverFenceLat: Double? = null,
        val serverFenceLng: Double? = null,
        val serverGeofenceActive: Boolean = true
    ) : PunchState()
    data class ClockedOut(val durationMinutes: Int?) : PunchState()
    data class OutOfBounds(val distance: Int, val allowedRadius: Int) : PunchState()
    data class Error(val message: String) : PunchState()
}

private data class ApiError(val message: String?, val distance: Int?, val allowedRadius: Int?)

class DashboardViewModel(private val apiService: GpunchApiService) : ViewModel() {

    private val _punchState = MutableLiveData<PunchState>(PunchState.Idle)
    val punchState: LiveData<PunchState> = _punchState

    private val _isClockedIn = MutableLiveData(false)
    val isClockedIn: LiveData<Boolean> = _isClockedIn

    private val _geofenceConfig = MutableLiveData<com.gpunch.models.GeofenceConfigDto?>(null)
    val geofenceConfig: LiveData<com.gpunch.models.GeofenceConfigDto?> = _geofenceConfig

    private val _historyRecords = MutableLiveData<List<UserHistoryItemDto>>(emptyList())
    val historyRecords: LiveData<List<UserHistoryItemDto>> = _historyRecords

    fun loadGeofenceConfig() {
        viewModelScope.launch {
            try {
                val response = apiService.getGeofenceConfig()
                if (response.isSuccessful) {
                    _geofenceConfig.postValue(response.body()?.config)
                }
            } catch (_: Exception) { /* silent – user can still attempt clock-in; server validates */ }
        }
    }

    fun loadPunchStatus() {
        viewModelScope.launch {
            try {
                val response = apiService.getPunchStatus()
                if (response.isSuccessful) {
                    val body = response.body()
                    _isClockedIn.postValue(body?.isClockedIn == true)
                }
            } catch (_: Exception) { /* silent */ }
        }
    }

    fun loadPunchHistory() {
        viewModelScope.launch {
            try {
                val response = apiService.getPunchHistory(limit = 50)
                if (response.isSuccessful) {
                    _historyRecords.postValue(response.body()?.records ?: emptyList())
                }
            } catch (_: Exception) { /* history is informational */ }
        }
    }

    fun clockIn(lat: Double, lng: Double, androidId: String) {
        _punchState.value = PunchState.Loading
        viewModelScope.launch {
            try {
                val response = apiService.clockIn(PunchRequest(lat, lng, androidId))
                val body = response.body()
                val error = if (response.isSuccessful) null else response.parseApiError()
                when {
                    response.code() == 403 && error?.distance != null -> {
                        _punchState.postValue(
                            PunchState.OutOfBounds(error.distance, error.allowedRadius ?: 0)
                        )
                    }
                    response.isSuccessful && body?.success == true && body.record != null -> {
                        _isClockedIn.postValue(true)
                        loadPunchHistory()
                        // distance is nested inside record for 201 responses; top-level body.distance
                        // is only populated on 403 out-of-bounds responses.
                        val dist = body.record.distance ?: body.distance ?: 0
                        val serverRadius = body.geofence?.allowedRadius ?: body.allowedRadius ?: 0
                        _punchState.postValue(
                            PunchState.ClockedIn(
                                record = body.record,
                                distance = dist,
                                serverRadius = serverRadius,
                                serverFenceLat = body.geofence?.latitude,
                                serverFenceLng = body.geofence?.longitude,
                                serverGeofenceActive = body.geofence?.isActive != false
                            )
                        )
                    }
                    else -> {
                        // Provide descriptive error messages
                        val msg = when (response.code()) {
                            403 -> error?.message ?: body?.message ?: "Access denied. Please check your credentials."
                            404 -> "Service not found. Please contact support."
                            500 -> "Server error. Please try again later."
                            else -> error?.message ?: body?.message ?: "Punch in failed (${response.code()})"
                        }
                        _punchState.postValue(PunchState.Error(msg))
                    }
                }
            } catch (e: Exception) {
                _punchState.postValue(PunchState.Error("Network error: ${e.message}"))
            }
        }
    }

    fun clockOut(lat: Double, lng: Double, androidId: String, autoClockOut: Boolean = false) {
        _punchState.value = PunchState.Loading
        viewModelScope.launch {
            try {
                val response = apiService.clockOut(PunchRequest(lat, lng, androidId, autoClockOut))
                val body = response.body()
                if (response.isSuccessful && body?.success == true) {
                    _isClockedIn.postValue(false)
                    _punchState.postValue(PunchState.ClockedOut(body.record?.durationMinutes))
                    loadPunchHistory()
                } else {
                    val error = response.parseApiError()
                    _punchState.postValue(PunchState.Error(error.message ?: body?.message ?: "Punch out failed"))
                }
            } catch (e: Exception) {
                _punchState.postValue(PunchState.Error("Network error: ${e.message}"))
            }
        }
    }

    fun reportMockLocation(lat: Double, lng: Double, androidId: String) {
        viewModelScope.launch {
            try {
                apiService.logAuditEvent(
                    AuditRequest(
                        eventType = "MOCK_LOCATION",
                        latitude = lat,
                        longitude = lng,
                        androidId = androidId
                    )
                )
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    fun reportOutOfBounds(
        lat: Double,
        lng: Double,
        androidId: String,
        distance: Int,
        allowedRadius: Int
    ) {
        viewModelScope.launch {
            try {
                apiService.logAuditEvent(
                    AuditRequest(
                        eventType = "OUT_OF_BOUNDS",
                        latitude = lat,
                        longitude = lng,
                        androidId = androidId,
                        metadata = mapOf(
                            "clientDistance" to distance,
                            "allowedRadius" to allowedRadius
                        )
                    )
                )
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    fun resetState() { _punchState.value = PunchState.Idle }

    private fun Response<*>.parseApiError(): ApiError {
        return try {
            val raw = errorBody()?.string()
            if (raw.isNullOrBlank()) return ApiError(null, null, null)
            val json = JsonParser.parseString(raw).asJsonObject
            ApiError(
                message = json.get("message")?.asString,
                distance = json.get("distance")?.asInt,
                allowedRadius = json.get("allowedRadius")?.asInt
            )
        } catch (_: Exception) {
            ApiError(null, null, null)
        }
    }
}
