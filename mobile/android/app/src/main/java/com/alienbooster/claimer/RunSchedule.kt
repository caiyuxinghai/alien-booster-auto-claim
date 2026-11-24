package com.alienbooster.claimer

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Daily background run in the device's local timezone. There is one pending alarm. */
internal object RunSchedule {
    internal const val ACTION = "com.alienbooster.claimer.DAILY_ALARM"
    private const val RETRY_MS = 15 * 60_000L
    private val pendingDispatch = AtomicBoolean(false)
    private fun prefs(c: Context) = c.getSharedPreferences("daily_dispatch", Context.MODE_PRIVATE)
    private fun operation(c: Context) = PendingIntent.getBroadcast(c, 40,
        Intent(c, ScheduleReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun status(c: Context): String = prefs(c).getString("last_message", "").orEmpty()
    fun nextAt(c: Context): Long? = prefs(c).getLong("next", 0L).takeIf { it > 0 }

    private fun dayAt(time: Long, zone: ZoneId) = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    private fun atMinute(day: LocalDate, minute: Int, zone: ZoneId) =
        day.atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()

    internal fun dueTime(now: Long, zone: ZoneId, minute: Int, from: Long, sentDays: Set<String>): Long {
        var day = dayAt(now, zone)
        var due = atMinute(day, minute, zone)
        // Today is eligible when configured for this minute, or when an existing alarm was missed.
        while (due / 60_000 < from / 60_000 || day.toString() in sentDays) {
            day = day.plusDays(1)
            due = atMinute(day, minute, zone)
        }
        return due
    }

    private fun due(c: Context, now: Long): Long = dueTime(now, ZoneId.systemDefault(), RunPrefs.scheduleMinute(c),
        RunPrefs.effectiveFrom(c), prefs(c).getStringSet("sent_days", emptySet()).orEmpty())

    fun refresh(c: Context) {
        val alarm = c.getSystemService(AlarmManager::class.java) ?: return
        if (!RunPrefs.scheduleEnabled(c)) {
            alarm.cancel(operation(c))
            prefs(c).edit().remove("next").remove("retry_at").apply()
            return
        }
        val now = System.currentTimeMillis()
        val plan = due(c, now)
        val retry = if (prefs(c).getLong("retry_config", -1L) == RunPrefs.effectiveFrom(c))
            prefs(c).getLong("retry_at", 0L) else 0L
        val target = if (plan > now) plan else max(now + 1000L, retry.coerceAtMost(now + RETRY_MS))
        val op = operation(c)
        if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) {
            try {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, op)
            } catch (_: SecurityException) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, op)
            }
        } else {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, op)
        }
        prefs(c).edit().putLong("next", target).apply()
    }

    private fun defer(c: Context, why: String) {
        prefs(c).edit().putLong("retry_at", System.currentTimeMillis() + RETRY_MS)
            .putLong("retry_config", RunPrefs.effectiveFrom(c)).putString("last_message", why).apply()
        refresh(c)
    }

    private fun reserveDay(c: Context, day: LocalDate): Boolean {
        val dates = prefs(c).getStringSet("sent_days", emptySet()).orEmpty()
            .filter { it >= day.minusDays(31).toString() }.toMutableSet()
        if (!dates.add(day.toString())) return false
        // Commit before IPC so a receiver process death cannot launch twice in the same day.
        return prefs(c).edit().putStringSet("sent_days", dates).commit()
    }

    private fun freeDay(c: Context, day: LocalDate) {
        val dates = prefs(c).getStringSet("sent_days", emptySet()).orEmpty().toMutableSet()
        dates.remove(day.toString())
        prefs(c).edit().putStringSet("sent_days", dates).commit()
    }

    private fun blocker(c: Context): String? = when {
        !RunPrefs.networkAllowed(c) -> if (RunPrefs.allowMobile(c)) "定时领取正在等待网络" else "定时领取正在等待 Wi-Fi"
        c.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false -> "手机已锁定，定时领取稍后再试"
        AdAccessibilityService.instance?.isActiveRun == true -> "主屏单号正在运行，定时领取稍后再试"
        else -> null
    }

    fun fire(c: Context, done: () -> Unit) {
        val now = System.currentTimeMillis()
        if (!RunPrefs.scheduleEnabled(c) || due(c, now) > now) {
            refresh(c)
            done()
            return
        }
        if (!pendingDispatch.compareAndSet(false, true)) { done(); return }
        val config = RunPrefs.effectiveFrom(c)
        val day = dayAt(now, ZoneId.systemDefault())
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        var reserved = false
        var timeout: Runnable? = null
        fun finish() {
            if (finished) return
            finished = true
            timeout?.let(handler::removeCallbacks)
            pendingDispatch.set(false)
            done()
        }
        fun unchanged(): Boolean = RunPrefs.scheduleEnabled(c) && config == RunPrefs.effectiveFrom(c) &&
            day == dayAt(System.currentTimeMillis(), ZoneId.systemDefault())
        fun retry(message: String) {
            if (unchanged()) defer(c, message) else refresh(c)
            finish()
        }
        try {
            val blocked = blocker(c)
            if (blocked != null) { retry(blocked); return }
            // Ensure the next opportunity survives loss of this receiver's process.
            defer(c, "正在连接后台助手")
            timeout = Runnable {
                if (!reserved) retry("后台助手响应超时，定时领取稍后再试")
                else {
                    prefs(c).edit().putString("last_message", "定时请求已发送，结果待核对").apply()
                    refresh(c)
                    finish()
                }
            }.also { handler.postDelayed(it, 8000) }
            val client = BackgroundClient(c)
            client.applyNetworkPolicy(RunPrefs.allowMobile(c)) network@{ policy ->
                if (finished) return@network
                if (!unchanged()) { refresh(c); finish(); return@network }
                if (policy !is BackgroundClient.Result.Ready) { retry("后台助手未就绪，定时领取稍后再试"); return@network }
                val latestBlock = blocker(c)
                if (latestBlock != null) { retry(latestBlock); return@network }
                if (!reserveDay(c, day)) { refresh(c); finish(); return@network }
                reserved = true
                client.request(BackgroundClient.Command.SCHEDULE_ALL_BACKGROUND) dispatch@{ result ->
                    if (finished) return@dispatch
                    when (result) {
                        is BackgroundClient.Result.Ready -> {
                            prefs(c).edit().remove("retry_at").putString("last_message", "今日定时领取已启动").apply()
                            refresh(c)
                            finish()
                        }
                        is BackgroundClient.Result.Failed -> {
                            if (!result.commandMayHaveRun) {
                                freeDay(c, day)
                                retry(result.message + "，定时领取稍后再试")
                            } else {
                                prefs(c).edit().putString("last_message", "定时请求已发送，结果待核对").apply()
                                refresh(c)
                                finish()
                            }
                        }
                    }
                }
            }
        } catch (_: RuntimeException) {
            retry("定时任务尚未启动，稍后再试")
        }
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.applicationContext
        when (intent.action) {
            RunSchedule.ACTION -> {
                val pending = goAsync()
                RunSchedule.fire(c) { pending.finish() }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
                RunSchedule.refresh(c)
        }
    }
}
