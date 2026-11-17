package com.alienbooster.claimer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One worker thread. Wake locks cover the run; `svc power stayon` is turned on
 * before the engine and off in finally. Stop is cooperative and is observed
 * between shell calls, so the worker leaves the loop within about 2 seconds.
 */
class ClaimService : Service() {
    private val stopFlag = AtomicBoolean(false)
    private val gate = Any()
    private var worker: Thread? = null
    private var generation = 0
    private var partial: PowerManager.WakeLock? = null
    private var screen: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ShizukuManager.init(this)
        val selftest = intent?.getBooleanExtra(ClaimExtras.SELFTEST, false) == true
        startInForeground(if (selftest) "自检中" else "准备中")
        if (intent?.action == ACTION_STOP) {
            stopFlag.set(true)
            worker?.interrupt()
            if (worker?.isAlive != true) {
                running = false
                NotificationHelper.result(this, "已停止")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        synchronized(gate) {
            if (worker?.isAlive == true) {
                LogStore.append(this, "已在运行")
                return START_NOT_STICKY
            }
            stopFlag.set(false)
            val gen = ++generation
            val maxAds = resolveMax(intent)
            val user = intent?.getIntExtra(ClaimExtras.USER, -1) ?: -1
            running = true
            worker = Thread({ runWorker(gen, selftest, maxAds, user) }, "claimer-worker")
            worker?.start()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopFlag.set(true)
        worker?.interrupt()
        releaseLocks()
        super.onDestroy()
    }

    private fun runWorker(gen: Int, selftest: Boolean, maxAds: Int, user: Int) {
        var summary = "已结束"
        acquireLocks()
        val beat = startHeartbeat()
        try {
            StatusStore.update(this) {
                it.copy(
                    running = true,
                    error = null,
                    selftest = selftest,
                    maxAds = maxAds,
                    user = if (user >= 0) user else 0,
                    day = DailyFlags.today()
                )
            }
            val engine = RewardEngine(
                context = applicationContext,
                stopped = { stopFlag.get() },
                maxAds = maxAds,
                userOverride = user,
                fastRefresh = AppSettings.fastRefresh(this)
            )
            val code = if (selftest) engine.selfTest() else engine.run()
            summary = summaryFor(code, selftest)
            LogStore.append(this, summary)
        } catch (t: Throwable) {
            summary = "运行异常 ${t.message ?: ""}".trim()
            LogStore.append(this, summary)
            StatusStore.update(this) { it.copy(running = false, error = summary) }
        } finally {
            beat.interrupt()
            releaseLocks()
            Thread {
                try {
                    Shell.stayOn(false)
                } catch (_: Throwable) {
                }
            }.apply {
                name = "claimer-stayon"
                isDaemon = false
                start()
            }
            synchronized(gate) {
                if (generation == gen) {
                    running = false
                    NotificationHelper.result(this, summary)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun summaryFor(code: Int, selftest: Boolean): String {
        val snap = StatusStore.read(this)
        if (selftest) {
            return if (code == RewardEngine.EXIT_OK) "自检完成" else "自检失败 ($code)"
        }
        return when (code) {
            RewardEngine.EXIT_OK -> "完成 ${snap.credits} 条"
            RewardEngine.EXIT_STOPPED -> "已停止"
            RewardEngine.EXIT_LOGIN -> "未登录"
            RewardEngine.EXIT_NO_CTA -> "没有领取按钮"
            RewardEngine.EXIT_NOT_READY -> "Shizuku 未就绪"
            RewardEngine.EXIT_NOT_INSTALLED -> "目标应用未安装"
            RewardEngine.EXIT_FAILS -> "连续失败，已停止"
            RewardEngine.EXIT_UNLOCK -> getString(R.string.unlock_failed)
            RewardEngine.EXIT_STATE -> "读不到界面"
            RewardEngine.EXIT_FRONT -> "无法切换到目标用户"
            else -> "结束 ($code)"
        }
    }

    private fun resolveMax(intent: Intent?): Int {
        val requested = intent?.getIntExtra(ClaimExtras.MAX_ADS, -1) ?: -1
        if (requested > 0) return requested.coerceAtMost(21)
        if (AppSettings.testOneAd(this)) return 1
        return 21
    }

    private fun startInForeground(text: String) {
        val notification = NotificationHelper.progress(this, text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NotificationHelper.ONGOING_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationHelper.ONGOING_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        partial = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "claimer:run")
        screen = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "claimer:screen"
        )
        partial?.setReferenceCounted(false)
        screen?.setReferenceCounted(false)
        partial?.acquire(3 * 60 * 60 * 1000L)
        screen?.acquire(3 * 60 * 60 * 1000L)
    }

    private fun releaseLocks() {
        try {
            if (partial?.isHeld == true) partial?.release()
        } catch (_: Throwable) {
        }
        try {
            if (screen?.isHeld == true) screen?.release()
        } catch (_: Throwable) {
        }
    }

    private fun startHeartbeat(): Thread {
        val thread = Thread({
            var last = ""
            while (!stopFlag.get() && !Thread.currentThread().isInterrupted) {
                try {
                    val shizuku = ShizukuManager.ping() && ShizukuManager.hasPermission()
                    val snap = StatusStore.touch(applicationContext, shizuku)
                    val text = when {
                        snap.selftest -> "自检中"
                        snap.running -> "第 ${snap.slot.coerceAtLeast(1)}/${snap.maxAds.coerceAtLeast(1)} 条 · 已入账 ${snap.credits}"
                        else -> "准备中"
                    }
                    if (text != last) {
                        last = text
                        NotificationHelper.updateProgress(this, text)
                    }
                } catch (_: Throwable) {
                }
                try {
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "claimer-heartbeat")
        thread.isDaemon = true
        thread.start()
        return thread
    }

    companion object {
        const val ACTION_STOP = "com.alienbooster.claimer.action.STOP"

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context, intent: Intent) {
            try {
                context.startForegroundService(intent)
            } catch (t: Throwable) {
                LogStore.append(context, "无法启动前台服务 ${t.message}")
                NotificationHelper.result(context, "请打开外星仔领时长后点开始")
            }
        }
    }
}
