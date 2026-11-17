package com.alienbooster.claimer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Daily alarm at the configured time. Exact when allowed, otherwise while-idle. */
object ScheduleManager {
    private const val REQUEST = 42

    fun reschedule(context: Context): Boolean {
        val app = context.applicationContext
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pending(app)
        if (!AppSettings.scheduleEnabled(app)) {
            alarm.cancel(pending)
            return canExact(alarm)
        }
        val whenMs = nextTrigger(
            AppSettings.scheduleHour(app),
            AppSettings.scheduleMinute(app)
        )
        val exact = canExact(alarm)
        if (exact) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pending)
        } else {
            // TODO: exact alarms are not granted. This fires in the idle window, so it can be late.
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pending)
            LogStore.append(app, "精确闹钟未授权，已改用非精确闹钟")
        }
        val label = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(whenMs))
        LogStore.append(app, "下次 $label")
        return exact
    }

    fun canExact(context: Context): Boolean {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return canExact(alarm)
    }

    fun nextTrigger(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        if (calendar.timeInMillis <= now) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    private fun canExact(alarm: AlarmManager): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) alarm.canScheduleExactAlarms() else true
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
