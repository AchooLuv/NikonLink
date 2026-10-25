package com.achooluv.nikonlink.transfer

import android.net.Network
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Finds the PTP/IP responder behind the camera's access point. */
object CameraDiscovery {

    private val CANDIDATES = listOf(
        "192.168.1.1",
        "192.168.0.1",
        "10.0.0.1",
        "192.168.100.1",
    )

    /**
     * Probes the usual Nikon gateway addresses (in parallel) and returns the first
     * one with an open PTP/IP port. [network] binds the probe sockets to the
     * camera Wi-Fi so cellular routes are not used.
     */
    suspend fun find(port: Int, network: Network?, timeoutMs: Int = 1500): String? =
        withContext(Dispatchers.IO) {
            coroutineScope {
                CANDIDATES.map { host ->
                    async {
                        if (probe(host, port, network, timeoutMs)) host else null
                    }
                }.awaitAll().firstOrNull { it != null }
            }
        }

    fun probe(host: String, port: Int, network: Network?, timeoutMs: Int = 1500): Boolean {
        val socket = try {
            network?.socketFactory?.createSocket() ?: Socket()
        } catch (_: Exception) {
            return false
        }
        return try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        } catch (_: IOException) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }
}
