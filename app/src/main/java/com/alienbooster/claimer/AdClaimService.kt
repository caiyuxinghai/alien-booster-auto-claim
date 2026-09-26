package com.alienbooster.claimer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * On-device daily claim for 外星仔加速器 pauseable time.
 * Runs inside the accessibility service so the phone does not need a PC.
 */
class AdClaimService : AccessibilityService() {

    private enum class TaskResult { WATCHED, NO_OPEN, QUOTA }

    @Volatile private var curPkg = ""
    @Volatile private var curCls = ""
    private val stopFlag = AtomicBoolean(false)
    private var worker: Thread? = null
    private var screenLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastProgress = ""

    override fun onServiceConnected() {
        instance = this
        log("无障碍服务已连接")
        Schedule.arm(this)
        if (pendingRun || Schedule.shouldCatchUp(this)) {
            pendingRun = false
            mainHandler.postDelayed({ startLoopInternal() }, 1500)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopLoopInternal()
        if (instance === this) instance = null
        return false
    }

    override fun onDestroy() {
        stopLoopInternal()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            curPkg = event.packageName?.toString() ?: curPkg
            curCls = event.className?.toString() ?: ""
        }
    }

    override fun onInterrupt() {}

    fun startLoopInternal() {
        if (!running.compareAndSet(false, true)) return
        stopFlag.set(false)
        Schedule.noteAttempt(this)
        Schedule.prefs(this).edit().putInt(Schedule.KEY_RETRY, 0).apply()
        worker = Thread({ runLoop() }, "claimer-worker").also { it.start() }
    }

    fun stopLoopInternal() {
        stopFlag.set(true)
        worker?.interrupt()
    }

    private fun runLoop() {
        val t0 = SystemClock.elapsedRealtime()
        var watched = 0
        var gainedMin = 0
        var noOpen = 0
        var unpaid = 0
        var finished = false
        var holdForWifi = false
        try {
            if (!Schedule.mayAutoStart(this)) {
                log("未连接 Wi-Fi，按设置本次不启动")
                holdForWifi = true
            } else {
            holdScreen()
            startInForeground()
            var netTries = 0
            while (!netOk() && netTries < 8 && !stopFlag.get()) {
                log("network down -> sleep 20s")
                sleep(20_000)
                netTries++
            }
            for (i in 1..MAX_ROUNDS) {
                if (stopFlag.get()) break
                if (!screenReady()) {
                    log("屏幕锁着或点不亮，稍后重试")
                    break
                }
                refreshFromRoot()
                if (isAd()) {
                    log("[$i] orphan ad")
                    val beforeP = lastProgress
                    val wall = SystemClock.elapsedRealtime()
                    watchAd(i)
                    val delta = waitCredit(null, beforeP, wall)
                    if (delta >= MIN_REWARD_SEC) {
                        val mins = delta / 60
                        watched++
                        gainedMin += mins
                        unpaid = 0
                        log("[$i] orphan +${mins}min (today +${gainedMin}min)")
                    } else if (quotaDone()) {
                        finished = true
                        break
                    }
                    continue
                }
                if (!ensureMain()) {
                    log("[$i] cannot reach main, reset")
                    bringFront()
                    noOpen++
                    if (noOpen >= MAX_NOOPEN) break
                    sleep(1500)
                    continue
                }
                if (quotaDone()) {
                    log("[$i] 今日任务已完成 -> quota done")
                    finished = true
                    break
                }
                val beforeC = readCounterSeconds()
                val beforeP = progressToken()
                if (beforeP.isNotEmpty()) lastProgress = beforeP
                val wall = SystemClock.elapsedRealtime()
                log("[$i] counter=${fmt(beforeC)} progress=$beforeP")
                when (doTask(i)) {
                    TaskResult.QUOTA -> {
                        finished = true
                        break
                    }
                    TaskResult.NO_OPEN -> {
                        noOpen++
                        log("[$i] no-open x$noOpen")
                        if (noOpen >= MAX_NOOPEN) break
                        sleep(backoff(noOpen))
                    }
                    TaskResult.WATCHED -> {
                        noOpen = 0
                        val delta = waitCredit(beforeC, beforeP, wall)
                        if (delta >= MIN_REWARD_SEC) {
                            val mins = delta / 60
                            watched++
                            unpaid = 0
                            gainedMin += mins
                            log("[$i] +${mins}min (today +${gainedMin}min)")
                        } else if (quotaDone()) {
                            finished = true
                            break
                        } else {
                            unpaid++
                            log("[$i] opened but unverified x$unpaid")
                            if (unpaid >= 4) break
                        }
                    }
                }
                sleep(800)
            }
            }
        } catch (e: Exception) {
            log("ERR ${e.javaClass.simpleName} ${e.message} ${e.stackTrace.firstOrNull()}")
        } finally {
            val dur = (SystemClock.elapsedRealtime() - t0) / 1000
            if (!holdForWifi) {
                log("DONE watched=$watched gained=+${gainedMin}min in ${dur}s finished=$finished")
            }
            if (finished || (!holdForWifi && quotaDone())) Schedule.markDone(this, gainedMin)
            else if (!stopFlag.get() && !Schedule.isDoneToday(this)) {
                Schedule.armRetry(this, if (holdForWifi) 15 * 60_000L else 20 * 60_000L)
            }
            running.set(false)
            stopInForeground()
            releaseScreen()
        }
    }

