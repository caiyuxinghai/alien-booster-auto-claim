package com.alienbooster.claimer

import java.util.Base64

/**
 * Wire format shared with [RemoteShellService]:
 * `exitCode + "\n" + base64(stdout) + "\n" + base64(stderr)`.
 * [Base64.getEncoder] is the JDK equivalent of `android.util.Base64.NO_WRAP`.
 */
object ShellProtocol {
    fun encode(exitCode: Int, stdout: ByteArray, stderr: ByteArray): String {
        val enc = Base64.getEncoder()
        return exitCode.toString() + "\n" + enc.encodeToString(stdout) + "\n" + enc.encodeToString(stderr)
    }

    fun decode(payload: String): ShellResult {
        val parts = payload.split('\n', limit = 3)
        if (parts.size < 3) {
            return ShellResult(-1, "", "bad shell protocol", false)
        }
        val code = parts[0].trim().toIntOrNull()
            ?: return ShellResult(-1, "", "bad exit code", false)
        val stdout = decodeBytes(parts[1])
            ?: return ShellResult(code, "", "bad stdout base64", false)
        val stderr = decodeBytes(parts[2])
            ?: return ShellResult(code, "", "bad stderr base64", false)
        return ShellResult(code, stdout, stderr, code == 0)
    }

    private fun decodeBytes(text: String): String? {
        if (text.isEmpty()) return ""
        return try {
            String(Base64.getDecoder().decode(text), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val ok: Boolean
)
