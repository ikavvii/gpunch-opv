package com.gpunch.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gpunch.api.GpunchApiService
import com.gpunch.api.RetrofitClient
import com.gpunch.models.PunchRequest
import com.gpunch.utils.SessionManager

/**
 * WorkManager worker that retries a pending auto clock-out request.
 *
 * Enqueued by [GeofenceMonitorService] when the clock-out API call fails
 * due to no network connectivity (Airplane Mode, etc.).
 *
 * WorkManager automatically retries with a CONNECTED network constraint,
 * satisfying TC-14: "Payload is saved; sent automatically when Airplane Mode is off."
 *
 * Input data keys:
 *   [KEY_LATITUDE]  – clock-out latitude
 *   [KEY_LONGITUDE] – clock-out longitude
 *   [KEY_ANDROID_ID] – device identifier
 */
class PendingClockOutWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_ANDROID_ID = "androidId"
        const val WORK_TAG = "pending_clock_out"
    }

    override suspend fun doWork(): Result {
        val latitude = inputData.getDouble(KEY_LATITUDE, 0.0)
        val longitude = inputData.getDouble(KEY_LONGITUDE, 0.0)
        val androidId = inputData.getString(KEY_ANDROID_ID) ?: return Result.failure()

        val sessionManager = SessionManager(applicationContext)

        // If the user somehow already clocked out (e.g. manual clock-out succeeded later),
        // there is nothing to do.
        if (!sessionManager.isClockedIn()) return Result.success()

        return try {
            val apiService = RetrofitClient.getInstance(sessionManager)
                .create(GpunchApiService::class.java)

            val response = apiService.clockOut(
                PunchRequest(
                    latitude = latitude,
                    longitude = longitude,
                    androidId = androidId,
                    autoClockOut = true
                )
            )

            if (response.isSuccessful) {
                sessionManager.setIsClockedIn(false)
                Result.success()
            } else {
                // Non-2xx response – likely a server error, retry
                Result.retry()
            }
        } catch (e: Exception) {
            // Network error – WorkManager will retry once network is available
            Result.retry()
        }
    }
}