    private fun doTask(i: Int): TaskResult {
        if (quotaDone()) return TaskResult.QUOTA
        val btn = findWatchPoint()
        if (btn == null) {
            log("[$i] 没有「看广告 领时长」按钮")
            return if (quotaDone()) TaskResult.QUOTA else TaskResult.NO_OPEN
        }
        tap(btn.first, btn.second)
        val openedAt = SystemClock.elapsedRealtime()
        if (!waitAdOpen(12_000)) {
            if (quotaDone()) return TaskResult.QUOTA
            log("[$i] task page not opened")
            return TaskResult.NO_OPEN
        }
        if (SystemClock.elapsedRealtime() - openedAt < 1200 && isMain()) {
            log("[$i] task page flashed")
            return TaskResult.NO_OPEN
        }
        return watchAd(i)
    }

    private fun watchAd(i: Int): TaskResult {
        val seconds = readRequiredSeconds()
        val waitSec = seconds ?: (KWAI_WAIT_MS / 1000).toInt()
        log(if (seconds != null) "[$i] stay ${waitSec}s on ad" else "[$i] stay ${waitSec}s (no timer text)")
        val end = SystemClock.elapsedRealtime() + (waitSec + 1) * 1000L
        while (SystemClock.elapsedRealtime() < end && !stopFlag.get()) {
            refreshFromRoot()
            if (isHonorDialog()) {
                tapText(collectTexts().second, "拒绝", DENY)
            } else if (!isAd() && curPkg.isNotEmpty() && curPkg != PKG && curPkg != packageName) {
                back()
            }
            sleep(1000)
        }
        leaveAndReenter("[$i] stayed ${waitSec}s, reopen")
        return TaskResult.WATCHED
    }

    private fun readRequiredSeconds(): Int? {
        val deadline = SystemClock.elapsedRealtime() + 4_000
        while (SystemClock.elapsedRealtime() < deadline && !stopFlag.get()) {
            val texts = collectTexts().first
            val found = experienceSeconds(texts) ?: readCountdown(texts)
            if (found != null) return found
            sleep(400)
        }
        return null
    }

    private fun leaveAndReenter(reason: String) {
        onMain { performGlobalAction(GLOBAL_ACTION_HOME) }
        sleep(1200)
        bringFront()
        sleep(1600)
        refreshFromRoot()
        if (!isMain()) {
            bringFront()
            sleep(1200)
        }
        log(reason)
    }

