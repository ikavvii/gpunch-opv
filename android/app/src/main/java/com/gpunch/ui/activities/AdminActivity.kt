package com.gpunch.ui.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
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
import com.gpunch.databinding.ItemUserBinding
import com.gpunch.models.AuditLogItemDto
import com.gpunch.models.UserItemDto
import com.gpunch.ui.viewmodels.AdminUiEvent
import com.gpunch.ui.viewmodels.AdminViewModel
import com.gpunch.utils.SessionManager

class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private val viewModel: AdminViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val tabs = listOf("Geofence", "Users", "Audit Logs")
        val fragments: List<Fragment> = listOf(
            GeofenceFragment(),
            UsersFragment(),
            AuditLogsFragment()
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

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            _b = FragmentAdminGeofenceBinding.bind(view)

            viewModel.geofenceConfig.observe(viewLifecycleOwner) { config ->
                config ?: return@observe
                b.etLatitude.setText(config.latitude.toString())
                b.etLongitude.setText(config.longitude.toString())
                b.etRadius.setText(config.allowedRadius.toInt().toString())
                b.etDomain.setText(config.allowedDomain)
            }

            b.btnRefreshGeofence.setOnClickListener { viewModel.loadGeofenceConfig() }

            b.btnSaveGeofence.setOnClickListener {
                val lat = b.etLatitude.text.toString().toDoubleOrNull()
                val lng = b.etLongitude.text.toString().toDoubleOrNull()
                val radius = b.etRadius.text.toString().toIntOrNull()
                val domain = b.etDomain.text.toString().trim()
                if (lat == null || lng == null || radius == null || domain.isEmpty()) {
                    Snackbar.make(b.root, "All fields are required", Snackbar.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                viewModel.updateGeofenceConfig(lat, lng, radius, domain)
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
        holder.b.tvLogAndroidId.text = if (log.androidId != null) "ID: ${log.androidId.take(16)}…" else ""
        if (log.latitude != null && log.longitude != null) {
            holder.b.tvLogLocation.text = "%.5f, %.5f".format(log.latitude, log.longitude)
        } else {
            holder.b.tvLogLocation.text = ""
        }
    }
}
