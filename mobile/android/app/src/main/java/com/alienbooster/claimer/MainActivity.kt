package com.alienbooster.claimer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.alienbooster.claimer.adb.LocalAdbConnectionException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private enum class Tab(val label: String) { CLAIM("领取"), SETTINGS("设置"), HISTORY("记录") }
    private enum class Scope(val label: String) { DUAL("双号"), ALL("全部账号"), CONTINUOUS("持续观看"), SOLO("仅主号") }
    private data class RestartPending(val oldInstance: String, val acknowledged: Boolean, val deadline: Long)

    private val bg = Color.rgb(14, 22, 28)
    private val panel = Color.rgb(27, 39, 48)
    private val inactive = Color.rgb(40, 55, 65)
    private val ink = Color.rgb(242, 247, 249)
    private val muted = Color.rgb(171, 189, 197)
    private val accent = Color.rgb(105, 226, 188)
    private val warn = Color.rgb(255, 201, 132)
    private val handler = Handler(Looper.getMainLooper())
    private val historyThread = Executors.newSingleThreadExecutor()
    private val client by lazy { BackgroundClient(applicationContext) }
    private val uiPrefs by lazy { getSharedPreferences("dashboard", MODE_PRIVATE) }
    private val localMode get() = RunPrefs.useAccessibility(this)
    private val localRunning get() = AdAccessibilityService.instance?.isActiveRun == true
    private var tab = Tab.CLAIM
    private var foreground = false
    private var scope = Scope.DUAL
    private val tabs = mutableMapOf<Tab, Button>()
    private lateinit var fullEngine: Button
    private lateinit var localEngine: Button
    private lateinit var fullOptions: LinearLayout
    private lateinit var accessibilitySettings: Button
    private val placements = mutableMapOf<Boolean, Button>()
    private val scopes = mutableMapOf<Scope, Button>()
    private lateinit var claimPage: View
    private lateinit var settingsPage: View
    private lateinit var historyPage: View
    private lateinit var historyRows: LinearLayout
    private lateinit var historyStatus: TextView
    private lateinit var clearHistory: Button
    private lateinit var modeHint: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: TextView
    private lateinit var start: Button
    private lateinit var pause: Button
    private lateinit var trial: Switch
    private lateinit var network: Switch
    private lateinit var schedule: Switch
    private lateinit var scheduleTime: Button
    private lateinit var scheduleStatus: TextView
    private lateinit var scheduleHint: TextView
    private lateinit var restoreIcons: Button
    private lateinit var restoreStatus: TextView
    private lateinit var restartAssistant: Button
    private lateinit var restartStatus: TextView
    private lateinit var phonePairStatus: TextView
    private var snapshot: BackgroundClient.Status? = null
    private val restoringIcons get() = helperError == null && snapshot?.state == BackgroundClient.State.RECOVERING
    private var restoreFeedback: String? = null
    private var restartFeedback: String? = null
    private var pendingRestart: RestartPending? = null
    private var helperError: String? = null
    private var polling = false
    private var commanding = false
    private var maybeActive = false
    private var activeMode = BackgroundClient.Mode.BACKGROUND
    private var feedback: String? = null
    private var historyVersion = 0
    private var clearLocalOnly = false
    private var lastAppliedPolicy: Boolean? = null
    private var policyRevision = 0
    private var syncingPolicy = false
    private var syncingTrial = false
    private val refresh = object : Runnable {
        override fun run() {
            render()
            val needsHelper = !localMode || tab == Tab.SETTINGS || maybeActive || pendingRestart != null
            if (needsHelper && !polling && !commanding && !syncingPolicy) {
                polling = true
                client.request(BackgroundClient.Command.STATUS) { result ->
                    if (isDestroyed) return@request
                    polling = false
                    applyResult(result)
                    if (!localMode && result is BackgroundClient.Result.Ready && !commanding && !syncingPolicy &&
                        pendingRestart == null &&
                        lastAppliedPolicy != RunPrefs.allowMobile(this@MainActivity)) syncPolicy(false)
                }
            }
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!uiPrefs.contains("helper_trial") && uiPrefs.contains("trial")) {
            uiPrefs.edit().putBoolean("helper_trial", uiPrefs.getBoolean("trial", false)).apply()
        }
        window.statusBarColor = bg
        window.navigationBarColor = bg
        foreground = uiPrefs.getBoolean("foreground", false)
        scope = runCatching { Scope.valueOf(uiPrefs.getString("scope", null) ?: "DUAL") }.getOrDefault(Scope.DUAL)
        val shell = column().apply {
            setBackgroundColor(bg)
            setPadding(dp(18), dp(12), dp(18), 0)
        }
        shell.addView(label("外星仔领时长", 24, ink, true), row(bottom = 12))
        val nav = strip()
        Tab.values().forEach { item ->
            tabs[item] = chip(nav, item.label) { selectTab(item) }
        }
        shell.addView(nav, row(bottom = 10))
        claimPage = page(::buildClaim)
        settingsPage = page(::buildSettings)
        historyPage = page(::buildHistory)
        listOf(claimPage, settingsPage, historyPage).forEach { shell.addView(it, row()) }
        setContentView(shell)
        selectTab(Tab.CLAIM)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
        if (tab == Tab.HISTORY) loadHistory()
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        historyThread.shutdown()
        super.onDestroy()
    }

    private fun selectTab(next: Tab) {
        tab = next
        tabs.forEach { (item, button) -> select(button, item == next) }
        claimPage.visibility = if (next == Tab.CLAIM) View.VISIBLE else View.GONE
        settingsPage.visibility = if (next == Tab.SETTINGS) View.VISIBLE else View.GONE
        historyPage.visibility = if (next == Tab.HISTORY) View.VISIBLE else View.GONE
        if (next == Tab.HISTORY) loadHistory()
        render()
    }

    private fun buildClaim(body: LinearLayout) {
        val options = card()
        val engineStrip = strip()
        fullEngine = chip(engineStrip, "完整助手") { selectEngine(false) }
        localEngine = chip(engineStrip, "无障碍主屏") { selectEngine(true) }
        options.addView(engineStrip)
        fullOptions = column()
        options.addView(fullOptions, row(top = 14))
        fullOptions.addView(label("运行位置", 14, muted, true))
        val positionStrip = strip()
        placements[false] = chip(positionStrip, "后台") { setMode(false, scope) }
        placements[true] = chip(positionStrip, "主屏") { setMode(true, scope) }
        fullOptions.addView(positionStrip, row(top = 7))
        fullOptions.addView(label("领取方式", 14, muted, true), row(top = 16))
        val scopeStrip = strip()
        Scope.values().forEach { item ->
            scopes[item] = chip(scopeStrip, item.label) { setMode(foreground, item) }
        }
        fullOptions.addView(scopeStrip, row(top = 7))
        modeHint = label("", 13, muted)
        options.addView(modeHint, row(top = 10))
        accessibilitySettings = button("开启无障碍权限", inactive, ink).apply {
            setOnClickListener { openAccessibilitySettings() }
        }
        options.addView(accessibilitySettings, row(height = 46, top = 6))
        body.addView(options)

        val actions = strip()
        start = button("开始 / 继续", accent, bg).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { startSelected() }
        }
        pause = button("暂停", inactive, ink).apply { setOnClickListener { pauseTask() } }
        actions.addView(start, LinearLayout.LayoutParams(0, dp(54), 2f).apply { marginEnd = dp(8) })
        actions.addView(pause, LinearLayout.LayoutParams(0, dp(54), 1f))
        body.addView(actions, row(top = 12, bottom = 12))

        val state = card()
        statusTitle = label("", 16, ink, true)
        progress = label("", 18, accent, true)
        statusText = label("", 14, muted)
        state.addView(statusTitle)
        state.addView(progress, row(top = 5))
        state.addView(statusText, row(top = 6))
        body.addView(state)
        trial = Switch(this).apply {
            textSize = 14f
            setTextColor(muted)
            isChecked = uiPrefs.getBoolean(trialPreference(), false)
            setOnCheckedChangeListener { _, on ->
                if (!syncingTrial) uiPrefs.edit().putBoolean(trialPreference(), on).apply()
            }
        }
        body.addView(trial, row(top = 6))
    }

    private fun buildSettings(body: LinearLayout) {
        val connectivity = card()
        connectivity.addView(label("播放网络", 14, accent, true))
        network = toggle("允许使用移动数据", RunPrefs.allowMobile(this)) { on ->
            RunPrefs.setAllowMobile(this, on)
            lastAppliedPolicy = null
            if (!localMode) syncPolicy(true)
        }
        connectivity.addView(network, row(height = 48))
        connectivity.addView(label("关闭时仅在 Wi-Fi 下观看。", 13, muted))
        body.addView(connectivity)
        val timer = card()
        timer.addView(label("每日定时", 14, accent, true))
        schedule = toggle("定时领取", RunPrefs.scheduleEnabled(this)) { on ->
            RunPrefs.setSchedule(this, on, RunPrefs.scheduleMinute(this))
            refreshSchedule()
            renderSettings()
        }
        timer.addView(schedule, row(height = 48))
        scheduleTime = button("", inactive, ink).apply {
            setOnClickListener {
                val minute = RunPrefs.scheduleMinute(this@MainActivity).coerceIn(0, 1439)
                TimePickerDialog(this@MainActivity, { _, h, m ->
                    RunPrefs.setSchedule(this@MainActivity, schedule.isChecked, h * 60 + m)
                    refreshSchedule()
                    renderSettings()
                }, minute / 60, minute % 60, DateFormat.is24HourFormat(this@MainActivity)).show()
            }
        }
        timer.addView(scheduleTime, row(height = 48))
        scheduleStatus = label("", 13, muted)
        timer.addView(scheduleStatus, row(top = 8))
        scheduleHint = label("", 13, muted)
        timer.addView(scheduleHint, row(top = 8))
        body.addView(timer, row(top = 12))

        val maintenance = card()
        maintenance.addView(label("助手维护", 14, accent, true))
        maintenance.addView(button("手机配对", inactive, ink).apply {
            setOnClickListener { showPhonePairing() }
        }, row(height = 48, top = 6))
        phonePairStatus = label("", 13, muted)
        maintenance.addView(phonePairStatus, row(top = 5))
        maintenance.addView(toggle("自动启动助手", RunPrefs.autoStartHelper(this)) {
            RunPrefs.setAutoStartHelper(this, it)
        }, row(height = 48))
        maintenance.addView(label("配对后，按需在本机启动。自动启动关闭时可在这里手动启动。", 13, muted))
        maintenance.addView(button("启动助手", inactive, ink).apply {
            setOnClickListener { startPhoneHelper() }
        }, row(height = 48, top = 6))
        restoreIcons = button("一键恢复图标", inactive, ink).apply {
            setOnClickListener { restoreStoreIcons() }
        }
        maintenance.addView(restoreIcons, row(height = 48, top = 6))
        maintenance.addView(label("按保护记录恢复商店显示。图标位置由荣耀桌面安排，移位后可长按拖回。", 13, muted), row(top = 8))
        restoreStatus = label("", 13, muted)
        maintenance.addView(restoreStatus, row(top = 8))
        restartAssistant = button("重启助手", inactive, ink).apply {
            setOnClickListener { restartAssistant() }
        }
        maintenance.addView(restartAssistant, row(height = 48, top = 8))
        restartStatus = label("", 13, muted)
        maintenance.addView(restartStatus, row(top = 8))
        body.addView(maintenance, row(top = 12))
    }

    private fun showPhonePairing() {
        if (commanding) return
        AlertDialog.Builder(this)
            .setTitle("手机无线配对")
            .setMessage("打开开发者选项里的无线调试，选择「使用配对码配对设备」。然后下拉通知栏，在本 App 的配对通知里填写六位码。")
            .setPositiveButton("去配对") { _, _ ->
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PAIR_NOTICE_PERMISSION)
                } else openWirelessPairing()
            }
            .setNeutralButton("手动填写") { _, _ -> showPairingFields() }
            .setNegativeButton("取消", null).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PAIR_NOTICE_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openWirelessPairing()
            else showPairingFields()
        }
    }

    private fun openWirelessPairing() {
        PhonePairingNotice.show(this)
        val direct = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
        runCatching { startActivity(direct) }.recoverCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }.onFailure { toast("请从系统设置打开开发者选项和无线调试") }
    }

    private fun numberField(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_NUMBER
        isSingleLine = true
    }

    private fun showPairingFields() {
        val code = numberField("六位配对码")
        val port = numberField("配对端口（选填）")
        val content = column().apply {
            setPadding(dp(20), 0, dp(20), 0)
            addView(label("可用分屏保留系统配对码窗口。", 14, muted))
            addView(code)
            addView(port)
        }
        AlertDialog.Builder(this).setTitle("手动手机配对").setView(content)
            .setNegativeButton("取消", null).setPositiveButton("配对") { _, _ ->
                val rawPort = port.text.toString()
                val number = rawPort.toIntOrNull()
                if (rawPort.isNotBlank() && number !in 1..65535) {
                    toast("配对端口无效")
                    return@setPositiveButton
                }
                commanding = true
                restartFeedback = "正在手机本机配对…"
                render()
                PhoneHelperBootstrap.pair(this, code.text.toString(), number) { result ->
                    if (isDestroyed) return@pair
                    commanding = false
                    if (result.isSuccess) startPhoneHelper()
                    else {
                        restartFeedback = result.exceptionOrNull()?.message ?: "配对失败"
                        render()
                    }
                }
            }.show()
    }

    private fun startPhoneHelper(port: Int? = null) {
        if (commanding || pendingRestart != null) return
        if (localRunning || maybeActive) {
            toast("请先暂停领取")
            return
        }
        if (!PhoneHelperBootstrap.isPaired(this)) {
            showPhonePairing()
            return
        }
        commanding = true
        restartFeedback = "正在从手机启动助手…"
        render()
        PhoneHelperBootstrap.start(this, port) { result ->
            if (isDestroyed) return@start
            commanding = false
            restartFeedback = result.exceptionOrNull()?.message ?: "后台助手已就绪"
            render()
            if (result.exceptionOrNull() is LocalAdbConnectionException && port == null) offerConnectPort()
        }
    }

    private fun offerConnectPort(connect: (Int) -> Unit = { startPhoneHelper(it) }) {
        val port = numberField("无线调试主页连接端口")
        AlertDialog.Builder(this).setTitle("手动连接端口").setView(port)
            .setMessage("打开系统无线调试，查看主页「IP 地址和端口」里冒号后面的数字。")
            .setNegativeButton("关闭", null).setPositiveButton("连接") { _, _ ->
                val value = port.text.toString().toIntOrNull()
                if (value != null && value in 1..65535) connect(value)
                else toast("连接端口无效")
            }.show()
    }

    private fun restoreStoreIcons() {
        if (commanding || restoringIcons || pendingRestart != null) return
        if (maybeActive || localRunning) {
            restoreFeedback = "请先暂停领取，等任务收尾后再恢复图标"
            render()
            return
        }
        commanding = true
        restoreFeedback = "正在连接后台助手…"
        render()
        client.request(BackgroundClient.Command.STATUS) { status ->
            if (isDestroyed) return@request
            if (status is BackgroundClient.Result.Ready) {
                client.request(BackgroundClient.Command.RECOVER_STORES, ::finishStoreRecovery)
            } else {
                finishStoreRecovery(status)
            }
        }
    }

    private fun finishStoreRecovery(result: BackgroundClient.Result) {
        if (isDestroyed) return
        commanding = false
        restoreFeedback = (result as? BackgroundClient.Result.Failed)?.message
        if (result is BackgroundClient.Result.Ready) applyResult(result)
        else render()
    }

    private fun restartAssistant() {
        if (commanding || pendingRestart != null) return
        if (localRunning) {
            restartFeedback = "请先暂停无障碍领取"
            render()
            return
        }
        commanding = true
        restartFeedback = "正在连接后台助手…"
        render()
        client.request(BackgroundClient.Command.STATUS) { preflight ->
            if (isDestroyed) return@request
            when (preflight) {
                is BackgroundClient.Result.Ready -> {
                    client.request(BackgroundClient.Command.RESTART_HELPER) { result ->
                        if (!isDestroyed) finishRestartRequest(preflight.status.instanceId, result)
                    }
                }
                is BackgroundClient.Result.Failed -> {
                    commanding = false
                    restartFeedback = preflight.message
                    applyResult(preflight)
                    if ((preflight.offline || preflight.needsUpgrade) && PhoneHelperBootstrap.isPaired(this)) {
                        startPhoneHelper()
                    }
                }
            }
        }
    }

    private fun finishRestartRequest(oldInstance: String, result: BackgroundClient.Result) {
        commanding = false
        val reply = (result as? BackgroundClient.Result.Ready)?.status
        val failure = result as? BackgroundClient.Result.Failed
        val accepted = reply?.state == BackgroundClient.State.RESTARTING
        if (accepted || failure?.commandMayHaveRun == true) {
            pendingRestart = RestartPending(reply?.instanceId ?: oldInstance, accepted,
                SystemClock.elapsedRealtime() + RESTART_TIMEOUT_MS)
            restartFeedback = "正在等待后台助手重新连接…"
        } else {
            restartFeedback = failure?.message ?: "后台助手未确认重启"
        }
        if (reply != null) applyResult(result) else render()
    }

    private fun verifyRestart(result: BackgroundClient.Result) {
        val pending = pendingRestart ?: return
        val status = (result as? BackgroundClient.Result.Ready)?.status
        if (status != null && status.instanceId != pending.oldInstance &&
            status.state != BackgroundClient.State.RESTARTING) {
            pendingRestart = null
            restartFeedback = when {
                !pending.acknowledged -> "已连接新的后台助手，重启请求未确认"
                status.state == BackgroundClient.State.IDLE -> "后台助手已重启并就绪"
                else -> "后台助手已重启 · " + status.message
            }
        } else if (SystemClock.elapsedRealtime() >= pending.deadline) {
            pendingRestart = null
            restartFeedback = "未能确认助手重启。若收不到状态，请使用恢复包启动助手。"
        }
    }

    private fun refreshSchedule() {
        runCatching { RunSchedule.refresh(this) }.onFailure { toast("定时设置已保存，系统暂未完成排程") }
    }

    private fun renderSettings() {
        phonePairStatus.showText(if (PhoneHelperBootstrap.isPaired(this)) "手机已配对" else "首次使用需在手机上配对一次")
        scheduleHint.showText(if (localMode) "到点领取主号。请保持亮屏解锁，并停留在桌面或领取页。"
            else "到点尝试在后台领取全部账号，遵循上面的网络设置。")
        val minute = RunPrefs.scheduleMinute(this).coerceIn(0, 1439)
        scheduleTime.showText("开始时间    " + String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60))
        val next = RunSchedule.nextAt(this)
        val last = RunSchedule.status(this)
        scheduleStatus.showText(when {
            !RunPrefs.scheduleEnabled(this) -> "定时已关闭" + if (last.isEmpty()) "" else " · $last"
            next != null -> "下次尝试 " + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(next)) +
                if (last.isEmpty()) "" else " · $last"
            else -> "等待系统排程"
        })
        restoreIcons.isEnabled = !commanding && !restoringIcons && pendingRestart == null
        restartAssistant.isEnabled = !commanding && pendingRestart == null
        val result = snapshot?.takeIf { it.state in restoreStates }
        val message = restoreFeedback ?: result?.message.orEmpty()
        restoreStatus.showText(message)
        restoreStatus.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        restoreStatus.setTextColor(if (result?.state == BackgroundClient.State.RECOVERY_FAILED) warn else muted)
        restartStatus.showText(restartFeedback.orEmpty())
        restartStatus.visibility = if (restartFeedback == null) View.GONE else View.VISIBLE
    }

    private fun buildHistory(body: LinearLayout) {
        val heading = strip()
        heading.addView(label("领取记录", 19, ink, true), LinearLayout.LayoutParams(0, dp(44), 1f))
        heading.addView(button("刷新", inactive, ink).apply { setOnClickListener { loadHistory() } },
            LinearLayout.LayoutParams(dp(64), dp(44)).apply { marginEnd = dp(8) })
        clearHistory = button("清空", panel, warn).apply {
            setOnClickListener {
                val onlyLocal = clearLocalOnly
                val title = if (onlyLocal) "清空无障碍领取记录？" else "清空所有领取记录？"
                confirm(title, if (onlyLocal) "将删除本机保存的无障碍领取记录。" else "所有已保存的领取耗时记录都将删除。") {
                    changeHistory {
                        if (onlyLocal) RunHistory.clearLocal(applicationContext) else RunHistory.clear(applicationContext)
                    }
                }
            }
        }
        heading.addView(clearHistory, LinearLayout.LayoutParams(dp(64), dp(44)))
        body.addView(heading)
        historyStatus = label("正在读取…", 13, muted)
        body.addView(historyStatus, row(top = 9))
        historyRows = column()
        body.addView(historyRows, row(top = 4))
    }

    private fun changeHistory(operation: () -> Unit) {
        historyVersion++
        historyStatus.showText("正在更新…")
        clearHistory.isEnabled = false
        historyRows.removeAllViews()
        historyThread.execute {
            val failure = runCatching { operation() }.exceptionOrNull()
            runOnUiThread {
                if (!isDestroyed) {
                    if (failure != null) toast("删除未完成，请检查后台助手连接后重试")
                    loadHistory()
                }
            }
        }
    }

    private fun loadHistory() {
        val version = ++historyVersion
        val onlyLocal = localMode
        historyStatus.showText("正在读取…")
        clearHistory.isEnabled = false
        historyThread.execute {
            var failure = false
            val records = try {
                if (onlyLocal) RunHistory.localOnly(applicationContext) else RunHistory.list(applicationContext)
            } catch (_: Exception) {
                failure = true
                runCatching { RunHistory.localOnly(applicationContext) }.getOrDefault(emptyList())
            }.sortedByDescending { it.started }
            runOnUiThread {
                if (isDestroyed || version != historyVersion) return@runOnUiThread
                historyRows.removeAllViews()
                clearLocalOnly = onlyLocal || failure
                clearHistory.showText(if (clearLocalOnly) "清空本机" else "清空")
                clearHistory.isEnabled = records.isNotEmpty()
                historyStatus.showText(when {
                    onlyLocal && failure -> "本机记录暂时无法读取"
                    failure -> "后台记录暂不可读，以下仅显示本机单号记录"
                    onlyLocal -> "无障碍主屏 · 共 " + records.size + " 条"
                    records.isEmpty() -> "还没有领取记录"
                    else -> "共 " + records.size + " 条"
                })
                records.forEach { record -> addRecord(record) }
            }
        }
    }

    private fun addRecord(record: RunRecord) {
        val item = card()
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
        val top = strip()
        top.addView(label(record.mode, 15, ink, true), LinearLayout.LayoutParams(0, dp(44), 1f))
        top.addView(button("删除", panel, warn).apply {
            contentDescription = "删除这条记录"
            setOnClickListener {
                confirm("删除这条领取记录？", fmt.format(Date(record.started)) + " · " + record.mode) {
                    changeHistory { RunHistory.delete(applicationContext, record.id) }
                }
            }
        }, LinearLayout.LayoutParams(dp(60), dp(44)))
        item.addView(top)
        val duration = (record.elapsed.coerceAtLeast(0L) / 1000).let { seconds ->
            when {
                seconds >= 3600 -> "%d 小时 %d 分 %d 秒".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
                seconds >= 60 -> "%d 分 %d 秒".format(seconds / 60, seconds % 60)
                else -> "$seconds 秒"
            }
        }
        item.addView(label("耗时 $duration    新增 " + record.gained + " 条", 15, accent, true))
        item.addView(label("开始 " + fmt.format(Date(record.started)) + "    结束 " + fmt.format(Date(record.end)), 12, muted), row(top = 6))
        item.addView(label(record.reason, 13, muted), row(top = 4))
        historyRows.addView(item, row(top = 9))
    }

    private fun selectEngine(local: Boolean) {
        if (local == localMode) { render(); return }
        if (commanding || pendingRestart != null) { toast("正在处理，请稍候"); return }
        if (maybeActive || localRunning) {
            feedback = "已有任务运行，切换引擎前请先暂停。"
            render()
            return
        }
        RunPrefs.setUseAccessibility(this, local)
        feedback = null
        refreshSchedule()
        render()
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { toast("请从系统设置打开无障碍权限") }
    }

    private fun trialPreference() = if (localMode) "accessibility_trial" else "helper_trial"

    private fun setMode(onMain: Boolean, chosen: Scope) {
        foreground = onMain
        scope = chosen
        uiPrefs.edit().putBoolean("foreground", foreground).putString("scope", scope.name).apply()
        feedback = null
        render()
    }

    private fun command() = when (scope) {
        Scope.SOLO -> when {
            foreground && trial.isChecked -> BackgroundClient.Command.START_FOREGROUND_SOLO_TRIAL
            foreground -> BackgroundClient.Command.START_FOREGROUND_SOLO
            trial.isChecked -> BackgroundClient.Command.START_BACKGROUND_SOLO_TRIAL
            else -> BackgroundClient.Command.START_BACKGROUND_SOLO
        }
        Scope.DUAL -> if (foreground) BackgroundClient.Command.START_FOREGROUND else BackgroundClient.Command.START
        Scope.ALL -> if (foreground) BackgroundClient.Command.START_ALL_FOREGROUND else BackgroundClient.Command.START_ALL_BACKGROUND
        Scope.CONTINUOUS -> if (foreground) BackgroundClient.Command.START_CONTINUOUS_FOREGROUND else BackgroundClient.Command.START_CONTINUOUS_BACKGROUND
    }

    private fun selectedLabel() = (if (foreground) "主屏" else "后台") + scope.label

    private fun startSelected() {
        feedback = null
        if (commanding) { toast("正在处理，请稍候"); return }
        if (pendingRestart != null) { toast("正在等待后台助手重新连接"); return }
        if (restoringIcons) { toast("正在恢复商店图标，请稍候"); return }
        if (maybeActive || localRunning) {
            feedback = if (localRunning) "无障碍主屏正在运行，切换前请先暂停。"
            else "正在运行" + activeMode.label + "，切换前请先暂停。"
            render()
            return
        }
        if (localMode) { startAccessibilitySelected(); return }
        val desired = command()
        commanding = true
        render()
        startComplete(desired, allowBootstrap = true)
    }

    private fun startComplete(desired: BackgroundClient.Command, allowBootstrap: Boolean) {
        val allow = RunPrefs.allowMobile(this)
        client.applyNetworkPolicy(allow) { result ->
            if (isDestroyed) return@applyNetworkPolicy
            applyResult(result)
            if (result is BackgroundClient.Result.Failed && (result.offline || result.needsUpgrade) && allowBootstrap) {
                if (PhoneHelperBootstrap.isPaired(this)) {
                    if (RunPrefs.autoStartHelper(this)) launchHelperForClaim(desired)
                    else {
                        commanding = false
                        render()
                        AlertDialog.Builder(this).setTitle("完整助手需要启动")
                            .setMessage("手机已配对。启动助手后会继续本次领取。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("启动并继续") { _, _ -> launchHelperForClaim(desired) }
                            .show()
                    }
                } else {
                    commanding = false
                    render()
                    showPhonePairing()
                }
                return@applyNetworkPolicy
            }
            if (result !is BackgroundClient.Result.Ready || maybeActive || restoringIcons || localRunning) {
                commanding = false
                if (maybeActive) feedback = "正在运行" + activeMode.label + "，请先暂停。"
                render()
                return@applyNetworkPolicy
            }
            lastAppliedPolicy = allow
            send(desired)
        }
    }

    private fun launchHelperForClaim(desired: BackgroundClient.Command, port: Int? = null) {
        if (localMode || localRunning || maybeActive || restoringIcons || pendingRestart != null) {
            commanding = false
            feedback = "当前状态已变化，请重新点开始"
            render()
            return
        }
        commanding = true
        feedback = "正在手机上启动助手…"
        render()
        PhoneHelperBootstrap.start(this, port) { result ->
            if (isDestroyed) return@start
            val failure = result.exceptionOrNull()
            if (failure == null) {
                feedback = null
                startComplete(desired, allowBootstrap = false)
            } else {
                commanding = false
                feedback = failure.message ?: "助手启动失败，请重试"
                render()
                if (failure is LocalAdbConnectionException && port == null) {
                    offerConnectPort { launchHelperForClaim(desired, it) }
                }
            }
        }
    }

    private fun startAccessibilitySelected() {
        if (AdAccessibilityService.instance == null) {
            AlertDialog.Builder(this).setTitle("需要无障碍权限")
                .setMessage("在系统无障碍设置中开启「外星仔一键领时长」，回到这里再点开始。")
                .setPositiveButton("去开启") { _, _ -> openAccessibilitySettings() }
                .setNegativeButton("取消", null).show()
            return
        }
        if (!RunPrefs.networkAllowed(this)) {
            feedback = if (RunPrefs.allowMobile(this)) "当前没有可用网络" else "仅允许 Wi-Fi，连接后再点开始"
            render()
            return
        }
        if (!RunSchedule.tryManualDispatch()) { toast("另一个启动请求正在处理"); return }
        commanding = true
        render()
        client.request(BackgroundClient.Command.STATUS) { result ->
            if (isDestroyed) {
                RunSchedule.finishManualDispatch()
                return@request
            }
            var failure: String? = null
            try {
                when (result) {
                    is BackgroundClient.Result.Ready -> {
                        applyResult(result)
                        if (maybeActive || restoringIcons ||
                            result.status.state == BackgroundClient.State.RESTARTING) {
                            failure = "完整助手正在运行或维护，请先暂停后再使用无障碍主屏。"
                        }
                    }
                    is BackgroundClient.Result.Failed -> {
                        if (!result.offline) failure = "完整助手状态无法确认，暂不启动另一种模式。"
                    }
                }
                if (failure == null && !localMode) failure = "引擎已切换，请重新点开始。"
                val service = AdAccessibilityService.instance
                if (failure == null && service == null) failure = "无障碍服务已断开，请重新开启权限。"
                if (failure == null && !RunPrefs.networkAllowed(this)) {
                    failure = RunPrefs.networkReason(this)
                }
                if (failure == null && service?.canStartFromDashboardOrLauncher() != true) {
                    failure = "请停留在领取页或桌面，再开始无障碍领取。"
                }
                if (failure == null && service != null) {
                    service.startRun(if (trial.isChecked) 1 else 21)
                    if (!service.isActiveRun) failure = SoloStore.status(this)
                }
            } catch (error: RuntimeException) {
                failure = "无障碍领取启动失败：" + error.javaClass.simpleName
            } finally {
                commanding = false
                RunSchedule.finishManualDispatch()
                feedback = failure
                render()
            }
        }
    }

    private fun send(cmd: BackgroundClient.Command) {
        commanding = true
        render()
        client.request(cmd) { result ->
            if (isDestroyed) return@request
            commanding = false
            if (cmd.mode != null && result is BackgroundClient.Result.Failed && result.commandMayHaveRun) {
                maybeActive = true
                activeMode = cmd.mode
            }
            applyResult(result)
            if (cmd.mode != null && result is BackgroundClient.Result.Ready && result.status.mode != cmd.mode &&
                maybeActive) feedback = "已有" + result.status.mode.label + "任务，请先暂停。"
            render()
        }
    }

    private fun syncPolicy(manual: Boolean) {
        if (commanding || syncingPolicy || pendingRestart != null) return
        syncingPolicy = true
        val allow = RunPrefs.allowMobile(this)
        val revision = ++policyRevision
        client.applyNetworkPolicy(allow) { result ->
            if (isDestroyed || revision != policyRevision) return@applyNetworkPolicy
            syncingPolicy = false
            if (result is BackgroundClient.Result.Ready) {
                lastAppliedPolicy = allow
                if (manual) toast("网络设置已保存")
            } else if (manual) toast("网络设置已保存，下次连接后台助手时同步")
        }
    }

    private fun pauseTask() {
        feedback = null
        if (commanding) { toast("正在处理，请稍候"); return }
        var handled = false
        if (localRunning) {
            AdAccessibilityService.instance?.pauseRun("已暂停，可点开始 / 继续")
            handled = true
        }
        if (maybeActive) {
            send(BackgroundClient.Command.STOP)
            handled = true
        }
        if (!handled) toast("当前没有运行中的任务") else render()
    }

    private fun applyResult(result: BackgroundClient.Result) {
        val previous = maybeActive
        when (result) {
            is BackgroundClient.Result.Ready -> {
                if (result.status.state in restoreStates && snapshot?.state != result.status.state) {
                    restoreFeedback = null
                }
                snapshot = result.status
                activeMode = result.status.mode
                maybeActive = result.status.state in setOf(BackgroundClient.State.RUNNING, BackgroundClient.State.WAITING)
                if (previous != maybeActive) feedback = null
                if (previous && !maybeActive && tab == Tab.HISTORY) loadHistory()
                helperError = null
            }
            is BackgroundClient.Result.Failed -> {
                helperError = result.message
                if (result.offline) {
                    snapshot = null
                    maybeActive = false
                    if (previous) feedback = null
                    lastAppliedPolicy = null
                }
            }
        }
        verifyRestart(result)
        render()
    }

    private fun render() {
        if (tab == Tab.SETTINGS) renderSettings()
        if (tab != Tab.CLAIM) return
        select(fullEngine, !localMode)
        select(localEngine, localMode)
        fullOptions.visibility = if (localMode) View.GONE else View.VISIBLE
        accessibilitySettings.visibility = if (localMode) View.VISIBLE else View.GONE
        accessibilitySettings.showText(if (AdAccessibilityService.instance == null) "开启无障碍权限"
            else "无障碍权限已连接 · 查看")
        placements.forEach { (value, button) -> select(button, value == foreground) }
        scopes.forEach { (value, button) -> select(button, value == scope) }
        trial.visibility = if ((localMode && !localRunning) ||
            (!localMode && scope == Scope.SOLO && !maybeActive)) View.VISIBLE else View.GONE
        val trialOn = uiPrefs.getBoolean(trialPreference(), false)
        if (trial.isChecked != trialOn) {
            syncingTrial = true
            trial.isChecked = trialOn
            syncingTrial = false
        }
        trial.showText(when {
            localMode -> "无障碍主号只跑 1 条"
            foreground -> "主屏主号只跑 1 条"
            else -> "后台主号只跑 1 条"
        })
        modeHint.showText(when {
            localMode -> "无需电脑或后台助手，仅操作主屏主号；锁屏或切出会暂停，本模式不停用商店。"
            scope == Scope.SOLO && foreground -> "在主屏领取主号，离开外星仔会暂停。"
            scope == Scope.SOLO -> "在虚拟屏领取主号，主屏可以使用其他应用。"
            scope == Scope.CONTINUOUS -> "领满后等待复查，可随时暂停。"
            foreground -> "在主屏轮换使用账号。离开外星仔会暂停。"
            else -> "在虚拟屏轮换使用账号，主屏可以使用其他应用。"
        })
        start.showText(when {
            restoringIcons -> "正在恢复图标"
            pendingRestart != null -> "正在重启助手"
            maybeActive || localRunning -> "任务运行中"
            else -> "开始 / 继续"
        })
        pause.isEnabled = (maybeActive || localRunning) && !commanding
        pause.alpha = if (pause.isEnabled) 1f else .5f
        val status = snapshot
        val selected = if (localMode) "无障碍主屏 · 仅主号" else selectedLabel()
        statusTitle.showText(when {
            commanding -> "正在处理…"
            localRunning -> "正在运行 · 无障碍主屏主号"
            pendingRestart != null -> "正在重连"
            maybeActive && status?.state == BackgroundClient.State.WAITING -> "等待中 · " + activeMode.label
            maybeActive -> "正在运行 · " + activeMode.label
            else -> "当前选择 · $selected"
        })
        progress.showText(when {
            localMode -> SoloStore.stages(this)
            status == null || (status.owner < 0 && status.clone < 0) -> ""
            status.mode == BackgroundClient.Mode.FOREGROUND_SOLO ||
                status.mode == BackgroundClient.Mode.BACKGROUND_SOLO -> "主号 " + status.owner + "/21"
            else -> "主号 " + (if (status.owner < 0) "—" else status.owner.toString()) + "/21     分身 " +
                (if (status.clone < 0) "—" else status.clone.toString()) + "/21"
        })
        progress.visibility = if (progress.text.isEmpty()) View.GONE else View.VISIBLE
        statusText.showText(feedback ?: when {
            localMode && commanding -> "正在核对另一种引擎是否空闲…"
            localMode && AdAccessibilityService.instance == null -> "请先开启无障碍权限，再开始主号领取。"
            localMode -> SoloStore.status(this)
            commanding -> "正在向手机助手发送请求"
            pendingRestart != null -> "正在等待后台助手重新连接…"
            helperError != null -> helperError!!
            !maybeActive && status != null && status.state in setOf(BackgroundClient.State.DONE, BackgroundClient.State.ERROR, BackgroundClient.State.STOPPED) ->
                "上次 · " + status.mode.label + " · " + status.message
            else -> status?.message ?: "正在确认后台助手状态…"
        })
        statusText.setTextColor(if (feedback != null || (!localMode && (helperError != null ||
            status?.state == BackgroundClient.State.ERROR || status?.state == BackgroundClient.State.RECOVERY_FAILED)) ||
            (localMode && AdAccessibilityService.instance == null)) warn else muted)
    }

    private fun page(build: (LinearLayout) -> Unit): ScrollView {
        val content = column().apply { setPadding(0, 0, 0, dp(18)) }
        build(content)
        return ScrollView(this).apply { isFillViewport = false; addView(content) }
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun card() = column().apply { background = rounded(panel, 16); setPadding(dp(16), dp(14), dp(16), dp(14)) }
    private fun strip() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(value: String, fill: Int, color: Int) = Button(this).apply {
        text = value; textSize = 14f; isAllCaps = false; setTextColor(color); background = rounded(fill, 12)
        backgroundTintList = null; elevation = 0f; stateListAnimator = null; minWidth = 0; minimumWidth = 0
        setPadding(dp(3), 0, dp(3), 0)
    }
    private fun chip(parent: LinearLayout, name: String, action: () -> Unit): Button {
        val first = parent.childCount == 0
        val b = button(name, inactive, ink).apply { setOnClickListener { action() } }
        parent.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (!first) marginStart = dp(4) })
        return b
    }
    private fun select(b: Button, chosen: Boolean) {
        if (b.tag == chosen) return
        b.tag = chosen
        b.isSelected = chosen
        b.background = rounded(if (chosen) accent else inactive, 12)
        b.setTextColor(if (chosen) bg else ink)
        b.setTypeface(Typeface.DEFAULT, if (chosen) Typeface.BOLD else Typeface.NORMAL)
    }
    private fun toggle(name: String, checked: Boolean, listener: (Boolean) -> Unit) = Switch(this).apply {
        text = name; textSize = 16f; setTextColor(ink); isChecked = checked; setOnCheckedChangeListener { _, value -> listener(value) }
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun row(height: Int = -2, top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, if (height < 0) height else dp(height)
    ).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    private fun TextView.showText(value: String) { if (!text.contentEquals(value)) text = value }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> action() }.show()
    }

    private companion object {
        const val PAIR_NOTICE_PERMISSION = 912
        const val RESTART_TIMEOUT_MS = 30_000L
        val restoreStates = setOf(BackgroundClient.State.RECOVERING, BackgroundClient.State.RECOVERED,
            BackgroundClient.State.RECOVERY_FAILED)
    }
}