    private fun waitCredit(before: Int?, beforeP: String, wallStart: Long): Int {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var best = 0
        while (SystemClock.elapsedRealtime() < deadline && !stopFlag.get()) {
            if (!isMain()) {
                if (isAd()) sleep(1000) else bringFront()
                sleep(500)
                continue
            }
            val nowC = readCounterSeconds()
            val nowP = progressToken()
            if (before != null && nowC != null) {
                val drain = ((SystemClock.elapsedRealtime() - wallStart) / 1000).toInt()
                best = max(best, nowC - before + drain)
                if (best >= MIN_REWARD_SEC) return best
            }
            if (beforeP.isNotEmpty() && nowP.isNotEmpty() && nowP != beforeP) {
                return if (best >= MIN_REWARD_SEC) best else MIN_REWARD_SEC
            }
            if (quotaDone()) return best
            sleep(1000)
        }
        return best
    }

    private fun ensureMain(budgetMs: Long = 50_000): Boolean {
        val end = SystemClock.elapsedRealtime() + budgetMs
        var backs = 0
        var logged = ""
        while (SystemClock.elapsedRealtime() < end && !stopFlag.get()) {
            refreshFromRoot()
            val focus = "$curPkg/$curCls"
            if (focus != logged) {
                log("focus $focus")
                logged = focus
            }
            if (isMain() && homeReady()) return true
            when {
                isAd() -> {
                    sleep(1500)
                    if (isAd()) closeAd("orphan ad")
                }
                isHonorDialog() -> {
                    tapText(collectTexts().second, "拒绝", DENY)
                    sleep(800)
                }
                curPkg == PKG -> {
                    backs++
                    back()
                    sleep(700)
                    if (backs >= 2) {
                        bringFront()
                        sleep(1500)
                        backs = 0
                    }
                }
                else -> {
                    bringFront()
                    sleep(1600)
                }
            }
        }
        refreshFromRoot()
        return isMain() && homeReady()
    }

