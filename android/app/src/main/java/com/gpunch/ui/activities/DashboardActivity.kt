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
import androidx.core.view.GravityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.gpunch.api.GpunchApiService
import com.gpunch.api.RetrofitClient
import com.gpunch.databinding.ActivityDashboardBinding
import com.gpunch.databinding.ItemDashboardHistoryBinding
import com.gpunch.models.PunchRequest
import com.gpunch.models.UserHistoryItemDto
import com.gpunch.services.GeofenceMonitorService
import com.gpunch.ui.viewmodels.DashboardViewModel
import com.gpunch.ui.viewmodels.PunchState
import com.gpunch.utils.GeofenceUtils
import com.gpunch.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.launch

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var viewModel: DashboardViewModel
    private lateinit var sessionManager: SessionManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var apiService: GpunchApiService
    private lateinit var androidId: String

    private var lastKnownLocation: Location? = null
    private var fenceLat = 0.0
    private var fenceLng = 0.0
    private var fenceRadius = 100.0
    private var geofenceConfigLoaded = false
    private var geofenceActive = true

    private val durationHandler = Handler(Looper.getMainLooper())
    private var durationRunnable: Runnable? = null
    private val locationPollHandler = Handler(Looper.getMainLooper())
    private var locationPollRunnable: Runnable? = null
    private var canClockInAtCurrentLocation = false
    private val historyAdapter = DashboardHistoryAdapter()

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

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
        requestNotificationPermissionIfNeeded()
        viewModel.loadPunchStatus()
        viewModel.loadGeofenceConfig()
        viewModel.loadPunchHistory()
        startLocationPolling()
    }

    private fun setupViewModel() {
        apiService = RetrofitClient.getInstance(sessionManager)
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
        viewModel.historyRecords.observe(this) { records ->
            historyAdapter.submitList(records)
            val hasRecords = records.isNotEmpty()
            binding.tvHistoryEmpty.visibility = if (hasRecords) View.GONE else View.VISIBLE
            binding.tvHistoryEmptyFull.visibility = if (hasRecords) View.GONE else View.VISIBLE
            binding.tvHistorySummary.text = if (hasRecords) {
                "${records.size} recent attendance records"
            } else {
                "No attendance records yet"
            }
        }
        viewModel.geofenceConfig.observe(this) { config ->
            if (config != null) {
                fenceLat = config.latitude
                fenceLng = config.longitude
                fenceRadius = config.allowedRadius
                geofenceActive = config.isActive != false
                geofenceConfigLoaded = true
                sessionManager.saveFence(fenceLat, fenceLng, fenceRadius, geofenceActive)
                updateZoneStatus()
            }
        }
    }

    private fun setupUI() {
        binding.tvWelcome.text = "Welcome, ${sessionManager.getUserName()}"
        binding.tvEmail.text = sessionManager.getUserEmail()

        binding.btnClockIn.setOnClickListener { onClockInClicked() }
        binding.btnClockOut.setOnClickListener { onClockOutClicked() }
        binding.btnMenu.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
        binding.btnRefreshLocation.setOnClickListener { checkLocationSettingsThenRefresh() }
        binding.rvHistoryFull.layoutManager = LinearLayoutManager(this)
        binding.rvHistoryFull.adapter = historyAdapter
        updateClockInAvailability()

        setupTabs()
        setupDrawer()

        // Hidden compatibility hooks for existing binding ids.
        if (sessionManager.getUserRole() == "admin") {
            binding.btnAdminPanel.setOnClickListener {
                startActivity(Intent(this, AdminActivity::class.java))
            }
        }
    }

    private fun setupTabs() {
        binding.tabNavigation.addTab(binding.tabNavigation.newTab().setText("Punch"))
        binding.tabNavigation.addTab(binding.tabNavigation.newTab().setText("History"))
        binding.tabNavigation.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> showDashboard(selectTab = false)
                    1 -> showHistory(selectTab = false)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (tab.position == 1) viewModel.loadPunchHistory()
            }
        })
        binding.navigationView.setCheckedItem(com.gpunch.R.id.nav_dashboard)
    }

    private fun setupDrawer() {
        val adminItem = binding.navigationView.menu.findItem(com.gpunch.R.id.nav_admin)
        adminItem.isVisible = sessionManager.getUserRole() == "admin"
        binding.navigationView.setNavigationItemSelectedListener { item ->
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            when (item.itemId) {
                com.gpunch.R.id.nav_dashboard -> {
                    showDashboard()
                    true
                }
                com.gpunch.R.id.nav_history -> {
                    showHistory()
                    true
                }
                com.gpunch.R.id.nav_admin -> {
                    startActivity(Intent(this, AdminActivity::class.java))
                    true
                }
                com.gpunch.R.id.nav_logout -> {
                    logout()
                    true
                }
                else -> false
            }
        }
    }

    private fun showDashboard(selectTab: Boolean = true) {
        binding.dashboardContent.visibility = View.VISIBLE
        binding.historyContent.visibility = View.GONE
        binding.navigationView.setCheckedItem(com.gpunch.R.id.nav_dashboard)
        if (selectTab && binding.tabNavigation.selectedTabPosition != 0) {
            binding.tabNavigation.getTabAt(0)?.select()
        }
    }

    private fun showHistory(selectTab: Boolean = true) {
        binding.dashboardContent.visibility = View.GONE
        binding.historyContent.visibility = View.VISIBLE
        binding.navigationView.setCheckedItem(com.gpunch.R.id.nav_history)
        if (selectTab && binding.tabNavigation.selectedTabPosition != 1) {
            binding.tabNavigation.getTabAt(1)?.select()
        }
        viewModel.loadPunchHistory()
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

        if (geofenceConfigLoaded && geofenceActive) {
            val distance = GeofenceUtils.distanceBetween(fenceLat, fenceLng, loc.latitude, loc.longitude)
            if (distance > fenceRadius) {
                val roundedDistance = distance.toInt()
                val roundedRadius = fenceRadius.toInt()
                Toast.makeText(
                    this,
                    "You are ${roundedDistance}m from the work zone (max ${roundedRadius}m).",
                    Toast.LENGTH_LONG
                ).show()
                viewModel.reportOutOfBounds(
                    loc.latitude,
                    loc.longitude,
                    androidId,
                    roundedDistance,
                    roundedRadius
                )
                return
            }
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
                val clockInMillis = try {
                    state.record.clockInTime?.let { ts ->
                        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                        fmt.parse(ts)?.time
                    } ?: System.currentTimeMillis()
                } catch (_: Exception) {
                    System.currentTimeMillis()
                }
                sessionManager.setIsClockedIn(true, clockInMillis, state.record.id)
                Toast.makeText(this, "Punched in (${state.distance}m from zone)", Toast.LENGTH_SHORT).show()
                geofenceActive = state.serverGeofenceActive
                if (state.serverGeofenceActive) {
                    startGeofenceService(
                        serverFenceLat = state.serverFenceLat,
                        serverFenceLng = state.serverFenceLng,
                        serverRadius = state.serverRadius
                    )
                }
                startLiveTimer()
                viewModel.resetState()
            }
            is PunchState.ClockedOut -> {
                binding.progressBar.visibility = View.GONE
                sessionManager.setIsClockedIn(false)
                val dur = state.durationMinutes
                val msg = if (dur != null) "Punched out. Duration: ${formatMinutes(dur)}" else "Punched out."
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                stopGeofenceService()
                stopLiveTimer()
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
                // Show descriptive error message from API
                val errorMsg = when {
                    state.message.contains("403") || state.message.contains("Unauthorized") ->
                        "Access denied: ${state.message}"
                    state.message.contains("404") ->
                        "Not found: ${state.message}"
                    state.message.contains("500") || state.message.contains("Internal") ->
                        "Server error. Please try again later."
                    state.message.isNotEmpty() ->
                        state.message
                    else ->
                        "An error occurred. Please try again."
                }
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                viewModel.resetState()
            }
            is PunchState.Idle -> {
                binding.progressBar.visibility = View.GONE
                updateClockInAvailability()
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
            startLiveTimer()
        } else {
            binding.tvStatus.text = getString(com.gpunch.R.string.status_clocked_out)
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, com.gpunch.R.color.statusRed))
            binding.btnClockIn.visibility = View.VISIBLE
            binding.btnClockOut.visibility = View.GONE
            stopLiveTimer()
            binding.tvDuration.text = "00:00:00"
            binding.tvDuration.visibility = View.VISIBLE
            updateClockInAvailability()
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

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                    updateZoneStatus()
                } else {
                    binding.tvLocation.text = "Location unavailable"
                    updateClockInAvailability()
                }
            }
            .addOnFailureListener {
                binding.tvLocation.text = getString(com.gpunch.R.string.error_location_unavailable)
                updateClockInAvailability()
            }
    }

    private fun updateZoneStatus() {
        val loc = lastKnownLocation
        if (loc == null) {
            canClockInAtCurrentLocation = false
            binding.workZoneView.setMetrics(null, null)
            updateClockInAvailability()
            return
        }
        if (!geofenceConfigLoaded) {
            canClockInAtCurrentLocation = false
            binding.workZoneView.setMetrics(null, null)
            updateClockInAvailability()
            return
        }
        if (!geofenceActive) {
            canClockInAtCurrentLocation = true
            binding.workZoneView.setInactive()
            updateClockInAvailability()
            return
        }

        val distance = GeofenceUtils.distanceBetween(fenceLat, fenceLng, loc.latitude, loc.longitude)
        canClockInAtCurrentLocation = distance <= fenceRadius
        binding.workZoneView.setMetrics(distance, fenceRadius)
        updateClockInAvailability()
    }

    private fun updateClockInAvailability() {
        if (sessionManager.isClockedIn()) return
        val hasLocation = lastKnownLocation != null
        binding.btnClockIn.isEnabled = hasLocation && geofenceConfigLoaded && (!geofenceActive || canClockInAtCurrentLocation)
    }

    private fun startLocationPolling() {
        stopLocationPolling()
        locationPollRunnable = object : Runnable {
            override fun run() {
                checkLocationSettingsThenRefresh()
                locationPollHandler.postDelayed(this, 15_000L)
            }
        }
        locationPollHandler.postDelayed(locationPollRunnable!!, 15_000L)
    }

    private fun stopLocationPolling() {
        locationPollRunnable?.let { locationPollHandler.removeCallbacks(it) }
        locationPollRunnable = null
    }

    // ─── Geofence Service ─────────────────────────────────────────────────────

    private fun startGeofenceService(
        serverFenceLat: Double? = null,
        serverFenceLng: Double? = null,
        serverRadius: Int? = null
    ) {
        val hasServerCenter = serverFenceLat != null && serverFenceLng != null
        val hasLoadedCenter = geofenceConfigLoaded

        if (!hasServerCenter && !hasLoadedCenter) {
            Toast.makeText(this, "Loading geofence config...", Toast.LENGTH_SHORT).show()
            viewModel.loadGeofenceConfig()
            return
        }

        val latToUse = serverFenceLat ?: fenceLat
        val lngToUse = serverFenceLng ?: fenceLng
        val radiusToUse = when {
            serverRadius != null && serverRadius > 0 -> serverRadius.toDouble()
            geofenceConfigLoaded -> fenceRadius
            else -> {
                Toast.makeText(this, "Geofence radius unavailable.", Toast.LENGTH_SHORT).show()
                return
            }
        }

        fenceLat = latToUse
        fenceLng = lngToUse
        fenceRadius = radiusToUse
        geofenceConfigLoaded = true
        sessionManager.saveFence(latToUse, lngToUse, radiusToUse, true)

        Log.i(
            "DashboardActivity",
            "Starting geofence service with center: ${latToUse},${lngToUse}, radius: ${radiusToUse}m"
        )

        val intent = GeofenceMonitorService.startIntent(this, latToUse, lngToUse, radiusToUse)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start monitoring: ${e.message}", Toast.LENGTH_LONG).show()
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
            .setTitle("Log Out")
            .setMessage(if (sessionManager.isClockedIn()) "GPunch will punch you out before ending the session." else "End this session?")
            .setPositiveButton("Log Out") { _, _ ->
                lifecycleScope.launch { clockOutThenLogout() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun clockOutThenLogout() {
        if (sessionManager.isClockedIn()) {
            val loc = lastKnownLocation
            try {
                val response = apiService.clockOut(
                    PunchRequest(
                        latitude = loc?.latitude ?: 0.0,
                        longitude = loc?.longitude ?: 0.0,
                        androidId = androidId,
                        autoClockOut = false
                    )
                )
                if (!response.isSuccessful && response.code() != 404) {
                    Toast.makeText(this, "Punch out failed. Please try again before logging out.", Toast.LENGTH_LONG).show()
                    return
                }
            } catch (_: Exception) {
                Toast.makeText(this, "Punch out could not be confirmed. Please try again.", Toast.LENGTH_LONG).show()
                return
            }
        }
        stopGeofenceService()
        sessionManager.clearSession()
        RetrofitClient.resetInstance()
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
    }

    private fun startLiveTimer() {
        stopLiveTimer()

        durationRunnable = object : Runnable {
            override fun run() {
                if (sessionManager.isClockedIn()) {
                    val clockInTime = sessionManager.getClockInTime()
                    if (clockInTime > 0) {
                        val elapsed = System.currentTimeMillis() - clockInTime
                        val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
                        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed) % 60
                        val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsed) % 60
                        binding.tvDuration.text = "%02d:%02d:%02d".format(hours, minutes, seconds)
                        binding.tvDuration.visibility = View.VISIBLE
                    }
                    durationHandler.postDelayed(this, 1000L)
                }
            }
        }
        durationHandler.post(durationRunnable!!)
    }

    private fun stopLiveTimer() {
        durationRunnable?.let {
            durationHandler.removeCallbacks(it)
        }
        durationRunnable = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLiveTimer()
        stopLocationPolling()
    }
}

