package com.alienbooster.claimer

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** `files/run.log`. Rotates at 512 KB down to the last 256 KB. */
object LogStore {
    const val MAX_BYTES = 512 * 1024
    const val KEEP_BYTES = 256 * 1024

    private val lock = Any()
    private val stampFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    fun file(context: Context): File = File(context.filesDir, "run.log")

    fun append(context: Context, message: String) {
        val line = stampFormat.get()!!.format(Date()) + " " + message
        synchronized(lock) {
            val log = file(context)
            log.appendText(line + "\n", Charsets.UTF_8)
            if (log.length() > MAX_BYTES) {
                rotate(log)
            }
        }
    }

    fun tail(context: Context, maxLines: Int): List<String> {
        synchronized(lock) {
            val log = file(context)
            if (!log.exists() || log.length() == 0L) return emptyList()
            val text = log.readText(Charsets.UTF_8)
            return text.lineSequence()
                .filter { it.isNotBlank() }
                .toList()
                .takeLast(maxLines)
        }
    }

    fun text(context: Context): String {
        synchronized(lock) {
            val log = file(context)
            if (!log.exists()) return ""
            return log.readText(Charsets.UTF_8)
        }
    }

    private fun rotate(log: File) {
        val bytes = log.readBytes()
        var start = (bytes.size - KEEP_BYTES).coerceAtLeast(0)
        while (start < bytes.size && bytes[start] != '\n'.code.toByte()) {
            start++
        }
        if (start < bytes.size) start++
        if (start >= bytes.size) {
            log.writeBytes(ByteArray(0))
        } else {
            log.writeBytes(bytes.copyOfRange(start, bytes.size))
        }
    }
}
