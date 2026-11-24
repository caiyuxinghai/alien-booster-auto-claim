package com.alienbooster.claimer

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

internal data class RunRecord(
    val id: String,
    val started: Long,
    val end: Long,
    val elapsed: Long,
    val mode: String,
    val reason: String,
    val gained: Int,
    val local: Boolean
)

/** Merges shell-helper records with records produced by the accessibility-only mode. */
internal object RunHistory {
    private const val PREFS = "run_history"
    private const val KEY = "records"
    private const val MAX_RECORDS = 100
    private val lock = Any()
    private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    @Throws(IOException::class)
    fun list(context: Context): List<RunRecord> {
        val remote = BackgroundClient(context.applicationContext).historyRpcBlocking("HISTORY")
        val merged = ArrayList<RunRecord>()
        merged.addAll(parseRemote(remote))
        merged.addAll(localOnly(context))
        return merged.distinctBy { it.id }
    }

    @Throws(IOException::class)
    fun localOnly(context: Context): List<RunRecord> = synchronized(lock) { readLocal(context) }

    @Throws(IOException::class)
    fun delete(context: Context, id: String) {
        if (!idPattern.matches(id)) throw IOException("记录标识无效")
        val app = context.applicationContext
        val removedLocal = synchronized(lock) {
            val records = readLocal(app)
            val remaining = records.filterNot { it.id == id }
            if (remaining.size != records.size) {
                writeLocal(app, remaining)
                true
            } else false
        }
        if (removedLocal) return
        val reply = BackgroundClient(app).historyRpcBlocking("DELETE_$id")
        checkAccepted(reply)
    }

    @Throws(IOException::class)
    fun clear(context: Context) {
        val app = context.applicationContext
        checkAccepted(BackgroundClient(app).historyRpcBlocking("CLEAR_HISTORY"))
        synchronized(lock) { writeLocal(app, emptyList()) }
    }

    @Throws(IOException::class)
    fun recordLocal(
        context: Context,
        started: Long,
        end: Long,
        elapsed: Long,
        mode: String,
        reason: String,
        gained: Int
    ) {
        if (started <= 0 || end <= 0 || elapsed < 0 || gained < 0) throw IOException("记录的时间或计数无效")
        val record = RunRecord(
            UUID.randomUUID().toString(), started, end, elapsed, clean(mode, 20),
            clean(reason, 100), gained, true
        )
        synchronized(lock) {
            val records = ArrayList<RunRecord>()
            records.add(record)
            records.addAll(readLocal(context.applicationContext))
            writeLocal(context.applicationContext, records)
        }
    }

    private fun readLocal(context: Context): List<RunRecord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]").orEmpty()
        try {
            val array = JSONArray(raw)
            if (array.length() > MAX_RECORDS) throw IOException("本机领取记录条数无效")
            val records = ArrayList<RunRecord>()
            for (index in 0 until array.length()) {
                records.add(record(array.getJSONObject(index), true))
            }
            return records
        } catch (error: JSONException) {
            throw IOException("本机领取记录格式无效", error)
        }
    }

    @Throws(IOException::class)
    private fun writeLocal(context: Context, records: List<RunRecord>) {
        val array = JSONArray()
        records.take(MAX_RECORDS).forEach { item ->
            array.put(JSONObject()
                .put("id", item.id)
                .put("started", item.started)
                .put("end", item.end)
                .put("elapsed", item.elapsed)
                .put("mode", clean(item.mode, 20))
                .put("reason", clean(item.reason, 100))
                .put("gained", item.gained))
        }
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, array.toString()).commit()
        if (!saved) throw IOException("本机领取记录保存失败")
    }

    @Throws(IOException::class)
    private fun parseRemote(raw: String): List<RunRecord> {
        try {
            val root = JSONObject(raw)
            if (!root.optBoolean("ok", false)) throw IOException("后台领取记录读取失败")
            val array = root.getJSONArray("records")
            if (array.length() > MAX_RECORDS) throw IOException("后台领取记录条数无效")
            val records = ArrayList<RunRecord>()
            for (index in 0 until array.length()) {
                records.add(record(array.getJSONObject(index), false))
            }
            return records
        } catch (error: JSONException) {
            throw IOException("后台领取记录格式无效", error)
        }
    }

    @Throws(IOException::class)
    private fun checkAccepted(raw: String) {
        try {
            if (!JSONObject(raw).optBoolean("ok", false)) throw IOException("后台记录操作未完成")
        } catch (error: JSONException) {
            throw IOException("后台记录回复格式无效", error)
        }
    }

    @Throws(IOException::class)
    private fun record(row: JSONObject, local: Boolean): RunRecord {
        try {
            val id = row.getString("id")
            val started = row.getLong("started")
            val end = row.getLong("end")
            val elapsed = row.getLong("elapsed")
            val gained = row.getInt("gained")
            if (!idPattern.matches(id) || started <= 0 || end <= 0 || elapsed < 0 || gained < 0) {
                throw IOException("领取记录内容无效")
            }
            val rawMode = row.optString("mode")
            val mode = if (local) rawMode else when (rawMode) {
                "BACKGROUND" -> "后台双号"
                "FOREGROUND" -> "主屏双号"
                "ALL_BACKGROUND" -> "后台全部账号"
                "ALL_FOREGROUND" -> "主屏全部账号"
                "CONTINUOUS_BACKGROUND" -> "后台持续观看"
                "CONTINUOUS_FOREGROUND" -> "主屏持续观看"
                else -> rawMode
            }
            return RunRecord(id, started, end, elapsed, clean(mode, 20),
                clean(row.optString("reason"), 100), gained, local)
        } catch (error: JSONException) {
            throw IOException("领取记录字段缺失", error)
        }
    }

    private fun clean(value: String, limit: Int): String =
        value.replace(Regex("[\\p{Cntrl}\\u2028\\u2029]"), " ").trim().take(limit)
}