class DashboardHistoryAdapter :
    ListAdapter<UserHistoryItemDto, DashboardHistoryAdapter.VH>(object : DiffUtil.ItemCallback<UserHistoryItemDto>() {
        override fun areItemsTheSame(a: UserHistoryItemDto, b: UserHistoryItemDto) = a.id == b.id
        override fun areContentsTheSame(a: UserHistoryItemDto, b: UserHistoryItemDto) = a == b
    }) {

    inner class VH(val b: ItemDashboardHistoryBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
        VH(ItemDashboardHistoryBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val isActive = item.clockOutTime == null
        holder.b.tvHistoryDate.text = "In: ${formatHistoryTime(item.clockInTime)}"
        holder.b.tvHistoryStatus.text = if (isActive) "Active" else "Completed"
        holder.b.tvHistoryStatus.setTextColor(
            holder.itemView.context.getColor(
                if (isActive) com.gpunch.R.color.statusGreen else com.gpunch.R.color.colorPrimary
            )
        )

        if (isActive) {
            val activeMinutes = item.clockInTime
                ?.let { parseUtcMillis(it) }
                ?.let { ((System.currentTimeMillis() - it) / 60000L).coerceAtLeast(0L).toInt() }
            holder.b.tvHistoryDetails.text = "Out: still punched in"
            holder.b.tvHistoryMeta.text = activeMinutes?.let { "Elapsed: ${formatHistoryMinutes(it)}" } ?: "Elapsed: in progress"
        } else {
            val duration = item.durationMinutes?.let { formatHistoryMinutes(it) } ?: "--"
            val mode = if (item.autoClockOut) "Auto punch out" else "Manual punch out"
            holder.b.tvHistoryDetails.text = "Out: ${formatHistoryTime(item.clockOutTime)}"
            holder.b.tvHistoryMeta.text = "Duration: $duration - $mode"
        }
    }

    private fun formatHistoryMinutes(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    private fun formatHistoryTime(raw: String?): String {
        val millis = raw?.let { parseUtcMillis(it) } ?: return "unavailable"
        return SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.US).format(Date(millis))
    }

    private fun parseUtcMillis(raw: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            try {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(raw)?.time
            } catch (_: Exception) {
                null
            }
        }
    }
}
