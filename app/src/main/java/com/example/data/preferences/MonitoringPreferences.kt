package com.example.data.preferences

import android.content.Context
import android.content.SharedPreferences

object MonitoringPreferences {

    private const val PREFS_NAME = "wifi_tracker_prefs"
    private const val KEY_MONITORING_ENABLED = "key_monitoring_enabled"
    private const val KEY_LAST_READING_TIMESTAMP = "key_last_reading_timestamp"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isMonitoringEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_MONITORING_ENABLED, true)
    }

    fun setMonitoringEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MONITORING_ENABLED, enabled).apply()
    }

    fun getLastReadingTimestamp(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_READING_TIMESTAMP, 0L)
    }

    fun setLastReadingTimestamp(context: Context, timestamp: Long) {
        getPrefs(context).edit().putLong(KEY_LAST_READING_TIMESTAMP, timestamp).apply()
    }
}
