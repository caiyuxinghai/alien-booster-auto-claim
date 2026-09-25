package com.alienbooster.claimer

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var statusA11y: TextView
    private lateinit var statusToday: TextView
    private lateinit var statusNext: TextView
    private lateinit var timeValue: TextView
    private lateinit var wifiSwitch: Switch
    private lateinit var scheduleSwitch: Switch
    private lateinit var rowTime: View
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private var switchesBound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        setContentView(R.layout.activity_main)

        statusA11y = findViewById(R.id.status_a11y)
        statusToday = findViewById(R.id.status_today)
        statusNext = findViewById(R.id.status_next)
        timeValue = findViewById(R.id.time_value)
        wifiSwitch = findViewById(R.id.switch_nowifi)
        scheduleSwitch = findViewById(R.id.switch_schedule)
        rowTime = findViewById(R.id.row_time)
        logView = findViewById(R.id.log_view)
        logScroll = findViewById(R.id.log_scroll)

        rowTime.setOnClickListener { pickTime() }
        findViewById<Button>(R.id.btn_start).setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            AdClaimService.requestStop()
            toast("已停止")
        }
        findViewById<Button>(R.id.btn_a11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        Schedule.arm(this)
        handleRunIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRunIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AdClaimService.logListener = { line -> runOnUiThread { append(line) } }
        refresh()
        bindSwitches()
        val file = File(filesDir, "claimer.log")
        logView.text = if (file.exists()) {
            file.readLines().takeLast(40).joinToString("\n")
        } else {
            "还没有运行记录"
        }
    }

    override fun onPause() {
        super.onPause()
        AdClaimService.logListener = null
    }

    private fun handleRunIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_RUN, false) == true) {
            AdClaimService.requestRun()
            window.decorView.post { moveTaskToBack(true) }
        }
    }

    private fun onStartClicked() {
        if (!AdClaimService.isEnabled()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("先打开无障碍服务")
            return
        }
        if (!Schedule.mayAutoStart(this)) {
            toast("当前没连 Wi-Fi，已按设置不启动")
        }
        AdClaimService.requestRun()
    }

    private fun pickTime() {
        TimePickerDialog(
            this,
            { _, hour, minute ->
                Schedule.setClock(this, hour, minute)
                refresh()
                toast(
                    if (Schedule.scheduleEnabled(this)) "每天 %02d:%02d 启动".format(hour, minute)
                    else "已记下 %02d:%02d，打开定时后生效".format(hour, minute)
                )
            },
            Schedule.hour(this),
            Schedule.minute(this),
            true
        ).show()
    }

    private fun refresh() {
        val enabled = AdClaimService.isEnabled()
        statusA11y.text = if (enabled) "无障碍已开启" else "无障碍未开启"
        statusA11y.setTextColor(getColor(if (enabled) R.color.ok else R.color.purple))
        val running = if (AdClaimService.isRunning()) "领取中" else "空闲"
        val done = if (Schedule.isDoneToday(this)) "今日已领完" else "今日未完成"
        statusToday.text = "$running · $done"
        val scheduled = Schedule.scheduleEnabled(this)
        val nextMs = Schedule.prefs(this).getLong(Schedule.KEY_NEXT, 0L)
        statusNext.text = when {
            !scheduled -> "定时启动已关闭"
            nextMs > 0 -> "下次 " + SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(nextMs))
            else -> "下次尚未安排"
        }
        timeValue.text = "%02d:%02d".format(Schedule.hour(this), Schedule.minute(this))
        rowTime.alpha = if (scheduled) 1f else 0.45f
        if (scheduleSwitch.isChecked != scheduled) scheduleSwitch.isChecked = scheduled
        val allow = Schedule.startWithoutWifi(this)
        if (wifiSwitch.isChecked != allow) wifiSwitch.isChecked = allow
    }

    private fun bindSwitches() {
        if (switchesBound) return
        switchesBound = true
        wifiSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != Schedule.startWithoutWifi(this)) {
                Schedule.setStartWithoutWifi(this, checked)
            }
        }
        scheduleSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == Schedule.scheduleEnabled(this)) return@setOnCheckedChangeListener
            Schedule.setScheduleEnabled(this, checked)
            refresh()
            toast(
                if (checked) "每天 %02d:%02d 自动启动".format(Schedule.hour(this), Schedule.minute(this))
                else "已关闭定时启动"
            )
        }
    }

    private fun append(line: String) {
        if (logView.text == "还没有运行记录") logView.text = ""
        logView.append(line + "\n")
        refresh()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_RUN = "run"
    }
}
