package com.alienbooster.claimer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The dashboard can request only the fixed operations supported by the on-device helper. */
internal class BackgroundClient(context: Context) {
    enum class Command(val mode: Mode? = null) {
        STATUS,
        START(Mode.BACKGROUND),
        START_FOREGROUND(Mode.FOREGROUND),
        START_ALL_BACKGROUND(Mode.ALL_BACKGROUND),
        SCHEDULE_ALL_BACKGROUND(Mode.ALL_BACKGROUND),
        START_ALL_FOREGROUND(Mode.ALL_FOREGROUND),
        START_CONTINUOUS_BACKGROUND(Mode.CONTINUOUS_BACKGROUND),
        START_CONTINUOUS_FOREGROUND(Mode.CONTINUOUS_FOREGROUND),
        STOP
    }
    enum class State { IDLE, RUNNING, WAITING, STOPPED, DONE, ERROR }
    enum class Mode(val prefix: String, val label: String, val foreground: Boolean) {
        BACKGROUND("", "后台领双号", false),
        FOREGROUND("[主屏双号] ", "主屏双号", true),
        ALL_BACKGROUND("[后台全部] ", "后台全部账号", false),
        ALL_FOREGROUND("[主屏全部] ", "主屏全部账号", true),
        CONTINUOUS_BACKGROUND("[后台持续] ", "后台持续观看", false),
        CONTINUOUS_FOREGROUND("[主屏持续] ", "主屏持续观看", true)
    }

    data class Status(val state: State, val owner: Int, val clone: Int, val message: String, val mode: Mode)

    sealed class Result {
        data class Ready(val status: Status) : Result()
        data class Failed(val message: String, val commandMayHaveRun: Boolean = false) : Result()
    }

    private val tokenFile = AtomicFile(File(context.applicationContext.filesDir, "dual.token"))
    private val mainHandler = Handler(Looper.getMainLooper())

    fun request(command: Command, onResult: (Result) -> Unit) {
        enqueue(command.name, onResult)
    }

    fun applyNetworkPolicy(allowed: Boolean, onResult: (Result) -> Unit) {
        enqueue(if (allowed) "NET_ALLOW_MOBILE" else "NET_WIFI_ONLY", onResult)
    }

    private fun enqueue(command: String, onResult: (Result) -> Unit) {
        executor.execute {
            val result = try {
                parse(exchange(command, false), command == "STATUS")
            } catch (error: TransportError) {
                Result.Failed(error.message ?: "后台助手连接失败", error.maybeSent)
            } catch (_: IOException) {
                Result.Failed("后台助手回复格式无效，正在核对状态", command != "STATUS")
            }
            mainHandler.post { onResult(result) }
        }
    }

    fun historyRpcBlocking(command: String): String {
        if (Looper.myLooper() == Looper.getMainLooper()) throw IOException("请在线程中读取记录")
        if (command != "HISTORY" && command != "CLEAR_HISTORY" && !deleteCommand.matches(command)) {
            throw IOException("未知记录操作")
        }
        val reply = exchange(command, true)
        if (!reply.startsWith("HIST\t")) throw IOException("后台助手无法完成记录操作")
        return reply.substring(5)
    }

    private class TransportError(message: String, val maybeSent: Boolean = false) : IOException(message)

    private fun exchange(command: String, history: Boolean): String {
        val token = try {
            synchronized(tokenLock) { loadToken() }
        } catch (_: IOException) {
            throw TransportError("后台授权文件不可读，请重新接入后台助手")
        }
        var sent = false
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(loopback, PORT), 700)
                val deadline = SystemClock.elapsedRealtime() + if (history) 4500 else 2000
                val input = socket.getInputStream().buffered()
                val clientNonce = hex(ByteArray(16).also { random.nextBytes(it) })
                socket.getOutputStream().write("HELLO\t$clientNonce\n".toByteArray(Charsets.US_ASCII))
                val hello = readReply(socket, input, deadline, 256).split('\t', limit = 3)
                val serverNonce = hello.getOrNull(1).orEmpty()
                if (hello.size != 3 || hello[0] != "AUTH" || serverNonce.length != 32 ||
                    serverNonce.any { it !in HEX } ||
                    !verify(token, "hello\t$clientNonce\t$serverNonce", hello[2])) {
                    throw TransportError("后台助手验证失败，请重新接入后台助手")
                }

