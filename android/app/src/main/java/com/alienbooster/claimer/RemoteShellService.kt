package com.alienbooster.claimer

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService. Runs as the shell UID and returns
 * `exitCode + "\n" + base64(stdout) + "\n" + base64(stderr)`.
 * Base64 is [Base64.NO_WRAP], which matches [ShellProtocol].
 *
 * Shizuku's ServiceStarter instantiates this class and casts it directly to IBinder
 * (see Shizuku-API server-shared `UserService.create`), so it must extend the AIDL Stub
 * itself — a class extending android.app.Service will not work.
 */
class RemoteShellService : IRemoteShell.Stub() {
    override fun exec(command: String?, timeoutMs: Int): String {
        return runCommand(command ?: "", timeoutMs)
    }

    private fun runCommand(command: String, timeoutMs: Int): String {
        val timeout = if (timeoutMs < 0) 0 else timeoutMs
        val process = try {
            ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(false)
                .start()
        } catch (t: Throwable) {
            val message = t.message ?: t.javaClass.simpleName
            return encode(-1, ByteArray(0), message.toByteArray(Charsets.UTF_8))
        }

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val outThread = Thread({ drain(process.inputStream, stdout) }, "shell-stdout")
        val errThread = Thread({ drain(process.errorStream, stderr) }, "shell-stderr")
        outThread.isDaemon = true
        errThread.isDaemon = true
        outThread.start()
        errThread.start()

        try {
            val finished = process.waitFor(timeout.toLong(), TimeUnit.MILLISECONDS)
            val code = if (finished) {
                process.exitValue()
            } else {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
                -1
            }
            outThread.join(1_000)
            errThread.join(1_000)
            return encode(code, stdout.toByteArray(), stderr.toByteArray())
        } catch (t: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            return encode(-1, stdout.toByteArray(), "interrupted".toByteArray(Charsets.UTF_8))
        }
    }

    private fun encode(code: Int, stdout: ByteArray, stderr: ByteArray): String {
        val out = Base64.encodeToString(stdout, Base64.NO_WRAP)
        val err = Base64.encodeToString(stderr, Base64.NO_WRAP)
        return "$code\n$out\n$err"
    }

    private fun drain(input: InputStream, into: ByteArrayOutputStream) {
        try {
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) into.write(buf, 0, n)
            }
        } catch (_: IOException) {
            // destroyForcibly closes the pipe.
        }
    }
}
