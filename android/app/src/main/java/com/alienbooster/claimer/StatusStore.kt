package com.alienbooster.claimer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Snapshot the dashboard polls. `status_json` holds the SPEC fields plus the
 * last 30 log lines and the calendar day the stages were read on.
 */
object StatusStore {
    private const val PREFS = "claimer_status"
    const val KEY = "status_json"
    const val LINE_LIMIT = 30

    private val lock = Any()

    data class Snapshot(
        val running: Boolean = false,
        val user: Int = 0,
        val slot: Int = 0,
        val maxAds: Int = 21,
        val credits: Int = 0,
        val stage1: Int = 0,
        val stage2: Int = 0,
        val stage3: Int = 0,
        val durationSec: Int? = null,
        val shizuku: Boolean = false,
        val lastLine: String = "",
        val updatedAt: Long = 0L,
        val error: String? = null,
        val lines: List<String> = emptyList(),
        val day: String = "",
        val selftest: Boolean = false
    ) {
        fun expectedMinutes(): Int = stage1 * 20 + stage2 * 30 + stage3 * 10
    }

    fun read(context: Context): Snapshot {
        synchronized(lock) {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
                ?: return Snapshot()
            return decode(raw)
        }
    }

    fun update(context: Context, block: (Snapshot) -> Snapshot): Snapshot {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val current = decode(prefs.getString(KEY, null))
            val next = block(current).copy(updatedAt = System.currentTimeMillis())
            prefs.edit().putString(KEY, encode(next)).apply()
            return next
        }
    }

    /** Keeps the last snapshot alive so the UI sees a fresh [Snapshot.updatedAt]. */
    fun touch(context: Context, shizuku: Boolean): Snapshot {
        return update(context) { it.copy(shizuku = shizuku) }
    }

    fun note(context: Context, line: String, shizuku: Boolean) {
        update(context) { current ->
            val lines = LogStore.tail(context, LINE_LIMIT)
            current.copy(lastLine = line, lines = lines, shizuku = shizuku)
        }
    }

    private fun decode(raw: String?): Snapshot {
        if (raw.isNullOrBlank()) return Snapshot()
        return try {
            val json = JSONObject(raw)
            val arr = json.optJSONArray("lines") ?: JSONArray()
            val lines = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                lines.add(arr.optString(i))
            }
            Snapshot(
                running = json.optBoolean("running", false),
                user = json.optInt("user", 0),
                slot = json.optInt("slot", 0),
                maxAds = json.optInt("maxAds", 21),
                credits = json.optInt("credits", 0),
                stage1 = json.optInt("stage1", 0),
                stage2 = json.optInt("stage2", 0),
                stage3 = json.optInt("stage3", 0),
                durationSec = if (json.has("durationSec") && !json.isNull("durationSec")) {
                    json.optInt("durationSec")
                } else {
                    null
                },
                shizuku = json.optBoolean("shizuku", false),
                lastLine = json.optString("lastLine", ""),
                updatedAt = json.optLong("updatedAt", 0L),
                error = json.optString("error", "").ifBlank { null },
                lines = lines,
                day = json.optString("day", ""),
                selftest = json.optBoolean("selftest", false)
            )
        } catch (_: Throwable) {
            Snapshot()
        }
    }

    private fun encode(snapshot: Snapshot): String {
        val json = JSONObject()
        json.put("running", snapshot.running)
        json.put("user", snapshot.user)
        json.put("slot", snapshot.slot)
        json.put("maxAds", snapshot.maxAds)
        json.put("credits", snapshot.credits)
        json.put("stage1", snapshot.stage1)
        json.put("stage2", snapshot.stage2)
        json.put("stage3", snapshot.stage3)
        if (snapshot.durationSec == null) {
            json.put("durationSec", JSONObject.NULL)
        } else {
            json.put("durationSec", snapshot.durationSec)
        }
        json.put("shizuku", snapshot.shizuku)
        json.put("lastLine", snapshot.lastLine)
        json.put("updatedAt", snapshot.updatedAt)
        json.put("error", snapshot.error ?: "")
        val arr = JSONArray()
        for (line in snapshot.lines.takeLast(LINE_LIMIT)) arr.put(line)
        json.put("lines", arr)
        json.put("day", snapshot.day)
        json.put("selftest", snapshot.selftest)
        return json.toString()
    }
}
