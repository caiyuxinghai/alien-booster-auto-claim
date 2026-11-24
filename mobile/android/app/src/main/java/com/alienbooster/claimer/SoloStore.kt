package com.alienbooster.claimer

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SoloStore {
    private fun prefs(c: Context) = c.getSharedPreferences("solo_v1", Context.MODE_PRIVATE)
    fun running(c: Context) = prefs(c).getBoolean("running", false)
    fun status(c: Context) = prefs(c).getString("status", "点开始后自动运行") ?: ""
    fun stages(c: Context) = prefs(c).getString("stages", "开始后读取外星仔的实际进度") ?: ""
    fun logs(c: Context) = prefs(c).getString("logs", "") ?: ""
    @Synchronized fun update(c: Context, running: Boolean? = null, status: String? = null, stages: String? = null) {
        prefs(c).edit().apply {
            running?.let { putBoolean("running", it) }
            status?.let { putString("status", it) }
            stages?.let { putString("stages", it) }
        }.apply()
    }
    @Synchronized fun log(c: Context, message: String) {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        prefs(c).edit().putString("logs", (logs(c) + "\n$time $message").lines().takeLast(120).joinToString("\n").trim()).apply()
    }
}
