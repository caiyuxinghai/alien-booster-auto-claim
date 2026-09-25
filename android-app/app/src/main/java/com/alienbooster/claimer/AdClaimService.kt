package com.alienbooster.claimer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
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
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

class AdClaimService : AccessibilityService() {

    private enum class TaskResult { WATCHED, FLASH, NO_OPEN, STUCK, QUOTA }

    @Volatile private var curPkg: String = ""
    @Volatile private var curCls: String = ""
    @Volatile private var stopFlag: Boolean = false
    @Volatile private var worker: Thread? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val logLock = Any()
    private val lifeLock = Any()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        stopFlag = true
        worker?.interrupt()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        stopFlag = true
        worker?.interrupt()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrEmpty()) curPkg = pkg
        // 只有 WINDOW_STATE_CHANGED 的 className 才是 Activity 类名；
        // CONTENT_CHANGED 的 className 是控件名（FrameLayout 等），会覆盖 curCls 导致 isMain 永远为假。
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = event.className?.toString()
            if (!cls.isNullOrEmpty()) curCls = cls
        }
    }

    override fun onInterrupt() {
    }

    private fun startLoopInternal() {
        val t = Thread({ runLoop() }, "claimer-worker")
        worker = t
        t.start()
    }

    private fun runLoop() {
        var wake: PowerManager.WakeLock? = null
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "claimer:run")
            wake.setReferenceCounted(false)
            wake.acquire()

            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("claim", "claim", NotificationManager.IMPORTANCE_LOW)
            )
            val notification = Notification.Builder(this, "claim")
                .setContentTitle("claimer 运行中")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }

            var i = 0
            var done = 0
            var gainedTotal = 0
            var base: Int? = null
            var noOpenStreak = 0
            var unpaidStreak = 0

            while (!stopFlag) {
                i++
                try {
                    screenGuard()
                    if (stopFlag) break
                    if (!netOk()) {
                        log("network down -> sleep 120s (not counted)")
                        sleep(120_000)
                        continue
                    }
                    if (!ensureMain()) {
                        log("[$i] cannot reach main, reset")
                        bringFrontHost()
                        sleep(2000)
                        continue
                    }
                    if (allDone(collectTexts().first)) {
                        log("STOP: 今日任务已完成，明天再跑。")
                        break
                    }
                    if (base == null) {
                        base = readCounter()
                        if (base == null) {
                            log("baseline unreadable, retry")
                            sleep(3000)
                            continue
                        } else {
                            log("baseline counter=$base")
                        }
                    }
                    // 兜底补检：抓残留广告 / 上一轮 verify 间隙的延迟入账（PC 版 [pre] 同款）
                    val cur = readCounter()
                    if (cur != null) {
                        if (cur > base!!) {
                            val g = cur - base!!
                            done++
                            gainedTotal += g
                            base = cur
                            noOpenStreak = 0
                            unpaidStreak = 0
                            log("[$i] late +$g (today +$gainedTotal)")
                        } else if (cur < base!! - 2) {
                            log("base dropped $base->$cur -> rebase")
                            base = cur
                        }
                    }
                    when (doTask(i)) {
                        TaskResult.QUOTA -> break
                        TaskResult.NO_OPEN, TaskResult.FLASH, TaskResult.STUCK -> {
                            noOpenStreak++
                            val s = backoff(noOpenStreak)
                            log("[$i] no-open/flash/stuck x$noOpenStreak -> backoff ${s / 1000}s")
                            sleep(s)
                        }
                        TaskResult.WATCHED -> {
                            val (gained, newBase) = verifyCounter(base!!)
                            base = newBase
                            if (gained != null) {
                                done++
                                gainedTotal += gained
                                noOpenStreak = 0
                                unpaidStreak = 0
                                log("[$i] +$gained (today +$gainedTotal)")
                            } else {
                                unpaidStreak++
                                log("[$i] opened but unverified x$unpaidStreak")
                                if (unpaidStreak >= 6) {
                                    log("reset loader")
                                    bringFrontHost()
                                    sleep(3000)
                                    unpaidStreak = 0
                                    noOpenStreak++
                                }
                            }
                        }
                    }
                    sleep(Random.nextLong(1500L, 3500L))
                } catch (e: InterruptedException) {
                    if (stopFlag) break
                    Thread.interrupted()
                    log("[$i] ERR ${e.message}")
                    try {
                        Thread.sleep(800)
                    } catch (_: InterruptedException) {
                        if (stopFlag) break
                        Thread.interrupted()
                    }
                } catch (e: Exception) {
                    log("[$i] ERR ${e.message}")
                    try {
                        Thread.sleep(800)
                    } catch (_: InterruptedException) {
                        if (stopFlag) break
                    }
                }
            }
            log("DONE tasks=$done gained=+$gainedTotal")
        } catch (e: Exception) {
            log("[0] ERR ${e.message}")
        } finally {
            val mine = wake
            synchronized(lifeLock) {
                if (worker === Thread.currentThread()) {
                    isRunning.set(false)
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } catch (_: Exception) {
                    }
                }
            }
            try {
                if (mine?.isHeld == true) mine.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun log(msg: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + msg
        try {
            val listener = logListener
            if (listener != null) {
                mainHandler.post {
                    try {
                        listener(line)
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        synchronized(logLock) {
            try {
                val file = File(filesDir, "claimer.log")
                file.appendText(line + "\n", Charsets.UTF_8)
                if (file.length() > 512 * 1024) {
                    val bytes = file.readBytes()
                    val from = (bytes.size - 256 * 1024).coerceAtLeast(0)
                    file.writeBytes(bytes.copyOfRange(from, bytes.size))
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun tap(x: Int, y: Int) {
        // StrokeDescription rejects a moveTo-only path (Path.isEmpty). A 1px line keeps the tap valid.
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        path.lineTo(x + 1f, y + 1f)
        dispatchStroke(path, 50L)
    }

    private fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        val path = Path()
        path.moveTo(x1.toFloat(), y1.toFloat())
        path.lineTo(x2.toFloat(), y2.toFloat())
        dispatchStroke(path, durationMs)
    }

    private fun dispatchStroke(path: Path, durationMs: Long) {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            },
            null
        )
        if (!accepted) return
        latch.await(3, TimeUnit.SECONDS)
    }

    private fun back() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun bringFrontHost() {
        try {
            val launch = packageManager.getLaunchIntentForPackage(PKG) ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(launch)
        } catch (_: Exception) {
        }
    }

    private fun collectTexts(): Pair<List<String>, List<Pair<String, Rect>>> {
        val texts = ArrayList<String>()
        val nodes = ArrayList<Pair<String, Rect>>()
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return texts to nodes
        walk(root, texts, nodes)
        return texts to nodes
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        texts: MutableList<String>,
        nodes: MutableList<Pair<String, Rect>>
    ) {
        try {
            val text = node.text?.toString() ?: ""
            if (node.isVisibleToUser && text.isNotEmpty()) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                texts.add(text)
                nodes.add(text to rect)
            }
            val count = node.childCount
            for (i in 0 until count) {
                val child = try {
                    node.getChild(i)
                } catch (_: Exception) {
                    null
                } ?: continue
                walk(child, texts, nodes)
            }
        } catch (_: Exception) {
        } finally {
            try {
                node.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun findCenter(nodes: List<Pair<String, Rect>>, target: String): Pair<Int, Int>? {
        for ((text, rect) in nodes) {
            if (text.contains(target)) {
                return rect.centerX() to rect.centerY()
            }
        }
        return null
    }

    private fun findAnyCenter(nodes: List<Pair<String, Rect>>, targets: List<String>): Pair<Int, Int>? {
        for (target in targets) {
            val hit = findCenter(nodes, target)
            if (hit != null) return hit
        }
        return null
    }

    // 广告关闭确认框：「坚持退出 / 残忍离开 / 狠心离开」等文案，按文本定位后点击。
    private fun isAdExitDialog(): Boolean {
        val (texts, _) = collectTexts()
        return texts.any { it.contains("确定要退出吗") || it.contains("坚持退出") || it.contains("狠心离开") || it.contains("残忍离开") || it.contains("确认退出") }
    }

    private fun tapAdExitDialog() {
        val (_, nodes) = collectTexts()
        val pt = findAnyCenter(nodes, listOf("坚持退出", "狠心离开", "残忍离开", "确认退出", "退出"))
        if (pt != null) tap(pt.first, pt.second)
    }

    private fun isAd(): Boolean {
        val id = "$curPkg|$curCls".lowercase()
        return AD_HINTS.any { id.contains(it) }
    }

    private fun isMain(): Boolean {
        return curPkg == PKG && curCls.endsWith(MAIN_SUFFIX)
    }

    private fun isKwai(): Boolean {
        val id = "$curPkg|$curCls".lowercase()
        return id.contains("kwad") || id.contains("ksad")
    }

    private fun isHonorDialog(): Boolean {
        val pkg = curPkg.lowercase()
        if (pkg.contains("systemmanager") || pkg.contains("hihonor")) return true
        val (texts, _) = collectTexts()
        return texts.any { it.contains("想要打开") }
    }

    private fun nodeSignature(): Int {
        val (texts, _) = collectTexts()
        return (texts.joinToString("|") + "#" + texts.size).hashCode()
    }

    private fun netOk(): Boolean {
        // 无 INTERNET 权限下 ping 被内核拒绝（sendmsg EPERM），改用系统连通性判定；
        // 判定失败时按有网处理（fail-open），宁可白试一轮也不能误判断网瘫痪循环。
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            true
        }
    }

    @Suppress("DEPRECATION")
    private fun screenGuard() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            var fails = 0
            while (!pm.isInteractive || km.isKeyguardLocked) {
                checkStop()
                if (!pm.isInteractive) {
                    val wl = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "claimer:wake"
                    )
                    wl.acquire()
                    try {
                        sleep(2_000)
                    } finally {
                        if (wl.isHeld) wl.release()
                    }
                }
                if (km.isKeyguardLocked) {
                    val dm = resources.displayMetrics
                    val x = dm.widthPixels / 2
                    val fromY = dm.heightPixels * 3 / 4
                    val toY = dm.heightPixels / 4
                    swipe(x, fromY, x, toY, 300)
                    sleep(600)
                }
                if (pm.isInteractive && !km.isKeyguardLocked) return
                fails++
                if (fails >= 3) {
                    log("屏幕无法唤醒（可能被口袋模式遮挡距离感应器），休眠 60s")
                    sleep(60_000)
                    return
                }
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Exception) {
        }
    }

    // PC 版经验：残留广告不是「异常」而是「晚开的正常广告」。
    // 强行关闭会丢弃已播时长的奖励。正确做法：领养它，等它播完，再按正常流程收尾。
    private fun adoptOrphanAd() {
        log("orphan ad detected -> adopt and watch it out")
        val openAt = SystemClock.elapsedRealtime()
        var lastSkip = 0L
        while (!stopFlag && isAd()) {
            checkStop()
            val elapsed = SystemClock.elapsedRealtime() - openAt
            if (elapsed > UNKNOWN_MAX_MS) break
            // 播满 SAFE_VIDEO_MS 后开始尝试跳过（等同 PC 的 skip_after）
            if (elapsed >= SAFE_VIDEO_MS && SystemClock.elapsedRealtime() - lastSkip >= 4000) {
                tap(SKIP_XY.first, SKIP_XY.second)
                lastSkip = SystemClock.elapsedRealtime()
                sleep(1200)
                if (isAdExitDialog()) {
                    tapAdExitDialog()
                    sleep(1000)
                }
            }
            sleep(FOCUS_POLL_MS)
        }
        if (isAd()) {
            tap(SKIP_XY.first, SKIP_XY.second)
            sleep(1200)
            if (isAdExitDialog()) {
                tapAdExitDialog()
                sleep(1000)
            }
            if (isAd()) back()
            sleep(800)
        }
    }

    private fun ensureMain(budgetMs: Long = 60_000L): Boolean {
        val deadline = SystemClock.elapsedRealtime() + budgetMs
        while (SystemClock.elapsedRealtime() < deadline) {
            checkStop()
            if (isMain()) return true
            if (isAd()) {
                adoptOrphanAd()
                continue
            }
            if (isHonorDialog()) {
                val (_, nodes) = collectTexts()
                val pt = findCenter(nodes, "拒绝") ?: DENY_XY
                tap(pt.first, pt.second)
                sleep(1200)
                continue
            }
            if (curPkg != PKG) {
                back()
                sleep(800)
                continue
            }
            back()
            sleep(800)
        }
        return false
    }

    private fun doTask(i: Int): TaskResult {
        val (texts, nodes) = collectTexts()
        if (allDone(texts)) {
            log("[$i] 今日任务已完成 -> quota done")
            return TaskResult.QUOTA
        }
        val target = findAnyCenter(nodes, WATCH_TEXTS) ?: WATCH_XY
        tap(target.first, target.second)
        if (!waitTaskOpen(target)) {
            log("[$i] task page not opened (retry later)")
            return TaskResult.NO_OPEN
        }
        val openAt = SystemClock.elapsedRealtime()
        return watchTask(i, openAt)
    }

    private fun waitTaskOpen(target: Pair<Int, Int>): Boolean {
        if (pollUntilAd(AD_OPEN_TIMEOUT_MS)) return true
        tap(target.first, target.second)
        return pollUntilAd(5_000L)
    }

    private fun pollUntilAd(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            checkStop()
            if (isHonorDialog()) {
                val (_, nodes) = collectTexts()
                val pt = findCenter(nodes, "拒绝") ?: DENY_XY
                tap(pt.first, pt.second)
                sleep(1200)
                continue
            }
            if (isAd()) return true
            sleep(FOCUS_POLL_MS)
        }
        return false
    }

    private fun watchTask(i: Int, openAt: Long): TaskResult {
        var emptyStreak = 0
        while (true) {
            checkStop()
            val closed = closedResult(i, openAt)
            if (closed != null) return closed
            if (isKwai()) return watchKwai(i, openAt)
            val (texts, _) = collectTexts()
            if (texts.isNotEmpty()) return watchReadable(i, openAt)
            emptyStreak++
            if (emptyStreak >= 5) return watchUnknown(i, openAt)
            sleep(FOCUS_POLL_MS)
        }
    }

    private fun watchKwai(i: Int, openAt: Long): TaskResult {
        var prevSig: Int? = null
        var nextSigAt = openAt + FROZEN_AFTER_MS
        while (SystemClock.elapsedRealtime() - openAt < KWAI_WAIT_MS) {
            checkStop()
            val closed = closedResult(i, openAt)
            if (closed != null) return closed
            val now = SystemClock.elapsedRealtime()
            if (now >= nextSigAt) {
                val sig = nodeSignature()
                if (prevSig != null && prevSig == sig) {
                    closeTask(i, "frozen frame")
                    return TaskResult.STUCK
                }
                prevSig = sig
                nextSigAt = now + FROZEN_EVERY_MS
            }
            sleep(FOCUS_POLL_MS)
        }
        closeTask(i, "kwai -> waited 26s")
        return TaskResult.WATCHED
    }

    private fun watchReadable(i: Int, openAt: Long): TaskResult {
        var countdownDone = false
        var prevSig: Int? = null
        var nextSigAt = openAt + FROZEN_AFTER_MS
        while (SystemClock.elapsedRealtime() - openAt < UNKNOWN_MAX_MS) {
            checkStop()
            val closed = closedResult(i, openAt)
            if (closed != null) return closed
            val (texts, nodes) = collectTexts()
            if (READY_TEXTS.any { hint -> texts.any { it.contains(hint) } }) {
                val pt = findCenter(nodes, "跳过") ?: SKIP_XY
                tap(pt.first, pt.second)
                sleep(800)
                closeTask(i, "ready -> skip")
                return TaskResult.WATCHED
            }
            if (!countdownDone) {
                val n = readCountdown(texts)
                if (n != null) {
                    log("[$i] countdown ${n}s")
                    sleep(min(n, 60) * 1000L + 2000L)
                    countdownDone = true
                    continue
                }
            }
            val now = SystemClock.elapsedRealtime()
            if (now >= nextSigAt) {
                val sig = nodeSignature()
                if (prevSig != null && prevSig == sig) {
                    closeTask(i, "frozen frame")
                    return TaskResult.STUCK
                }
                prevSig = sig
                nextSigAt = now + FROZEN_EVERY_MS
            }
            sleep(FOCUS_POLL_MS)
        }
        closeTask(i, "timed out")
        return TaskResult.WATCHED
    }

    private fun watchUnknown(i: Int, openAt: Long): TaskResult {
        val deadline = openAt + SAFE_VIDEO_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            checkStop()
            val closed = closedResult(i, openAt)
            if (closed != null) return closed
            sleep(FOCUS_POLL_MS)
        }
        tap(SKIP_XY.first, SKIP_XY.second)
        sleep(1000)
        closeTask(i, "unknown -> timed skip")
        return TaskResult.WATCHED
    }

    private fun closedResult(i: Int, openAt: Long): TaskResult? {
        if (isAd()) return null
        return if (SystemClock.elapsedRealtime() - openAt < FLASH_MS) {
            log("[$i] task page flashed")
            TaskResult.FLASH
        } else {
            closeTask(i, "page closed itself")
            TaskResult.WATCHED
        }
    }

    private fun readCountdown(texts: List<String>): Int? {
        val blob = texts.joinToString("")
        for (re in COUNTDOWN_RES) {
            val n = re.find(blob)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (n != null) return n
        }
        return null
    }

    private fun closeTask(i: Int, reason: String) {
        if (isAd()) {
            back()
            sleep(800)
        }
        if (isAdExitDialog()) {
            tapAdExitDialog()
            sleep(800)
        }
        if (isAd()) {
            bringFrontHost()
            sleep(2200)
        }
        log("[$i] $reason")
    }

    private fun readCounter(): Int? {
        val (texts, _) = collectTexts()
        val match = PAUSE_RE.find(texts.joinToString("")) ?: return null
        val hour = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val minute = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        return hour * 60 + minute
    }

    private fun allDone(texts: List<String>): Boolean {
        val blob = texts.joinToString("")
        return QUOTA_RE.containsMatchIn(blob) || NINE_NINE_RE.containsMatchIn(blob)
    }

    private fun verifyCounter(base: Int): Pair<Int?, Int> {
        var current = base
        for (attempt in 1..8) {
            checkStop()
            var value = readCounter()
            if (value == null && curPkg != PKG) {
                bringFrontHost()
                sleep(1000)
                value = readCounter()
            }
            if (value != null) {
                if (value > current) return (value - current) to value
                if (value < current - 2) {
                    log("base dropped $current->$value -> rebase")
                    current = value
                }
            }
            if (attempt < 8) sleep(2500)
        }
        return null to current
    }

    private fun backoff(streak: Int): Long {
        val pow = 2.0.pow((streak - 1).coerceAtLeast(0).toDouble())
        val seconds = minOf(30.0 * pow, 120.0)
        return (seconds * Random.nextDouble(0.8, 1.2) * 1000.0).toLong()
    }

    private fun checkStop() {
        if (stopFlag || Thread.currentThread().isInterrupted) {
            throw InterruptedException("stop")
        }
    }

    private fun sleep(ms: Long) {
        if (ms <= 0L) return
        checkStop()
        Thread.sleep(ms)
    }

    companion object {
        private const val PKG = "com.etalien.booster"
        private const val MAIN_SUFFIX = ".ui.MainActivity"
        private val AD_HINTS = listOf("kwad","byazt","ksad","pangle","topon","mintegral","sigmob","klevin","unityads","applovin","gdtad","qq.e","reward","advert","interstitial","splash")
        private val READY_TEXTS = listOf("领取成功","奖励已发放","已获得")
        private val WATCH_TEXTS = listOf("看广告","领时长","观看广告")
        private val COUNTDOWN_RES = listOf(Regex("(\\d+)\\s*s后可领取奖励"), Regex("奖励将于\\s*(\\d+)\\s*秒后发放"))
        private val QUOTA_RE = Regex("(今日已领完|明日再来|已领完|全部领取|已到上限|已满|今日广告已看完)")
        private val NINE_NINE_RE = Regex("9\\s*/\\s*9")
        private val PAUSE_RE = Regex("(\\d+)时(\\d+)分")     // 宿主界面计数器文本格式
        private val WATCH_XY = 635 to 2156      // 触发按钮坐标兜底
        private val SKIP_XY = 1132 to 254       // 关闭按钮坐标兜底
        private val DENY_XY = 370 to 2519       // 系统跳转确认框"拒绝"坐标兜底
        private const val KWAI_WAIT_MS = 26_000L
        private const val AD_OPEN_TIMEOUT_MS = 10_000L
        private const val FOCUS_POLL_MS = 500L
        private const val FLASH_MS = 3_000L
        private const val UNKNOWN_MAX_MS = 45_000L
        private const val SAFE_VIDEO_MS = 30_000L
        private const val FROZEN_AFTER_MS = 15_000L
        private const val FROZEN_EVERY_MS = 8_000L

        @Volatile
        var instance: AdClaimService? = null

        val isRunning = AtomicBoolean(false)

        @Volatile
        var logListener: ((String) -> Unit)? = null

        fun startLoop() {
            val svc = instance ?: return
            synchronized(svc.lifeLock) {
                if (!isRunning.compareAndSet(false, true)) return
                svc.stopFlag = false
                svc.startLoopInternal()
            }
        }

        fun stopLoop() {
            val svc = instance ?: return
            svc.stopFlag = true
            svc.worker?.interrupt()
        }
    }
}
