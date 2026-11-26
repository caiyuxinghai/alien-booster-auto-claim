package com.alienbooster.claimer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.content.pm.ServiceInfo
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A single, explicit user-started normal UI loop. No shell or Shizuku dependency. */
class AdAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var generation = 0
    private var sessionLimit = 21
    private var credits = 0
    private var failures = 0
    private var startedAt = 0L
    private var historyStartedAt = 0L
    private var historyClockStarted = 0L
    private var watchStarted = 0L
    val isActiveRun: Boolean get() = active
    private var lastAction = 0L
    private var returnedAt = 0L
    private var outsideAt = 0L
    private var outsideReturns = 0
    private var noScreenAt = 0L
    private var missingMainAt = 0L
    private var pending = false
    private var adSeen = false
    private var beforeStages: List<Int>? = null
    private var beforeDuration: Int? = null
    private var lastStatus = ""
    private enum class StopPhase { NONE, OPEN_DETAILS, CONFIRM_STOP, REOPEN }
    private var stopPhase = StopPhase.NONE
    private var phaseAt = 0L
    private var targetDetailsSeen = false
    private var confirmationClicked = false
    private var restarted = false
    private var adOpenedAt = 0L
    private val stopTexts = setOf("强行停止", "强制停止", "结束运行")
    private val targetLabel by lazy {
        try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(TARGET, 0)).toString() }
        catch (_: Throwable) { "外星仔加速器" }
    }
    private var screenLock: PowerManager.WakeLock? = null
    private val prefs by lazy { getSharedPreferences("ad_loop_v1", MODE_PRIVATE) }
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            try { inspect() } catch (t: Throwable) {
                SoloStore.log(this@AdAccessibilityService, t.stackTraceToString().take(1500))
                pauseRun("读取界面失败，请再次点开始：${t.javaClass.simpleName}")
            }
            if (active) handler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        instance = this
        SoloStore.update(this, running = false, status = "权限已就绪，点开始 / 继续")
        SoloStore.log(this, "手机自动操作服务已就绪")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!active || event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() !in installSurfaces) return
        val root = rootInActiveWindow ?: return
        val packageName = root.packageName?.toString()
        root.recycle()
        if (packageName in installSurfaces) stopForInstallSurface()
    }

    override fun onInterrupt() = pauseRun("系统中断，请再次点开始 / 继续")

    override fun onDestroy() {
        pauseRun("服务已关闭，请重新开启无障碍权限")
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun canStartFromDashboardOrLauncher(): Boolean {
        val root = rootInActiveWindow ?: return false
        val currentPackage = root.packageName?.toString().orEmpty()
        root.recycle()
        return currentPackage == packageName || currentPackage in launcherPackages
    }

    /** Re-read actual progress; a saved pending ad is reconciled before another is started. */
    fun startRun(limit: Int = 21) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { startRun(limit) }
            return
        }
        if (active) return
        if (!RunPrefs.useAccessibility(this)) {
            SoloStore.update(this, running = false, status = "请先在领取页选择无障碍主屏")
            return
        }
        if (!canStartFromDashboardOrLauncher()) {
            SoloStore.update(this, running = false, status = "请回到桌面或领取页再开始，避免打断其他应用")
            return
        }
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked) {
            SoloStore.update(this, running = false, status = "请先解锁手机，再点开始 / 继续")
            return
        }
        generation++
        active = true
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("run", "自动领取运行状态", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "run").setContentTitle("外星仔正在自动领取")
            .setContentText("点此返回控制页，可随时暂停")
            .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(100, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(100, notification)
        holdScreen()
        sessionLimit = limit.coerceIn(1, 21)
        credits = 0
        failures = 0
        historyStartedAt = System.currentTimeMillis()
        historyClockStarted = SystemClock.elapsedRealtime()
        startedAt = now()
        watchStarted = startedAt
        lastAction = 0
        returnedAt = 0
        outsideAt = 0
        outsideReturns = 0
        noScreenAt = 0
        missingMainAt = 0
        stopPhase = StopPhase.NONE
        restarted = false
        adOpenedAt = 0
        targetDetailsSeen = false
        confirmationClicked = false
        lastStatus = ""
        restorePending()
        if (pending && adSeen) adOpenedAt = watchStarted
        SoloStore.update(this, running = true)
        status(if (pending) "继续上次流程，核对实际进度" else "正在打开外星仔")
        val root = rootInActiveWindow
        val alreadyTarget = root?.packageName?.toString() == TARGET
        root?.recycle()
        if (!alreadyTarget) {
            val intent = targetIntent()
            if (intent == null) {
                pauseRun("未找到外星仔加速器")
                return
            }
            try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Throwable) { pauseRun("无法打开外星仔，请手动打开后再点继续"); return }
        }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 800)
    }

    fun pauseRun(reason: String = "已暂停") {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { pauseRun(reason) }
            return
        }
        val recordStarted = historyStartedAt
        val recordClock = historyClockStarted
        historyStartedAt = 0
        historyClockStarted = 0
        active = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        generation++
        handler.removeCallbacks(tick)
        releaseScreen()
        stopPhase = StopPhase.NONE
        SoloStore.update(this, running = false, status = reason)
        if (lastStatus != reason) SoloStore.log(this, reason)
        lastStatus = reason
        if (recordStarted > 0) {
            val elapsed = (SystemClock.elapsedRealtime() - recordClock).coerceAtLeast(0)
            runCatching {
                RunHistory.recordLocal(this, recordStarted, System.currentTimeMillis(), elapsed,
                    "主屏仅主号", reason, credits)
            }.onFailure { SoloStore.log(this, "耗时记录保存失败：${it.javaClass.simpleName}") }
        }
    }

    private fun inspect() {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked) { pauseRun("手机已锁屏，解锁后点继续"); return }
        if (!RunPrefs.networkAllowed(this)) { pauseRun(RunPrefs.networkReason(this)); return }
        if (now() - startedAt > 3 * 60 * 60_000L) { pauseRun("本次运行时间较长，已暂停"); return }
        val root = rootInActiveWindow
        if (root == null) {
            if (noScreenAt == 0L) noScreenAt = now()
            if (now() - noScreenAt > 12_000) pauseRun("读不到当前界面，请回到外星仔后点继续")
            return
        }
        noScreenAt = 0
        if (root.packageName?.toString() in installSurfaces) {
            root.recycle()
            stopForInstallSurface()
            return
        }
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collect(root, nodes, 0)
        try {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg == "com.hihonor.android.internal.app" &&
                (now() - startedAt < 15_000 || stopPhase == StopPhase.REOPEN || (pending && adSeen)) &&
                nodes.any { label(it) == "使用以下方式打开" }) {
                val owner = nodes.firstOrNull { it.viewIdResourceName == "android:id/text1" &&
                    label(it) == targetLabel && !it.contentDescription?.toString().orEmpty().contains("分身") }
                if (owner != null && now() - lastAction >= 1000 && tap(Rect().also(owner::getBoundsInScreen))) {
                    lastAction = now()
                    outsideAt = 0
                    status("已选择外星仔主号，继续当前流程")
                }
                return
            }
            if (dismissBlockingDialog(pkg, nodes)) return
            if (stopPhase != StopPhase.NONE) {
                inspectRestart(pkg, nodes)
                return
            }
            if (pkg != TARGET) {
                if (outsideAt == 0L) outsideAt = now()
                when (AdPolicy.outsideAction(pkg == packageName, now() - startedAt, now() - outsideAt, pending && adSeen, outsideReturns)) {
                    AdPolicy.OutsideAction.WAIT -> Unit
                    AdPolicy.OutsideAction.RETURN -> {
                        outsideReturns++
                        outsideAt = now()
                        status("广告跳到外部应用，正在返回外星仔")
                        val back = targetIntent()
                        if (back == null) {
                            pauseRun("未找到外星仔加速器")
                        } else {
                            runCatching { startActivity(back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                .onFailure { pauseRun("无法返回外星仔，请手动打开后点继续") }
                        }
                    }
                    AdPolicy.OutsideAction.PAUSE -> pauseRun("已离开外星仔，回到应用后点继续")
                }
                return
            }
            outsideAt = 0
            val main = nodes.any { it.viewIdResourceName?.endsWith(":id/UIPCADTaskList") == true }
            if (main) inspectMain(nodes) else inspectAd(nodes)
        } finally {
            nodes.asReversed().forEach { it.recycle() }
        }
    }

    private fun stopForInstallSurface() {
        pauseRun("检测到应用市场或安装器，已暂停。返回后可手动继续。")
    }

    private fun inspectMain(nodes: List<AccessibilityNodeInfo>) {
        val labels = nodes.filter { it.viewIdResourceName?.endsWith(":id/UIStateTitle") == true }.map { label(it) }
        val stages = AdPolicy.stageCounts(labels)
        val duration = readDuration(nodes)
        if (stages != null) SoloStore.update(this, stages = "阶段一 ${stages[0]}/9　阶段二 ${stages[1]}/3　加油包 ${stages[2]}/9")
        if (pending) {
            if (!restarted) {
                if (adSeen) {
                    if (watchReady()) beginRestart() else waitingStatus()
                } else if (now() - watchStarted >= 30_000) beginRestart("广告未打开，重启核对")
                else status("等待广告加载，核对前不会重复点击", log = false)
                return
            }
            if (returnedAt == 0L) returnedAt = now()
            if (now() - returnedAt < 1500) { status("广告返回，正在核对领取结果"); return }
            if (stages == null) {
                if (now() - returnedAt > 20_000) pauseRun("重启后读不到完整阶段，请回到主页后点继续")
                return
            }
            val hasBaseline = beforeStages != null || beforeDuration != null
            val credited = hasBaseline && AdPolicy.credited(beforeStages, stages, beforeDuration, duration)
            if (credited) {
                credits++
                failures = 0
                SoloStore.log(this, "确认入账，当前阶段 ${stages?.joinToString("/") ?: "未知"}；本次已确认 $credits 条")
            } else if (hasBaseline) {
                failures++
                SoloStore.log(this, "未确认入账（连续 $failures 次），以外星仔显示进度为准")
            } else {
                SoloStore.log(this, "已返回主界面，缺少中断前基线；读取当前阶段继续，不补记成功")
            }
            clearPending()
            lastAction = now()
            if (failures >= 3) { pauseRun("连续 3 次未确认入账，已暂停；可稍后点继续"); return }
            if (credits >= sessionLimit) { pauseRun("本次已确认 $credits 条，已暂停"); return }
        }
        if (AdPolicy.complete(stages)) { pauseRun("今日 21 条已完成（以外星仔阶段进度为准）"); return }
        if (stages == null) {
            if (missingMainAt == 0L) missingMainAt = now()
            status("等待读取完整的三个阶段")
            if (now() - missingMainAt > 20_000) pauseRun("无法读取阶段进度，请回到外星仔主页后点继续")
            return
        }
        if (now() - lastAction < 2200) return
        val cta = nodes.firstOrNull { it.viewIdResourceName?.endsWith(":id/UIADSubmitText") == true }
        val text = cta?.let(::label).orEmpty()
        if (text.contains("明日") || text.contains("已完成") || text.contains("用尽") || text.contains("上限")) {
            pauseRun("外星仔提示：$text")
            return
        }
        if (cta == null || (!text.contains("广告") && !text.contains("重试") && !text.contains("领取"))) {
            if (missingMainAt == 0L) missingMainAt = now()
            status("等待领取按钮")
            if (now() - missingMainAt > 20_000) pauseRun("没有可用的领取按钮，稍后可点继续")
            return
        }
        missingMainAt = 0
        beforeStages = stages
        beforeDuration = duration
        pending = true
        adSeen = false
        restarted = false
        adOpenedAt = 0
        watchStarted = now()
        returnedAt = 0
        outsideReturns = 0
        savePending()
        val button = nodes.firstOrNull { it.viewIdResourceName?.endsWith(":id/UIADSubmit") == true } ?: cta
        if (click(button)) {
            lastAction = now()
            SoloStore.log(this, "点击领取：$text；计时开始")
            status("广告播放中，计时后重启核对实际进度")
        } else {
            clearPending()
            pauseRun("无法点击领取按钮，请手动回到主页后点继续")
        }
    }

    private fun inspectAd(nodes: List<AccessibilityNodeInfo>) {
        missingMainAt = 0
        if (!pending) {
            if (now() - startedAt < 6000) { status("正在读取外星仔主页"); return }
            if (nodes.any { label(it) == "立即登录" || label(it) == "登录" }) {
                pauseRun("请先在外星仔登录，再点继续")
                return
            }
            pending = true
            adSeen = true
            beforeStages = null
            beforeDuration = null
            watchStarted = now()
            adOpenedAt = watchStarted
            restarted = false
            savePending()
            SoloStore.log(this, "继续已有广告，重新计时 20 秒后核对实际阶段")
        }
        if (!adSeen) {
            adSeen = true
            adOpenedAt = now()
            savePending()
            SoloStore.log(this, "检测到广告界面，开始至少 15 秒打开计时")
        }
        returnedAt = 0
        if (watchReady()) beginRestart() else waitingStatus()
    }

    private fun watchReady() = now() - watchStarted >= 20_000 && adSeen && now() - adOpenedAt >= 15_000
    private fun waitingStatus() = status("广告计时 ${((now() - watchStarted) / 1000)} 秒，随后重启核对实际进度", log = false)

    private fun beginRestart(message: String = "计时完成，打开外星仔应用信息") {
        stopPhase = StopPhase.OPEN_DETAILS
        phaseAt = now()
        lastAction = 0
        targetDetailsSeen = false
        confirmationClicked = false
        outsideAt = 0
        status(message)
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$TARGET")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SoloStore.log(this, "打开目标应用信息：$TARGET")
        } catch (t: Throwable) {
            pauseRun("打不开外星仔应用信息：${t.javaClass.simpleName}")
        }
    }

    /** Only this self-initiated, identity-checked flow may operate system Settings. */
    private fun inspectRestart(pkg: String, nodes: List<AccessibilityNodeInfo>) {
        if (now() - phaseAt > 20_000) {
            val reason = when (stopPhase) {
                StopPhase.OPEN_DETAILS -> "应用信息页未找到目标强行停止按钮"
                StopPhase.CONFIRM_STOP -> "系统强行停止确认超时"
                StopPhase.REOPEN -> "重开外星仔后未出现主页"
                else -> "重启流程超时"
            }
            pauseRun("$reason（20 秒），请处理后点继续")
            return
        }
        if (stopPhase == StopPhase.REOPEN) {
            if (pkg == TARGET && nodes.any { it.viewIdResourceName == "$TARGET:id/UIPCADTaskList" }) {
                stopPhase = StopPhase.NONE
                restarted = true
                adSeen = true
                savePending()
                returnedAt = 0
                outsideAt = 0
                inspectMain(nodes)
            }
            return
        }
        if (pkg != "com.android.settings") return
        val detailsMatch = nodes.any { it.viewIdResourceName == "android:id/title" && label(it) == targetLabel }
        if (detailsMatch) targetDetailsSeen = true
        val stopButton = nodes.firstOrNull {
            it.viewIdResourceName == "com.android.settings:id/right_button" && label(it) in stopTexts
        }
        if (stopPhase == StopPhase.OPEN_DETAILS) {
            if (!detailsMatch || stopButton == null) return
            if (!stopButton.isEnabled) { reopenAfterStop("目标强行停止按钮已禁用，重开应用"); return }
            if (click(stopButton)) {
                SoloStore.log(this, "点击系统强行停止：$targetLabel")
                lastAction = now()
                stopPhase = StopPhase.CONFIRM_STOP
                phaseAt = now()
                status("等待系统强行停止确认")
            }
            return
        }
        if (!targetDetailsSeen) return
        val message = nodes.firstOrNull { it.viewIdResourceName == "android:id/message" }?.let(::label).orEmpty()
        val confirm = nodes.firstOrNull {
            it.viewIdResourceName == "android:id/button1" && (label(it) in stopTexts || label(it) == "确定")
        }
        if (!confirmationClicked && confirm != null && (message.contains("强行停止") || message.contains("强制停止") || message.contains("停止该应用"))) {
            if (now() - lastAction < 700) return
            if (click(confirm)) {
                confirmationClicked = true
                lastAction = now()
                SoloStore.log(this, "确认系统强行停止：$targetLabel")
                status("已确认强行停止，等待重开")
            }
            return
        }
        if (detailsMatch && stopButton != null && !stopButton.isEnabled) {
            reopenAfterStop("目标已停止，冷启动读取实际阶段")
        } else if (confirmationClicked && confirm == null && detailsMatch && now() - lastAction >= 1500) {
            reopenAfterStop("系统确认框已关闭，冷启动读取实际阶段")
        }
    }

    private fun reopenAfterStop(message: String) {
        stopPhase = StopPhase.REOPEN
        phaseAt = now()
        restarted = true
        adSeen = true
        savePending()
        status(message)
        val intent = targetIntent()
        if (intent == null) { pauseRun("未找到外星仔加速器"); return }
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SoloStore.log(this, "冷启动目标应用，随后核验服务器阶段")
        } catch (_: Throwable) { pauseRun("无法重开外星仔，请手动打开后点继续") }
    }

    private fun dismissBlockingDialog(pkg: String, nodes: List<AccessibilityNodeInfo>): Boolean {
        val raw = nodes.map(::label).filter(String::isNotBlank).joinToString("\n")
        val exitPrompt = pkg == TARGET && raw.contains("温馨提醒") && raw.contains("确定要退出")
        val externalPrompt = pkg in setOf("com.hihonor.systemmanager", "com.android.settings", "com.android.permissioncontroller") &&
            (raw.contains("想要打开") || raw.contains("请求打开")) && (raw.contains("应用") || raw.contains("外星仔"))
        if (!exitPrompt && !externalPrompt) return false
        if (now() - lastAction < 1000) return true
        val dismiss = nodes.firstOrNull { label(it) == "拒绝" && it.isVisibleToUser } ?: nodes.firstOrNull { label(it) == "取消" && it.isVisibleToUser }
        if (dismiss != null && click(dismiss)) {
            lastAction = now()
            SoloStore.log(this, if (exitPrompt) "取消外星仔退出提醒" else "拒绝打开外部应用，继续当前流程")
        }
        return true
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        out.add(node)
        if (depth > 40 || out.size > 1200) return
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, out, depth + 1) }
    }

    private fun label(node: AccessibilityNodeInfo): String = node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)
        ?: node.contentDescription?.toString()?.trim().orEmpty()

    private fun click(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled) return false
        var current: AccessibilityNodeInfo? = node
        var owned = false
        for (depth in 0..3) {
            val n = current ?: break
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                if (owned) n.recycle()
                return true
            }
            val next = n.parent
            if (owned) n.recycle()
            current = next
            owned = true
        }
        if (owned) current?.recycle()
        return tap(Rect().also(node::getBoundsInScreen))
    }

    private fun tap(rect: Rect): Boolean {
        if (rect.isEmpty || rect.centerX() !in 0 until resources.displayMetrics.widthPixels || rect.centerY() !in 0 until resources.displayMetrics.heightPixels) return false
        val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build(), null, handler)
    }

    private fun readDuration(nodes: List<AccessibilityNodeInfo>): Int? {
        fun value(suffix: String) = nodes.firstOrNull { it.viewIdResourceName?.endsWith(":id/$suffix") == true }?.let(::label)?.toIntOrNull()
        val hours = value("UIPCDurationHourTxt") ?: return null
        val minutes = value("UIPCDurationMinuteTxt") ?: return null
        return hours * 3600 + minutes * 60 + (value("UIPCDurationSecondTxt") ?: 0)
    }

    private fun targetIntent(): Intent? {
        return packageManager.getLaunchIntentForPackage(TARGET)
    }

    private fun status(message: String, log: Boolean = true) {
        SoloStore.update(this, running = active, status = message)
        if (lastStatus != message && log) SoloStore.log(this, message)
        lastStatus = message
    }

    private fun savePending() {
        prefs.edit().putBoolean("pending", pending).putString("day", today())
            .putString("beforeStages", beforeStages?.joinToString(","))
            .putInt("beforeDuration", beforeDuration ?: -1).putBoolean("adSeen", adSeen).apply()
    }

    private fun restorePending() {
        pending = prefs.getBoolean("pending", false) && prefs.getString("day", "") == today()
        if (pending) {
            beforeStages = prefs.getString("beforeStages", null)?.split(",")?.mapNotNull(String::toIntOrNull)?.takeIf { it.size == 3 }
            beforeDuration = prefs.getInt("beforeDuration", -1).takeIf { it >= 0 }
            adSeen = prefs.getBoolean("adSeen", false)
        } else clearPending()
    }

    private fun clearPending() {
        pending = false
        adSeen = false
        restarted = false
        adOpenedAt = 0
        beforeStages = null
        beforeDuration = null
        returnedAt = 0
        outsideReturns = 0
        prefs.edit().remove("pending").remove("beforeStages").remove("beforeDuration").remove("adSeen").apply()
    }

    private fun now() = SystemClock.uptimeMillis()
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

    @Suppress("DEPRECATION")
    private fun holdScreen() {
        releaseScreen()
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            screenLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "alienclaimer:active").apply {
                setReferenceCounted(false)
                acquire(3 * 60 * 60_000L)
            }
        } catch (_: Throwable) {
            SoloStore.log(this, "无法保持亮屏，请手动保持屏幕开启")
        }
    }

    private fun releaseScreen() {
        try { screenLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) { }
        screenLock = null
    }

    companion object {
        private val launcherPackages = setOf(
            "com.hihonor.android.launcher",
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher"
        )
        private val installSurfaces = setOf(
            "com.hihonor.appmarket",
            "com.huawei.appmarket",
            "com.android.vending",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.hihonor.android.packageinstaller",
            "com.huawei.android.packageinstaller"
        )
        const val TARGET = "com.etalien.booster"
        @Volatile var instance: AdAccessibilityService? = null
            private set
    }
}
