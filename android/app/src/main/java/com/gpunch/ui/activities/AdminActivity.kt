package com.gpunch.ui.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import com.gpunch.api.RetrofitClient
import com.gpunch.databinding.ActivityAdminBinding
import com.gpunch.databinding.FragmentAdminAuditBinding
import com.gpunch.databinding.FragmentAdminGeofenceBinding
import com.gpunch.databinding.FragmentAdminUsersBinding
import com.gpunch.databinding.ItemAuditLogBinding
import com.gpunch.databinding.ItemAbsenteeBinding
import com.gpunch.databinding.ItemUserBinding
import com.gpunch.models.AttendanceItemDto
import com.gpunch.models.AuditLogItemDto
import com.gpunch.models.UserItemDto
import com.gpunch.ui.viewmodels.AdminUiEvent
import com.gpunch.ui.viewmodels.AdminViewModel
import com.gpunch.utils.SessionManager

class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    val viewModel: AdminViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val tabs = listOf("Geofence", "Users", "Audit Logs", "Attendance")
        val fragments: List<Fragment> = listOf(
            GeofenceFragment(),
            UsersFragment(),
            AuditLogsFragment(),
            AttendanceFragment()
        )

        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = fragments.size
            override fun createFragment(position: Int) = fragments[position]
        }

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, pos ->
            tab.text = tabs[pos]
        }.attach()

        viewModel.isLoading.observe(this) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.event.observe(this) { event ->
            when (event) {
                is AdminUiEvent.Success -> {
                    Snackbar.make(binding.root, event.message, Snackbar.LENGTH_LONG).show()
                    viewModel.clearEvent()
                }
                is AdminUiEvent.Error -> {
                    Snackbar.make(binding.root, "Error: ${event.message}", Snackbar.LENGTH_LONG).show()
                    viewModel.clearEvent()
                }
                null -> Unit
            }
        }
    }

    // ─── Geofence Fragment ────────────────────────────────────────────────────

    class GeofenceFragment : Fragment(com.gpunch.R.layout.fragment_admin_geofence) {
        private var _b: FragmentAdminGeofenceBinding? = null
        private val b get() = _b!!
        private val viewModel: AdminViewModel by lazy {
            (requireActivity() as AdminActivity).viewModel
        }
        private val mapPickerLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val data = result.data ?: return@registerForActivityResult
                    b.etLatitude.setText(data.getDoubleExtra(GeofenceMapPickerActivity.EXTRA_LATITUDE, 0.0).toString())
                    b.etLongitude.setText(data.getDoubleExtra(GeofenceMapPickerActivity.EXTRA_LONGITUDE, 0.0).toString())
                }
            }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            _b = FragmentAdminGeofenceBinding.bind(view)

            viewModel.geofenceConfig.observe(viewLifecycleOwner) { config ->
                config ?: return@observe
                b.etLatitude.setText(config.latitude.toString())
                b.etLongitude.setText(config.longitude.toString())
                b.etRadius.setText(config.allowedRadius.toInt().toString())
                b.etDomain.setText((config.allowedDomains?.takeIf { it.isNotEmpty() } ?: listOf(config.allowedDomain)).joinToString(", "))
                b.switchGeofenceActive.isChecked = config.isActive != false
            }

            b.btnRefreshGeofence.setOnClickListener { viewModel.loadGeofenceConfig() }
            b.btnPickOnMap.setOnClickListener {
                val lat = b.etLatitude.text.toString().toDoubleOrNull() ?: 11.0168
                val lng = b.etLongitude.text.toString().toDoubleOrNull() ?: 76.9558
                val radius = b.etRadius.text.toString().toIntOrNull() ?: 100
                mapPickerLauncher.launch(
                    Intent(requireContext(), GeofenceMapPickerActivity::class.java)
                        .putExtra(GeofenceMapPickerActivity.EXTRA_LATITUDE, lat)
                        .putExtra(GeofenceMapPickerActivity.EXTRA_LONGITUDE, lng)
                        .putExtra(GeofenceMapPickerActivity.EXTRA_RADIUS, radius)
                )
            }

            b.btnSaveGeofence.setOnClickListener {
                val lat = b.etLatitude.text.toString().toDoubleOrNull()
                val lng = b.etLongitude.text.toString().toDoubleOrNull()
                val radius = b.etRadius.text.toString().toIntOrNull()
                val domains = b.etDomain.text.toString().trim()
                if (lat == null || lng == null || radius == null || domains.isEmpty()) {
                    Snackbar.make(b.root, "All fields are required", Snackbar.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                viewModel.updateGeofenceConfig(lat, lng, radius, domains, b.switchGeofenceActive.isChecked)
            }

            viewModel.loadGeofenceConfig()
        }

        override fun onDestroyView() { super.onDestroyView(); _b = null }
    }

    // ─── Users Fragment ───────────────────────────────────────────────────────

    class UsersFragment : Fragment(com.gpunch.R.layout.fragment_admin_users) {
        private var _b: FragmentAdminUsersBinding? = null
        private val b get() = _b!!
        private val viewModel: AdminViewModel by lazy {
            (requireActivity() as AdminActivity).viewModel
        }
        private val adapter = UserAdapter { userId, userName ->
            AlertDialog.Builder(requireContext())
                .setTitle("Reset Device")
                .setMessage("Clear device binding for $userName? They will need to re-verify on a new device.")
                .setPositiveButton("Reset") { _, _ -> viewModel.resetDevice(userId) }
                .setNegativeButton("Cancel", null)
                .show()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            _b = FragmentAdminUsersBinding.bind(view)
            b.rvUsers.layoutManager = LinearLayoutManager(requireContext())
            b.rvUsers.adapter = adapter

            viewModel.users.observe(viewLifecycleOwner) { users ->
                adapter.submitList(users)
            }
            viewModel.usersTotal.observe(viewLifecycleOwner) { total ->
                b.tvUserCount.text = "Users ($total)"
            }

            b.btnRefreshUsers.setOnClickListener { viewModel.loadUsers() }
            viewModel.loadUsers()
        }

        override fun onDestroyView() { super.onDestroyView(); _b = null }
    }

    // ─── Audit Logs Fragment ──────────────────────────────────────────────────

    class AuditLogsFragment : Fragment(com.gpunch.R.layout.fragment_admin_audit) {
        private var _b: FragmentAdminAuditBinding? = null
        private val b get() = _b!!
        private val viewModel: AdminViewModel by lazy {
            (requireActivity() as AdminActivity).viewModel
        }
        private val adapter = AuditLogAdapter()
        private var activeFilter: String? = null

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            _b = FragmentAdminAuditBinding.bind(view)
            b.rvAuditLogs.layoutManager = LinearLayoutManager(requireContext())
            b.rvAuditLogs.adapter = adapter

            viewModel.auditLogs.observe(viewLifecycleOwner) { logs ->
                adapter.submitList(logs)
            }
            viewModel.auditTotal.observe(viewLifecycleOwner) { total ->
                b.tvLogCount.text = "Security Events ($total)"
            }

            b.btnRefreshLogs.setOnClickListener { viewModel.loadAuditLogs(eventType = activeFilter) }

            // Chip filters
            val chipMap = mapOf(
                b.chipAll to null,
                b.chipMock to "MOCK_LOCATION",
                b.chipUnauth to "UNAUTHORIZED_DEVICE",
                b.chipOtpFail to "OTP_FAILED",
                b.chipDomain to "INVALID_DOMAIN"
            )
            chipMap.forEach { (chip, filter) ->
                chip.setOnClickListener {
                    activeFilter = filter
                    viewModel.loadAuditLogs(eventType = activeFilter)
                }
            }

            viewModel.loadAuditLogs()
        }

        override fun onDestroyView() { super.onDestroyView(); _b = null }
    }
}

