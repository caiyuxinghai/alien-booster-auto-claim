package com.alienbooster.claimer

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/** One daily alarm using the selected mode and the device's local timezone. */
internal object RunSchedule {
    internal const val ACTION = "com.alienbooster.claimer.DAILY_ALARM"
    private const val RETRY_MS = 15 * 60_000L
    private enum class Dispatch { MANUAL, SCHEDULED }
    private val pendingDispatch = AtomicReference<Dispatch?>(null)
    fun tryManualDispatch() = pendingDispatch.compareAndSet(null, Dispatch.MANUAL)
    fun finishManualDispatch() { pendingDispatch.compareAndSet(Dispatch.MANUAL, null) }
    fun dispatchInFlight() = pendingDispatch.get() != null
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

    private fun blocker(c: Context, local: Boolean): String? = when {
        !RunPrefs.networkAllowed(c) -> if (RunPrefs.allowMobile(c)) "定时领取正在等待网络" else "定时领取正在等待 Wi-Fi"
        c.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false -> "手机已锁定，定时领取稍后再试"
        AdAccessibilityService.instance?.isActiveRun == true -> "主屏单号正在运行，定时领取稍后再试"
        local && AdAccessibilityService.instance == null -> "定时领取正在等待无障碍权限"
        local && c.getSystemService(PowerManager::class.java)?.isInteractive != true -> "手机已熄屏，定时领取稍后再试"
        local && AdAccessibilityService.instance?.canStartFromDashboardOrLauncher() != true ->
            "等待回到桌面或领取页后定时领取"
        else -> null
    }

    fun canStartHelper(c: Context): Boolean =
        RunPrefs.scheduleEnabled(c) && !RunPrefs.useAccessibility(c) && RunPrefs.autoStartHelper(c) &&
            PhoneHelperBootstrap.isPaired(c) && due(c, System.currentTimeMillis()) <= System.currentTimeMillis() &&
            blocker(c, false) == null

    fun fire(c: Context, allowHelperStartup: Boolean = true, done: () -> Unit) {
        val now = System.currentTimeMillis()
        if (!RunPrefs.scheduleEnabled(c) || due(c, now) > now) {
            refresh(c)
            done()
            return
        }
        if (!pendingDispatch.compareAndSet(null, Dispatch.SCHEDULED)) {
            defer(c, "任务正在启动，定时领取稍后再试")
            done()
            return
        }
        val config = RunPrefs.effectiveFrom(c)
        val local = RunPrefs.useAccessibility(c)
        val day = dayAt(now, ZoneId.systemDefault())
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        var reserved = false
        var timeout: Runnable? = null
        fun finish() {
            if (finished) return
            finished = true
            timeout?.let(handler::removeCallbacks)
            pendingDispatch.compareAndSet(Dispatch.SCHEDULED, null)
            done()
        }
        fun unchanged(): Boolean = RunPrefs.scheduleEnabled(c) && config == RunPrefs.effectiveFrom(c) &&
            local == RunPrefs.useAccessibility(c) &&
            day == dayAt(System.currentTimeMillis(), ZoneId.systemDefault())
        fun retry(message: String) {
            if (unchanged()) defer(c, message) else refresh(c)
            finish()
        }
        fun started(message: String) {
            prefs(c).edit().remove("retry_at").putString("last_message", message).apply()
            refresh(c)
            finish()
        }
        try {
            val blocked = blocker(c, local)
            if (blocked != null) { retry(blocked); return }
            // Ensure the next opportunity survives loss of this receiver's process.
            defer(c, "正在核对定时领取条件")
            timeout = Runnable {
                if (!reserved) retry("后台助手响应超时，定时领取稍后再试")
                else {
                    prefs(c).edit().putString("last_message", "定时请求已发送，结果待核对").apply()
                    refresh(c)
                    finish()
                }
            }.also { handler.postDelayed(it, 8000) }
            val client = BackgroundClient(c)
            val afterCheck: (BackgroundClient.Result) -> Unit = checked@{ result ->
                if (finished) return@checked
                if (!unchanged()) { refresh(c); finish(); return@checked }
                if (result is BackgroundClient.Result.Failed && !(local && result.offline)) {
                    if (allowHelperStartup && (result.offline || result.needsUpgrade) && canStartHelper(c)) {
                        val scheduler = c.getSystemService(JobScheduler::class.java)
                        val info = JobInfo.Builder(4417, ComponentName(c, ScheduledHelperStartup::class.java))
                            .setMinimumLatency(500L).build()
                        if (scheduler?.schedule(info) == JobScheduler.RESULT_SUCCESS) {
                            retry("正在从手机启动后台助手")
                            return@checked
                        }
                    }
                    retry("后台助手状态未确认，定时领取稍后再试")
                    return@checked
                }
                if (result is BackgroundClient.Result.Ready && result.status.state in setOf(
                        BackgroundClient.State.RUNNING, BackgroundClient.State.WAITING,
                        BackgroundClient.State.RECOVERING, BackgroundClient.State.RESTARTING)) {
                    retry("助手有任务，定时领取稍后再试")
                    return@checked
                }
                val latestBlock = blocker(c, local)
                if (latestBlock != null) { retry(latestBlock); return@checked }
                if (!reserveDay(c, day)) { refresh(c); finish(); return@checked }
                reserved = true
                if (local) {
                    val service = AdAccessibilityService.instance
                    try {
                        service?.startRun(21)
                    } catch (_: RuntimeException) {
                        service?.pauseRun("无障碍领取启动失败")
                    }
                    if (service?.isActiveRun == true) started("今日定时无障碍主号领取已启动")
                    else {
                        freeDay(c, day)
                        retry("无障碍领取未启动，稍后再试")
                    }
                    return@checked
                }
                client.request(BackgroundClient.Command.SCHEDULE_ALL_BACKGROUND) dispatch@{ result ->
                    if (finished) return@dispatch
                    when (result) {
                        is BackgroundClient.Result.Ready -> {
                            started("今日定时后台领取已启动")
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
            if (local) client.request(BackgroundClient.Command.STATUS, afterCheck)
            else client.applyNetworkPolicy(RunPrefs.allowMobile(c), afterCheck)
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

class ScheduledHelperStartup : JobService() {
    private var active: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (!RunSchedule.canStartHelper(this)) return false
        active = params
        PhoneHelperBootstrap.start(this) { result ->
            if (active !== params) return@start
            if (result.isSuccess && RunSchedule.canStartHelper(this)) {
                RunSchedule.fire(this, allowHelperStartup = false) { finish(params) }
            } else finish(params)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (active === params) active = null
        return false
    }

    private fun finish(params: JobParameters) {
        if (active !== params) return
        active = null
        jobFinished(params, false)
    }
}