                val proof = sign(token, "cmd\t$clientNonce\t$serverNonce\t$command")
                sent = command != "STATUS" && command != "HISTORY"
                socket.getOutputStream().apply {
                    write("CMD\t$command\t$proof\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val reply = readReply(socket, input, deadline, if (history) 96 * 1024 else 2048)
                val end = reply.lastIndexOf('\t')
                if (end < 0) throw IOException("Missing reply authentication")
                val body = reply.substring(0, end)
                if (!verify(token, "result\t$clientNonce\t$serverNonce\t$body", reply.substring(end + 1))) {
                    throw TransportError("后台助手回复无法验证，正在重新确认状态", sent)
                }
                body
            }
        } catch (error: TransportError) {
            throw error
        } catch (_: ConnectException) {
            throw TransportError("后台助手未连接。首次使用或重启手机后，需要启动后台助手", sent)
        } catch (_: SocketTimeoutException) {
            throw TransportError("后台助手响应超时，正在重新确认状态", sent)
        } catch (_: IOException) {
            throw TransportError("后台助手连接中断，正在重新确认状态", sent)
        } catch (_: SecurityException) {
            throw TransportError("系统未允许连接本机后台助手", sent)
        } catch (_: GeneralSecurityException) {
            throw TransportError("无法验证后台助手，请重新接入", sent)
        }
    }

    private fun loadToken(): String {
        if (!tokenFile.baseFile.exists() && !File(tokenFile.baseFile.path + ".bak").exists()) {
            val hex = hex(ByteArray(32).also { random.nextBytes(it) })
            val stream = tokenFile.startWrite()
            try {
                stream.write(hex.toByteArray(Charsets.US_ASCII))
                tokenFile.finishWrite(stream)
            } catch (error: IOException) {
                tokenFile.failWrite(stream)
                throw error
            }
            return hex
        }

        return tokenFile.openRead().use { stream ->
            val bytes = ByteArrayOutputStream(64)
            while (bytes.size() < 66) {
                val next = stream.read()
                if (next == -1) break
                bytes.write(next)
            }
            val token = bytes.toString(Charsets.US_ASCII.name()).trimEnd('\n')
            if (token.length != 64 || token.any { it !in HEX }) throw IOException("Invalid authorization")
            token
        }
    }

    private fun readReply(socket: Socket, input: InputStream, deadline: Long, maxBytes: Int): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(2048)
        while (bytes.size() < maxBytes) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) throw SocketTimeoutException()
            socket.soTimeout = remaining.toInt()
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - bytes.size()))
            if (count < 0) throw IOException("Incomplete reply")
            for (i in 0 until count) {
                if (buffer[i] == '\n'.code.toByte()) {
                    if (i != count - 1) throw IOException("Unexpected trailing data")
                    bytes.write(buffer, 0, i)
                    return bytes.toString(Charsets.UTF_8.name())
                }
            }
            bytes.write(buffer, 0, count)
        }
        throw IOException("Oversized reply")
    }

    private fun sign(token: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        return hex(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    private fun verify(token: String, message: String, signature: String): Boolean =
        signature.length == 64 && signature.all { it in HEX } && MessageDigest.isEqual(
            sign(token, message).toByteArray(Charsets.US_ASCII), signature.toByteArray(Charsets.US_ASCII)
        )

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0xf])
        }
    }

    private fun parse(reply: String, statusCheck: Boolean): Result {
        val fields = reply.split('\t', limit = 5)
        if (fields.first() == "ERR") {
            if (fields.getOrNull(1) == "BUSY") return Result.Failed("已有任务运行")
            return Result.Failed("后台助手未接受请求，请检查是否已接入此版本")
        }
        if (fields.size != 5 || fields[0] != "OK" || fields[4].any { it.isISOControl() }) {
            throw IOException("Invalid reply")
        }
        val state = State.entries.firstOrNull { it.name == fields[1] } ?: throw IOException("Invalid state")
        val owner = fields[2].toIntOrNull()?.takeIf { it in -1..21 } ?: throw IOException("Invalid owner progress")
        val clone = fields[3].toIntOrNull()?.takeIf { it in -1..21 } ?: throw IOException("Invalid clone progress")
        if (statusCheck && !fields[4].startsWith(STATUS_PROTOCOL)) {
            return Result.Failed("后台助手需要更新，请运行 0.12.0 恢复包")
        }
        val detail = if (statusCheck) fields[4].removePrefix(STATUS_PROTOCOL) else fields[4]
        val mode = Mode.entries.firstOrNull { it.prefix.isNotEmpty() && detail.startsWith(it.prefix) }
            ?: Mode.BACKGROUND
        val message = detail.removePrefix(mode.prefix)
        return Result.Ready(Status(state, owner, clone, message, mode))
    }

    companion object {
        private const val PORT = 34783
        private const val STATUS_PROTOCOL = "[0.12] "
        private const val HEX = "0123456789abcdef"
        private val random = SecureRandom()
        private val tokenLock = Any()
        private val deleteCommand = Regex("DELETE_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private val executor = Executors.newSingleThreadExecutor()
    }
}
