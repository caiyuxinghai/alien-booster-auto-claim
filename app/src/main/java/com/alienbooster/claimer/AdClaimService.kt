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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
    private var runForce = false

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
        runForce = forceWatch
        forceWatch = false
        val rounds = if (runForce) 1 else MAX_ROUNDS
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
            if (runForce) log("测试：今日已看完，仍点按钮看 1 次")
            holdScreen()
            startInForeground()
            var netTries = 0
            while (!netOk() && netTries < 8 && !stopFlag.get()) {
                log("network down -> sleep 20s")
                sleep(20_000)
                netTries++
            }
            for (i in 1..rounds) {
                if (stopFlag.get()) break
                if (!screenReady()) {
                    log("屏幕锁着或点不亮，稍后重试")
                    break
                }
                refreshFromRoot()
                if (sessionOpen()) {
                    log("[$i] session already open $curPkg/$curCls")
                    val beforeP = lastProgress
                    val wall = SystemClock.elapsedRealtime()
                    if (killBooster(i)) {
                        bringFront()
                        sleep(800)
                    }
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
                    if (sessionOpen()) {
                        log("[$i] ad in the way")
                        continue
                    }
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
                sleep(200)
            }
            }
        } catch (e: Exception) {
            log("ERR ${e.javaClass.simpleName} ${e.message} ${e.stackTrace.firstOrNull()}")
        } finally {
            val dur = (SystemClock.elapsedRealtime() - t0) / 1000
            if (!holdForWifi) {
                log("DONE watched=$watched gained=+${gainedMin}min in ${dur}s finished=$finished")
            }
            if (!runForce && (finished || (!holdForWifi && quotaDone()))) Schedule.markDone(this, gainedMin)
            else if (!runForce && !stopFlag.get() && !Schedule.isDoneToday(this)) {
                Schedule.armRetry(this, if (holdForWifi) 15 * 60_000L else 20 * 60_000L)
            }
            running.set(false)
            stopInForeground()
            releaseScreen()
        }
    }

    private fun doTask(i: Int): TaskResult {
        if (quotaDone()) return TaskResult.QUOTA
        if (!armSession(i)) {
            log("[$i] task page not opened focus=$curPkg/$curCls")
            return if (quotaDone()) TaskResult.QUOTA else TaskResult.NO_OPEN
        }
        log("[$i] session armed $curPkg/$curCls")
        sleep(1200)
        if (!killBooster(i)) return TaskResult.NO_OPEN
        sleep(800)
        denyJump()
        return TaskResult.WATCHED
    }

    /**
     * A session is an ad activity. 「加载中」still sitting on MainActivity does not credit
     * when the process is killed. 「广告加载失败，点击重试」does not either.
     */
    private fun armSession(i: Int): Boolean {
        for (attempt in 1..2) {
            if (!clickWatchButton()) {
                log("[$i] 没有看广告按钮")
                return false
            }
            if (waitSession(24_000)) return true
            if (attempt == 1) {
                log("[$i] session not armed focus=$curPkg/$curCls -> retap")
                sleep(600)
            }
        }
        return false
    }

    private fun waitSession(timeoutMs: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end && !stopFlag.get()) {
            refreshFromRoot()
            if (denyJump()) {
                sleep(400)
                continue
            }
            if (sessionOpen()) return true
            if (isHonorDialog()) tap(DENY.first, DENY.second)
            sleep(300)
        }
        refreshFromRoot()
        return sessionOpen()
    }

    /**
     * Reward page only. The cj.mobile transparent shell is not a session:
     * force-stopping it leaves the stage unchanged. BeiZi reward video and
     * the octopus stopover dialog do credit.
     */
    private fun sessionOpen(): Boolean {
        if (isHonorDialog()) return false
        val cls = curCls.lowercase()
        if (cls.contains("transparent")) return false
        if (cls.contains("beizi") && !cls.contains("reward") && !cls.contains("video")) return false
        if (rewardActivity()) return true
        val texts = collectTexts().first
        if (texts.isEmpty()) return false
        val home = texts.any {
            it.contains("阶段") || it.contains("领时长") || it.contains("可暂停") || it.contains("点击重试")
        }
        if (home || !boosterLeftMain()) return false
        return looksLikeAd(texts) || texts.any { it.contains("加载中") || it.contains("请稍等") }
    }

    private fun rewardActivity(): Boolean {
        if (!(boosterLeftMain() || (!isMain() && isAd()))) return false
        val cls = curCls.lowercase()
        if (cls.contains("transparent")) return false
        if (cls.contains("beizi")) return cls.contains("reward") || cls.contains("video")
        return cls.contains("stopover") || cls.endsWith("adactivity")
    }

    private fun boosterLeftMain(): Boolean {
        return curPkg == PKG && curCls.isNotEmpty() && !curCls.endsWith("MainActivity")
    }

    /**
     * A normal app cannot force-stop another package. Shell is tried first;
     * otherwise the system app-info page's 强行停止 is the same action adb uses.
     */
    private fun killBooster(i: Int): Boolean {
        if (shellForceStop()) {
            log("[$i] force-stop shell")
            return true
        }
        return settingsForceStop(i)
    }

    private fun shellForceStop(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("am", "force-stop", PKG))
            if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return false
            }
            val err = proc.errorStream.bufferedReader().readText()
            val out = proc.inputStream.bufferedReader().readText()
            val blob = (err + out).lowercase()
            if (proc.exitValue() != 0 || err.isNotBlank()) return false
            if (blob.contains("exception") || blob.contains("permission") || blob.contains("security")) {
                return false
            }
            sleep(800)
            refreshFromRoot()
            curPkg.isNotEmpty() && curPkg != PKG
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Honor keeps 强行停止 enabled only while the booster process is alive.
     * A confirm tap is not success — success is that button going disabled.
     */
    private fun openAppInfo(): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$PKG")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            log("open app info failed ${e.message}")
            false
        }
    }

    private fun settingsForceStop(i: Int): Boolean {
        if (!openAppInfo()) return false
        val deadline = SystemClock.elapsedRealtime() + 18_000
        var swiped = false
        var tappedStop = false
        var broughtHome = false
        while (SystemClock.elapsedRealtime() < deadline && !stopFlag.get()) {
            if (confirmStopDialog()) {
                log("[$i] force-stop confirm tapped")
                sleep(700)
                continue
            }
            val (texts, _) = collectTexts()
            val blob = texts.joinToString("")
            val onPage = blob.contains("外星仔") || blob.contains("加速") || blob.contains("etalien")
            if (onPage && upperStopDisabled() && !cancelVisible()) {
                log("[$i] force-stop dead")
                sleep(700)
                bringFront()
                log("[$i] bringFront")
                sleep(1500)
                refreshFromRoot()
                if (!isMain()) {
                    if (tapExactOpen()) {
                        log("[$i] opened from settings")
                        sleep(1200)
                    } else if (reopenFromLauncher(i)) {
                        log("[$i] launcher reopen")
                    } else {
                        log("[$i] no open button")
                    }
                }
                return true
            }
            if (!tappedStop && onPage && tapUpperStop()) {
                tappedStop = true
                log("[$i] force-stop tapped")
                sleep(500)
                continue
            }
            if (!onPage && (curPkg == PKG || boosterLeftMain() || isAd()) && !broughtHome &&
                SystemClock.elapsedRealtime() > deadline - 14_000
            ) {
                broughtHome = true
                log("[$i] settings covered -> home")
                onMain { performGlobalAction(GLOBAL_ACTION_HOME) }
                sleep(600)
                openAppInfo()
                continue
            }
            if (!swiped && !tappedStop && onPage && SystemClock.elapsedRealtime() > deadline - 4_000) {
                swiped = true
                swipeUp()
            }
            sleep(300)
        }
        log("[$i] force-stop not dead")
        return false
    }

    /**
     * The confirm dialog's「强行停止」text is often not enabled; the clickable parent is.
     * Only the upper app-info button (beside 卸载) going disabled means the process died.
     */
    private fun upperStopDisabled(): Boolean {
        val hits = labeledNodes("强行停止", "强制停止", "结束运行").filter { it.second.centerY() < 1800 }
        if (hits.isEmpty()) return false
        return hits.none { nodeChainEnabled(it.first) }
    }

    private fun cancelVisible(): Boolean {
        return labeledNodes("取消").any { (n, r) -> n.text?.toString() == "取消" && r.centerY() > 1800 }
    }

    /**
     * Force-stop puts the booster in the stopped state. A background startActivity
     * cannot clear it. The home-screen usage card is not the icon — it opens
     * 健康使用手机. Search and then tap the app result, which is a real user launch.
     */
    private fun reopenFromLauncher(i: Int): Boolean {
        if (openStoppedBooster()) {
            log("[$i] launcher reopen")
            return true
        }
        log("[$i] launcher icon missing")
        return false
    }

    private fun openStoppedBooster(): Boolean {
        if (tapRealIcon()) return true
        if (tapSearchResult()) return true
        if (!curPkg.contains("launcher") && findClass("EditText") == null) {
            onMain { performGlobalAction(GLOBAL_ACTION_HOME) }
            sleep(700)
            refreshFromRoot()
        }
        if (tapRealIcon()) return true
        return typeAndLaunch()
    }

    /** Icon cell on a home page. The usage-card line is only ~50px tall and is not an icon. */
    private fun tapRealIcon(): Boolean {
        val hit = labeledNodes("外星仔加速器").filter { (n, r) ->
            val t = nodeLabel(n)
            t.contains("外星仔加速器") && !t.contains("领时长") &&
                r.height() > 80 && r.width() > 80 && r.centerY() > 400
        }.minByOrNull { it.second.centerY() } ?: return false
        tap(hit.second.centerX(), hit.second.centerY())
        return true
    }

    private fun tapSearchResult(): Boolean {
        val edit = windowEdit() ?: return false
        val query = edit.first.text?.toString().orEmpty()
        if (!query.contains("外星仔")) return false
        val hit = windowNodes("外星仔加速器").filter { (n, _) ->
            val cls = n.className?.toString().orEmpty()
            val t = nodeLabel(n)
            !cls.contains("EditText") && t.contains("外星仔加速器") && !t.contains("领时长")
        }.minByOrNull { it.second.centerY() } ?: return false
        val r = hit.second
        log("search tap ${r.centerX()},${r.centerY()}")
        clickNode(hit.first)
        sleep(700)
        refreshFromRoot()
        if (!isMain()) {
            val ok = tap(r.centerX(), r.centerY())
            log("search gesture ${if (ok) "ok" else "cancelled"}")
            sleep(500)
            bringFront()
        }
        return true
    }

    private fun typeAndLaunch(): Boolean {
        var edit = windowEdit()
        if (edit == null) {
            val chip = windowNodes("搜索").filter { (_, r) -> r.centerY() > 2100 && r.width() < 500 }
                .minByOrNull { it.second.centerY() } ?: return false
            tap(chip.second.centerX(), chip.second.centerY())
            sleep(900)
            edit = windowEdit() ?: return false
        }
        val (node, _) = edit
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            "外星仔加速器"
        )
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            log("search setText failed")
            return false
        }
        sleep(1100)
        if (tapSearchResult()) return true
        log("search result not in tree")
        val submit = labeledNodes("搜索").filter { (_, r) ->
            r.centerY() in 1200..1900 && r.centerX() > 800
        }.maxByOrNull { it.second.centerX() }
        if (submit != null) {
            tap(submit.second.centerX(), submit.second.centerY())
            sleep(900)
        }
        return tapSearchResult()
    }

    private fun nodeLabel(n: AccessibilityNodeInfo): String {
        return n.text?.toString().orEmpty().ifEmpty {
            n.contentDescription?.toString().orEmpty()
        }
    }

    private fun allRoots(): List<AccessibilityNodeInfo> {
        val wins = try {
            windows
        } catch (_: Exception) {
            null
        }
        val roots = wins?.mapNotNull { it.root }?.filter { !it.packageName.isNullOrEmpty() }.orEmpty()
        if (roots.isNotEmpty()) return roots
        val one = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        }
        return if (one != null) listOf(one) else emptyList()
    }

    private fun windowEdit(): Pair<AccessibilityNodeInfo, Rect>? {
        for (root in allRoots()) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            var n = 0
            while (stack.isNotEmpty() && n < 2000) {
                val node = stack.removeLast()
                n++
                if (node.className?.toString().orEmpty().contains("EditText")) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (onScreen(r)) return node to r
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
            }
        }
        return null
    }

    private fun windowNodes(vararg labels: String): List<Pair<AccessibilityNodeInfo, Rect>> {
        val hits = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        for (root in allRoots()) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            var n = 0
            while (stack.isNotEmpty() && n < 2000) {
                val node = stack.removeLast()
                n++
                val label = nodeLabel(node)
                if (labels.any { label == it || label.contains(it) }) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (onScreen(r)) hits.add(node to r)
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
            }
        }
        return hits
    }

    private fun findClass(substr: String): Pair<AccessibilityNodeInfo, Rect>? {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var n = 0
        while (stack.isNotEmpty() && n < 2000) {
            val node = stack.removeLast()
            n++
            if (node.className?.toString().orEmpty().contains(substr)) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (onScreen(r)) return node to r
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    /** Honor asks before an ad jumps to another app. 拒绝 sits on the same spot as 取消. */
    private fun denyJump(): Boolean {
        val nodes = labeledNodes("拒绝", "允许")
        val deny = nodes.find { (n, _) -> n.text?.toString() == "拒绝" } ?: return false
        if (nodes.none { (n, _) -> n.text?.toString() == "允许" }) return false
        clickNode(deny.first)
        return true
    }

    /** After force-stop, Settings' 打开 is allowed to launch the stopped app. Not 默认打开. */
    private fun tapExactOpen(): Boolean {
        val hit = labeledNodes("打开").filter { (n, r) ->
            val t = n.text?.toString().orEmpty().ifEmpty {
                n.contentDescription?.toString().orEmpty()
            }
            t == "打开" && r.centerY() < 1800
        }.minByOrNull { it.second.centerY() } ?: return false
        clickNode(hit.first)
        return true
    }

    private fun tapUpperStop(): Boolean {
        val hit = labeledNodes("强行停止", "强制停止", "结束运行")
            .filter { it.second.centerY() < 1800 && nodeChainEnabled(it.first) }
            .maxByOrNull { it.second.centerY() } ?: return false
        clickNode(hit.first)
        return true
    }

    /** Honor shows the confirm dialog a few seconds after 强行停止. Click the button on the same row as 取消. */
    private fun confirmStopDialog(): Boolean {
        val nodes = labeledNodes("取消", "确定", "确认", "强行停止", "强制停止")
        val cancel = nodes.filter { (n, r) -> onScreen(r) && n.text?.toString() == "取消" }
            .maxByOrNull { it.second.centerY() } ?: return false
        val cancelBox = cancel.second
        val ok = nodes.filter { (n, r) ->
            val t = n.text?.toString().orEmpty()
            onScreen(r) && r.centerX() > cancelBox.centerX() + 40 &&
                kotlin.math.abs(r.centerY() - cancelBox.centerY()) < 160 &&
                (t == "确定" || t == "确认" || t == "强行停止" || t == "强制停止")
        }.maxByOrNull { it.second.centerX() } ?: return false
        clickNode(ok.first)
        return true
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 5) {
            if (cur.isClickable && cur.isEnabled) {
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            }
            cur = cur.parent
            hops++
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (onScreen(r)) tap(r.centerX(), r.centerY())
    }

    private fun labeledNodes(vararg labels: String): List<Pair<AccessibilityNodeInfo, Rect>> {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val hits = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var n = 0
        while (stack.isNotEmpty() && n < 2000) {
            val node = stack.removeLast()
            n++
            val label = node.text?.toString().orEmpty().ifEmpty {
                node.contentDescription?.toString().orEmpty()
            }
            if (labels.any { label == it || label.contains(it) }) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (onScreen(r)) hits.add(node to r)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
        }
        return hits
    }

    private fun tapEnabledLabel(vararg labels: String): Boolean {
        val point = findEnabledLabel(*labels) ?: return false
        tap(point.first, point.second)
        return true
    }

    private fun findEnabledLabel(vararg labels: String): Pair<Int, Int>? {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var best: Pair<Int, Int>? = null
        var bestY = -1
        var n = 0
        while (stack.isNotEmpty() && n < 400) {
            val node = stack.removeLast()
            n++
            val label = node.text?.toString().orEmpty().ifEmpty {
                node.contentDescription?.toString().orEmpty()
            }
            if (labels.any { label.contains(it) } && nodeChainEnabled(node)) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (onScreen(r) && r.centerY() >= bestY) {
                    best = r.centerX() to r.centerY()
                    bestY = r.centerY()
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
        }
        return best
    }

    private fun nodeChainEnabled(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 4) {
            if (!cur.isEnabled) return false
            cur = cur.parent
            hops++
        }
        return true
    }

    private fun swipeUp() {
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                val path = Path().apply {
                    moveTo(636f, 1900f)
                    lineTo(636f, 900f)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0, 280)
                val sent = dispatchGesture(
                    GestureDescription.Builder().addStroke(stroke).build(),
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) = latch.countDown()
                        override fun onCancelled(gestureDescription: GestureDescription?) = latch.countDown()
                    },
                    null
                )
                if (!sent) latch.countDown()
            } catch (_: Exception) {
                latch.countDown()
            }
        }
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
    }

    private fun clickWatchButton(): Boolean {
        val point = findWatchPoint() ?: return false
        log("tap ${point.first},${point.second}")
        tap(point.first, point.second)
        return true
    }

    private fun waitCredit(before: Int?, beforeP: String, wallStart: Long): Int {
        val deadline = SystemClock.elapsedRealtime() + 18_000
        var best = 0
        var sawMain = false
        while (SystemClock.elapsedRealtime() < deadline && !stopFlag.get()) {
            if (denyJump()) {
                sleep(700)
                continue
            }
            if (!isMain()) {
                when {
                    isAd() -> sleep(1000)
                    curPkg.contains("settings") -> {
                        back()
                        sleep(400)
                    }
                    else -> {
                        if (!openStoppedBooster()) bringFront()
                        sleep(800)
                    }
                }
                sleep(400)
                continue
            }
            if (!sawMain) {
                log("back on main")
                sawMain = true
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
            sleep(300)
        }
        return best
    }

    private fun ensureMain(budgetMs: Long = 50_000): Boolean {
        val end = SystemClock.elapsedRealtime() + budgetMs
        var backs = 0
        var logged = ""
        var searchTried = false
        while (SystemClock.elapsedRealtime() < end && !stopFlag.get()) {
            val focus = "$curPkg/$curCls"
            if (focus != logged) {
                log("focus $focus")
                logged = focus
            }
            when {
                sessionOpen() -> return false
                isHonorDialog() -> {
                    tap(DENY.first, DENY.second)
                    sleep(600)
                }
                curPkg.contains("settings") -> {
                    backs++
                    back()
                    sleep(450)
                    if (backs >= 3) {
                        bringFront()
                        backs = 0
                    }
                }
                denyJump() -> sleep(400)
                curPkg.contains("launcher") || curPkg.contains("hihonor.search") -> {
                    bringFront()
                    log("bringFront")
                    sleep(1500)
                    refreshFromRoot()
                    if (!(isMain() && homeReady()) && !searchTried) {
                        searchTried = true
                        openStoppedBooster()
                    }
                    sleep(600)
                }
                isMain() && homeReady() -> return true
                curPkg == PKG -> {
                    backs++
                    back()
                    sleep(700)
                    if (backs >= 2) {
                        bringFront()
                        sleep(1200)
                        backs = 0
                    }
                }
                else -> {
                    bringFront()
                    sleep(1200)
                }
            }
            if (!isAd()) refreshFromRoot()
        }
        return isMain() && homeReady()
    }

    private fun looksLikeAd(texts: List<String>): Boolean {
        val blob = texts.joinToString("")
        return blob.contains("跳过") || blob.contains("立即前往") || blob.contains("可立即领奖") ||
            blob.contains("领取奖励") || blob.contains("去体验") || blob.contains("去看")
    }

    private fun homeReady(): Boolean {
        val texts = collectTexts().first
        if (texts.any { QUOTA.containsMatchIn(it) }) return true
        return texts.any { it.contains("领时长") || it.contains("可暂停") || it.contains("阶段") }
    }

    private fun quotaDone(): Boolean {
        if (runForce) return false
        val texts = collectTexts().first
        if (texts.any { QUOTA.containsMatchIn(it) }) return true
        val done = texts.count { it == "已完成" }
        val stillOpen = texts.any { it == "待解锁" || RATIO.containsMatchIn(it) }
        return done >= 3 && !stillOpen
    }

    private fun findWatchPoint(): Pair<Int, Int>? {
        val (_, bounds) = collectTexts()
        val labels = mutableListOf("领时长", "点击重试")
        if (runForce) labels.addAll(listOf("明日再来", "今日广告已看完"))
        val hit = bounds
            .filter { (t, r) -> onScreen(r) && labels.any { label -> t.contains(label) } }
            .maxByOrNull { it.second.centerY() }
        if (hit != null) return hit.second.centerX() to hit.second.centerY()
        return WATCH
    }

    private fun onScreen(r: Rect): Boolean {
        return r.left >= 0 && r.top >= 0 && r.right > r.left && r.bottom > r.top
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

    private fun isMain(): Boolean {
        if (curPkg != PKG) return false
        if (curCls.isEmpty()) return true
        return curCls.startsWith(PKG) && curCls.endsWith("MainActivity")
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

    /** Off the main looper. A node query there waits for an event the looper can no longer deliver. */
    private fun refreshFromRoot() {
        try {
            val pkg = rootInActiveWindow?.packageName?.toString().orEmpty()
            if (pkg.isEmpty() || pkg == curPkg) return
            curPkg = pkg
            curCls = ""
        } catch (_: Exception) {
        }
    }

    private fun collectTexts(): Pair<List<String>, List<Pair<String, Rect>>> {
        val texts = ArrayList<String>()
        val bounds = ArrayList<Pair<String, Rect>>()
        try {
            val root = rootInActiveWindow ?: return texts to bounds
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
        } catch (_: Exception) {
        }
        return texts to bounds
    }

    private fun tap(x: Int, y: Int): Boolean {
        if (x < 0 || y < 0) return false
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        mainHandler.post {
            try {
                val path = Path().apply {
                    moveTo(x.toFloat(), y.toFloat())
                    lineTo(x + 1f, y + 1f)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0, 120)
                val sent = dispatchGesture(
                    GestureDescription.Builder().addStroke(stroke).build(),
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            ok.set(true)
                            latch.countDown()
                        }
                        override fun onCancelled(gestureDescription: GestureDescription?) = latch.countDown()
                    },
                    null
                )
                if (!sent) latch.countDown()
            } catch (_: Exception) {
                latch.countDown()
            }
        }
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        return ok.get()
    }

    private fun back() {
        onMain { performGlobalAction(GLOBAL_ACTION_BACK) }
    }

    private fun bringFront() {
        try {
            val intent = Intent().apply {
                component = ComponentName(PKG, "com.etalien.booster.ui.MainActivity")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
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
            if (!latch.await(3, TimeUnit.SECONDS)) return null
        } catch (_: InterruptedException) {
            return null
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
        val DENY = 370 to 2519
        const val MAX_ROUNDS = 24
        const val MAX_NOOPEN = 5
        const val MIN_REWARD_SEC = 8 * 60

        val AD_HINTS = listOf(
            "kwad", "byazt", "ksad", "pangle", "topon", "mintegral", "sigmob",
            "klevin", "unityads", "applovin", "gdtad", "qq.e", "reward",
            "advert", "interstitial", "splash", "beizi"
        )
        val QUOTA = Regex("今日已领完|明日再来|已领完|全部领取|已到上限|今日广告已看完")
        val COUNTER = Regex("""(\d+)\s*时\s*(\d+)\s*分(?:\s*(\d+)\s*秒)?""")
        val RATIO = Regex("""(\d+)\s*/\s*(\d+)""")

        @Volatile var instance: AdClaimService? = null
        @Volatile var logListener: ((String) -> Unit)? = null
        @Volatile var pendingRun = false
        @Volatile var forceWatch = false
        val running = AtomicBoolean(false)
        private val logLock = Any()

        fun isEnabled() = instance != null
        fun isRunning() = running.get()

        fun requestRun(force: Boolean = false) {
            if (force) forceWatch = true
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
