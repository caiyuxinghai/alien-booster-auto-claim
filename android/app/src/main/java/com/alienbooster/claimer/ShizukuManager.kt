package com.alienbooster.claimer

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Binds [RemoteShellService] through Shizuku. [exec] blocks, so call it from a
 * background thread. Shizuku delivers [ServiceConnection] on the main looper;
 * waiting on the main thread would deadlock.
 */
object ShizukuManager {
    const val PERMISSION_REQUEST_CODE = 1001
    private const val TAG = "ShizukuManager"
    private const val BIND_TIMEOUT_MS = 8_000L

    @Volatile private var appContext: Context? = null
    @Volatile private var remote: IRemoteShell? = null
    @Volatile private var binderDead: Boolean = false
    @Volatile private var listening = false

    private val bindLock = Any()

    private val userServiceDeath = IBinder.DeathRecipient {
        remote = null
    }

    private val deadListener = Shizuku.OnBinderDeadListener {
        binderDead = true
        remote = null
    }

    private val receivedListener = Shizuku.OnBinderReceivedListener {
        binderDead = false
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (listening) return
        synchronized(bindLock) {
            if (listening) return
            try {
                Shizuku.addBinderDeadListener(deadListener)
                Shizuku.addBinderReceivedListener(receivedListener)
                listening = true
            } catch (t: Throwable) {
                Log.w(TAG, "listener register failed", t)
            }
        }
    }

    fun ping(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            Log.w(TAG, "pingBinder failed", t)
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    fun requestPermission(requestCode: Int = PERMISSION_REQUEST_CODE) {
        try {
            if (ping() && !hasPermission()) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "requestPermission failed", t)
        }
    }

    fun ready(): Boolean = !binderDead && ping() && hasPermission()

    fun statusLabel(): String {
        if (binderDead || !ping()) return "Shizuku 未连接"
        if (!hasPermission()) return "Shizuku 未授权"
        return "Shizuku 已连接"
    }

    fun exec(command: String, timeoutMs: Int): ShellResult {
        val main = try {
            Looper.getMainLooper()
        } catch (_: Throwable) {
            null
        }
        if (main != null && Looper.myLooper() == main) {
            return ShellResult(-1, "", "exec must run on a background thread", false)
        }
        val shell = ensureRemote() ?: return ShellResult(-1, "", statusLabel(), false)
        return try {
            val raw = shell.exec(command, timeoutMs)
                ?: return ShellResult(-1, "", "null reply", false)
            ShellProtocol.decode(raw)
        } catch (t: Throwable) {
            remote = null
            Log.w(TAG, "exec failed", t)
            ShellResult(-1, "", t.message ?: t.javaClass.simpleName, false)
        }
    }

    private fun ensureRemote(): IRemoteShell? {
        alive()?.let { return it }
        val ctx = appContext ?: return null
        if (!ping() || !hasPermission()) return null
        synchronized(bindLock) {
            alive()?.let { return it }
            val latch = CountDownLatch(1)
            val slot = arrayOfNulls<IRemoteShell>(1)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    slot[0] = acceptBinder(binder)
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    remote = null
                    latch.countDown()
                }
            }
            try {
                val args = Shizuku.UserServiceArgs(
                    ComponentName(ctx.packageName, RemoteShellService::class.java.name)
                )
                    .daemon(false)
                    .processNameSuffix("shell")
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE)
                Shizuku.bindUserService(args, connection)
            } catch (t: Throwable) {
                Log.w(TAG, "bindUserService failed", t)
                return null
            }
            val opened = try {
                latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "UserService bind interrupted", t)
                false
            }
            if (!opened) {
                Log.w(TAG, "UserService bind timed out")
            }
            if (slot[0] != null) remote = slot[0]
            return alive()
        }
    }

    private fun acceptBinder(binder: IBinder?): IRemoteShell? {
        if (binder == null || !binder.pingBinder()) return null
        try {
            binder.linkToDeath(userServiceDeath, 0)
        } catch (_: Throwable) {
            // Already linked, or the binder died between ping and link.
        }
        return try {
            IRemoteShell.Stub.asInterface(binder)
        } catch (t: Throwable) {
            Log.w(TAG, "asInterface failed", t)
            null
        }
    }

    private fun alive(): IRemoteShell? {
        val current = remote ?: return null
        return try {
            if (current.asBinder().isBinderAlive) {
                current
            } else {
                remote = null
                null
            }
        } catch (_: Throwable) {
            remote = null
            null
        }
    }
}
