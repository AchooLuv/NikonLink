package com.achooluv.nikonlink

import android.app.Application
import android.net.Network
import android.util.LruCache
import com.achooluv.nikonlink.camera.CameraFile
import com.achooluv.nikonlink.camera.CameraIdentity
import com.achooluv.nikonlink.camera.CameraProfile
import com.achooluv.nikonlink.data.ConnectMode
import com.achooluv.nikonlink.data.SettingsStore
import com.achooluv.nikonlink.net.CameraWifi
import com.achooluv.nikonlink.net.LanScanner
import com.achooluv.nikonlink.ptp.Ptp
import com.achooluv.nikonlink.ptp.PtpEvent
import com.achooluv.nikonlink.ptp.PtpIpClient
import com.achooluv.nikonlink.service.ConnectionService
import com.achooluv.nikonlink.transfer.CameraDiscovery
import com.achooluv.nikonlink.transfer.Downloader
import com.achooluv.nikonlink.transfer.expandRawJpegPairs
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
import kotlinx.coroutines.withContext
import java.io.IOException

/** Connection lifecycle as the UI sees it. */
sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val step: String) : ConnState
    data class Connected(val model: String) : ConnState
    data class Reconnecting(val attempt: Int, val waitSeconds: Int) : ConnState
    data class Error(val message: String) : ConnState
}

/** Metadata pulled from the newest photo on the card. */
data class CameraExtras(
    val lens: String? = null,
    val exposure: String? = null,
    val shutterCount: Int? = null,
)

/** Shown when Wi-Fi station mode cannot find a camera on the current LAN. */
private const val STA_NOT_FOUND =
    "没在局域网里找到相机：确认相机和手机连的是同一个路由器 Wi-Fi，且路由器没有开启「AP 隔离」；" +
        "也可以点「手动 IP」直接填相机地址。"

