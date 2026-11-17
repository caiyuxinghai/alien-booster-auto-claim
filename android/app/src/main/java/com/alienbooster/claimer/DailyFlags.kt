package com.alienbooster.claimer

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** `files/claimed_u<user>_<yyyy-MM-dd>.flag`. Test mode must not call [write]. */
object DailyFlags {
    private val dayFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
    private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    fun today(now: Long = System.currentTimeMillis()): String =
        dayFormat.get()!!.format(Date(now))

    fun file(context: Context, user: Int, now: Long = System.currentTimeMillis()): File {
        return File(context.filesDir, "claimed_u${user}_${today(now)}.flag")
    }

    fun exists(context: Context, user: Int, now: Long = System.currentTimeMillis()): Boolean =
        file(context, user, now).exists()

    fun write(context: Context, user: Int, credits: Int, now: Long = System.currentTimeMillis()) {
        val stamp = timeFormat.get()!!.format(Date(now))
        file(context, user, now).writeText("credits=$credits at $stamp\n", Charsets.UTF_8)
    }
}
