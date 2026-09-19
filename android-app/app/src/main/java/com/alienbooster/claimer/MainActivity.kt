package com.alienbooster.claimer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        statusView = TextView(this).apply {
            textSize = 15f
            setPadding(24, 24, 24, 8)
        }
        startBtn = Button(this).apply { text = "开始（21 轮）" }
        stopBtn = Button(this).apply { text = "停止"; isEnabled = false }
        val permBtn = Button(this).apply { text = "打开无障碍设置" }
        logView = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 8, 24, 24)
            movementMethod = ScrollingMovementMethod()
        }
        val scroll = ScrollView(this).apply { addView(logView) }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(startBtn, LinearLayout.LayoutParams(0, -2, 1f))
            addView(stopBtn, LinearLayout.LayoutParams(0, -2, 1f))
            addView(permBtn, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(statusView)
            addView(btnRow)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)

        permBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        startBtn.setOnClickListener {
            if (AdClaimService.isEnabled()) {
                logView.text = ""
                AdClaimService.startLoop(21)
                refreshButtons()
            } else {
                appendLog("无障碍服务未开启，先点「打开无障碍设置」授权本应用")
            }
        }
        stopBtn.setOnClickListener {
            AdClaimService.stopLoop()
            refreshButtons()
            appendLog("已手动停止")
        }
    }

    override fun onResume() {
        super.onResume()
        AdClaimService.logListener = { msg ->
            runOnUiThread {
                appendLog(msg)
                refreshButtons()
            }
        }
        refreshButtons()
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        AdClaimService.logListener = null
    }

    private fun refreshStatus() {
        statusView.text = if (AdClaimService.isEnabled())
            "无障碍服务：已开启"
        else
            "无障碍服务：未开启（点下方按钮去设置里找到「外星仔自动领时长」并打开）"
    }

    private fun refreshButtons() {
        val running = AdClaimService.isRunning()
        startBtn.isEnabled = !running && AdClaimService.isEnabled()
        stopBtn.isEnabled = running
        refreshStatus()
    }

    private fun appendLog(msg: String) {
        logView.append(msg + "\n")
        logView.post {
            (logView.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }
}
