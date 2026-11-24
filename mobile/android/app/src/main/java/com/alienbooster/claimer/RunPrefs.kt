package com.alienbooster.claimer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

internal object RunPrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("run_settings", Context.MODE_PRIVATE)

    fun allowMobile(c: Context) = prefs(c).getBoolean("allow_mobile", false)
    fun setAllowMobile(c: Context, allow: Boolean) {
        prefs(c).edit().putBoolean("allow_mobile", allow).apply()
    }

    fun scheduleEnabled(c: Context) = prefs(c).getBoolean("daily_enabled", false)
    fun scheduleMinute(c: Context) = prefs(c).getInt("daily_minute", 8 * 60)
    fun effectiveFrom(c: Context) = prefs(c).getLong("daily_from", 0L)

    fun setSchedule(c: Context, enabled: Boolean, minuteOfDay: Int) {
        require(minuteOfDay in 0 until 24 * 60)
        if (scheduleEnabled(c) == enabled && scheduleMinute(c) == minuteOfDay) return
        prefs(c).edit().putBoolean("daily_enabled", enabled).putInt("daily_minute", minuteOfDay)
            .putLong("daily_from", System.currentTimeMillis()).apply()
    }

    fun networkAllowed(c: Context): Boolean {
        val manager = c.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            (allowMobile(c) || (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)))
    }

    fun networkReason(c: Context) = if (allowMobile(c)) "当前网络无法联网，已暂停" else "等待可用 Wi-Fi，已暂停"
}
