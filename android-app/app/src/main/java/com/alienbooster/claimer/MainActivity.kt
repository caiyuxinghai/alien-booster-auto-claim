package com.alienbooster.claimer

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private val logLines = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        statusView = TextView(this).apply {
            textSize = 18f
            setPadding(0, 0, 0, pad)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val startBtn = Button(this).apply {
            text = "开始"
            setOnClickListener { onStartClicked() }
        }
        val stopBtn = Button(this).apply {
            text = "停止"
            setOnClickListener {
                AdClaimService.stopLoop()
                refreshStatus()
            }
        }
        row.addView(
            startBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            stopBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        scrollView = ScrollView(this).apply {
            addView(
                logView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        root.addView(
            statusView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        setContentView(root)

        AdClaimService.logListener = { line ->
            runOnUiThread { appendLog(line) }
        }
    }

    private fun onStartClicked() {
        if (AdClaimService.instance == null) {
            Toast.makeText(this, "请先开启 claimer 无障碍服务", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            AdClaimService.startLoop()
            refreshStatus()
        }
    }

    private fun appendLog(line: String) {
        logLines.add(line)
        if (logLines.size > 500) {
            logLines.subList(0, logLines.size - 500).clear()
        }
        logView.text = logLines.joinToString("\n")
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refreshStatus() {
        statusView.text = if (AdClaimService.isRunning.get()) "运行中" else "已停止"
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        AdClaimService.logListener = null
        super.onDestroy()
    }
}
