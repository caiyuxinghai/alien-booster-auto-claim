package com.alienbooster.claimer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Port of rewarded_ad_loop.py v4 to an on-device AccessibilityService.
 *
 * Mapping from the adb version:
 *   dumpsys window (focus)  -> TYPE_WINDOW_STATE_CHANGED events (zero cost)
 *   uiautomator dump (~7s)  -> rootInActiveWindow traversal (local, ms)
 *   input tap               -> dispatchGesture
 *   keyevent BACK           -> performGlobalAction(GLOBAL_ACTION_BACK)
 *   am force-stop           -> unavailable; BACK + relaunch instead
 */
class AdClaimService : AccessibilityService() {

    companion object {
        const val PKG = "com.etalien.booster"
        const val MAIN_CLS = "com.etalien.booster.ui.MainActivity"

        // Screen coords, hardcoded for Honor WIN Y 1272x2800.
        val WATCH = 635 to 2156
        val SKIP = 1132 to 254
        val DENY = 370 to 2519

        const val KWAI_WAIT_MS = 26_000L
        const val AD_OPEN_TIMEOUT_MS = 10_000L
        const val FOCUS_POLL_MS = 250L
        const val MAX_NOOPEN = 6

        val AD_HINTS = listOf(
            "kwad", "byazt", "ksad", "pangle", "topon", "mintegral", "sigmob",
            "klevin", "unityads", "applovin", "gdtad", "qq.e", "reward",
            "advert", "interstitial", "splash"
        )

        val READY_TEXTS = listOf("领取成功", "奖励已发放", "已获得")
        val COUNTDOWN_RES = listOf(
            Regex("""(\d+)\s*s后可领取奖励"""),
            Regex("""奖励将于\s*(\d+)\s*秒后发放"""),
        )
        const val QUOTA_DONE_TEXT = "今日广告已看完"

        @Volatile var instance: AdClaimService? = null
        @Volatile var logListener: ((String) -> Unit)? = null
        @Volatile var runningState = AtomicBoolean(false)
        @Volatile var quotaDone = AtomicBoolean(false)

        fun isEnabled() = instance != null
        fun isRunning() = runningState.get()
        fun startLoop(rounds: Int) { instance?.startLoopInternal(rounds) }
        fun stopLoop() { instance?.stopLoopInternal() }
        fun logLine(msg: String) { instance?.log(msg) ?: logListener?.invoke(msg) }
    }

    // Latest foreground window identity, fed by accessibility events.
    @Volatile private var curPkg = ""
    @Volatile private var curCls = ""

