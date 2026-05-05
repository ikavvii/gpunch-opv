package com.gpunch.models

import com.google.gson.annotations.SerializedName

// ─── Request DTOs ─────────────────────────────────────────────────────────────

data class RegisterRequest(
    @SerializedName("name") val name: String,
    @SerializedName("email") val email: String,
    @SerializedName("androidId") val androidId: String
)

data class VerifyOtpRequest(
    @SerializedName("email") val email: String,
    @SerializedName("otp") val otp: String,
    @SerializedName("androidId") val androidId: String
)

data class LoginRequest(
    @SerializedName("email") val email: String,
    @SerializedName("androidId") val androidId: String
)

data class PunchRequest(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("androidId") val androidId: String,
    @SerializedName("autoClockOut") val autoClockOut: Boolean = false
)

data class AuditRequest(
    @SerializedName("eventType") val eventType: String,
    @SerializedName("latitude") val latitude: Double?,
    @SerializedName("longitude") val longitude: Double?,
    @SerializedName("androidId") val androidId: String,
    @SerializedName("metadata") val metadata: Map<String, Any> = emptyMap()
)

data class ResendOtpRequest(
    @SerializedName("email") val email: String
)

// ─── Response DTOs ────────────────────────────────────────────────────────────

data class ApiResponse<T>(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("token") val token: String?,
    @SerializedName("user") val user: UserDto?,
    @SerializedName("record") val record: T?,
    @SerializedName("isClockedIn") val isClockedIn: Boolean?,
    @SerializedName("distance") val distance: Int?,
    @SerializedName("allowedRadius") val allowedRadius: Int?
)

data class UserDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("email") val email: String,
    @SerializedName("role") val role: String,
    @SerializedName("androidId") val androidId: String?
)

data class AttendanceRecordDto(
    @SerializedName("id") val id: String,
    @SerializedName("clockInTime") val clockInTime: String?,
    @SerializedName("clockOutTime") val clockOutTime: String?,
    @SerializedName("durationMinutes") val durationMinutes: Int?,
    @SerializedName("distance") val distance: Int?
)

data class GeofenceConfigDto(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("allowedRadius") val allowedRadius: Double,
    @SerializedName("allowedDomain") val allowedDomain: String
)

data class PunchStatusResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("isClockedIn") val isClockedIn: Boolean,
    @SerializedName("record") val record: AttendanceRecordDto?
)

data class GeofenceConfigResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("config") val config: GeofenceConfigDto?
)

data class GenericResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?
)

data class AuditLogResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("logId") val logId: String?
)

// ─── Admin Request / Response DTOs ───────────────────────────────────────────

data class UpdateGeofenceRequest(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("allowedRadius") val allowedRadius: Int,
    @SerializedName("allowedDomain") val allowedDomain: String
)

data class UserItemDto(
    @SerializedName("_id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("email") val email: String,
    @SerializedName("role") val role: String,
    @SerializedName("isVerified") val isVerified: Boolean,
    @SerializedName("isActive") val isActive: Boolean,
    @SerializedName("androidId") val androidId: String?
)

data class UsersResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("page") val page: Int,
    @SerializedName("total") val total: Int,
    @SerializedName("users") val users: List<UserItemDto>
)

data class AuditLogItemDto(
    @SerializedName("_id") val id: String,
    @SerializedName("eventType") val eventType: String,
    @SerializedName("userId") val userId: String?,
    @SerializedName("androidId") val androidId: String?,
    @SerializedName("latitude") val latitude: Double?,
    @SerializedName("longitude") val longitude: Double?,
    @SerializedName("metadata") val metadata: Map<String, Any>?,
    @SerializedName("createdAt") val createdAt: String
)

data class AuditLogsResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("page") val page: Int,
    @SerializedName("total") val total: Int,
    @SerializedName("logs") val logs: List<AuditLogItemDto>
)