    private fun waitAdOpen(timeoutMs: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end && !stopFlag.get()) {
            refreshFromRoot()
            if (isAd()) return true
            if (isHonorDialog()) {
                tapText(collectTexts().second, "拒绝", DENY)
                sleep(600)
            }
            if (curPkg.isNotEmpty() && curPkg != PKG && curPkg != packageName && !isLauncher(curPkg)) {
                return true
            }
            sleep(300)
        }
        return isAd()
    }

    private fun closeAd(reason: String) {
        if (isAd() || (curPkg.isNotEmpty() && curPkg != PKG && curPkg != packageName)) {
            dismissOverlays()
            back()
            sleep(700)
            dismissOverlays()
        }
        if (!isMain()) {
            bringFront()
            sleep(1200)
        }
        if (isAd()) {
            back()
            sleep(500)
            bringFront()
            sleep(1200)
        }
        log(reason)
    }

    private fun dismissOverlays() {
        val (texts, bounds) = collectTexts()
        if (texts.any { it.contains("想要打开") }) {
            tapText(bounds, "拒绝", DENY)
            sleep(600)
            return
        }
        val leave = LEAVE.firstOrNull { label -> texts.any { it.contains(label) } }
        if (leave != null) {
            tapText(bounds, leave)
            sleep(700)
        }
    }

    private fun homeReady(): Boolean {
        val texts = collectTexts().first
        if (texts.any { QUOTA.containsMatchIn(it) }) return true
        return texts.any { it.contains("领时长") || it.contains("可暂停") || it.contains("阶段") }
    }

    private fun quotaDone(): Boolean {
        val texts = collectTexts().first
        return texts.any { QUOTA.containsMatchIn(it) }
    }

    private fun findWatchPoint(): Pair<Int, Int>? {
        val (_, bounds) = collectTexts()
        val hit = bounds
            .filter { (t, _) -> t.contains("领时长") || t.contains("点击重试") }
            .maxByOrNull { it.second.centerY() }
        if (hit != null) return hit.second.centerX() to hit.second.centerY()
        return WATCH
    }

    private fun progressToken(): String {
        val texts = collectTexts().first
        val ratios = texts.mapNotNull { RATIO.find(it)?.value?.replace(" ", "") }
        val flags = texts.filter { it == "已完成" || it == "待解锁" }
        return (ratios + flags).joinToString("|")
    }

    private fun readCounterSeconds(): Int? {
        val blob = collectTexts().first.joinToString("")
        val m = COUNTER.find(blob) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        val sec = m.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
        return h * 3600 + min * 60 + sec
    }

    private fun experienceSeconds(texts: List<String>): Int? {
        val blob = texts.joinToString("")
        val patterns = listOf(
            Regex("""体验\s*(\d+)\s*秒"""),
            Regex("""浏览\s*(\d+)\s*秒"""),
            Regex("""去看\s*(\d+)\s*秒"""),
            Regex("""(\d+)\s*秒可立即领奖"""),
            Regex("""(\d+)\s*秒可直接"""),
        )
        for (re in patterns) {
            val n = re.find(blob)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (n != null && n in 3..60) return n
        }
        return null
    }

    private fun readCountdown(texts: List<String>): Int? {
        for (t in texts) {
            for (re in COUNTDOWN) {
                val n = re.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (n != null) return n
            }
        }
        return null
    }

    private fun isMain(): Boolean {
        return curPkg == PKG && (curCls.endsWith("MainActivity") || curCls.isEmpty())
    }

    private fun isAd(): Boolean {
        val s = "$curPkg|$curCls".lowercase()
        return AD_HINTS.any { s.contains(it) }
    }

    private fun isHonorDialog(): Boolean {
        val s = "$curPkg|$curCls".lowercase()
        return s.contains("systemmanager") || s.contains("securitycenter")
    }

    private fun isLauncher(pkg: String): Boolean {
        return pkg.contains("launcher") || pkg == "com.android.systemui" || pkg == "android"
    }

    private fun refreshFromRoot() {
        onMain {
            val root = rootInActiveWindow ?: return@onMain
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isNotEmpty()) curPkg = pkg
        }
    }

    private fun collectTexts(): Pair<List<String>, List<Pair<String, Rect>>> {
        val out = onMain {
            val texts = ArrayList<String>()
            val bounds = ArrayList<Pair<String, Rect>>()
            val root = rootInActiveWindow ?: return@onMain texts to bounds
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            var n = 0
            while (stack.isNotEmpty() && n < 400) {
                val node = stack.removeLast()
                n++
                val t = node.text?.toString()?.trim().orEmpty()
                val d = node.contentDescription?.toString()?.trim().orEmpty()
                val label = if (t.isNotEmpty()) t else d
                if (label.isNotEmpty()) {
                    texts.add(label)
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (!r.isEmpty) bounds.add(label to r)
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { stack.add(it) }
                }
            }
            texts to bounds
        }
        return out ?: (emptyList<String>() to emptyList())
    }

    private fun tapText(bounds: List<Pair<String, Rect>>, label: String, fallback: Pair<Int, Int>? = null) {
        val hit = bounds.firstOrNull { it.first.contains(label) }
        if (hit != null) tap(hit.second.centerX(), hit.second.centerY())
        else if (fallback != null) tap(fallback.first, fallback.second)
    }

    private fun tap(x: Int, y: Int) {
        val latch = CountDownLatch(1)
        mainHandler.post {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 70)
            val sent = dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) = latch.countDown()
                    override fun onCancelled(gestureDescription: GestureDescription?) = latch.countDown()
                },
                null
            )
            if (!sent) latch.countDown()
        }
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            stopFlag.set(true)
        }
    }

    private fun back() {
        onMain { performGlobalAction(GLOBAL_ACTION_BACK) }
    }

    private fun bringFront() {
        try {
            val launch = packageManager.getLaunchIntentForPackage(PKG)
            val intent = (launch ?: Intent()).apply {
                if (launch == null) {
                    component = ComponentName(PKG, "com.etalien.booster.ui.MainActivity")
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
        } catch (e: Exception) {
            log("bringFront 失败: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun screenReady(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            holdScreen()
            sleep(1500)
        }
        if (!pm.isInteractive) {
            log("屏幕无法唤醒（可能被口袋模式挡住距离感应器）")
            return false
        }
        val km = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (km.isKeyguardLocked) {
            log("锁屏未解开，无法点按")
            return false
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun holdScreen() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (screenLock?.isHeld != true) {
            screenLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "claimer:screen"
            ).apply {
                setReferenceCounted(false)
                acquire(45 * 60_000L)
            }
        }
    }

    private fun releaseScreen() {
        if (screenLock?.isHeld == true) screenLock?.release()
        screenLock = null
    }

    private fun netOk(): Boolean {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    private fun startInForeground() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(
                    NotificationChannel("claim_run", "领取中", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val notif = Notification.Builder(this, "claim_run")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("正在领取可暂停时长")
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    8, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    8, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(8, notif)
            }
        } catch (e: Exception) {
            log("前台通知失败: ${e.message}")
        }
    }

    private fun stopInForeground() {
        try {
            stopForeground(STOP_FOREGROUND_DETACH)
        } catch (_: Exception) {
        }
    }

    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var value: T? = null
        mainHandler.post {
            try {
                value = block()
            } catch (e: Exception) {
                log("main ${e.javaClass.simpleName} ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(3, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            stopFlag.set(true)
        }
        return value
    }

    private fun sleep(ms: Long) {
        var left = ms
        while (left > 0 && !stopFlag.get()) {
            val step = minOf(left, 400)
            try {
                Thread.sleep(step)
            } catch (_: InterruptedException) {
                return
            }
            left -= step
        }
    }

    private fun log(msg: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + msg
        logListener?.invoke(line)
        try {
            synchronized(logLock) {
                val f = File(filesDir, "claimer.log")
                if (f.length() > 180_000) {
                    val tail = f.readText().takeLast(80_000)
                    f.writeText(tail)
                }
                f.appendText(line + "\n")
            }
        } catch (_: Exception) {
        }
    }

    private fun fmt(seconds: Int?): String {
        if (seconds == null) return "?"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return "%d:%02d:%02d".format(h, m, s)
    }

    private fun backoff(n: Int) = (8_000L * n).coerceAtMost(40_000L)

    companion object {
        const val PKG = "com.etalien.booster"
        val WATCH = 635 to 2156
        val SKIP = 1132 to 254
        val DENY = 370 to 2519
        const val KWAI_WAIT_MS = 26_000L
        const val AD_CAP_MS = 70_000L
        const val MAX_ROUNDS = 24
        const val MAX_NOOPEN = 5
        const val MIN_REWARD_SEC = 8 * 60

        val AD_HINTS = listOf(
            "kwad", "byazt", "ksad", "pangle", "topon", "mintegral", "sigmob",
            "klevin", "unityads", "applovin", "gdtad", "qq.e", "reward",
            "advert", "interstitial", "splash"
        )
        val READY = listOf("领取成功", "奖励已发放", "已获得")
        val LEAVE = listOf("狠心离开", "残忍离开", "坚持退出", "确认退出")
        val COUNTDOWN = listOf(
            Regex("""(\d+)\s*s后可领取奖励"""),
            Regex("""奖励将于\s*(\d+)\s*秒后发放"""),
            Regex("""(\d+)\s*秒后可领"""),
        )
        val QUOTA = Regex("今日已领完|明日再来|已领完|全部领取|已到上限|今日广告已看完")
        val COUNTER = Regex("""(\d+)\s*时\s*(\d+)\s*分(?:\s*(\d+)\s*秒)?""")
        val RATIO = Regex("""(\d+)\s*/\s*(\d+)""")

        @Volatile var instance: AdClaimService? = null
        @Volatile var logListener: ((String) -> Unit)? = null
        @Volatile var pendingRun = false
        val running = AtomicBoolean(false)
        private val logLock = Any()

        fun isEnabled() = instance != null
        fun isRunning() = running.get()

        fun requestRun() {
            val svc = instance
            if (svc != null) svc.startLoopInternal()
            else pendingRun = true
        }

        fun requestStop() {
            pendingRun = false
            instance?.stopLoopInternal()
        }
    }
}
