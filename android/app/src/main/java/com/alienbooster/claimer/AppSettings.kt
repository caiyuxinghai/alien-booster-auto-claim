package com.alienbooster.claimer

import android.content.Context

/** SharedPreferences keys from SPEC section 10. Defaults are the first-run values. */
object AppSettings {
    const val PREFS = "claimer"
    const val KEY_AUTO_DUAL = "autoDual"
    const val KEY_SCHEDULE_ENABLED = "scheduleEnabled"
    const val KEY_SCHEDULE_HOUR = "scheduleHour"
    const val KEY_SCHEDULE_MINUTE = "scheduleMinute"
    const val KEY_PIN = "pin"
    const val KEY_TEST_ONE_AD = "testOneAd"
    const val KEY_FAST_REFRESH = "fastRefresh"
    const val KEY_USER_MODE = "userMode"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun autoDual(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_DUAL, true)

    fun setAutoDual(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_DUAL, value).apply()
    }

    fun scheduleEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SCHEDULE_ENABLED, false)

    fun setScheduleEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SCHEDULE_ENABLED, value).apply()
    }

    fun scheduleHour(context: Context): Int =
        prefs(context).getInt(KEY_SCHEDULE_HOUR, 10)

    fun scheduleMinute(context: Context): Int =
        prefs(context).getInt(KEY_SCHEDULE_MINUTE, 10)

    fun setScheduleTime(context: Context, hour: Int, minute: Int) {
        prefs(context).edit()
            .putInt(KEY_SCHEDULE_HOUR, hour.coerceIn(0, 23))
            .putInt(KEY_SCHEDULE_MINUTE, minute.coerceIn(0, 59))
            .apply()
    }

    fun pin(context: Context): String =
        prefs(context).getString(KEY_PIN, "") ?: ""

    fun setPin(context: Context, value: String) {
        val digits = value.filter { it.isDigit() }
        prefs(context).edit().putString(KEY_PIN, digits).apply()
    }

    fun testOneAd(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TEST_ONE_AD, false)

    fun setTestOneAd(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_TEST_ONE_AD, value).apply()
    }

    /** Stored for later. v1 still force-stops after every round. */
    fun fastRefresh(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FAST_REFRESH, false)

    fun userMode(context: Context): String =
        prefs(context).getString(KEY_USER_MODE, "auto") ?: "auto"
}
