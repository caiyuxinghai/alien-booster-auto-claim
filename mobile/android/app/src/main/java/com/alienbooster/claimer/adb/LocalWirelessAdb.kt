package com.alienbooster.claimer.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.wangzan101.mika.core.adb.AdbConnection
import com.wangzan101.mika.core.adb.AdbPairing
import com.wangzan101.mika.core.adb.AdbPairingException
import com.wangzan101.mika.core.adb.AdbServiceTypes
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class LocalAdbConnectionException(message: String) : IOException(message)

internal object LocalWirelessAdb {
    private const val HOST = "127.0.0.1"
    private const val HELPER_PORT = 34783
    private fun prefs(context: Context) = context.getSharedPreferences("phone_pairing", Context.MODE_PRIVATE)

    fun isPaired(context: Context) = prefs(context).getBoolean("paired", false)

    fun pair(context: Context, code: String, port: Int?) {
        require(Build.VERSION.SDK_INT >= 31) { "本机配对需要 Android 12 或更新版本" }
        require(code.matches(Regex("[0-9]{6}"))) { "请输入系统显示的六位配对码" }
        val candidates = if (port != null) listOf(port) else discover(context, AdbServiceTypes.PAIRING)
        if (candidates.isEmpty()) throw IOException("找不到本机配对端口，请保持系统配对码窗口打开")
        val pairing = AdbPairing(AndroidAdb.tls(AndroidAdb.pairingIdentity(context)))
        for (candidate in candidates) {
            try {
                pairing.pair(HOST, candidate, code, 4_000)
                prefs(context).edit().putBoolean("paired", true).remove("connect_port").apply()
                return
            } catch (failure: AdbPairingException) {
                if (failure.reason == AdbPairingException.Reason.WRONG_CODE) {
                    throw IOException("配对码错误或已过期，请重新打开系统配对窗口")
                }
            }
        }
        throw IOException("无法连接配对窗口。请重新打开配对码，也可手动填写配对端口")
    }

    fun connect(context: Context, port: Int?): AdbConnection {
        require(Build.VERSION.SDK_INT >= 31) { "本机启动需要 Android 12 或更新版本" }
        if (!isPaired(context)) throw IOException("请先完成手机配对")
        val tls = AndroidAdb.tls(AndroidAdb.identity(context))
        val saved = prefs(context).getInt("connect_port", 0)
        val tried = mutableSetOf(HELPER_PORT)
        fun tryPort(candidate: Int, timeoutMs: Int = 1_800): AdbConnection? {
            if (candidate !in 1..65535 || !tried.add(candidate)) return null
            val connection = try {
                AdbConnection.open(HOST, candidate, tls, timeoutMs)
            } catch (_: IOException) {
                return null
            }
            prefs(context).edit().putInt("connect_port", candidate).apply()
            return connection
        }
        if (port != null) {
            return tryPort(port) ?: throw LocalAdbConnectionException("连不上无线调试，请核对无线调试主页的连接端口")
        }
        tryPort(saved)?.let { return it }
        for (candidate in discover(context, AdbServiceTypes.CONNECT)) {
            tryPort(candidate)?.let { return it }
        }
        val fallback = try {
            LoopbackAdbDiscovery.find(tried)
        } catch (_: IOException) {
            null
        }
        if (fallback != null) {
            tryPort(fallback, 650)?.let {
                Log.i("ClaimerLocalAdb", "Connected via loopback fallback")
                return it
            }
        }
        throw LocalAdbConnectionException("未连接到本机无线调试。请打开系统无线调试，或填写连接端口")
    }

    // Only advertise-to-self results are accepted. All actual sockets use loopback.
    @Suppress("DEPRECATION")
    private fun discover(context: Context, serviceType: String): List<Int> {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return emptyList()
        val found = LinkedBlockingQueue<NsdServiceInfo>(24)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) = Unit
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { found.offer(info) }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
        }
        val ports = linkedSetOf<Int>()
        try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            var deadline = SystemClock.elapsedRealtime() + 4_000
            while (ports.size < 3) {
                val time = deadline - SystemClock.elapsedRealtime()
                if (time <= 0) break
                val candidate = found.poll(time, TimeUnit.MILLISECONDS) ?: break
                val result = LinkedBlockingQueue<Int>(1)
                nsd.resolveService(candidate, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) { result.offer(0) }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        result.offer(if (isLocal(info.host)) info.port else 0)
                    }
                })
                val resolved = result.poll(1_200, TimeUnit.MILLISECONDS) ?: 0
                if (resolved in 1..65535 && ports.add(resolved) && ports.size == 1) {
                    deadline = minOf(deadline, SystemClock.elapsedRealtime() + 400)
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        return ports.toList()
    }

    private fun isLocal(address: InetAddress?): Boolean {
        if (address == null) return false
        return address.isLoopbackAddress || runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence().any { adapter ->
                adapter.inetAddresses.asSequence().any { it == address }
            }
        }.getOrDefault(false)
    }
}