    private val stopFlag = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onServiceConnected() {
        instance = this
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "alienbooster:claim"
        )
        log("无障碍服务已连接")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopLoopInternal()
        instance = null
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            val cls = event.className?.toString() ?: ""
            curPkg = pkg
            curCls = cls
        }
    }

    override fun onInterrupt() {}

    // ---------- primitives ----------

    private fun log(msg: String) {
        logListener?.invoke(msg)
        try {
            val f = java.io.File(getExternalFilesDir(null), "claim-log.txt")
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            f.appendText("$ts $msg\n")
        } catch (_: Exception) {}
    }

    private fun tap(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(), null, null
        )
    }

    private fun back() = performGlobalAction(GLOBAL_ACTION_BACK)

    private fun bringFront() {
        try {
            val i = Intent().apply {
                component = ComponentName(PKG, MAIN_CLS)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(i)
        } catch (e: Exception) {
            log("bringFront 失败: ${e.message}")
        }
    }

    private fun collectTexts(): Pair<List<String>, List<Pair<String, Rect>>> {
        val texts = ArrayList<String>()
        val bounds = ArrayList<Pair<String, Rect>>()
        val queue = ConcurrentLinkedQueue<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { queue.add(it) } ?: return texts to bounds
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val t = node.text?.toString()
            if (!t.isNullOrEmpty()) {
                texts.add(t)
                val r = Rect()
                node.getBoundsInScreen(r)
                bounds.add(t to r)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return texts to bounds
    }

    private fun findCenter(texts: List<Pair<String, Rect>>, text: String): Pair<Int, Int>? {
        for ((t, r) in texts) {
            if (t == text || t.contains(text)) return (r.centerX() to r.centerY())
        }
        return null
    }

    // ---------- state predicates ----------

    private fun isMain(): Boolean {
        return curPkg == PKG &&
            (curCls.endsWith("MainActivity") || curCls == MAIN_CLS)
    }

    private fun isAd(): Boolean {
        val s = "$curPkg|$curCls".lowercase()
        return AD_HINTS.any { s.contains(it) }
    }

    private fun isHonorDialog(): Boolean {
        val s = "$curPkg|$curCls".lowercase()
        return s.contains("systemmanager") || s.contains("hihonor")
    }

    // ---------- loop pieces (ported from rewarded_ad_loop.py) ----------

    private fun ensureMain(budgetMs: Long = 60_000): Boolean {
        val end = System.currentTimeMillis() + budgetMs
        var pressedBack = false
        while (System.currentTimeMillis() < end && !stopFlag.get()) {
            when {
                isMain() -> return true
                isAd() -> Thread.sleep(2000)          // let a playing ad finish
                isHonorDialog() -> { tap(DENY.first, DENY.second); Thread.sleep(1200) }
                !pressedBack && curPkg.isNotEmpty() && curPkg != PKG -> {
                    back(); pressedBack = true; Thread.sleep(1000)
                }
                else -> { bringFront(); Thread.sleep(2200) }
            }
        }
        return isMain()
    }

    private fun waitAdOpen(timeoutMs: Long = AD_OPEN_TIMEOUT_MS): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end && !stopFlag.get()) {
            if (isAd()) return true
            if (isHonorDialog()) { tap(DENY.first, DENY.second); Thread.sleep(1000) }
            Thread.sleep(FOCUS_POLL_MS)
        }
        return isAd()
    }

    private fun closeAd(i: Int, reason: String) {
        if (isAd()) {
            bringFront(); Thread.sleep(1500)
            if (isAd()) {                          // stuck -> back out + relaunch
                back(); Thread.sleep(800); bringFront(); Thread.sleep(2200)
            }
        }
        log("[$i] $reason")
    }

    private fun doAd(i: Int): Boolean {
        // Quota check BEFORE tapping: the app still plays ads after quota is
        // done (grants nothing), so detect the exhausted button text first.
        val (mainTexts, _) = collectTexts()
        if (mainTexts.any { it.contains(QUOTA_DONE_TEXT) }) {
            log("[$i] 今日广告已看完 -> quota done")
            quotaDone.set(true)
            return false
        }
        var opened = false
        for (attempt in 1..2) {
            tap(WATCH.first, WATCH.second)
            if (waitAdOpen()) { opened = true; break }
            if (attempt == 1) { log("[$i] no ad opened -> retap"); Thread.sleep(1500) }
        }
        if (!opened) {
            val (texts, _) = collectTexts()
            if (texts.any { it.contains(QUOTA_DONE_TEXT) }) {
                log("[$i] 今日广告已看完 -> quota done")
                quotaDone.set(true)
            } else {
                log("[$i] no ad opened (inventory / retry button)")
            }
            return false
        }

        val id = "$curPkg|$curCls".lowercase()
        if (id.contains("kwad") || id.contains("ksad")) {
            // 快手 SurfaceView: tree unreadable, reward banks at video end.
            Thread.sleep(KWAI_WAIT_MS)
            closeAd(i, "kwai -> waited ${KWAI_WAIT_MS / 1000}s")
            return true
        }

        var (texts, bounds) = collectTexts()
        log("[$i] ad=$curCls texts=" + texts.joinToString("|").take(200))
        if (texts.any { it.contains("想要打开") } && texts.any { it.contains("拒绝") }) {
            val c = findCenter(bounds, "拒绝") ?: DENY
            tap(c.first, c.second)
            Thread.sleep(1000)
            val again = collectTexts()
            texts = again.first; bounds = again.second
        }

        if (texts.any { t -> READY_TEXTS.any { r -> t.contains(r) } }) {
            val c = findCenter(bounds, "跳过") ?: SKIP
            tap(c.first, c.second)
            Thread.sleep(1200)
            closeAd(i, "ready -> skip")
            return true
        }

        val countdown = texts.firstNotNullOfOrNull { t ->
            COUNTDOWN_RES.firstNotNullOfOrNull { re ->
                re.find(t)?.groupValues?.get(1)?.toIntOrNull()
            }
        }
        if (countdown != null) {
            log("[$i] countdown ${countdown}s")
            Thread.sleep((countdown - 2).coerceAtLeast(1) * 1000L)
            val (texts2, bounds2) = collectTexts()
            if (texts2.any { it.contains("想要打开") } && texts2.any { it.contains("拒绝") }) {
                val c = findCenter(bounds2, "拒绝") ?: DENY
                tap(c.first, c.second)
                Thread.sleep(1000)
            }
            val c = findCenter(bounds2, "跳过") ?: SKIP
            tap(c.first, c.second)
            Thread.sleep(1200)
            closeAd(i, "dumpable -> skipped")
            return true
        }

        log("[$i] unknown ad layout -> timed skip")
        Thread.sleep(15_000)
        tap(SKIP.first, SKIP.second)
        Thread.sleep(1200)
        closeAd(i, "unknown -> timed skip")
        return true
    }

    // ---------- lifecycle ----------

    fun startLoopInternal(rounds: Int) {
        if (runningState.get()) return
        stopFlag.set(false)
        quotaDone.set(false)
        runningState.set(true)
        wakeLock?.acquire(rounds * 60_000L)
        worker = Thread {
            val t0 = System.currentTimeMillis()
            var watched = 0
            var noopenStreak = 0
            try {
                for (i in 1..rounds) {
                    if (stopFlag.get()) break
                    if (!ensureMain()) {
                        log("[$i] cannot reach main, reset")
                        bringFront(); Thread.sleep(2000)
                    }
                    try {
                        if (quotaDone.get()) {
                            log("STOP: 今日额度已完成，明天再跑。")
                            break
                        }
                        if (doAd(i)) {
                            watched++; noopenStreak = 0
                        } else {
                            if (quotaDone.get()) {
                                log("STOP: 今日额度已完成，明天再跑。")
                                break
                            }
                            noopenStreak++
                            if (noopenStreak >= MAX_NOOPEN) {
                                log("STOP: $noopenStreak ads in a row never opened " +
                                    "(likely daily quota done or no inventory).")
                                break
                            }
                        }
                    } catch (e: Exception) {
                        log("[$i] ERR ${e.message}")
                    }
                    Thread.sleep(800)
                    val elapsed = (System.currentTimeMillis() - t0) / 1000
                    log("    elapsed ${elapsed}s  watched=$watched")
                }
            } finally {
                val dur = (System.currentTimeMillis() - t0) / 1000.0
                val avg = if (watched > 0) dur / watched else 0.0
                log("DONE watched=$watched in ${dur.toInt()}s " +
                    "(${String.format("%.1f", avg)}s/ad)")
                runningState.set(false)
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        }.also { it.start() }
    }

    fun stopLoopInternal() {
        stopFlag.set(true)
        worker?.interrupt()
        worker = null
    }
}
