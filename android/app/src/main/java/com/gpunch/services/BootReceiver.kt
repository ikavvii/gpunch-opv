package com.gpunch.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.gpunch.utils.SessionManager

/**
 * Restarts the GeofenceMonitorService after the device reboots,
 * if the user was clocked in at the time of the reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val session = SessionManager(context)
        if (!session.isLoggedIn() || !session.isClockedIn()) return

        // We don't have the fence config cached here; the user will need to
        // open the app to re-establish the service. This receiver is a
        // best-effort placeholder for offline sync future work (F09).
    }
}
