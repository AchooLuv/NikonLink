package com.achooluv.nikonlink

import android.app.Application
import android.net.Network
import android.util.LruCache
import com.achooluv.nikonlink.camera.CameraFile
import com.achooluv.nikonlink.camera.CameraIdentity
import com.achooluv.nikonlink.camera.CameraProfile
import com.achooluv.nikonlink.data.SettingsStore
import com.achooluv.nikonlink.net.CameraWifi
import com.achooluv.nikonlink.ptp.Ptp
import com.achooluv.nikonlink.ptp.PtpEvent
import com.achooluv.nikonlink.ptp.PtpIpClient
import com.achooluv.nikonlink.service.ConnectionService
import com.achooluv.nikonlink.transfer.CameraDiscovery
import com.achooluv.nikonlink.transfer.Downloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Connection lifecycle as the UI sees it. */
sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val step: String) : ConnState
    data class Connected(val model: String) : ConnState
    data class Reconnecting(val attempt: Int, val waitSeconds: Int) : ConnState
    data class Error(val message: String) : ConnState
}

/**
 * Single source of truth for the camera link, the library and transfers.
 * Lives for the whole process so the PTP/IP session survives configuration
 * changes and activity restarts.
 */
class NikonLinkRepository private constructor(private val app: Application) {

    val settings = SettingsStore(app)
    val wifi = CameraWifi(app)
    private val downloader = Downloader(app, settings)
    private val client = PtpIpClient("NikonLink/${BuildConfig.VERSION_NAME} (Android)")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state

    private val _identity = MutableStateFlow<CameraIdentity?>(null)
    val identity: StateFlow<CameraIdentity?> = _identity

    private val _profile = MutableStateFlow<CameraProfile?>(null)
    val profile: StateFlow<CameraProfile?> = _profile

    private val _files = MutableStateFlow<List<CameraFile>>(emptyList())
    val files: StateFlow<List<CameraFile>> = _files

    private val _libraryBusy = MutableStateFlow(false)
    val libraryBusy: StateFlow<Boolean> = _libraryBusy

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore

    private val _progress = MutableStateFlow(Downloader.Progress())
    val progress: StateFlow<Downloader.Progress> = _progress

    /** Bumped whenever a file finishes so the grid can refresh its "downloaded" badges. */
    private val _importsVersion = MutableStateFlow(0)
    val importsVersion: StateFlow<Int> = _importsVersion

    /** False when the body refuses the PTP/IP event channel (auto-pull is then unavailable). */
    private val _eventsSupported = MutableStateFlow(true)
    val eventsSupported: StateFlow<Boolean> = _eventsSupported

    /** Files that failed in the last batch, so the browse screen can offer a retry. */
    private val _failedFiles = MutableStateFlow<List<CameraFile>>(emptyList())
    val failedFiles: StateFlow<List<CameraFile>> = _failedFiles

    /** MediaStore URI of the most recently imported file ("open in gallery"). */
    private val _lastUri = MutableStateFlow<String?>(null)
    val lastUri: StateFlow<String?> = _lastUri

