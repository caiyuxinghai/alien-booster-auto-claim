package com.alienbooster.claimer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.alienbooster.claimer.adb.LocalWirelessAdb
import com.wangzan101.mika.core.adb.AdbConnection
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

internal object PhoneHelperBootstrap {
    private const val ROOT = "/data/local/tmp/codex-dual"
    private val files = listOf("dual-helper.jar", "market_lease.sh", "supervise.sh", "bootstrap.sh")
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun isPaired(context: Context) = LocalWirelessAdb.isPaired(context)

    fun pair(context: Context, code: String, pairingPort: Int? = null, callback: (Result<Unit>) -> Unit) {
        val app = context.applicationContext
        dispatch(callback) { LocalWirelessAdb.pair(app, code, pairingPort) }
    }

    fun start(context: Context, connectPort: Int? = null, callback: (Result<Unit>) -> Unit) {
        val app = context.applicationContext
        dispatch(callback) { startBlocking(app, connectPort) }
    }

    private fun dispatch(callback: (Result<Unit>) -> Unit, action: () -> Unit) {
        worker.execute {
            val result = runCatching(action)
            main.post { callback(result) }
        }
    }

    private fun startBlocking(context: Context, connectPort: Int?) {
        val client = BackgroundClient(context)
        when (val current = client.statusBlocking()) {
            is BackgroundClient.Result.Ready -> return
            is BackgroundClient.Result.Failed -> {
                if (!current.offline && !current.needsUpgrade) throw IOException(current.message)
            }
        }
        val launchFailure = LocalWirelessAdb.connect(context, connectPort).use { connection ->
            val id = UUID.randomUUID().toString().replace("-", "")
            val stage = "$ROOT/mobile-$id"
            val makeStage = "umask 077; " +
                "[ ! -L $ROOT ] && mkdir -p $ROOT && " +
                "[ \"\$(stat -c %u $ROOT)\" = 2000 ] && chmod 700 $ROOT && " +
                "mkdir $stage && printf 'READY\\n'"
            if (command(connection, makeStage) != "READY") throw IOException("无法建立助手私有目录")
            try {
                for (name in files) {
                    val data = context.assets.open("helper/$name").use { it.readBytes() }
                    val path = "$stage/$name"
                    push(connection, path, data)
                    val expected = MessageDigest.getInstance("SHA-256").digest(data)
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    if (command(connection, "sha256sum $path").substringBefore(' ') != expected) {
                        throw IOException("助手文件校验失败")
                    }
                }
                try {
                    val reply = command(connection, "/system/bin/sh $stage/bootstrap.sh $id", 24_000)
                    when (reply.lineSequence().lastOrNull()) {
                        "READY" -> null
                        "FAIL_BUSY" -> IOException("助手正在运行或收尾，请暂停任务后再启动")
                        "FAIL_APP_AUTH" -> IOException("助手授权文件不可读，请重新打开 App 后再试")
                        else -> IOException("助手启动未完成，请在助手维护中重试")
                    }
                } catch (failure: IOException) {
                    failure
                }
            } finally {
                val stagedFiles = (files + listOf("generation", "token")).joinToString(" ") { "$stage/$it" }
                runCatching { command(connection, "rm -f $stagedFiles; rmdir $stage", 1_800) }
            }
        }
        // ADB can close the launching command before delivering its final status line.
        // The helper's authenticated protocol is the source of truth for readiness.
        val deadline = SystemClock.elapsedRealtime() + 2_000
        do {
            when (val current = client.statusBlocking()) {
                is BackgroundClient.Result.Ready -> return
                is BackgroundClient.Result.Failed -> {
                    if (!current.offline || SystemClock.elapsedRealtime() >= deadline) {
                        throw launchFailure ?: IOException(current.message)
                    }
                }
            }
            Thread.sleep(150)
        } while (true)
    }

    private fun command(connection: AdbConnection, script: String, timeoutMs: Long = 4_000): String {
        val quoted = "'" + script.replace("'", "'\\''") + "'"
        return connection.exec("/system/bin/sh -c $quoted").use { stream ->
            val result = ByteArrayOutputStream()
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (true) {
                val time = deadline - SystemClock.elapsedRealtime()
                if (time <= 0) throw IOException("等待手机助手启动超时")
                val data = stream.readWithin(time) ?: break
                if (result.size() + data.size > 8_192) throw IOException("手机返回了无法识别的启动结果")
                result.write(data)
            }
            result.toString(Charsets.UTF_8.name()).trim()
        }
    }

    private fun push(connection: AdbConnection, path: String, data: ByteArray) {
        connection.open("sync:").use { stream ->
            val request = ByteArrayOutputStream()
            fun frame(id: String, value: Int) {
                request.write(id.toByteArray(Charsets.US_ASCII))
                request.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
            }
            val destination = "$path,33152".toByteArray(Charsets.UTF_8)
            frame("SEND", destination.size)
            request.write(destination)
            for (offset in data.indices step 65536) {
                val count = minOf(data.size - offset, 65536)
                frame("DATA", count)
                request.write(data, offset, count)
            }
            frame("DONE", (System.currentTimeMillis() / 1000).toInt())
            stream.write(request.toByteArray())
            val response = ByteArrayOutputStream()
            while (response.size() < 8) {
                response.write(stream.readWithin(3_000) ?: throw IOException("传输助手文件时连接中断"))
            }
            if (response.toString(Charsets.US_ASCII.name()).take(4) != "OKAY") {
                throw IOException("手机未接受助手文件")
            }
        }
    }
}
