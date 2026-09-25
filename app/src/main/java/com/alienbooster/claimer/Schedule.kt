package com.alienbooster.claimer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.util.Calendar

object Schedule {
    const val PREFS = "claim"
    const val KEY_DONE = "last_done_day"
    const val KEY_RETRY = "retry_count"
    const val KEY_RETRY_DAY = "retry_day"
    const val KEY_NEXT = "next_at"
    const val KEY_GAINED = "gained_min"
    const val KEY_HOUR = "hour"
    const val KEY_MINUTE = "minute"
    const val KEY_START_WITHOUT_WIFI = "start_without_wifi"
    const val CHANNEL = "claim_alarm"

    private const val REQ_DAILY = 11
    private const val REQ_RETRY = 12

    fun today(ctx: Context): String {
        val c = Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDoneToday(ctx: Context) = prefs(ctx).getString(KEY_DONE, "") == today(ctx)

    fun markDone(ctx: Context, gainedMin: Int) {
        prefs(ctx).edit()
            .putString(KEY_DONE, today(ctx))
            .putInt(KEY_GAINED, gainedMin)
            .putInt(KEY_RETRY, 0)
            .apply()
        arm(ctx)
    }

    fun shouldCatchUp(ctx: Context): Boolean {
        if (isDoneToday(ctx)) return false
        val last = prefs(ctx).getLong("last_attempt", 0L)
        if (System.currentTimeMillis() - last < 90_000) return false
        val c = Calendar.getInstance()
        val minutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return minutes >= hour(ctx) * 60 + minute(ctx)
    }

    fun noteAttempt(ctx: Context) {
        prefs(ctx).edit().putLong("last_attempt", System.currentTimeMillis()).apply()
    }

    fun arm(ctx: Context) {
        val trigger = nextDailyMillis(ctx)
        schedule(ctx, REQ_DAILY, trigger)
        prefs(ctx).edit().putLong(KEY_NEXT, trigger).apply()
    }

    fun armRetry(ctx: Context, delayMs: Long) {
        val day = today(ctx)
        val p = prefs(ctx)
        val count = if (p.getString(KEY_RETRY_DAY, "") == day) p.getInt(KEY_RETRY, 0) else 0
        if (count >= 6) {
            arm(ctx)
            return
        }
        p.edit().putString(KEY_RETRY_DAY, day).putInt(KEY_RETRY, count + 1).apply()
        val trigger = System.currentTimeMillis() + delayMs
        schedule(ctx, REQ_RETRY, trigger)
        p.edit().putLong(KEY_NEXT, trigger).apply()
    }

    fun hour(ctx: Context) = prefs(ctx).getInt(KEY_HOUR, 8).coerceIn(0, 23)

    fun minute(ctx: Context) = prefs(ctx).getInt(KEY_MINUTE, 10).coerceIn(0, 59)

    fun setClock(ctx: Context, hour: Int, minute: Int) {
        prefs(ctx).edit()
            .putInt(KEY_HOUR, hour.coerceIn(0, 23))
            .putInt(KEY_MINUTE, minute.coerceIn(0, 59))
            .apply()
        arm(ctx)
    }

    fun startWithoutWifi(ctx: Context) = prefs(ctx).getBoolean(KEY_START_WITHOUT_WIFI, false)

    fun setStartWithoutWifi(ctx: Context, allow: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_START_WITHOUT_WIFI, allow).apply()
    }

    fun mayAutoStart(ctx: Context): Boolean {
        if (startWithoutWifi(ctx)) return true
        return hasWifi(ctx)
    }

    fun hasWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return cm.allNetworks.any { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun nextDailyMillis(ctx: Context): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour(ctx))
            set(Calendar.MINUTE, minute(ctx))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    private fun schedule(ctx: Context, request: Int, trigger: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(ctx, request)
        val exact = if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
    }

    private fun pending(ctx: Context, request: Int): PendingIntent {
        val intent = Intent(ctx, DailyReceiver::class.java).setAction(DailyReceiver.ACTION_RUN)
        return PendingIntent.getBroadcast(
            ctx,
            request,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(CHANNEL, "领取可暂停时长", NotificationManager.IMPORTANCE_HIGH)
        nm.createNotificationChannel(ch)
    }

    fun raiseFullScreen(ctx: Context) {
        ensureChannel(ctx)
        val launch = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_RUN, true)
        }
        val pi = PendingIntent.getActivity(
            ctx, 21, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("领取可暂停时长")
            .setContentText("正在打开外星仔加速器")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java)?.notify(21, notif)
        try {
            ctx.startActivity(launch)
        } catch (_: Exception) {
        }
    }
}
