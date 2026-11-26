package com.alienbooster.claimer.adb

import com.wangzan101.mika.core.adb.AdbMessage
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel

/** One on-demand search of this device's IPv4 loopback for a wireless ADB listener. */
internal object LoopbackAdbDiscovery {
    private const val BUDGET_MS = 2_300L
    private const val PROBE_TIMEOUT_MS = 180L
    private const val MAX_PENDING = 160
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val hello = AdbMessage(
        AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD,
        "host::\u0000".toByteArray(Charsets.US_ASCII)
    ).encode()

    private class Probe(val port: Int, val deadline: Long) {
        var request: ByteBuffer? = null
        var response: ByteBuffer? = null
    }

    fun find(skip: Set<Int>): Int? {
        val deadline = now() + BUDGET_MS
        val ports = ephemeralPorts().iterator()
        Selector.open().use { selector ->
            val pending = mutableSetOf<SelectionKey>()
            fun close(key: SelectionKey) {
                pending.remove(key)
                key.cancel()
                runCatching { key.channel().close() }
            }
            try {
                while ((ports.hasNext() || pending.isNotEmpty()) && now() < deadline &&
                    !Thread.currentThread().isInterrupted) {
                    var opened = 0
                    while (ports.hasNext() && pending.size < MAX_PENDING && opened < MAX_PENDING &&
                        now() < deadline) {
                        val port = ports.nextInt()
                        if (port in skip) continue
                        opened++
                        val channel = SocketChannel.open()
                        try {
                            channel.configureBlocking(false)
                            val connected = channel.connect(InetSocketAddress(loopback, port))
                            if (connected && channel.socket().localPort == port) {
                                channel.close()
                                continue
                            }
                            val operation = if (connected) SelectionKey.OP_WRITE else SelectionKey.OP_CONNECT
                            pending += channel.register(selector, operation, Probe(port, now() + PROBE_TIMEOUT_MS))
                        } catch (_: IOException) {
                            channel.close()
                        }
                    }
                    selector.select((deadline - now()).coerceIn(1L, 12L))
                    val selected = selector.selectedKeys().iterator()
                    while (selected.hasNext()) {
                        val key = selected.next()
                        selected.remove()
                        val channel = key.channel() as SocketChannel
                        val probe = key.attachment() as Probe
                        try {
                            if (key.isConnectable && channel.finishConnect()) {
                                if (channel.socket().localPort == probe.port) {
                                    close(key)
                                    continue
                                }
                                key.interestOps(SelectionKey.OP_WRITE)
                            }
                            if (key.isWritable) {
                                val request = probe.request ?: ByteBuffer.wrap(hello).also { probe.request = it }
                                channel.write(request)
                                if (!request.hasRemaining()) key.interestOps(SelectionKey.OP_READ)
                            }
                            if (key.isReadable) {
                                val response = probe.response ?: ByteBuffer.allocate(AdbMessage.HEADER_SIZE)
                                    .order(ByteOrder.LITTLE_ENDIAN).also { probe.response = it }
                                if (channel.read(response) < 0) close(key)
                                else if (!response.hasRemaining()) {
                                    if (response.getInt(0) == AdbMessage.STLS && response.getInt(12) == 0 &&
                                        response.getInt(20) == AdbMessage.STLS.inv()) return probe.port
                                    close(key)
                                }
                            }
                        } catch (_: IOException) {
                            close(key)
                        }
                    }
                    val time = now()
                    pending.filter { (it.attachment() as Probe).deadline <= time }.forEach(::close)
                }
            } finally {
                pending.toList().forEach(::close)
            }
        }
        return null
    }

    private fun now() = System.nanoTime() / 1_000_000L

    private fun ephemeralPorts(): IntRange {
        val bounds = runCatching {
            File("/proc/sys/net/ipv4/ip_local_port_range").readText().trim().split(Regex("\\s+"))
                .mapNotNull(String::toIntOrNull)
        }.getOrNull()
        if (bounds?.size == 2 && bounds[0] in 1024..65535 && bounds[1] in bounds[0]..65535) {
            return bounds[0]..bounds[1]
        }
        return 32768..60999
    }
}
