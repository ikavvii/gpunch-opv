package com.gpunch.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gpunch.utils.SessionManager

/**
 * Restarts the GeofenceMonitorService after the device reboots,
 * if the user was punched in at the time of the reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val session = SessionManager(context)
        if (!session.isLoggedIn() || !session.isClockedIn()) return

        if (!session.isFenceActive() || !session.hasFence()) return

        val serviceIntent = GeofenceMonitorService.startIntent(
            context,
            session.getFenceLat(),
            session.getFenceLng(),
            session.getFenceRadius()
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
