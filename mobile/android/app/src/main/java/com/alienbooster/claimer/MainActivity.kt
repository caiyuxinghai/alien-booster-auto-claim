package com.alienbooster.claimer

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
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
    private var tab = Tab.CLAIM
    private var foreground = false
    private var scope = Scope.DUAL
    private val tabs = mutableMapOf<Tab, Button>()
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
    private lateinit var restoreIcons: Button
    private lateinit var restoreStatus: TextView
    private lateinit var restartAssistant: Button
    private lateinit var restartStatus: TextView
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
    private var lastAppliedPolicy: Boolean? = null
    private var policyRevision = 0
    private var syncingPolicy = false
    private val refresh = object : Runnable {
        override fun run() {
            render()
            if (!polling && !commanding && !syncingPolicy) {
                polling = true
                client.request(BackgroundClient.Command.STATUS) { result ->
                    if (isDestroyed) return@request
                    polling = false
                    applyResult(result)
                    if (result is BackgroundClient.Result.Ready && !commanding && !syncingPolicy &&
                        pendingRestart == null &&
                        lastAppliedPolicy != RunPrefs.allowMobile(this@MainActivity)) syncPolicy(false)
                }
            }
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        foreground = uiPrefs.getBoolean("foreground", false)
        scope = runCatching { Scope.valueOf(uiPrefs.getString("scope", null) ?: "DUAL") }.getOrDefault(Scope.DUAL)
        if (!foreground && scope == Scope.SOLO) scope = Scope.DUAL
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
        options.addView(label("运行位置", 14, muted, true))
        val positionStrip = strip()
        placements[false] = chip(positionStrip, "后台") { setMode(false, scope) }
        placements[true] = chip(positionStrip, "主屏") { setMode(true, scope) }
        options.addView(positionStrip, row(top = 7))
        options.addView(label("领取方式", 14, muted, true), row(top = 16))
        val scopeStrip = strip()
        Scope.values().forEach { item ->
            scopes[item] = chip(scopeStrip, item.label) { setMode(foreground, item) }
        }
        options.addView(scopeStrip, row(top = 7))
        modeHint = label("", 13, muted)
        options.addView(modeHint, row(top = 10))
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
            text = "主屏主号只跑 1 条"
            textSize = 14f
            setTextColor(muted)
            isChecked = uiPrefs.getBoolean("trial", false)
            setOnCheckedChangeListener { _, on -> uiPrefs.edit().putBoolean("trial", on).apply() }
        }
        body.addView(trial, row(top = 6))
    }

    private fun buildSettings(body: LinearLayout) {
        val connectivity = card()
        connectivity.addView(label("播放网络", 14, accent, true))
        network = toggle("允许使用移动数据", RunPrefs.allowMobile(this)) { on ->
            RunPrefs.setAllowMobile(this, on)
            lastAppliedPolicy = null
            syncPolicy(true)
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
        timer.addView(label("到点尝试在后台领取全部账号，遵循上面的网络设置。", 13, muted), row(top = 8))
        body.addView(timer, row(top = 12))

        val maintenance = card()
        maintenance.addView(label("助手维护", 14, accent, true))
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
        maintenance.addView(label(
            "助手离线时，用已授权的 USB 或无线调试连接电脑，双击恢复包里的「启动后台助手.bat」。",
            13, muted
        ), row(top = 8))
        restartStatus = label("", 13, muted)
        maintenance.addView(restartStatus, row(top = 8))
        body.addView(maintenance, row(top = 12))
    }

    private fun restoreStoreIcons() {
        if (commanding || restoringIcons || pendingRestart != null) return
        if (maybeActive) {
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
                confirm("清空所有领取记录？", "所有已保存的领取耗时记录都将删除。") { changeHistory { RunHistory.clear(applicationContext) } }
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
        historyStatus.showText("正在读取…")
        historyThread.execute {
            var failure = false
            val records = try { RunHistory.list(applicationContext) } catch (_: Exception) {
                failure = true
                runCatching { RunHistory.localOnly(applicationContext) }.getOrDefault(emptyList())
            }.sortedByDescending { it.started }
            runOnUiThread {
                if (isDestroyed || version != historyVersion) return@runOnUiThread
                historyRows.removeAllViews()
                clearHistory.isEnabled = records.isNotEmpty() && !failure
                historyStatus.showText(when {
                    failure -> "后台记录暂不可读，以下仅显示本机单号记录"
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

    private fun setMode(onMain: Boolean, chosen: Scope) {
        foreground = onMain
        scope = if (!onMain && chosen == Scope.SOLO) Scope.DUAL else chosen
        uiPrefs.edit().putBoolean("foreground", foreground).putString("scope", scope.name).apply()
        feedback = null
        render()
    }

    private fun command() = when (scope) {
        Scope.SOLO -> if (trial.isChecked) BackgroundClient.Command.START_FOREGROUND_SOLO_TRIAL
                      else BackgroundClient.Command.START_FOREGROUND_SOLO
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
        if (maybeActive) {
            feedback = "正在运行" + activeMode.label + "，切换前请先暂停。"
            render()
            return
        }
        val desired = command()
        commanding = true
        render()
        val allow = RunPrefs.allowMobile(this)
        client.applyNetworkPolicy(allow) { result ->
            if (isDestroyed) return@applyNetworkPolicy
            applyResult(result)
            if (result !is BackgroundClient.Result.Ready || maybeActive || restoringIcons) {
                commanding = false
                if (maybeActive) feedback = "正在运行" + activeMode.label + "，请先暂停。"
                render()
                return@applyNetworkPolicy
            }
            lastAppliedPolicy = allow
            send(desired)
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
        if (maybeActive) send(BackgroundClient.Command.STOP)
        else toast("当前没有运行中的任务")
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
                    feedback = null
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
        placements.forEach { (value, button) -> select(button, value == foreground) }
        scopes.forEach { (value, button) ->
            button.visibility = if (value != Scope.SOLO || foreground) View.VISIBLE else View.GONE
            select(button, value == scope)
        }
        trial.visibility = if (scope == Scope.SOLO && !maybeActive) View.VISIBLE else View.GONE
        modeHint.showText(when {
            scope == Scope.SOLO -> "在主屏领取主号，离开外星仔会暂停。"
            scope == Scope.CONTINUOUS -> "领满后等待复查，可随时暂停。"
            foreground -> "在主屏轮换使用账号。离开外星仔会暂停。"
            else -> "在虚拟屏轮换使用账号，主屏可以使用其他应用。"
        })
        start.showText(when {
            restoringIcons -> "正在恢复图标"
            pendingRestart != null -> "正在重启助手"
            maybeActive -> "任务运行中"
            else -> "开始 / 继续"
        })
        pause.isEnabled = maybeActive && !commanding
        pause.alpha = if (pause.isEnabled) 1f else .5f
        val status = snapshot
        val selected = selectedLabel()
        statusTitle.showText(when {
            commanding -> "正在处理…"
            pendingRestart != null -> "正在重连"
            maybeActive && status?.state == BackgroundClient.State.WAITING -> "等待中 · " + activeMode.label
            maybeActive -> "正在运行 · " + activeMode.label
            else -> "当前选择 · $selected"
        })
        progress.showText(when {
            status == null || (status.owner < 0 && status.clone < 0) -> ""
            status.mode == BackgroundClient.Mode.FOREGROUND_SOLO -> "主号 " + status.owner + "/21"
            else -> "主号 " + (if (status.owner < 0) "—" else status.owner.toString()) + "/21     分身 " +
                (if (status.clone < 0) "—" else status.clone.toString()) + "/21"
        })
        progress.visibility = if (progress.text.isEmpty()) View.GONE else View.VISIBLE
        statusText.showText(feedback ?: when {
            commanding -> "正在向手机助手发送请求"
            pendingRestart != null -> "正在等待后台助手重新连接…"
            helperError != null -> helperError!!
            !maybeActive && status != null && status.state in setOf(BackgroundClient.State.DONE, BackgroundClient.State.ERROR, BackgroundClient.State.STOPPED) ->
                "上次 · " + status.mode.label + " · " + status.message
            else -> status?.message ?: "正在确认后台助手状态…"
        })
        statusText.setTextColor(if (feedback != null || helperError != null || status?.state == BackgroundClient.State.ERROR ||
            status?.state == BackgroundClient.State.RECOVERY_FAILED) warn else muted)
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
        const val RESTART_TIMEOUT_MS = 30_000L
        val restoreStates = setOf(BackgroundClient.State.RECOVERING, BackgroundClient.State.RECOVERED,
            BackgroundClient.State.RECOVERY_FAILED)
    }
}
