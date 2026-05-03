package com.gpunch.api

import com.gpunch.models.*
import retrofit2.Response
import retrofit2.http.*

interface GpunchApiService {

    // ─── Auth ─────────────────────────────────────────────────────────────────

    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<GenericResponse>

    @POST("api/auth/verify-otp")
    suspend fun verifyOtp(@Body request: VerifyOtpRequest): Response<ApiResponse<AttendanceRecordDto>>

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<GenericResponse>

    @POST("api/auth/resend-otp")
    suspend fun resendOtp(@Body request: ResendOtpRequest): Response<GenericResponse>

    // ─── Punch ────────────────────────────────────────────────────────────────

    @POST("api/punch/in")
    suspend fun clockIn(@Body request: PunchRequest): Response<ApiResponse<AttendanceRecordDto>>

    @POST("api/punch/out")
    suspend fun clockOut(@Body request: PunchRequest): Response<ApiResponse<AttendanceRecordDto>>

    @GET("api/punch/status")
    suspend fun getPunchStatus(): Response<PunchStatusResponse>

    @GET("api/punch/history")
    suspend fun getPunchHistory(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20
    ): Response<GenericResponse>

    // ─── Admin / Config ───────────────────────────────────────────────────────

    @GET("api/admin/geofence")
    suspend fun getGeofenceConfig(): Response<GeofenceConfigResponse>

    // ─── Audit ────────────────────────────────────────────────────────────────

    @POST("api/audit")
    suspend fun logAuditEvent(@Body request: AuditRequest): Response<AuditLogResponse>
}
