package com.alienbooster.claimer

import android.util.Log

object Shell {
    private const val TAG = "Shell"
    private const val PKG = FocusInfo.TARGET_PKG
    private const val COMPONENT = "com.etalien.booster/.ui.MainActivity"
    private const val UI_XML = "/data/local/tmp/claimer_ui.xml"

    const val DUMP_TIMEOUT_MS = 15_000

    private val DUMP_CMD =
        "rm -f $UI_XML; uiautomator dump $UI_XML; cat $UI_XML"

    fun focus(): FocusInfo? {
        val result = ShizukuManager.exec("dumpsys window | grep mCurrentFocus", 10_000)
        return FocusInfo.parse(result.stdout)
    }

    fun dumpXml(): String? {
        var attempt = 0
        while (attempt < 2) {
            val result = ShizukuManager.exec(DUMP_CMD, DUMP_TIMEOUT_MS)
            val xml = extractUiXml(result.stdout)
            if (!xml.isNullOrBlank()) return xml
            attempt += 1
            if (attempt < 2) {
                Log.w(TAG, "uiautomator dump empty, retry once")
            }
        }
        return null
    }

    fun tap(x: Int, y: Int): ShellResult {
        return ShizukuManager.exec("input tap $x $y", 8_000)
    }

    fun key(code: Int): ShellResult {
        return ShizukuManager.exec("input keyevent $code", 8_000)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): ShellResult {
        return ShizukuManager.exec("input swipe $x1 $y1 $x2 $y2 $durationMs", 10_000)
    }

    /** Digits only. Used for the lock-screen PIN after a keyguard check. */
    fun text(value: String): ShellResult {
        val digits = value.filter { it.isDigit() }
        if (digits.isEmpty()) return ShellResult(0, "", "", true)
        return ShizukuManager.exec("input text $digits", 8_000)
    }

    fun start(user: Int): ShellResult {
        return ShizukuManager.exec("am start --user $user -n $COMPONENT", 15_000)
    }

    fun forceStop(user: Int): ShellResult {
        return ShizukuManager.exec("am force-stop --user $user $PKG", 10_000)
    }

    fun isInstalled(user: Int): Boolean {
        val result = ShizukuManager.exec("pm list packages --user $user $PKG", 10_000)
        return result.stdout.contains("package:$PKG")
    }

    fun stayOn(on: Boolean): ShellResult {
        val value = if (on) "true" else "false"
        return ShizukuManager.exec("svc power stayon $value", 8_000)
    }

    internal fun extractUiXml(stdout: String): String? {
        val decl = stdout.indexOf("<?xml")
        val tag = stdout.indexOf("<hierarchy")
        val start = when {
            decl >= 0 && tag >= 0 -> minOf(decl, tag)
            decl >= 0 -> decl
            tag >= 0 -> tag
            else -> return null
        }
        val xml = stdout.substring(start).trim()
        if (!xml.contains("<hierarchy")) return null
        return xml
    }
}
