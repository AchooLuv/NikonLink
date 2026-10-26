package com.achooluv.nikonlink.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
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
 * Finds a camera that is already on the local network (Wi-Fi station mode).
 *
 * The subnet list comes from [LinkProperties] first (authoritative, works even
 * when interface enumeration is restricted), with [NetworkInterface] as a
 * fallback; a saved camera IP contributes its own /24 as well.
 */
object LanScanner {

    private const val CONCURRENCY = 64

    fun localSubnets(context: Context, network: Network? = null): List<String> {
        val prefixes = LinkedHashSet<String>()

        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val target = network ?: cm?.activeNetwork
            val props: LinkProperties? = cm?.getLinkProperties(target)
            props?.linkAddresses?.forEach { link ->
                val ip = link.address as? Inet4Address ?: return@forEach
                if (ip.isLoopbackAddress || ip.isLinkLocalAddress) return@forEach
                prefixes += prefixOf(ip)
            }
        }

        if (prefixes.isEmpty()) {
            runCatching {
                NetworkInterface.getNetworkInterfaces().toList().forEach { iface ->
                    if (!iface.isUp || iface.isLoopback) return@forEach
                    iface.inetAddresses.toList().forEach { address ->
                        val ip = address as? Inet4Address ?: return@forEach
                        if (ip.isLoopbackAddress || ip.isLinkLocalAddress) return@forEach
                        prefixes += prefixOf(ip)
                    }
                }
            }
        }
        return prefixes.toList()
    }

    private fun prefixOf(ip: Inet4Address): String {
        val b = ip.address
        return "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}."
    }

    suspend fun scan(
        context: Context,
        port: Int = 15740,
        timeoutMs: Int = 650,
        extraHost: String? = null,
        onProgress: ((Int) -> Unit)? = null,
    ): List<String> = withContext(Dispatchers.IO) {
        val prefixes = LinkedHashSet(localSubnets(context))
        extraHost?.trim()?.takeIf { it.count { ch -> ch == '.' } == 3 }?.let { host ->
            prefixes += host.substringBeforeLast('.') + "."
        }
        if (prefixes.isEmpty()) return@withContext emptyList()

        val hosts = prefixes.flatMap { prefix -> (1..254).map { "$prefix$it" } }.distinct()
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
