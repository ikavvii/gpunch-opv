package com.gpunch.ui.activities

import android.Manifest
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.gpunch.api.GpunchApiService
import com.gpunch.api.RetrofitClient
import com.gpunch.databinding.ActivityDashboardBinding
import com.gpunch.services.GeofenceMonitorService
import com.gpunch.ui.viewmodels.DashboardViewModel
import com.gpunch.ui.viewmodels.PunchState
import com.gpunch.utils.GeofenceUtils
import com.gpunch.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var viewModel: DashboardViewModel
    private lateinit var sessionManager: SessionManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var androidId: String

    private var lastKnownLocation: Location? = null
    private var fenceLat = 0.0
    private var fenceLng = 0.0
    private var fenceRadius = 100.0

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) checkLocationSettingsThenRefresh()
            else Toast.makeText(this, getString(com.gpunch.R.string.error_permission_location), Toast.LENGTH_LONG).show()
        }

    // TC-05: launcher for the system location-settings resolution dialog
    private val locationSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                refreshLocation()
            } else {
                Toast.makeText(
                    this,
                    getString(com.gpunch.R.string.error_location_unavailable),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)
        androidId = GeofenceUtils.getAndroidId(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupViewModel()
        setupUI()
        requestLocationPermissionIfNeeded()
        viewModel.loadPunchStatus()
        viewModel.loadGeofenceConfig()
    }

    private fun setupViewModel() {
        val apiService = RetrofitClient.getInstance(sessionManager)
            .create(GpunchApiService::class.java)
        viewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>) =
                    DashboardViewModel(apiService) as T
            }
        ).get(DashboardViewModel::class.java)

        viewModel.isClockedIn.observe(this) { isClockedIn -> updateClockStatus(isClockedIn) }
        viewModel.punchState.observe(this) { state -> handlePunchState(state) }
        viewModel.geofenceConfig.observe(this) { config ->
            if (config != null) {
                fenceLat = config.latitude
                fenceLng = config.longitude
                fenceRadius = config.allowedRadius
            }
        }
    }

    private fun setupUI() {
        binding.tvWelcome.text = "Welcome, ${sessionManager.getUserName()}"
        binding.tvEmail.text = sessionManager.getUserEmail()

        binding.btnClockIn.setOnClickListener { onClockInClicked() }
        binding.btnClockOut.setOnClickListener { onClockOutClicked() }
        binding.btnLogout.setOnClickListener { logout() }
        binding.btnRefreshLocation.setOnClickListener { checkLocationSettingsThenRefresh() }

        // Show Admin Panel card only for admin/faculty role
        if (sessionManager.getUserRole() == "admin") {
            binding.cardAdminPanel.visibility = View.VISIBLE
            binding.btnAdminPanel.setOnClickListener {
                startActivity(Intent(this, AdminActivity::class.java))
            }
        }
    }

    private fun onClockInClicked() {
        val loc = lastKnownLocation
        if (loc == null) {
            refreshLocation()
            Toast.makeText(this, "Getting location…", Toast.LENGTH_SHORT).show()
            return
        }

        if (GeofenceUtils.isMockLocation(this, loc)) {
            Toast.makeText(this, getString(com.gpunch.R.string.error_mock_location), Toast.LENGTH_LONG).show()
            viewModel.reportMockLocation(loc.latitude, loc.longitude, androidId)
            return
        }

        viewModel.clockIn(loc.latitude, loc.longitude, androidId)
    }

    private fun onClockOutClicked() {
        val loc = lastKnownLocation
        val lat = loc?.latitude ?: 0.0
        val lng = loc?.longitude ?: 0.0
        viewModel.clockOut(lat, lng, androidId)
    }

    private fun handlePunchState(state: PunchState) {
        when (state) {
            is PunchState.Loading -> {
                binding.progressBar.visibility = View.VISIBLE
                binding.btnClockIn.isEnabled = false
                binding.btnClockOut.isEnabled = false
            }
            is PunchState.ClockedIn -> {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this, "Clocked in! (${state.distance}m from zone)", Toast.LENGTH_SHORT).show()
                startGeofenceService()
                viewModel.resetState()
            }
            is PunchState.ClockedOut -> {
                binding.progressBar.visibility = View.GONE
                val dur = state.durationMinutes
                val msg = if (dur != null) "Clocked out. Duration: ${formatMinutes(dur)}" else "Clocked out."
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                stopGeofenceService()
                viewModel.resetState()
            }
            is PunchState.OutOfBounds -> {
                binding.progressBar.visibility = View.GONE
                binding.btnClockIn.isEnabled = true
                Toast.makeText(
                    this,
                    "You are ${state.distance}m from the work zone (max ${state.allowedRadius}m).",
                    Toast.LENGTH_LONG
                ).show()
                viewModel.resetState()
            }
            is PunchState.Error -> {
                binding.progressBar.visibility = View.GONE
                binding.btnClockIn.isEnabled = true
                binding.btnClockOut.isEnabled = true
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                viewModel.resetState()
            }
            is PunchState.Idle -> {
                binding.progressBar.visibility = View.GONE
                binding.btnClockIn.isEnabled = true
                binding.btnClockOut.isEnabled = true
            }
        }
    }

    private fun updateClockStatus(isClockedIn: Boolean) {
        if (isClockedIn) {
            binding.tvStatus.text = getString(com.gpunch.R.string.status_clocked_in)
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, com.gpunch.R.color.statusGreen))
            binding.btnClockIn.visibility = View.GONE
            binding.btnClockOut.visibility = View.VISIBLE

            // Show elapsed time
            val clockInTime = sessionManager.getClockInTime()
            if (clockInTime > 0) {
                val elapsed = System.currentTimeMillis() - clockInTime
                val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
                val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed) % 60
                binding.tvDuration.text = "Duration: ${hours}h ${minutes}m"
                binding.tvDuration.visibility = View.VISIBLE
            }
        } else {
            binding.tvStatus.text = getString(com.gpunch.R.string.status_clocked_out)
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, com.gpunch.R.color.statusRed))
            binding.btnClockIn.visibility = View.VISIBLE
            binding.btnClockOut.visibility = View.GONE
            binding.tvDuration.visibility = View.GONE
        }
    }

    // ─── Location ─────────────────────────────────────────────────────────────

    private fun requestLocationPermissionIfNeeded() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            checkLocationSettingsThenRefresh()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    /**
     * TC-05: Verifies that high-accuracy location is enabled before fetching location.
     * If the device has GPS turned off, the system settings dialog is shown so the
     * user can enable it without leaving the app.
     */
    private fun checkLocationSettingsThenRefresh() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 10_000L
        ).build()

        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .build()

        LocationServices.getSettingsClient(this)
            .checkLocationSettings(settingsRequest)
            .addOnSuccessListener {
                // Location settings satisfied – fetch location
                refreshLocation()
            }
            .addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    // TC-05: GPS is off – show the system dialog to enable high-accuracy location
                    try {
                        locationSettingsLauncher.launch(
                            IntentSenderRequest.Builder(exception.resolution).build()
                        )
                    } catch (e: IntentSender.SendIntentException) {
                        Log.e("DashboardActivity", "Could not launch location settings dialog", e)
                        Toast.makeText(
                            this,
                            getString(com.gpunch.R.string.error_location_unavailable),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Toast.makeText(
                        this,
                        getString(com.gpunch.R.string.error_location_unavailable),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }

    private fun refreshLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return

        val cts = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    lastKnownLocation = location
                    binding.tvLocation.text = "Lat: %.5f  Lng: %.5f".format(location.latitude, location.longitude)
                } else {
                    binding.tvLocation.text = "Location unavailable"
                }
            }
            .addOnFailureListener {
                binding.tvLocation.text = getString(com.gpunch.R.string.error_location_unavailable)
            }
    }

    // ─── Geofence Service ─────────────────────────────────────────────────────

    private fun startGeofenceService() {
        val intent = GeofenceMonitorService.startIntent(this, fenceLat, fenceLng, fenceRadius)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopGeofenceService() {
        stopService(GeofenceMonitorService.stopIntent(this))
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun formatMinutes(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    private fun logout() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Logout") { _, _ ->
                stopGeofenceService()
                sessionManager.clearSession()
                RetrofitClient.resetInstance()
                startActivity(
                    Intent(this, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
