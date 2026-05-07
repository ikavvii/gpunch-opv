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
    ): Response<PunchHistoryResponse>

    // ─── Admin / Config ───────────────────────────────────────────────────────

    @GET("api/admin/geofence")
    suspend fun getGeofenceConfig(): Response<GeofenceConfigResponse>

    @POST("api/admin/geofence")
    suspend fun updateGeofenceConfig(@Body request: UpdateGeofenceRequest): Response<GeofenceConfigResponse>

    @GET("api/admin/users")
    suspend fun getUsers(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20
    ): Response<UsersResponse>

    @POST("api/admin/reset-device/{userId}")
    suspend fun resetDevice(@Path("userId") userId: String): Response<GenericResponse>

    @GET("api/admin/audit-logs")
    suspend fun getAuditLogs(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
        @Query("eventType") eventType: String? = null
    ): Response<AuditLogsResponse>

    @GET("api/admin/attendance")
    suspend fun getAttendanceReport(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
        @Query("date") date: String? = null,
        @Query("startDate") startDate: String? = null,
        @Query("endDate") endDate: String? = null,
        @Query("search") search: String? = null
    ): Response<AttendanceReportResponse>

    @GET("api/admin/attendance/summary")
    suspend fun getAttendanceSummary(): Response<AttendanceSummaryResponse>

    @GET("api/admin/attendance/absentees")
    suspend fun getAttendanceAbsentees(
        @Query("date") date: String
    ): Response<AbsenteesResponse>

    // Note: export-csv returns a raw CSV file; handled via OkHttp directly in the ViewModel.

    // ─── Audit ────────────────────────────────────────────────────────

    @POST("api/audit")
    suspend fun logAuditEvent(@Body request: AuditRequest): Response<AuditLogResponse>

    // ─── Export CSV ─────────────────────────────────────────────────

    @GET("api/admin/export-csv")
    suspend fun exportCsv(
        @Query("date") date: String? = null,
        @Query("startDate") startDate: String? = null,
        @Query("endDate") endDate: String? = null
    ): Response<okhttp3.ResponseBody>
}
