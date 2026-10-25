package com.achooluv.nikonlink.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Joins the camera's own Wi-Fi access point and hands back the [Network] so the
 * PTP/IP sockets can be bound to it (the phone keeps cellular data meanwhile).
 */
class CameraWifi(private val context: Context) {

    private val connectivity: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)

    @Volatile
    var network: Network? = null
        private set

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** SSID the phone is currently associated with, or null. */
    fun currentSsid(): String? {
        val wifi = context.getSystemService(WifiManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val info = wifi.connectionInfo ?: return null
        @Suppress("DEPRECATION")
        val raw = info.ssid ?: return null
        val ssid = raw.trim('"', ' ')
        return ssid.ifBlank { null }
    }

    /** True for the access points Nikon bodies announce ("NIKON_..."). */
    fun looksLikeCamera(ssid: String?): Boolean =
        ssid?.uppercase()?.contains("NIKON") == true

    /**
     * Requests the camera AP described by [ssid]/[passphrase]. Suspends until the
     * system reports the network as available, or throws after [timeoutMs].
     */
    suspend fun requestNetwork(
        ssid: String,
        passphrase: String,
        timeoutMs: Long = 60_000,
    ): Network = suspendCancellableCoroutine { cont ->
        val cm = connectivity ?: throw IOException("系统网络服务不可用")

        val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (passphrase.isNotBlank()) {
            specifierBuilder.setWpa2Passphrase(passphrase)
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                this@CameraWifi.network = network
                if (cont.isActive) cont.resume(network)
            }

            override fun onUnavailable() {
                if (cont.isActive) {
                    cont.resumeWithException(
                        IOException("无法加入相机 Wi-Fi：请确认相机 Wi-Fi 已开启、密码正确"),
                    )
                }
            }

            override fun onLost(network: Network) {
                if (this@CameraWifi.network === network) this@CameraWifi.network = null
            }
        }
        callback = cb
        runCatching { cm.requestNetwork(request, cb, timeoutMs.toInt()) }
            .onFailure { if (cont.isActive) cont.resumeWithException(it) }

        cont.invokeOnCancellation {
            runCatching { cm.unregisterNetworkCallback(cb) }
            if (callback === cb) callback = null
        }
    }

    /** Releases the request-network callback, dropping the camera Wi-Fi association. */
    fun release() {
        callback?.let { cb -> runCatching { connectivity?.unregisterNetworkCallback(cb) } }
        callback = null
        network = null
    }

    companion object {
        /** Camera APs assign themselves this address when they host the network. */
        const val DEFAULT_CAMERA_HOST = "192.168.1.1"
    }
}
