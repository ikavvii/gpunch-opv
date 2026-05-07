package com.gpunch.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages user session state using encrypted SharedPreferences.
 */
class SessionManager(context: Context) {

    companion object {
        private const val PREF_NAME = "gpunch_prefs"
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_ROLE = "user_role"
        private const val KEY_ANDROID_ID = "android_id"
        private const val KEY_IS_CLOCKED_IN = "is_clocked_in"
        private const val KEY_CLOCK_IN_TIME = "clock_in_time"
        private const val KEY_ACTIVE_RECORD_ID = "active_record_id"
        private const val KEY_PENDING_EMAIL = "pending_email"
        private const val KEY_FENCE_LAT = "fence_lat"
        private const val KEY_FENCE_LNG = "fence_lng"
        private const val KEY_FENCE_RADIUS = "fence_radius"
        private const val KEY_FENCE_ACTIVE = "fence_active"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun saveSession(
        token: String,
        userId: String,
        name: String,
        email: String,
        role: String,
        androidId: String
    ) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_USER_NAME, name)
            .putString(KEY_USER_EMAIL, email)
            .putString(KEY_USER_ROLE, role)
            .putString(KEY_ANDROID_ID, androidId)
            .apply()
    }

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)
    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)
    fun getUserName(): String? = prefs.getString(KEY_USER_NAME, null)
    fun getUserEmail(): String? = prefs.getString(KEY_USER_EMAIL, null)
    fun getUserRole(): String? = prefs.getString(KEY_USER_ROLE, null)
    fun getAndroidId(): String? = prefs.getString(KEY_ANDROID_ID, null)

    fun isLoggedIn(): Boolean = !getToken().isNullOrBlank()

    fun setIsClockedIn(isClockedIn: Boolean, clockInTime: Long = System.currentTimeMillis(), recordId: String? = null) {
        prefs.edit()
            .putBoolean(KEY_IS_CLOCKED_IN, isClockedIn)
            .putLong(KEY_CLOCK_IN_TIME, if (isClockedIn) clockInTime else 0L)
            .putString(KEY_ACTIVE_RECORD_ID, if (isClockedIn) recordId else null)
            .apply()
    }

    fun isClockedIn(): Boolean = prefs.getBoolean(KEY_IS_CLOCKED_IN, false)
    fun getClockInTime(): Long = prefs.getLong(KEY_CLOCK_IN_TIME, 0L)
    fun getActiveRecordId(): String? = prefs.getString(KEY_ACTIVE_RECORD_ID, null)

    fun saveFence(lat: Double, lng: Double, radius: Double, active: Boolean = true) {
        prefs.edit()
            .putLong(KEY_FENCE_LAT, lat.toBits())
            .putLong(KEY_FENCE_LNG, lng.toBits())
            .putLong(KEY_FENCE_RADIUS, radius.toBits())
            .putBoolean(KEY_FENCE_ACTIVE, active)
            .apply()
    }

    fun getFenceLat(): Double = Double.fromBits(prefs.getLong(KEY_FENCE_LAT, 0.0.toBits()))
    fun getFenceLng(): Double = Double.fromBits(prefs.getLong(KEY_FENCE_LNG, 0.0.toBits()))
    fun getFenceRadius(): Double = Double.fromBits(prefs.getLong(KEY_FENCE_RADIUS, 0.0.toBits()))
    fun isFenceActive(): Boolean = prefs.getBoolean(KEY_FENCE_ACTIVE, true)
    fun hasFence(): Boolean = getFenceRadius() > 0.0

    fun setPendingEmail(email: String) {
        prefs.edit().putString(KEY_PENDING_EMAIL, email).apply()
    }
    fun getPendingEmail(): String? = prefs.getString(KEY_PENDING_EMAIL, null)

    fun clearSession() {
        prefs.edit().clear().apply()
    }
}
