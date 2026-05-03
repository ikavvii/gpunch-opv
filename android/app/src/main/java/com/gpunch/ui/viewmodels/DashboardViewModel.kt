package com.gpunch.ui.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gpunch.api.GpunchApiService
import com.gpunch.models.AuditRequest
import com.gpunch.models.AttendanceRecordDto
import com.gpunch.models.PunchRequest
import kotlinx.coroutines.launch

sealed class PunchState {
    object Idle : PunchState()
    object Loading : PunchState()
    data class ClockedIn(val record: AttendanceRecordDto, val distance: Int) : PunchState()
    data class ClockedOut(val durationMinutes: Int?) : PunchState()
    data class OutOfBounds(val distance: Int, val allowedRadius: Int) : PunchState()
    data class Error(val message: String) : PunchState()
}

class DashboardViewModel(private val apiService: GpunchApiService) : ViewModel() {

    private val _punchState = MutableLiveData<PunchState>(PunchState.Idle)
    val punchState: LiveData<PunchState> = _punchState

    private val _isClockedIn = MutableLiveData(false)
    val isClockedIn: LiveData<Boolean> = _isClockedIn

    private val _geofenceConfig = MutableLiveData<com.gpunch.models.GeofenceConfigDto?>(null)
    val geofenceConfig: LiveData<com.gpunch.models.GeofenceConfigDto?> = _geofenceConfig

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

    fun clockIn(lat: Double, lng: Double, androidId: String) {
        _punchState.value = PunchState.Loading
        viewModelScope.launch {
            try {
                val response = apiService.clockIn(PunchRequest(lat, lng, androidId))
                val body = response.body()
                when {
                    response.code() == 403 && body?.distance != null -> {
                        _punchState.postValue(
                            PunchState.OutOfBounds(body.distance, body.allowedRadius ?: 0)
                        )
                    }
                    response.isSuccessful && body?.success == true && body.record != null -> {
                        _isClockedIn.postValue(true)
                        _punchState.postValue(PunchState.ClockedIn(body.record, body.distance ?: 0))
                    }
                    else -> {
                        val msg = body?.message ?: "Clock-in failed (${response.code()})"
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
                } else {
                    _punchState.postValue(PunchState.Error(body?.message ?: "Clock-out failed"))
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

    fun resetState() { _punchState.value = PunchState.Idle }
}
