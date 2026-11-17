package com.alienbooster.claimer

import android.Manifest
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dashboard. Opening it only reads status and settings. The target app is
 * started by 开始 and 自检, not by launching this activity.
 */
class MainActivity : android.app.Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val poll: Runnable = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }
    private var rendering = false
    private var logStamp = Long.MIN_VALUE
    private lateinit var startBg: Drawable
    private lateinit var stopBg: Drawable

    private lateinit var statusDot: View
    private lateinit var statusChip: TextView
    private lateinit var startButton: Button
    private lateinit var stage1Count: TextView
    private lateinit var stage2Count: TextView
    private lateinit var stage3Count: TextView
    private lateinit var stage1Bar: ProgressBar
    private lateinit var stage2Bar: ProgressBar
    private lateinit var stage3Bar: ProgressBar
    private lateinit var gainText: TextView
    private lateinit var phaseText: TextView
    private lateinit var nextText: TextView
    private lateinit var console: TextView
    private lateinit var scheduleSwitch: Switch
    private lateinit var scheduleTime: TextView
    private lateinit var scheduleWarn: TextView
    private lateinit var dualSwitch: Switch
    private lateinit var shizukuState: TextView
    private lateinit var pinEdit: EditText
    private lateinit var testSwitch: Switch

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuManager.init(applicationContext)
        window.statusBarColor = getColor(R.color.bg)
        window.navigationBarColor = getColor(R.color.bg)
        setContentView(R.layout.activity_main)
        bindViews()
        startBg = getDrawable(R.drawable.bg_button_start)!!.mutate()
        stopBg = getDrawable(R.drawable.bg_button_stop)!!.mutate()
        statusDot.background = statusDot.background.mutate()
        console.movementMethod = ScrollingMovementMethod.getInstance()
        wireClicks()
        requestNotifications()
        render()
        consumeLaunchExtras(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeLaunchExtras(intent)
    }

    override fun onStart() {
        super.onStart()
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (_: Throwable) {
        }
    }

    override fun onResume() {
        super.onResume()
        if (!ClaimService.running) {
            val snap = StatusStore.read(this)
            if (snap.running) {
                StatusStore.update(this) { it.copy(running = false) }
            }
        }
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    override fun onStop() {
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (_: Throwable) {
        }
        super.onStop()
    }

    private fun bindViews() {
        statusDot = findViewById(R.id.status_dot)
        statusChip = findViewById(R.id.status_chip_text)
        startButton = findViewById(R.id.start_button)
        stage1Count = findViewById(R.id.stage1_count)
        stage2Count = findViewById(R.id.stage2_count)
        stage3Count = findViewById(R.id.stage3_count)
        stage1Bar = findViewById(R.id.stage1_bar)
        stage2Bar = findViewById(R.id.stage2_bar)
        stage3Bar = findViewById(R.id.stage3_bar)
        gainText = findViewById(R.id.gain_text)
        phaseText = findViewById(R.id.phase_text)
        nextText = findViewById(R.id.next_text)
        console = findViewById(R.id.console)
        scheduleSwitch = findViewById(R.id.schedule_switch)
        scheduleTime = findViewById(R.id.schedule_time)
        scheduleWarn = findViewById(R.id.schedule_warn)
        dualSwitch = findViewById(R.id.dual_switch)
        shizukuState = findViewById(R.id.shizuku_state)
        pinEdit = findViewById(R.id.pin_edit)
        testSwitch = findViewById(R.id.test_switch)
        listOf(
            startButton,
            findViewById<Button>(R.id.grant_button),
            findViewById<Button>(R.id.open_shizuku_button),
            findViewById<Button>(R.id.selftest_button),
            findViewById<Button>(R.id.export_button),
            findViewById<Button>(R.id.battery_button)
        ).forEach { it.backgroundTintList = null }
    }

    private fun wireClicks() {
        startButton.setOnClickListener {
            if (ClaimService.running || StatusStore.read(this).running) {
                stopRun()
            } else {
                requestNotifications()
                startRun(maxAdsFromSettings(), user = -1, selftest = false)
            }
        }
        findViewById<Button>(R.id.selftest_button).setOnClickListener {
            if (ClaimService.running) {
                toast("已在运行")
            } else {
                requestNotifications()
                startRun(maxAds = 0, user = -1, selftest = true)
            }
        }
        findViewById<Button>(R.id.export_button).setOnClickListener { exportLog() }
        findViewById<Button>(R.id.battery_button).setOnClickListener { openBattery() }
        findViewById<Button>(R.id.grant_button).setOnClickListener { requestShizuku() }
        findViewById<Button>(R.id.open_shizuku_button).setOnClickListener { openShizuku() }
        scheduleSwitch.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            AppSettings.setScheduleEnabled(this, checked)
            val exact = ScheduleManager.reschedule(this)
            if (checked && !exact) showExactAlarmWarning()
            render()
        }
        dualSwitch.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            AppSettings.setAutoDual(this, checked)
        }
        testSwitch.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            AppSettings.setTestOneAd(this, checked)
        }
        scheduleTime.setOnClickListener { pickTime() }
        pinEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (rendering) return
                AppSettings.setPin(this@MainActivity, s?.toString().orEmpty())
            }
        })
    }

    private fun render() {
        val snap = StatusStore.read(this)
        val running = snap.running || ClaimService.running
        val connected = ShizukuManager.ping()
        val granted = connected && ShizukuManager.hasPermission()
        val chip = when {
            running -> getString(R.string.running_chip) to getColor(R.color.accent)
            !connected -> getString(R.string.shizuku_off) to getColor(R.color.error)
            !granted -> getString(R.string.shizuku_denied) to getColor(R.color.error)
            else -> getString(R.string.shizuku_on) to getColor(R.color.success)
        }
        statusChip.text = chip.first
        statusDot.background.setTint(chip.second)
        startButton.text = getString(if (running) R.string.stop else R.string.start)
        startButton.background = if (running) stopBg else startBg
        startButton.backgroundTintList = null

        val today = DailyFlags.today()
        val fresh = running || snap.day == today
        val s1 = if (fresh) snap.stage1 else 0
        val s2 = if (fresh) snap.stage2 else 0
        val s3 = if (fresh) snap.stage3 else 0
        stage1Count.text = "$s1/9"
        stage2Count.text = "$s2/3"
        stage3Count.text = "$s3/9"
        stage1Bar.progress = s1.coerceIn(0, 9)
        stage2Bar.progress = s2.coerceIn(0, 3)
        stage3Bar.progress = s3.coerceIn(0, 9)
        val minutes = s1 * 20 + s2 * 30 + s3 * 10
        gainText.text = getString(R.string.minutes, minutes)

        val user0Done = DailyFlags.exists(this, 0)
        val user128Done = DailyFlags.exists(this, 128)
        val dual = AppSettings.autoDual(this)
        phaseText.text = when {
            running && snap.selftest -> "自检中"
            running -> getString(R.string.running_phase, snap.slot.coerceAtLeast(1))
            !snap.error.isNullOrBlank() -> snap.error
            user0Done && (!dual || user128Done) -> getString(R.string.done_phase)
            else -> getString(R.string.idle_phase)
        }
        val next = ScheduleManager.nextTrigger(
            AppSettings.scheduleHour(this),
            AppSettings.scheduleMinute(this)
        )
        val whenLabel = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(next))
        nextText.text = if (AppSettings.scheduleEnabled(this)) "下次 $whenLabel" else "定时未开启"
        shizukuState.text = ShizukuManager.statusLabel()

        rendering = true
        scheduleSwitch.isChecked = AppSettings.scheduleEnabled(this)
        dualSwitch.isChecked = dual
        testSwitch.isChecked = AppSettings.testOneAd(this)
        scheduleTime.text = getString(
            R.string.every_day,
            AppSettings.scheduleHour(this),
            AppSettings.scheduleMinute(this)
        )
        if (!pinEdit.hasFocus()) {
            val stored = AppSettings.pin(this)
            if (pinEdit.text?.toString() != stored) pinEdit.setText(stored)
        }
        rendering = false

        val exactMissing = AppSettings.scheduleEnabled(this) && !ScheduleManager.canExact(this)
        scheduleWarn.visibility = if (exactMissing) View.VISIBLE else View.GONE
        if (exactMissing) scheduleWarn.text = "精确闹钟未授权，到点可能推迟"
        renderConsole()
    }

    private fun renderConsole() {
        val file = LogStore.file(this)
        val stamp = if (file.exists()) file.length() xor file.lastModified() else 0L
        if (stamp == logStamp && !console.text.isNullOrEmpty()) return
        logStamp = stamp
        val lines = LogStore.tail(this, 16)
        console.text = colorLines(lines)
        console.post {
            val layout = console.layout ?: return@post
            val y = (layout.getLineTop(console.lineCount) - console.height).coerceAtLeast(0)
            console.scrollTo(0, y)
        }
    }

    private fun colorLines(lines: List<String>): CharSequence {
        if (lines.isEmpty()) return " "
        val accent = getColor(R.color.accent)
        val text = SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            if (index > 0) text.append('\n')
            val start = text.length
            text.append(line)
            val space = line.indexOf(' ')
            if (space > 0) {
                text.setSpan(
                    ForegroundColorSpan(accent),
                    start,
                    start + space,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return text
    }

    private fun startRun(maxAds: Int, user: Int, selftest: Boolean) {
        val intent = Intent(this, ClaimService::class.java)
        if (selftest) {
            intent.putExtra(ClaimExtras.SELFTEST, true)
        } else {
            intent.putExtra(ClaimExtras.AUTO_START, true)
            if (maxAds > 0) intent.putExtra(ClaimExtras.MAX_ADS, maxAds)
        }
        intent.putExtra(ClaimExtras.USER, user)
        ClaimService.start(this, intent)
    }

    private fun stopRun() {
        val intent = Intent(this, ClaimService::class.java).setAction(ClaimService.ACTION_STOP)
        startService(intent)
    }

    private fun maxAdsFromSettings(): Int = if (AppSettings.testOneAd(this)) 1 else 21

    private fun consumeLaunchExtras(source: Intent?) {
        if (source == null) return
        val selftest = source.getBooleanExtra(ClaimExtras.SELFTEST, false)
        val auto = source.getBooleanExtra(ClaimExtras.AUTO_START, false)
        if (!selftest && !auto) return
        val maxAds = if (source.hasExtra(ClaimExtras.MAX_ADS)) {
            source.getIntExtra(ClaimExtras.MAX_ADS, -1)
        } else {
            -1
        }
        val user = if (source.hasExtra(ClaimExtras.USER)) {
            source.getIntExtra(ClaimExtras.USER, -1)
        } else {
            -1
        }
        source.removeExtra(ClaimExtras.SELFTEST)
        source.removeExtra(ClaimExtras.AUTO_START)
        source.removeExtra(ClaimExtras.MAX_ADS)
        source.removeExtra(ClaimExtras.USER)
        setIntent(source)
        if (selftest) {
            startRun(maxAds = 0, user = user, selftest = true)
        } else {
            val ads = if (maxAds > 0) maxAds else maxAdsFromSettings()
            startRun(maxAds = ads, user = user, selftest = false)
        }
    }

    private fun requestShizuku() {
        if (!ShizukuManager.ping()) {
            toast(getString(R.string.shizuku_off))
            openShizuku()
            return
        }
        if (ShizukuManager.hasPermission()) {
            toast(getString(R.string.shizuku_on))
            return
        }
        ShizukuManager.requestPermission()
    }

    private fun openShizuku() {
        val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (launch == null) {
            toast("未安装 Shizuku")
            return
        }
        startActivity(launch)
    }

    private fun pickTime() {
        TimePickerDialog(
            this,
            { _, hour, minute ->
                AppSettings.setScheduleTime(this, hour, minute)
                if (AppSettings.scheduleEnabled(this)) {
                    val exact = ScheduleManager.reschedule(this)
                    if (!exact) showExactAlarmWarning()
                }
                render()
            },
            AppSettings.scheduleHour(this),
            AppSettings.scheduleMinute(this),
            true
        ).show()
    }

    private fun showExactAlarmWarning() {
        toast("精确闹钟未授权，到点可能推迟")
        if (Build.VERSION.SDK_INT < 31) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun openBattery() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
                toast("无法打开电池优化设置")
            }
        }
    }

    private fun exportLog() {
        val body = LogStore.text(this)
        val clip = getSystemService(ClipboardManager::class.java)
        clip?.setPrimaryClip(ClipData.newPlainText("run.log", body.ifEmpty { "(empty)" }))
        val external = getExternalFilesDir(null)
        if (external != null) {
            File(external, "run.log").writeText(body, Charsets.UTF_8)
        }
        if (!LogStore.file(this).exists()) {
            LogStore.file(this).writeText("", Charsets.UTF_8)
        }
        val uri = Uri.parse("content://$packageName.log/run.log")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, body.take(100_000))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, "run.log", uri)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.export_log)))
        } catch (_: Throwable) {
            toast("日志已复制")
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