/** Exponential backoff (seconds) used while re-establishing a dropped link. */
internal fun reconnectDelaySeconds(attempt: Int): Int {
    val schedule = intArrayOf(1, 2, 4, 8, 16, 30)
    return schedule[(attempt - 1).coerceIn(0, schedule.lastIndex)]
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

    /** Lens / exposure / shutter count read from the newest photo's EXIF header. */
    private val _extras = MutableStateFlow<CameraExtras?>(null)
    val extras: StateFlow<CameraExtras?> = _extras

    private val thumbnailCache = object : LruCache<Int, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: ByteArray): Int = value.size
    }

    private val thumbMutex = Mutex()
    private val downloadMutex = Mutex()

    private var thumbLargeUnsupported = false
    private var extrasLoaded = false
    private var allHandles: List<Int> = emptyList()
    private val kindByHandle = HashMap<Int, com.achooluv.nikonlink.camera.FileKind>()
    private var loadedPages = 0
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var keepAliveJob: Job? = null
    private var downloadJob: Job? = null
    private var userDisconnected = false
    private var currentHost: String = ""
    private var lastScanSubnets: List<String> = emptyList()

    /** Set while we deliberately close a link, so the disconnect hook stays quiet. */
    @Volatile
    private var suppressDisconnect = false

    init {
        client.onEvent = { event -> handleEvent(event) }
        client.onDisconnected = { error -> handleLinkLost(error) }
    }

    // ------------------------------------------------------------- connection

    /**
     * Joins the camera AP (when [ssid] is given), locates the PTP/IP responder and
     * opens a session. Safe to call again to retry.
     */
    fun connect(
        ssid: String? = null,
        passphrase: String? = null,
        host: String? = null,
        mode: ConnectMode = settings.mode.value,
    ) {
        connectJob?.cancel()
        reconnectJob?.cancel()
        reconnectJob = null
        userDisconnected = false
        settings.setMode(mode)
        connectJob = scope.launch {
            try {
                _state.value = ConnState.Connecting("正在准备连接…")
                // Closing a live link fires onDisconnected synchronously; swallow it
                // so it cannot spawn a competing reconnect loop.
                suppressDisconnect = true
                runCatching { client.close() }
                suppressDisconnect = false
                // A socket factory captured from an old Network keeps dialing a dead
                // route (the "must clear app data to reconnect" bug) — always reset.
                client.socketFactory = javax.net.SocketFactory.getDefault()
                wifi.release()

                var network: Network? = null
                if (mode == ConnectMode.AP) {
                    val targetSsid = ssid?.takeIf { it.isNotBlank() } ?: settings.lastSsid
                    val targetPass = passphrase ?: settings.lastPassphrase

                    if (targetSsid.isNotBlank() && !wifi.looksLikeCamera(wifi.currentSsid())) {
                        _state.value = ConnState.Connecting("正在加入相机 Wi-Fi…")
                        network = wifi.requestNetwork(targetSsid, targetPass)
                    } else if (wifi.looksLikeCamera(wifi.currentSsid())) {
                        network = wifi.network
                    }
                    if (network != null) client.socketFactory = network.socketFactory
                } else {
                    // STA: the phone stays on its normal Wi-Fi; the camera is a peer
                    // on that same LAN, so nothing is joined here.
                    Diagnostics.log("STA 模式：使用当前局域网（${wifi.currentSsid() ?: "未知网络"}）")
                }

                _state.value = ConnState.Connecting(
                    if (mode == ConnectMode.STA) "正在扫描局域网…" else "正在查找相机…",
                )
                val candidates = candidateHosts(network)
                val resolved = if (mode == ConnectMode.STA) {
                    host?.takeIf { it.isNotBlank() }
                        ?: rememberedHost()
                        ?: scanLanForCamera()
                        ?: throw IOException(STA_NOT_FOUND)
                } else {
                    host?.takeIf { it.isNotBlank() }
                        ?: candidates.firstOrNull { CameraDiscovery.probe(it, Ptp.PORT, network, 1200) }
                        ?: CameraDiscovery.find(Ptp.PORT, network, candidates)
                        ?: CameraWifi.DEFAULT_CAMERA_HOST
                }

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
                kindByHandle.clear()
                thumbnailCache.evictAll()
                extrasLoaded = false
                _extras.value = null

                val storage = readStorage()
                _identity.value = CameraIdentity(
                    model = info.model.ifBlank { "Nikon 相机" },
                    manufacturer = info.manufacturer,
                    serial = info.serialNumber,
                    firmware = info.deviceVersion,
                    battery = runCatching { client.batteryLevel() }.getOrNull(),
                    storageFree = storage.first,
                    storageTotal = storage.second,
                )
                Diagnostics.log("存储可用 ${storage.first} / ${storage.second}")

                if (mode == ConnectMode.AP) {
                    val savedSsid = ssid?.takeIf { it.isNotBlank() } ?: settings.lastSsid
                    if (savedSsid.isNotBlank()) {
                        settings.lastSsid = savedSsid
                        settings.lastPassphrase = passphrase ?: settings.lastPassphrase
                    }
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

    /** Wipes every local preference and record, then drops the link. */
    fun resetAll() {
        disconnect()
        scope.launch {
            delay(250)
            settings.clearAll()
            runCatching {
                val base = app.getExternalFilesDir(null) ?: app.filesDir
                java.io.File(base, "incoming").deleteRecursively()
            }
            _failedFiles.value = emptyList()
            _lastUri.value = null
            _progress.value = Downloader.Progress()
            _queue.value = emptyList()
            Diagnostics.log("已重置本地连接与下载记录")
        }
    }

    /** Last camera address, when it still answers — makes repeat STA connects instant. */
    private fun rememberedHost(): String? =
        settings.lastHost.takeIf {
            it.isNotBlank() && CameraDiscovery.probe(it, Ptp.PORT, null, 900)
        }

    /** Rescans the current LAN for a camera (Wi-Fi station mode). */
    private suspend fun scanLanForCamera(): String? {
        val subnets = LanScanner.localSubnets(app)
        lastScanSubnets = subnets
        Diagnostics.log("STA 扫描网段：${subnets.ifEmpty { listOf("未取到本机网段") }.joinToString()}")
        val found = LanScanner.scan(
            context = app,
            port = Ptp.PORT,
            extraHost = settings.lastHost,
        ) { done ->
            _state.value = ConnState.Connecting("正在扫描局域网…（已检查 $done 个地址）")
        }
        Diagnostics.log("局域网扫描结果：${found.ifEmpty { listOf("未发现相机") }.joinToString()}")
        return found.firstOrNull()
    }

    /** Aggregates free/total bytes across every card the body exposes. */
    private fun readStorage(): Pair<Long?, Long?> {
        return runCatching {
            var free = 0L
            var total = 0L
            var seen = false
            for (id in client.storageIds()) {
                val info = runCatching { client.storageInfo(id) }.getOrNull() ?: continue
                if (info.maxCapacity <= 0) continue
                seen = true
                free += info.freeSpace
                total += info.maxCapacity
            }
            if (!seen) null to null else free to total
        }.getOrDefault(null to null)
    }

    fun disconnect() {
        userDisconnected = true
        connectJob?.cancel()
        reconnectJob?.cancel()
        reconnectJob = null
        keepAliveJob?.cancel()
        ConnectionService.stop(app)
        suppressDisconnect = true
        runCatching { client.close() }
        suppressDisconnect = false
        client.socketFactory = javax.net.SocketFactory.getDefault()
        scope.launch {
            wifi.release()
            _state.value = ConnState.Idle
            _identity.value = null
            _files.value = emptyList()
            allHandles = emptyList()
            kindByHandle.clear()
            thumbnailCache.evictAll()
            extrasLoaded = false
            _extras.value = null
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
        if (suppressDisconnect) return
        if (connectJob?.isActive == true) return
        if (userDisconnected) {
            _state.value = ConnState.Idle
            return
        }
        if (_state.value is ConnState.Reconnecting) return
        reconnectJob = scope.launch { reconnectLoop(error) }
    }

    /**
     * User pressed "stop": abort an in-flight connect attempt or the auto-reconnect
     * loop, and drop the link. The next manual connect starts from a clean slate.
     */
    fun cancelConnect() {
        userDisconnected = true
        connectJob?.cancel()
        connectJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        keepAliveJob?.cancel()
        keepAliveJob = null
        suppressDisconnect = true
        runCatching { client.close() }
        suppressDisconnect = false
        client.socketFactory = javax.net.SocketFactory.getDefault()
        scope.launch {
            runCatching { wifi.release() }
            ConnectionService.stop(app)
            _state.value = ConnState.Idle
            Diagnostics.log("用户取消了连接/重连")
        }
    }

    private suspend fun reconnectLoop(cause: Throwable) {
        for (attempt in 1..30) {
            if (userDisconnected) return
            val wait = reconnectDelaySeconds(attempt)
            _state.value = ConnState.Reconnecting(attempt, wait)
            delay(wait * 1000L)
            if (userDisconnected) return
            _state.value = ConnState.Connecting("正在重新连接（第 $attempt 次）…")
            try {
                client.close()

                // The phone may have roamed back to cellular, which would leave the
                // camera AP unreachable forever. Re-request the network, then probe
                // before spending 12 s on a doomed TCP connect.
                val mode = settings.mode.value
                val network = if (mode == ConnectMode.AP) ensureCameraNetwork() else null
                client.socketFactory = network?.socketFactory ?: javax.net.SocketFactory.getDefault()

                val host = if (mode == ConnectMode.STA) {
                    settings.lastHost.takeIf {
                        it.isNotBlank() && CameraDiscovery.probe(it, Ptp.PORT, null, 800)
                    } ?: scanLanForCamera() ?: currentHost.ifBlank { CameraWifi.DEFAULT_CAMERA_HOST }
                } else {
                    resolveHost(network) ?: currentHost.ifBlank { CameraWifi.DEFAULT_CAMERA_HOST }
                }
                if (!CameraDiscovery.probe(host, Ptp.PORT, network, 1500)) {
                    Diagnostics.log("重连第 $attempt 次：$host 不可达")
                    continue
                }
                client.connect(host)
                currentHost = host

                val info = client.deviceInfo()
                _state.value = ConnState.Connected(info.model.ifBlank { "Nikon 相机" })
                ConnectionService.update(app, "已重新连接 ${info.model.ifBlank { "Nikon 相机" }}")
                Diagnostics.log("重连成功：$host")
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

    /**
     * Re-joins the saved camera access point when the Wi-Fi association was lost,
     * so reconnects work even after the phone fell back to mobile data.
     */
    private suspend fun ensureCameraNetwork(): Network? {
        wifi.network?.let { return it }
        val ssid = settings.lastSsid
        if (ssid.isBlank()) return null
        if (wifi.looksLikeCamera(wifi.currentSsid())) {
            return runCatching { wifi.requestNetwork(ssid, settings.lastPassphrase, 25_000) }.getOrNull()
        }
        Diagnostics.log("重新申请相机 Wi-Fi：$ssid")
        return runCatching { wifi.requestNetwork(ssid, settings.lastPassphrase, 25_000) }
            .onFailure { Diagnostics.log("重新申请 Wi-Fi 失败：${it.message}") }
            .getOrNull()
    }

    /** Finds the camera again after a rejoin: saved host first, then the usual addresses. */
    private suspend fun resolveHost(network: Network?): String? {
        val candidates = candidateHosts(network)
        candidates.firstOrNull { CameraDiscovery.probe(it, Ptp.PORT, network, 900) }?.let { return it }
        return CameraDiscovery.find(Ptp.PORT, network, candidates, timeoutMs = 900)
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
                kindByHandle.clear()
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
            kindByHandle[file.handle] = file.kind
            loaded += file
        }
        loadedPages++
        _files.value = _files.value + loaded
        _hasMore.value = loadedPages * pageSize < allHandles.size

        if (!extrasLoaded && loaded.isNotEmpty()) {
            extrasLoaded = true
            runCatching { loadExtras() }
        }
    }

    /**
     * Reads the first 256 KiB of the newest photo and decodes lens / exposure /
     * shutter count from its EXIF, without downloading the whole file.
     */
    private suspend fun loadExtras() {
        val candidate = _files.value.firstOrNull { it.kind == com.achooluv.nikonlink.camera.FileKind.JPEG }
            ?: _files.value.firstOrNull { it.kind == com.achooluv.nikonlink.camera.FileKind.RAW }
            ?: return
        val head = runCatching { client.readHead(candidate.handle, 256 * 1024) }.getOrNull() ?: return
        val exif = com.achooluv.nikonlink.exif.ExifReader.read(head)
        if (!exif.hasAnything) return
        _extras.value = CameraExtras(
            lens = exif.lensModel,
            exposure = listOfNotNull(exif.aperture, exif.focalLength).joinToString(" · ").ifBlank { null },
            shutterCount = exif.shutterCount,
        )
        Diagnostics.log("EXIF 镜头=${exif.lensModel} 快门=${exif.shutterCount}")
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
        // Honour the "RAW 使用相机缩略图" switch: with it off, RAW cells stay as placeholders.
        if (!settings.showRawThumbnails.value &&
            kindByHandle[handle] == com.achooluv.nikonlink.camera.FileKind.RAW
        ) {
            return null
        }
        thumbnailCache.get(handle)?.let { return it }
        // PTP I/O is blocking; never run it on the caller's (main) dispatcher.
        return withContext(Dispatchers.IO) {
            thumbMutex.withLock {
                thumbnailCache.get(handle)?.let { return@withLock it }
                if (_state.value !is ConnState.Connected) return@withLock null
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
                bytes
            }
        }
    }

    // -------------------------------------------------------------- transfers

    fun download(files: List<CameraFile>, force: Boolean = false) {
        if (files.isEmpty()) return
        downloadJob?.cancel()
        _failedFiles.value = emptyList()
        val expanded = if (settings.pairJpeg.value) expandRawJpegPairs(files) else files
        downloadJob = scope.launch {
            downloadMutex.withLock {
                val chunk = settings.chunkSize.value
                val partial = _profile.value?.supportsPartialObject ?: true
                val result = downloader.download(client, expanded, chunk, force, partial) { p ->
                    _progress.value = p
                }
                _failedFiles.value = result.failed
                _lastUri.value = result.lastUri
                _importsVersion.value++
                refreshIdentityStorage()
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

    /** Refreshes free/total space after a batch moved files off the card. */
    private fun refreshIdentityStorage() {
        runCatching {
            val (free, total) = readStorage()
            _identity.value = _identity.value?.copy(storageFree = free, storageTotal = total)
        }
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
