package com.achooluv.nikonlink.net

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Finds a camera that is already on the local network (Wi-Fi station mode):
 * the phone and the camera share a router, so we probe port 15740 across the
 * phone's own /24 subnets instead of joining a camera access point.
 */
object LanScanner {

    /** Hosts per subnet are probed with this many sockets in flight. */
    private const val CONCURRENCY = 48

    suspend fun scan(
        port: Int = 15740,
        timeoutMs: Int = 320,
        onProgress: ((Int) -> Unit)? = null,
    ): List<String> = withContext(Dispatchers.IO) {
        val subnets = localSubnets()
        if (subnets.isEmpty()) return@withContext emptyList()

        val hosts = subnets.flatMap { prefix -> (1..254).map { "$prefix$it" } }
        val gate = Semaphore(CONCURRENCY)
        var done = 0

        coroutineScope {
            hosts.map { host ->
                async {
                    val open = gate.withPermit { reachable(host, port, timeoutMs) }
                    synchronized(this@coroutineScope) {
                        done++
                        if (done % 32 == 0) onProgress?.invoke(done)
                    }
                    if (open) host else null
                }
            }.awaitAll().filterNotNull()
        }.sortedBy { it.substringAfterLast('.').toIntOrNull() ?: 0 }
    }

    /** "192.168.1." prefixes for every site-local IPv4 interface. */
    private fun localSubnets(): List<String> {
        val result = LinkedHashSet<String>()
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().forEach { iface ->
                if (!iface.isUp || iface.isLoopback) return@forEach
                iface.inetAddresses.toList().forEach { address ->
                    val ip = address as? Inet4Address ?: return@forEach
                    if (ip.isLoopbackAddress || ip.isLinkLocalAddress) return@forEach
                    val bytes = ip.address
                    result += "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}.${bytes[2].toInt() and 0xFF}."
                }
            }
        }
        return result.toList()
    }

    private fun reachable(host: String, port: Int, timeoutMs: Int): Boolean {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }
}
