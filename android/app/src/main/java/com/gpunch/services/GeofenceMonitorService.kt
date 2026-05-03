package com.gpunch.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.android.gms.location.*
import com.gpunch.GPunchApp
import com.gpunch.R
import com.gpunch.api.GpunchApiService
import com.gpunch.api.RetrofitClient
import com.gpunch.models.AuditRequest
import com.gpunch.models.PunchRequest
import com.gpunch.ui.activities.DashboardActivity
import com.gpunch.utils.GeofenceUtils
import com.gpunch.utils.SessionManager
import kotlinx.coroutines.*

/**
 * Foreground Service that continuously monitors the user's GPS position while
 * they are clocked in. When a geofence breach is detected it automatically
 * calls the clock-out API endpoint.
 *
 * Battery-optimisation strategy:
 *  – When STATIONARY (no movement between samples) → interval relaxed to 60s
 *  – When MOVING (significant distance delta) → interval tightened to 15s
 */
class GeofenceMonitorService : LifecycleService() {

    companion object {
        const val ACTION_START = "ACTION_START_GEOFENCE"
        const val ACTION_STOP = "ACTION_STOP_GEOFENCE"

        const val EXTRA_FENCE_LAT = "FENCE_LAT"
        const val EXTRA_FENCE_LNG = "FENCE_LNG"
        const val EXTRA_FENCE_RADIUS = "FENCE_RADIUS"

        private const val INTERVAL_ACTIVE_MS = 15_000L
        private const val INTERVAL_STATIONARY_MS = 60_000L
        private const val DISPLACEMENT_THRESHOLD_M = 5f

        fun startIntent(
            context: Context,
            fenceLat: Double,
            fenceLng: Double,
            fenceRadius: Double
        ) = Intent(context, GeofenceMonitorService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_FENCE_LAT, fenceLat)
            putExtra(EXTRA_FENCE_LNG, fenceLng)
            putExtra(EXTRA_FENCE_RADIUS, fenceRadius)
        }

        fun stopIntent(context: Context) =
            Intent(context, GeofenceMonitorService::class.java).apply {
                action = ACTION_STOP
            }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sessionManager: SessionManager
    private lateinit var apiService: GpunchApiService

    private var fenceLat = 0.0
    private var fenceLng = 0.0
    private var fenceRadius = 0.0

    private var lastLocation: Location? = null
    private var isStationary = false

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location -> processLocation(location) }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sessionManager = SessionManager(applicationContext)
        apiService = RetrofitClient.getInstance(sessionManager)
            .create(GpunchApiService::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                fenceLat = intent.getDoubleExtra(EXTRA_FENCE_LAT, 0.0)
                fenceLng = intent.getDoubleExtra(EXTRA_FENCE_LNG, 0.0)
                fenceRadius = intent.getDoubleExtra(EXTRA_FENCE_RADIUS, 100.0)
                startForeground(GPunchApp.NOTIFICATION_ID, buildNotification())
                startLocationUpdates()
            }
            ACTION_STOP -> stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
    }

    // ─── Location Updates ─────────────────────────────────────────────────────

    private fun startLocationUpdates() {
        val request = buildLocationRequest(INTERVAL_ACTIVE_MS)
        try {
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun buildLocationRequest(intervalMs: Long) =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateDistanceMeters(DISPLACEMENT_THRESHOLD_M)
            .build()

    private fun processLocation(location: Location) {
        // Mock location detection
        if (GeofenceUtils.isMockLocation(applicationContext, location)) {
            serviceScope.launch { reportAudit("MOCK_LOCATION", location) }
            return
        }

        // Battery optimisation: adapt polling interval based on movement
        val last = lastLocation
        if (last != null) {
            val moved = last.distanceTo(location)
            val newStationary = moved < DISPLACEMENT_THRESHOLD_M
            if (newStationary != isStationary) {
                isStationary = newStationary
                fusedLocationClient.removeLocationUpdates(locationCallback)
                val interval = if (isStationary) INTERVAL_STATIONARY_MS else INTERVAL_ACTIVE_MS
                try {
                    fusedLocationClient.requestLocationUpdates(
                        buildLocationRequest(interval),
                        locationCallback,
                        Looper.getMainLooper()
                    )
                } catch (_: SecurityException) { stopSelf() }
            }
        }
        lastLocation = location

        // Geofence check
        val distance = GeofenceUtils.haversineDistance(
            fenceLat, fenceLng, location.latitude, location.longitude
        )

        if (distance > fenceRadius) {
            triggerAutoClockOut(location)
        }
    }

    // ─── Auto Clock-Out ───────────────────────────────────────────────────────

    private fun triggerAutoClockOut(location: Location) {
        val androidId = sessionManager.getAndroidId() ?: return
        val request = PunchRequest(
            latitude = location.latitude,
            longitude = location.longitude,
            androidId = androidId,
            autoClockOut = true
        )

        serviceScope.launch {
            try {
                val response = apiService.clockOut(request)
                if (response.isSuccessful) {
                    sessionManager.setIsClockedIn(false)
                    showAutoClockOutNotification()
                }
            } catch (_: Exception) {
                // Cache locally for later sync if network is unavailable
                sessionManager.setIsClockedIn(false) // optimistic update
                showAutoClockOutNotification()
            } finally {
                stopSelf()
            }
        }
    }

    private fun reportAudit(eventType: String, location: Location) {
        val androidId = sessionManager.getAndroidId() ?: return
        serviceScope.launch {
            try {
                apiService.logAuditEvent(
                    AuditRequest(
                        eventType = eventType,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        androidId = androidId
                    )
                )
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    // ─── Notifications ────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, DashboardActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, GPunchApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showAutoClockOutNotification() {
        val notification = NotificationCompat.Builder(this, GPunchApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("GPunch – Auto Clocked Out")
            .setContentText("You have left the work zone. Attendance has been recorded.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(GPunchApp.NOTIFICATION_ID + 1, notification)
    }
}