// ─── RecyclerView Adapters ────────────────────────────────────────────────────

class UserAdapter(private val onReset: (userId: String, userName: String) -> Unit) :
    ListAdapter<UserItemDto, UserAdapter.VH>(object : DiffUtil.ItemCallback<UserItemDto>() {
        override fun areItemsTheSame(a: UserItemDto, b: UserItemDto) = a.id == b.id
        override fun areContentsTheSame(a: UserItemDto, b: UserItemDto) = a == b
    }) {

    inner class VH(val b: ItemUserBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
        VH(ItemUserBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val user = getItem(position)
        holder.b.tvUserName.text = user.name
        holder.b.tvUserEmail.text = user.email
        holder.b.chipRole.text = user.role.uppercase()
        holder.b.tvDeviceStatus.text = if (user.androidId != null)
            "Device bound: ${user.androidId.take(12)}…"
        else
            "No device bound"
        holder.b.btnResetDevice.visibility = if (user.androidId != null) View.VISIBLE else View.GONE
        holder.b.btnResetDevice.setOnClickListener { onReset(user.id, user.name) }
    }
}

class AuditLogAdapter :
    ListAdapter<AuditLogItemDto, AuditLogAdapter.VH>(object : DiffUtil.ItemCallback<AuditLogItemDto>() {
        override fun areItemsTheSame(a: AuditLogItemDto, b: AuditLogItemDto) = a.id == b.id
        override fun areContentsTheSame(a: AuditLogItemDto, b: AuditLogItemDto) = a == b
    }) {

    inner class VH(val b: ItemAuditLogBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
        VH(ItemAuditLogBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val log = getItem(position)
        holder.b.chipEventType.text = log.eventType.replace('_', ' ')
        holder.b.tvLogTime.text = log.createdAt.take(19).replace('T', ' ')
        holder.b.tvLogSubject.text = log.email ?: "Unknown user"
        holder.b.tvLogReason.text = auditReason(log)
        holder.b.tvLogAndroidId.text = if (log.androidId != null) "Device: ${log.androidId.take(16)}…" else "Device: not supplied"
        if (log.latitude != null && log.longitude != null) {
            val distance = log.distanceFromZone?.let { " - ${it}m from zone" } ?: ""
            holder.b.tvLogLocation.text = "Location: %.5f, %.5f%s".format(log.latitude, log.longitude, distance)
        } else {
            holder.b.tvLogLocation.text = "Location: not supplied"
        }
        holder.b.tvLogNetwork.text = "IP: ${log.ipAddress ?: "unknown"}"
    }

    private fun auditReason(log: AuditLogItemDto): String {
        val metadata = log.metadata.orEmpty()
        return when (log.eventType) {
            "INVALID_DOMAIN" -> {
                val attempted = metadata["attemptedDomain"] ?: "unknown"
                val allowed = metadata["allowedDomain"] ?: "configured domain"
                "Registration blocked: @$attempted is not @$allowed."
            }
            "UNAUTHORIZED_DEVICE" -> {
                val bound = metadata["boundDevice"] ?: "registered device"
                "Device mismatch against $bound."
            }
            "OTP_FAILED" -> "OTP verification failed: ${metadata["reason"] ?: "unknown reason"}."
            "MOCK_LOCATION" -> "Mock or spoofed location was reported by the phone."
            "OUT_OF_BOUNDS" -> {
                val allowed = metadata["allowedRadius"] ?: "configured"
                "Punch attempted outside the ${allowed}m radius."
            }
            else -> metadata.entries.joinToString { "${it.key}: ${it.value}" }.ifBlank { "No extra details." }
        }
    }
}

// ─── Attendance Fragment ────────────────────────────────────────────

class AttendanceFragment : Fragment(com.gpunch.R.layout.fragment_admin_attendance) {
    private var _b: com.gpunch.databinding.FragmentAdminAttendanceBinding? = null
    private val b get() = _b!!
    private val viewModel: AdminViewModel by lazy {
        (requireActivity() as AdminActivity).viewModel
    }
    private val adapter = AttendanceAdapter()
    private val absenteeAdapter = AbsenteeAdapter()
    private var showingAbsentees = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = com.gpunch.databinding.FragmentAdminAttendanceBinding.bind(view)

        b.rvAttendance.layoutManager = LinearLayoutManager(requireContext())
        b.rvAttendance.adapter = adapter
        b.rvAbsentees.layoutManager = LinearLayoutManager(requireContext())
        b.rvAbsentees.adapter = absenteeAdapter

        viewModel.attendanceRecords.observe(viewLifecycleOwner) { records ->
            adapter.submitList(records)
        }
        viewModel.attendanceTotal.observe(viewLifecycleOwner) { total ->
            b.tvRecordCount.text = "Punch records ($total)"
        }
        viewModel.attendanceSummary.observe(viewLifecycleOwner) { summary ->
            summary?.let {
                b.tvTodayCount.text = (it.today?.uniqueUsers ?: it.today?.count ?: 0).toString()
                b.tvActiveCount.text = (it.today?.activeNow ?: 0).toString()
                b.tvHoursToday.text = String.format(java.util.Locale.US, "%.1f", it.today?.totalHours ?: 0.0)
                b.tvYesterdayCount.text = (it.yesterday?.uniqueUsers ?: it.yesterday?.count ?: 0).toString()
            }
        }
        viewModel.attendanceAbsentees.observe(viewLifecycleOwner) { users ->
            absenteeAdapter.submitList(users)
        }
        viewModel.attendanceAbsenteesTotal.observe(viewLifecycleOwner) { total ->
            b.tvAbsenteeCount.text = if (showingAbsentees) "Absentees ($total)" else "Absentees hidden"
        }

        // Load summary on first load
        viewModel.loadAttendanceSummary()

        // Date filter
        b.btnFilterDate.setOnClickListener {
            val date = b.etDate.text.toString().trim()
            if (date.isNotEmpty()) {
                viewModel.loadAttendanceReport(date = date)
            } else {
                viewModel.loadAttendanceReport()
            }
        }

        // Search
        b.btnSearch.setOnClickListener {
            val search = b.etSearch.text.toString().trim()
            viewModel.loadAttendanceReport(search = search.ifEmpty { null })
        }

        b.btnShowAbsentees.setOnClickListener {
            showingAbsentees = !showingAbsentees
            b.rvAbsentees.visibility = if (showingAbsentees) View.VISIBLE else View.GONE
            b.btnShowAbsentees.text = if (showingAbsentees) "Hide Absentees" else "Show Absentees"
            if (showingAbsentees) {
                viewModel.loadAttendanceAbsentees(selectedDate())
            } else {
                b.tvAbsenteeCount.text = "Absentees hidden"
            }
        }

        // Export buttons
        b.btnExportToday.setOnClickListener {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            exportCsv(date = today)
        }

        b.btnExportCsv.setOnClickListener {
            exportCsv()
        }

        // Initial load
        viewModel.loadAttendanceReport()
    }

    private fun exportCsv(date: String? = null) {
        viewModel.exportCsv(date = date) { _, message ->
            Snackbar.make(b.root, message ?: "Export completed", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun selectedDate(): String {
        return b.etDate.text.toString().trim().ifEmpty {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

// ─── Attendance RecyclerView Adapter ───────────────────────────────

class AttendanceAdapter :
    ListAdapter<AttendanceItemDto, AttendanceAdapter.VH>(object : DiffUtil.ItemCallback<AttendanceItemDto>() {
        override fun areItemsTheSame(a: AttendanceItemDto, b: AttendanceItemDto) = a.id == b.id
        override fun areContentsTheSame(a: AttendanceItemDto, b: AttendanceItemDto) = a == b
    }) {

    inner class VH(val b: com.gpunch.databinding.ItemAttendanceBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
        VH(com.gpunch.databinding.ItemAttendanceBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val record = getItem(position)
        val name = record.userId?.name ?: "Unknown"
        val email = record.userId?.email ?: "Unknown"

        holder.b.tvUserName.text = name
        holder.b.tvUserEmail.text = email

        // Format clock-in time
        holder.b.tvClockIn.text = record.clockInTime?.take(19)?.replace('T', ' ') ?: "--"

        // Format clock-out time
        holder.b.tvClockOut.text = record.clockOutTime?.take(19)?.replace('T', ' ') ?: "Active"

        // Format duration
        holder.b.tvDuration.text = if (record.durationMinutes != null) {
            val hrs = record.durationMinutes / 60
            val mins = record.durationMinutes % 60
            if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
        } else {
            "--"
        }

        // Show auto clock-out chip
        holder.b.chipAutoClockOut.visibility = if (record.autoClockOut) View.VISIBLE else View.GONE
    }
}

class AbsenteeAdapter :
    ListAdapter<UserItemDto, AbsenteeAdapter.VH>(object : DiffUtil.ItemCallback<UserItemDto>() {
        override fun areItemsTheSame(a: UserItemDto, b: UserItemDto) = a.id == b.id
        override fun areContentsTheSame(a: UserItemDto, b: UserItemDto) = a == b
    }) {

    inner class VH(val b: ItemAbsenteeBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
        VH(ItemAbsenteeBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val user = getItem(position)
        holder.b.tvAbsenteeName.text = user.name
        holder.b.tvAbsenteeEmail.text = user.email
    }
}