    private val thumbnailCache = object : LruCache<Int, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: ByteArray): Int = value.size
    }

    private val thumbMutex = Mutex()
    private val downloadMutex = Mutex()

    private var thumbLargeUnsupported = false
    private var allHandles: List<Int> = emptyList()
    private var loadedPages = 0
    private var connectJob: Job? = null
    private var keepAliveJob: Job? = null
    private var downloadJob: Job? = null
    private var userDisconnected = false
    private var currentHost: String = ""

    init {
        client.onEvent = { event -> handleEvent(event) }
        client.onDisconnected = { error -> handleLinkLost(error) }
    }

    // ------------------------------------------------------------- connection

    /**
     * Joins the camera AP (when [ssid] is given), locates the PTP/IP responder and
     * opens a session. Safe to call again to retry.
     */
    fun connect(ssid: String? = null, passphrase: String? = null, host: String? = null) {
        connectJob?.cancel()
        userDisconnected = false
        connectJob = scope.launch {
            try {
                _state.value = ConnState.Connecting("正在准备连接…")
                client.close()
                wifi.release()

                var network: Network? = null
                val targetSsid = ssid?.takeIf { it.isNotBlank() } ?: settings.lastSsid
                val targetPass = passphrase ?: settings.lastPassphrase

                if (targetSsid.isNotBlank() && !wifi.looksLikeCamera(wifi.currentSsid())) {
                    _state.value = ConnState.Connecting("正在加入相机 Wi-Fi…")
                    network = wifi.requestNetwork(targetSsid, targetPass)
                } else if (wifi.looksLikeCamera(wifi.currentSsid())) {
                    network = wifi.network
                }

                if (network != null) client.socketFactory = network.socketFactory

                _state.value = ConnState.Connecting("正在查找相机…")
                val candidates = candidateHosts(network)
                val resolved = host?.takeIf { it.isNotBlank() }
                    ?: candidates.firstOrNull { CameraDiscovery.probe(it, Ptp.PORT, network, 1200) }
                    ?: CameraDiscovery.find(Ptp.PORT, network, candidates)
                    ?: CameraWifi.DEFAULT_CAMERA_HOST

                _state.value = ConnState.Connecting("正在建立 PTP/IP 会话…")
                Diagnostics.log("连接 $resolved:${Ptp.PORT}")
                client.connect(resolved)
                _eventsSupported.value = client.eventsAvailable

                _state.value = ConnState.Connecting("正在读取相机信息…")
                val info = client.deviceInfo()
                Diagnostics.log("相机 ${info.manufacturer} ${info.model} fw=${info.deviceVersion} 事件通道=${client.eventsAvailable}")
                _queueSupported.value = true
                _queue.value = emptyList()
                val profile = CameraProfile.forModel(info.model)
                val partial = profile.supportsPartialObject && info.supports(Ptp.OP_GET_PARTIAL_OBJECT)

                _profile.value = profile.copy(supportsPartialObject = partial)
                currentHost = resolved
                thumbLargeUnsupported = !profile.supportsLargeThumb
                loadedPages = 0
                allHandles = emptyList()
                _files.value = emptyList()
                thumbnailCache.evictAll()

                _identity.value = CameraIdentity(
                    model = info.model.ifBlank { "Nikon 相机" },
                    manufacturer = info.manufacturer,
                    serial = info.serialNumber,
                    firmware = info.deviceVersion,
                    battery = runCatching { client.batteryLevel() }.getOrNull(),
                    storageFree = null,
                    storageTotal = null,
                )

                if (targetSsid.isNotBlank()) {
                    settings.lastSsid = targetSsid
                    settings.lastPassphrase = targetPass
                }
                settings.lastHost = resolved

                _state.value = ConnState.Connected(info.model.ifBlank { "Nikon 相机" })
                ConnectionService.start(app, "已连接 ${info.model.ifBlank { "Nikon 相机" }} · 可传输")
                startKeepAlive()
                refreshLibrary()
                if (settings.autoPull.value) syncAutoPullBaseline()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                _state.value = ConnState.Error(humanError(e))
                Diagnostics.log("连接失败：${humanError(e)}")
                ConnectionService.stop(app)
                client.close()
            }
        }
    }

    fun disconnect() {        userDisconnected = true
        connectJob?.cancel()
        keepAliveJob?.cancel()
        ConnectionService.stop(app)
        scope.launch {
            runCatching { client.close() }
            wifi.release()
            _state.value = ConnState.Idle
            _identity.value = null
            _files.value = emptyList()
            allHandles = emptyList()
            thumbnailCache.evictAll()
        }
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        if (!settings.keepAlive.value) return
        val intervalMs = settings.keepAliveSeconds.value * 1000L
        keepAliveJob = scope.launch {
            while (true) {
                delay(intervalMs)
                if (_state.value !is ConnState.Connected) break
                val failure = runCatching { client.keepAlive() }.exceptionOrNull()
                if (failure != null) {
                    handleLinkLost(failure)
                    break
                }
            }
        }
    }

    /** Re-arms the heartbeat after the interval preference changed. */
    fun restartKeepAlive() {
        if (_state.value is ConnState.Connected) startKeepAlive()
    }

    private fun handleLinkLost(error: Throwable) {
        if (userDisconnected) {
            _state.value = ConnState.Idle
            return
        }
        if (_state.value is ConnState.Reconnecting) return
        scope.launch { reconnectLoop(error) }
    }

    private suspend fun reconnectLoop(cause: Throwable) {
        val backoff = listOf(1, 2, 4, 8, 16, 30)
        for (attempt in 1..30) {
            if (userDisconnected) return
            val wait = backoff[(attempt - 1).coerceAtMost(backoff.lastIndex)]
            _state.value = ConnState.Reconnecting(attempt, wait)
            delay(wait * 1000L)
            if (userDisconnected) return
            _state.value = ConnState.Connecting("正在重新连接（第 $attempt 次）…")
            try {
                client.close()
                client.connect(currentHost.ifBlank { CameraWifi.DEFAULT_CAMERA_HOST })
                val info = client.deviceInfo()
                _state.value = ConnState.Connected(info.model.ifBlank { "Nikon 相机" })
                ConnectionService.update(app, "已重新连接 ${info.model.ifBlank { "Nikon 相机" }}")
                startKeepAlive()
                refreshLibrary()
                return
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // keep retrying
            }
        }
        _state.value = ConnState.Error("连接已断开：${humanError(cause)}")
        ConnectionService.stop(app)
    }

    // ---------------------------------------------------------------- library

    fun refreshLibrary() {
        scope.launch {
            if (_state.value !is ConnState.Connected) return@launch
            _libraryBusy.value = true
            try {
                val ids = client.storageIds()
                val handles = ArrayList<Int>()
                for (id in ids) {
                    runCatching { client.objectHandles(id) }.onSuccess { handles += it.toList() }
                }
                allHandles = handles.distinct().sortedDescending()
                loadedPages = 0
                _files.value = emptyList()
                reconcileBaseline()
                loadPage()
            } catch (e: Exception) {
                // Keep the old list; surface nothing louder than a retry affordance.
            } finally {
                _libraryBusy.value = false
            }
        }
    }

    fun loadMore() {
        scope.launch {
            if (_libraryBusy.value) return@launch
            _libraryBusy.value = true
            try {
                loadPage()
            } finally {
                _libraryBusy.value = false
            }
        }
    }

    private suspend fun loadPage() {
        val pageSize = 120
        val start = loadedPages * pageSize
        if (start >= allHandles.size) {
            _hasMore.value = false
            return
        }
        val slice = allHandles.subList(start, minOf(start + pageSize, allHandles.size))
        val loaded = ArrayList<CameraFile>(slice.size)
        for (handle in slice) {
            val info = runCatching { client.objectInfo(handle) }.getOrNull() ?: continue
            if (info.isFolder) continue
            val file = CameraFile.from(info, handle)
            if (file.kind == com.achooluv.nikonlink.camera.FileKind.OTHER) continue
            loaded += file
        }
        loadedPages++
        _files.value = _files.value + loaded
        _hasMore.value = loadedPages * pageSize < allHandles.size
    }

    // ------------------------------------------------------ camera-marked queue

    private val _queue = MutableStateFlow<List<CameraFile>>(emptyList())
    val transferQueue: StateFlow<List<CameraFile>> = _queue

    private val _queueBusy = MutableStateFlow(false)
    val queueBusy: StateFlow<Boolean> = _queueBusy

    private val _queueSupported = MutableStateFlow(true)
    val queueSupported: StateFlow<Boolean> = _queueSupported

    /**
     * Reads the photos marked on the camera body for "send to smart device"
     * (Nikon vendor ops 0x9407 lock / 0x9408 read). Bodies without the vendor
     * extension are simply reported as unsupported.
     */
    fun refreshTransferQueue() {
        scope.launch {
            if (_state.value !is ConnState.Connected) return@launch
            _queueBusy.value = true
            try {
                client.setTransferListLock(true)
                try {
                    val handles = client.transferList()
                    val files = ArrayList<CameraFile>(handles.size)
                    for (handle in handles) {
                        val info = runCatching { client.objectInfo(handle) }.getOrNull() ?: continue
                        if (info.isFolder) continue
                        files += CameraFile.from(info, handle)
                    }
                    _queue.value = files
                    Diagnostics.log("相机标记 ${files.size} 个")
                } finally {
                    runCatching { client.setTransferListLock(false) }
                }
            } catch (e: com.achooluv.nikonlink.ptp.PtpException) {
                if (e.code == Ptp.RESP_OPERATION_NOT_SUPPORTED) {
                    _queueSupported.value = false
                    _queue.value = emptyList()
                    Diagnostics.log("相机不支持发送队列（0x9407/0x9408）")
                } else {
                    Diagnostics.log("读取发送队列失败：${e.message}")
                }
            } catch (e: Exception) {
                Diagnostics.log("读取发送队列失败：${e.message}")
            } finally {
                _queueBusy.value = false
            }
        }
    }

    /** Loads a JPEG preview for [handle]; returns null when the body has none. */    suspend fun thumbnail(handle: Int): ByteArray? {
        thumbnailCache.get(handle)?.let { return it }
        thumbMutex.withLock {
            thumbnailCache.get(handle)?.let { return it }
            if (_state.value !is ConnState.Connected) return null
            val bytes = runCatching {
                if (!thumbLargeUnsupported) {
                    try {
                        client.largeThumbnail(handle)
                    } catch (e: com.achooluv.nikonlink.ptp.PtpException) {
                        thumbLargeUnsupported = true
                        client.thumbnail(handle)
                    }
                } else {
                    client.thumbnail(handle)
                }
            }.getOrElse { null }
            if (bytes != null && bytes.isNotEmpty()) thumbnailCache.put(handle, bytes)
            return bytes
        }
    }

    // -------------------------------------------------------------- transfers

    fun download(files: List<CameraFile>, force: Boolean = false) {
        if (files.isEmpty()) return
        downloadJob?.cancel()
        _failedFiles.value = emptyList()
        downloadJob = scope.launch {
            downloadMutex.withLock {
                val chunk = settings.chunkSize.value
                val partial = _profile.value?.supportsPartialObject ?: true
                val result = downloader.download(client, files, chunk, force, partial) { p ->
                    _progress.value = p
                }
                _failedFiles.value = result.failed
                _lastUri.value = result.lastUri
                _importsVersion.value++
            }
        }
    }

    /** Re-runs only the files that failed last time. */
    fun retryFailed() {
        val failed = _failedFiles.value
        if (failed.isNotEmpty()) download(failed)
    }

    /** Clears the "last batch" summary bar. */
    fun clearTransferResult() {
        _failedFiles.value = emptyList()
        _lastUri.value = null
        _progress.value = _progress.value.copy(message = null, doneCount = 0, failedCount = 0)
    }

    fun cancelDownloads() {
        val job = downloadJob
        if (job != null && job.isActive) {
            job.cancel()
            _progress.value = _progress.value.copy(active = false, message = "已取消，已下载部分保留为断点")
        } else {
            _progress.value = _progress.value.copy(message = "没有正在进行的传输")
        }
    }

    // ----------------------------------------------------------------- events

    private fun handleEvent(event: PtpEvent) {
        if (event.code != Ptp.EVENT_OBJECT_ADDED) return
        val handle = event.params.firstOrNull() ?: return
        if (!settings.autoPull.value) return
        if (_state.value !is ConnState.Connected) return
        // Only objects shot after auto-pull was armed; never the existing card.
        val baseline = settings.autoPullBaseline
        if (baseline >= 0 && handle <= baseline) return
        scope.launch {
            // Give the camera a moment to finish writing the file.
            delay(600)
            repeat(3) { attempt ->
                val info = runCatching { client.objectInfo(handle) }.getOrNull()
                if (info != null && !info.isFolder && info.compressedSize > 0) {
                    val file = CameraFile.from(info, handle)
                    downloadMutex.withLock {
                        downloader.download(
                            client,
                            listOf(file),
                            settings.chunkSize.value,
                            force = false,
                            supportsPartial = _profile.value?.supportsPartialObject ?: true,
                        ) { p -> _progress.value = p }
                    }
                    return@launch
                }
                delay(700L * (attempt + 1))
            }
        }
    }

    /**
     * Arms auto-pull at the current top of the card. Objects already on the card
     * are marked as seen; only shots taken afterwards are pulled automatically.
     */
    fun setAutoPull(enabled: Boolean) {
        if (enabled) {
            val max = allHandles.maxOrNull() ?: -1
            if (max >= 0) settings.autoPullBaseline = max
            for (file in _files.value) settings.markImported(file.handle, file.size)
            _importsVersion.value++
        }
        settings.setAutoPull(enabled)
    }

    /**
     * Keeps the baseline sane when the card changed underneath us (format or a
     * different card): stale high water marks would otherwise block every pull.
     */
    private fun reconcileBaseline() {
        val max = allHandles.maxOrNull() ?: return
        val baseline = settings.autoPullBaseline
        if (baseline < 0 || max < baseline) settings.autoPullBaseline = max
    }

    private fun syncAutoPullBaseline() = setAutoPull(true)

    private fun candidateHosts(network: Network?): List<String> {
        val out = LinkedHashSet<String>()
        settings.lastHost.takeIf { it.isNotBlank() }?.let(out::add)
        runCatching {
            val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
            val active = network ?: cm?.activeNetwork
            cm?.getLinkProperties(active)?.routes?.forEach { route ->
                route.gateway?.hostAddress?.let { out += it }
            }
        }
        out += CameraDiscovery.defaults()
        return out.toList()
    }

    fun humanError(e: Throwable): String = when (e) {
        is com.achooluv.nikonlink.ptp.PtpException -> "相机返回错误：${e.message}"
        is com.achooluv.nikonlink.ptp.PtpLinkException -> "PTP/IP 握手失败：${e.message}"
        is java.net.SocketTimeoutException -> timeoutHint()
        is java.net.ConnectException -> "连不上相机端口：网络不可达，确认手机 Wi-Fi 已连到 NIKON_ 网络"
        is java.net.UnknownHostException -> "地址无法解析，请检查手动填写的相机 IP"
        is IOException -> {
            val raw = e.message.orEmpty()
            if (raw.contains("failed to connect", true) || raw.contains("timeout", true)) {
                timeoutHint()
            } else {
                raw.ifBlank { "网络错误" }
            }
        }
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun timeoutHint(): String =
        "连接超时：没在 ${currentHost.ifBlank { CameraWifi.DEFAULT_CAMERA_HOST }}:${Ptp.PORT} 找到相机。" +
            "确认手机 Wi-Fi 已连上 NIKON_ 网络；若相机不在 192.168.1.x 网段，用「换 IP 重试」填写实际地址。"

    fun isImported(file: CameraFile): Boolean = settings.isImported(file.handle, file.size)

    companion object {
        @Volatile
        private var instance: NikonLinkRepository? = null

        fun get(context: android.content.Context): NikonLinkRepository {
            return instance ?: synchronized(this) {
                instance ?: NikonLinkRepository(context.applicationContext as Application).also {
                    instance = it
                }
            }
        }
    }
}
