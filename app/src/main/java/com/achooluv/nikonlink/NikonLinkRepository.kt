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
import com.achooluv.nikonlink.ptp.parseNikonEvents
import com.achooluv.nikonlink.service.ConnectionService
import com.achooluv.nikonlink.transfer.CameraDiscovery
import com.achooluv.nikonlink.transfer.Downloader
import com.achooluv.nikonlink.transfer.expandRawJpegPairs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
    // Stable identity: the camera remembers the pairing by this name, so it must not
    // change between versions (the version used to be part of it).
    private val client = PtpIpClient("NikonLink (Android)")

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
    private val pairingMutex = Mutex()

    /** Progress of the smart-device pairing (SnapBridge-compatible registration). */
    enum class PairingState { Idle, Running, Done, Failed }

    private val _pairing = MutableStateFlow(PairingState.Idle)
    val pairing: StateFlow<PairingState> = _pairing

    private val _pairingNote = MutableStateFlow<String?>(null)
    val pairingNote: StateFlow<String?> = _pairingNote

    /**
     * Registers this phone on the camera the way SnapBridge does, so the body keeps
     * the network profile and stops showing its connection wizard. The camera must
     * be sitting on the pairing screen; the user confirms on the body.
     */
    fun pairCamera() {
        if (_state.value !is ConnState.Connected) {
            _pairing.value = PairingState.Failed
            _pairingNote.value = "先在连接页连上相机，再执行配对"
            return
        }
        scope.launch {
            pairingMutex.withLock {
                _pairing.value = PairingState.Running
                _pairingNote.value = "正在配对，若相机弹出确认请按 OK…"
                try {
                    val code = client.pairAsSmartDevice()
                    _pairing.value = PairingState.Done
                    _pairingNote.value = if (code.isNotBlank()) {
                        "配对完成（配对码 $code），相机应已显示已连接"
                    } else {
                        "配对完成，相机应已显示已连接"
                    }
                    Diagnostics.log("配对完成，配对码=$code")
                } catch (e: Exception) {
                    _pairing.value = PairingState.Failed
                    _pairingNote.value = "配对失败：${humanError(e)}"
                    Diagnostics.log("配对失败：${humanError(e)}")
                }
            }
        }
    }

    fun clearPairingNote() {
        _pairing.value = PairingState.Idle
        _pairingNote.value = null
    }

    /** State of the remote shutter button on the capture screen. */
    enum class CaptureState { Idle, Busy, Done, Failed }

    private val _captureState = MutableStateFlow(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState

    private val _captureNote = MutableStateFlow<String?>(null)
    val captureNote: StateFlow<String?> = _captureNote

    /** Completed by the 0xC101 event when a remote shot lands in SDRAM. */
    @Volatile
    private var sdramLatch: CompletableDeferred<Int?>? = null

    /** True while a release is in flight; heartbeats and retries must not interfere. */
    @Volatile
    private var captureInFlight = false

    /**
     * Remote shutter (op 0x9207). The body takes about a second to expose, so the
     * call runs on the IO scope and the UI disables the button while [CaptureState.Busy].
     */
    fun capture() {
        if (_state.value !is ConnState.Connected) {
            _captureState.value = CaptureState.Failed
            _captureNote.value = "相机未连接"
            return
        }
        if (_captureState.value == CaptureState.Busy) return
        scope.launch { captureOnce() }
    }

    /** One release, awaited. Shared by the shutter button and interval shooting. */
    private suspend fun captureOnce(useSelfTimer: Boolean = true) {
            _captureState.value = CaptureState.Busy
            val waitSeconds = if (useSelfTimer) settings.selfTimerSeconds.value else 0
            if (waitSeconds > 0) {
                for (left in waitSeconds downTo 1) {
                    _captureNote.value = "${left} 秒后拍摄…"
                    delay(1000)
                }
            }
            _captureNote.value = "正在拍摄…"
            try {
                captureInFlight = true
                liveViewPaused = true
                var fresh: Int? = null
                var triggered = false
                val attempts = listOf<Pair<String, () -> Unit>>(
                    "0x9207 存卡" to { client.capture(release = Ptp.NIKON_CAPTURE_NO_AF, destination = Ptp.NIKON_RELEASE_TO_CARD) },
                    "0x9207 存SDRAM" to { client.capture(release = Ptp.NIKON_CAPTURE_NO_AF, destination = Ptp.NIKON_RELEASE_TO_SDRAM) },
                    "0x90C0 拍SDRAM" to { client.captureSdram() },
                )
                for ((label, send) in attempts) {
                    val (sent, handle) = shootAndWait(emptySet(), label, send)
                    if (sent) {
                        triggered = true
                        fresh = handle
                        break
                    }
                }
                if (fresh != null) {
                    // Show the small thumbnail immediately (fast), then swap in the
                    // large one in the background so the page is not waiting on 0x90C4.
                    _captureState.value = CaptureState.Done
                    _captureNote.value = "已拍摄"
                    _lastShot.value = runCatching { client.thumbnail(fresh) }.getOrNull()
                    Diagnostics.log("遥控拍摄成功，handle=$fresh")
                    runCatching { refreshRemoteInfo() }
                    val handle = fresh
                    scope.launch {
                        repeat(3) {
                            val big = runCatching { client.largeThumbnail(handle) }.getOrNull()
                            if (big != null) {
                                _lastShot.value = big
                                return@launch
                            }
                            delay(300)
                        }
                    }
                } else if (triggered) {
                    // The release was accepted; a slow exposure (long shutter +
                    // noise reduction) can take many seconds before the event.
                    _captureState.value = CaptureState.Done
                    _captureNote.value = "已触发拍摄，长曝光/降噪可能要等几秒"
                    Diagnostics.log("遥控拍摄：已触发但未在等待时间内收到照片")
                } else {
                    _captureState.value = CaptureState.Failed
                    _captureNote.value = "相机没有接受拍摄指令，检查挡位（M/A/P/S）与存储卡"
                    Diagnostics.log("遥控拍摄：三种指令都被拒绝")
                }
            } catch (e: Exception) {
                _captureState.value = CaptureState.Failed
                _captureNote.value = "拍摄失败：${humanError(e)}"
                Diagnostics.log("遥控拍摄失败：${humanError(e)}")
            } finally {
                captureInFlight = false
                liveViewPaused = false
                // Never leave the button stuck on "拍摄中" if something cancelled us.
                if (_captureState.value == CaptureState.Busy) {
                    _captureState.value = CaptureState.Failed
                    _captureNote.value = "拍摄超时，请重试"
                }
            }
    }

    // ------------------------------------------------------- interval shooting

    data class IntervalState(val running: Boolean = false, val shot: Int = 0, val total: Int = 0)

    private val _interval = MutableStateFlow(IntervalState())
    val interval: StateFlow<IntervalState> = _interval
    private var intervalJob: Job? = null

    /** Fires [count] shots every [seconds] seconds; count <= 0 means until stopped. */
    fun startInterval(seconds: Int, count: Int) {
        if (intervalJob?.isActive == true) return
        intervalJob = scope.launch {
            val unlimited = count <= 0
            val limit = if (unlimited) Int.MAX_VALUE else count
            var shot = 0
            try {
                while (shot < limit) {
                    // Never overlap a manual release or keep firing after a drop.
                    while (_captureState.value == CaptureState.Busy) delay(200)
                    if (_state.value !is ConnState.Connected) break
                    shot++
                    _interval.value = IntervalState(true, shot, if (unlimited) 0 else limit)
                    captureOnce(useSelfTimer = false)
                    if (shot >= limit) break
                    delay(seconds.coerceAtLeast(1) * 1000L)
                }
            } finally {
                _interval.value = IntervalState(false, shot, if (unlimited) 0 else limit)
                if (shot >= limit) _captureNote.value = "间隔拍摄完成（$shot 张）"
                else _captureNote.value = "间隔拍摄已停止（$shot 张）"
                Diagnostics.log("间隔拍摄结束 shot=$shot limit=$limit")
            }
        }
    }

    fun stopInterval() {
        intervalJob?.cancel()
        intervalJob = null
    }

    fun resetCaptureState() {
        _captureState.value = CaptureState.Idle
        _captureNote.value = null
    }

    /** Remote-mode lifecycle for the capture screen. */
    enum class RemoteState { Idle, Starting, Ready, Failed }

    private val _remote = MutableStateFlow(RemoteState.Idle)
    val remote: StateFlow<RemoteState> = _remote

    /** Thumbnail of the last frame this app shot, shown as a capture preview. */
    private val _lastShot = MutableStateFlow<ByteArray?>(null)
    val lastShot: StateFlow<ByteArray?> = _lastShot

    /** Latest live-view JPEG while the remote page is open. */
    private val _preview = MutableStateFlow<ByteArray?>(null)
    val preview: StateFlow<ByteArray?> = _preview

    /** Live-view pipeline state, for the page's status line. */
    enum class LiveViewState { Idle, Starting, Running, Failed }
    private val _liveView = MutableStateFlow(LiveViewState.Idle)
    val liveView: StateFlow<LiveViewState> = _liveView

    private var liveViewJob: Job? = null
    private var paramJob: Job? = null

    /** A release holds the command channel; frames resume right after it. */
    @Volatile
    private var liveViewPaused = false

    /** Shooting parameters shown above the remote viewfinder. */
    data class RemoteInfo(
        val mode: String,
        val aperture: String,
        val iso: String,
        val shutter: String,
        val focal: String,
    )

    private val _remoteInfo = MutableStateFlow<RemoteInfo?>(null)
    val remoteInfo: StateFlow<RemoteInfo?> = _remoteInfo

    /**
     * Reads the shooting parameters once and updates both the overlay strings and
     * the control entries, so a periodic refresh costs five transactions total.
     */
    private suspend fun refreshRemoteInfo(quiet: Boolean = false) = withContext(Dispatchers.IO) {
        fun scalar(code: Int, size: Int): Int? {
            val bytes = runCatching { client.readProp(code) }.getOrNull() ?: return null
            if (bytes.size < size) return null
            var v = 0
            for (i in size - 1 downTo 0) v = (v shl 8) or (bytes[i].toInt() and 0xFF)
            return v
        }

        val modeRaw = scalar(0x500E, 2)
        val apertureRaw = scalar(0x5007, 2)
        val isoRaw = scalar(0x500F, 2)
        val shutterRaw = scalar(0x500D, 4)
        val focalBytes = runCatching { client.readProp(0x5008) }.getOrNull()

        val mode = when (modeRaw) {
            1 -> "M"
            2 -> "AUTO"
            3 -> "A"
            4 -> "S"
            5 -> "P"
            else -> "—"
        }
        val aperture = apertureRaw?.takeIf { it > 0 }?.let { "f/%.1f".format(it / 100.0) } ?: "—"
        val iso = isoRaw?.takeIf { it > 0 }?.toString() ?: "—"
        // 0x500D counts 0.1 ms units on these bodies (80 -> 8 ms -> 1/125).
        val shutterUs = shutterRaw?.takeIf { it > 0 }?.toLong()?.times(100)
        val shutter = shutterUs?.let {
            if (it >= 1_000_000) "%.1fs".format(it / 1_000_000.0) else "1/${(1_000_000.0 / it).toInt()}"
        } ?: "—"
        val focal = when {
            focalBytes == null || focalBytes.isEmpty() -> "—"
            focalBytes.size >= 4 -> {
                var v = 0L
                for (i in 3 downTo 0) v = (v shl 8) or (focalBytes[i].toLong() and 0xFF)
                if (v <= 0) "—" else if (v >= 1000) "%.0fmm".format(v / 100.0) else "${v}mm"
            }
            else -> {
                val v = (focalBytes[0].toInt() and 0xFF) or
                    (if (focalBytes.size > 1) (focalBytes[1].toInt() and 0xFF) shl 8 else 0)
                if (v <= 0) "—" else "${v}mm"
            }
        }

        _remoteInfo.value = RemoteInfo(mode, aperture, iso, shutter, focal)
        // Keep control entries in sync without reading the same property twice.
        _controls.value = _controls.value.map { control ->
            when (control.key) {
                "mode" -> modeRaw?.let { control.copy(current = it) } ?: control
                "aperture" -> apertureRaw?.let { control.copy(current = it) } ?: control
                "iso" -> isoRaw?.let { control.copy(current = it) } ?: control
                "shutter" -> shutterRaw?.let { control.copy(current = it) } ?: control
                else -> control
            }
        }
        if (!quiet) Diagnostics.log("参数 挡位=$mode 光圈=$aperture ISO=$iso 快门=$shutter 焦段=$focal")
    }



    fun beginRemote() {
        if (_state.value !is ConnState.Connected) {
            _remote.value = RemoteState.Failed
            return
        }
        scope.launch {
            _remote.value = RemoteState.Starting
            _liveView.value = LiveViewState.Starting
            try {
                runCatching { client.clearEvents() }
                runCatching { client.deleteAllSdram() }
                val prohibit = runCatching { client.liveViewProhibit() }.getOrNull()
                Diagnostics.log("LiveViewProhibit=0x${prohibit?.toString(16) ?: "?"}")

                // Z bodies only accept remote writes once application mode is on;
                // 0x9435 is the documented switch (mode 1 = host control).
                val appMode = runCatching { client.changeApplicationMode(1) }
                Diagnostics.log("0x9435(1) ApplicationMode -> ${appMode.exceptionOrNull()?.let { humanError(it) } ?: "OK"}")
                val mode = runCatching { client.changeCameraMode(1) }
                Diagnostics.log("0x90C2(1) ChangeCameraMode -> ${mode.exceptionOrNull()?.let { humanError(it) } ?: "OK"}")
                val selector = runCatching { client.writeProp(Ptp.PROP_LIVE_VIEW_SELECTOR, byteArrayOf(0)) }
                Diagnostics.log("0xD1A6=0 LiveViewSelector -> ${selector.exceptionOrNull()?.let { humanError(it) } ?: "OK"}")
                applyLiveViewQualityBlocking()

                val lv = runCatching { client.startLiveViewOnly() }
                Diagnostics.log("0x9201 StartLiveView -> ${lv.exceptionOrNull()?.let { humanError(it) } ?: "OK"}")

                // DeviceReady polling: the body answers DeviceBusy while the live
                // view pipeline spins up (gphoto calls this nikon_wait_busy).
                var ready = false
                repeat(20) {
                    if (client.deviceReadyNow()) { ready = true; return@repeat }
                    delay(100)
                }
                Diagnostics.log("0x90C8 ready=$ready")
                delay(300)
                val status = runCatching { client.readPropByte(0xD1A2) }.getOrDefault(-1)
                val cameraStatus = runCatching { client.readPropByte(Ptp.PROP_CAMERA_LIVE_VIEW_STATUS) }.getOrDefault(-1)
                val imageStatus = runCatching { client.readPropByte(Ptp.PROP_LIVE_VIEW_IMAGE_STATUS) }.getOrDefault(-1)
                Diagnostics.log("LiveViewStatus 0xD1A2=$status / 0xD1B8=$cameraStatus / 0xD1BB=$imageStatus")
                refreshRemoteInfo()
                refreshControls()
                _remote.value = RemoteState.Ready
                startLiveViewPolling()
                startParamRefresh()
            } catch (e: Exception) {
                _remote.value = RemoteState.Failed
                _liveView.value = LiveViewState.Failed
                Diagnostics.log("遥控模式开启失败：${humanError(e)}")
            }
        }
    }

    fun endRemote() {
        // Leaving the page must not keep firing an interval sequence.
        stopInterval()
        // Only undo what beginRemote actually started; on a plain capture page the
        // camera was never taken over, so touching live view here would disturb it.
        if (_remote.value == RemoteState.Idle) return
        liveViewJob?.cancel()
        liveViewJob = null
        paramJob?.cancel()
        paramJob = null
        _preview.value = null
        _liveView.value = LiveViewState.Idle
        _remoteInfo.value = null
        scope.launch {
            runCatching { client.stopLiveView() }
            runCatching { client.changeApplicationMode(0) }
            runCatching { client.returnToCamera() }
            _remote.value = RemoteState.Idle
        }
    }

    /** One selectable value of a camera parameter. */
    data class ParamOption(val raw: Int, val label: String)

    /** A shooting parameter the remote page can read and (maybe) write. */
    data class CameraControl(
        val key: String,
        val label: String,
        val prop: Int,
        val type: Int,
        val current: Int,
        val writable: Boolean,
        val options: List<ParamOption>,
    )

    private val _controls = MutableStateFlow<List<CameraControl>>(emptyList())
    val controls: StateFlow<List<CameraControl>> = _controls

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32v(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun typeSize(type: Int): Int = when (type) {
        0x0001, 0x0002 -> 1   // INT8 / UINT8
        0x0003, 0x0004 -> 2   // INT16 / UINT16
        0x0005, 0x0006 -> 4   // INT32 / UINT32
        0x0007, 0x0008 -> 8   // INT64 / UINT64
        else -> -1
    }

    private fun readTyped(b: ByteArray, o: Int, type: Int): Int = when (typeSize(type)) {
        1 -> if (type == 0x0001) b[o].toInt() else b[o].toInt() and 0xFF
        2 -> if (type == 0x0003) u16(b, o).toShort().toInt() else u16(b, o)
        4 -> if (type == 0x0005) u32v(b, o).toInt() else u32v(b, o).toInt()
        else -> -1
    }

    private fun encodeTyped(value: Int, type: Int): ByteArray = when (typeSize(type)) {
        1 -> byteArrayOf(value.toByte())
        2 -> byteArrayOf(value.toByte(), (value ushr 8).toByte())
        4 -> byteArrayOf(
            value.toByte(), (value ushr 8).toByte(),
            (value ushr 16).toByte(), (value ushr 24).toByte(),
        )
        else -> ByteArray(0)
    }

    private fun formatAperture(raw: Int): String = "f/%.1f".format(raw / 100.0)

    /** Shutter speed from 0.1 ms units (80 -> 8 ms -> 1/125). */
    private fun formatShutter(raw: Int): String {
        if (raw <= 0) return "—"
        return if (raw >= 10_000) "%.1fs".format(raw / 10_000.0)
        else "1/${(10_000.0 / raw).toInt()}"
    }

    private fun shutterOptions(): List<ParamOption> {
        val out = ArrayList<ParamOption>()
        for (den in intArrayOf(32000, 25000, 20000, 16000, 12500, 10000, 8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3)) {
            val raw = Math.round(10_000.0 / den).toInt()
            out.add(ParamOption(raw, "1/$den"))
        }
        for (sec in intArrayOf(1, 2, 3, 4, 5, 6, 8, 10, 15, 20, 30)) {
            out.add(ParamOption(sec * 10_000, "${sec}s"))
        }
        return out
    }

    private fun defaultsFor(key: String): List<ParamOption> = when (key) {
        "aperture" -> listOf(
            140, 160, 180, 200, 220, 250, 280, 320, 350, 400, 450, 500, 560, 630, 710,
            800, 900, 1000, 1100, 1300, 1400, 1600, 1800, 2000, 2200, 2500, 2800, 3200,
        ).map { ParamOption(it, formatAperture(it)) }
        "iso" -> listOf(
            100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000,
            2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 51200,
        ).map { ParamOption(it, it.toString()) }
        "shutter" -> shutterOptions()
        "wb" -> listOf(2, 4, 5, 6, 7, 32784, 32785, 32786, 32787).map { ParamOption(it, labelFor("wb", it)) }
        "drive" -> listOf(1, 2).map { ParamOption(it, labelFor("drive", it)) }
        // Fallback: -5..+5 EV in 1/3-stop steps, in 1/1000 EV units.
        "ev" -> listOf(-5000, -4666, -4333, -4000, -3666, -3333, -3000, -2666, -2333, -2000,
            -1666, -1333, -1000, -666, -333, 0, 333, 666, 1000, 1333, 1666, 2000, 2333, 2666,
            3000, 3333, 3666, 4000, 4333, 4666, 5000)
            .map { raw -> ParamOption(raw, if (raw > 0) "+%.1f".format(raw / 1000.0) else "%.1f".format(raw / 1000.0)) }
        "mode" -> listOf(
            ParamOption(1, "M"), ParamOption(3, "A"), ParamOption(4, "S"),
            ParamOption(5, "P"), ParamOption(2, "AUTO"),
        )
        else -> emptyList()
    }

    private fun labelFor(key: String, raw: Int): String = when (key) {
        "aperture" -> formatAperture(raw)
        "shutter" -> formatShutter(raw)
        // Nikon exposes exposure bias as 1/1000 EV steps.
        "ev" -> {
            val ev = raw / 1000.0
            if (ev > 0) "+%.1f".format(ev) else "%.1f".format(ev)
        }
        "mode" -> when (raw) {
            1 -> "M"; 2 -> "AUTO"; 3 -> "A"; 4 -> "S"; 5 -> "P"; else -> raw.toString()
        }
        "wb" -> when (raw) {
            0, 2 -> "自动"; 3 -> "一键"; 4 -> "晴天"; 5 -> "荧光"; 6 -> "白炽"; 7 -> "闪光"
            32784 -> "阴天"; 32785 -> "阴影"; 32786 -> "色温"; 32787 -> "预设"
            else -> "WB $raw"
        }
        "drive" -> when (raw) {
            1 -> "单张"; 2 -> "连拍"; 3 -> "定时"; 4 -> "安静"; else -> "模式 $raw"
        }
        else -> raw.toString()
    }

    /** Reads descriptors for the parameters shown in the viewfinder overlay. */
    private suspend fun refreshControls() = withContext(Dispatchers.IO) {
        val defs = listOf(
            Triple("mode", "挡位", 0x500E),
            Triple("aperture", "光圈", 0x5007),
            Triple("iso", "ISO", 0x500F),
            Triple("shutter", "快门", 0x500D),
            Triple("ev", "曝光补偿", 0x5010),
            Triple("wb", "白平衡", 0x5005),
            Triple("drive", "驱动模式", 0x5013),
        )
        val out = ArrayList<CameraControl>()
        for ((key, label, prop) in defs) {
            val desc = runCatching { client.propDescriptor(prop) }.getOrNull()
            var type = 0
            var current = -1
            var writable = false
            val options = ArrayList<ParamOption>()
            if (desc != null && desc.size >= 5) {
                type = u16(desc, 2)
                writable = desc[4].toInt() == 1
                val sz = typeSize(type)
                if (sz > 0 && desc.size >= 5 + sz) current = readTyped(desc, 5, type)
                val formOff = 5 + 2 * sz
                if (sz > 0 && desc.size > formOff) {
                    when (desc[formOff].toInt()) {
                        1 -> {
                            val min = readTyped(desc, formOff + 1, type)
                            val max = readTyped(desc, formOff + 1 + sz, type)
                            val step = readTyped(desc, formOff + 1 + 2 * sz, type)
                            if (step > 0 && max > min) {
                                var v = min
                                while (v <= max && options.size < 300) {
                                    options.add(ParamOption(v, labelFor(key, v)))
                                    v += step
                                }
                            }
                        }
                        2 -> {
                            val count = u16(desc, formOff + 1)
                            var off = formOff + 3
                            for (i in 0 until count.coerceAtMost(300)) {
                                if (off + sz > desc.size) break
                                val v = readTyped(desc, off, type)
                                options.add(ParamOption(v, labelFor(key, v)))
                                off += sz
                            }
                        }
                    }
                }
            }
            // Shutter's descriptor form is not a plain range; use the standard list.
            if (key == "shutter" || options.isEmpty()) {
                options.clear()
                options.addAll(defaultsFor(key))
                if (key == "shutter") type = 0x0006
                if (type == 0) type = 0x0004
            }
            // 0x500D advertises Get/Set in M mode but still rejects writes with 0x201C.
            if (key == "shutter") writable = false
            // Only keep the modes a Z body actually exposes.
            if (key == "mode") {
                options.removeAll { it.raw !in setOf(1, 2, 3, 4, 5) }
            }
            // The descriptor's "current" field is an index for some Nikon props;
            // read the real value through GetDevicePropValue instead.
            val szNow = typeSize(type)
            val valueProp = prop
            if (szNow > 0) {
                val rawValue = runCatching { client.readProp(valueProp) }.getOrNull()
                if (rawValue != null && rawValue.size >= szNow) {
                    // 0x500D reports 0.1 ms units directly; the list matches it.
                    current = readTyped(rawValue, 0, type)
                }
            }
            out.add(CameraControl(key, label, prop, type, current, writable, options))
        }
        _controls.value = out
        Diagnostics.log("参数控制：" + out.joinToString(" ") {
            "${it.label}=${it.current}${if (it.writable) "(可写)" else "(只读)"}/${it.options.size}项"
        })
    }

    /** Writes one parameter and refreshes the overlay values. */
    fun setControl(control: CameraControl, raw: Int) {
        scope.launch {
            // Hold off frame polling so the write and the read-back are not stuck
            // behind multi-second live-view transactions on a weak link.
            liveViewPaused = true
            // Optimistic update so the overlay flips immediately; the reads below
            // then reconcile with whatever the body actually accepted.
            _controls.value = _controls.value.map {
                if (it.key == control.key) it.copy(current = raw) else it
            }
            _remoteInfo.value = _remoteInfo.value?.let { info ->
                when (control.key) {
                    "mode" -> info.copy(mode = labelFor(control.key, raw))
                    "aperture" -> info.copy(aperture = labelFor(control.key, raw))
                    "iso" -> info.copy(iso = labelFor(control.key, raw))
                    "shutter" -> info.copy(shutter = labelFor(control.key, raw))
                    else -> info
                }
            }
            try {
                val payload = encodeTyped(raw, control.type)
            val result = runCatching {
                client.writeProp(control.prop, payload)
            }
                Diagnostics.log(
                    "设置 ${control.label}=${labelFor(control.key, raw)} -> " +
                        (result.exceptionOrNull()?.let { humanError(it) } ?: "OK"),
                )
                // Give the body a moment before reading back; otherwise the old
                // value wins and the overlay flips back.
                delay(450)
                runCatching { refreshRemoteInfo(quiet = true) }
            } finally {
                liveViewPaused = false
            }
        }
    }

    /** Keeps the overlay in sync with dial changes made on the body. */
    /** Applies the chosen preview size/compression (takes effect on the next frame). */
    fun applyLiveViewQuality() {
        scope.launch(Dispatchers.IO) { applyLiveViewQualityBlocking() }
    }

    private suspend fun applyLiveViewQualityBlocking() = withContext(Dispatchers.IO) {
        val q = settings.liveViewQuality.value
        val size = if (q == 0) 2 else 3
        val compression = if (q == 2) 4 else 0
        runCatching { client.writeProp(Ptp.PROP_LIVE_VIEW_IMAGE_SIZE, byteArrayOf(size.toByte())) }
        runCatching { client.writeProp(Ptp.PROP_LIVE_VIEW_IMAGE_COMPRESSION, byteArrayOf(compression.toByte())) }
        Diagnostics.log("取景画质档位=$q (size=$size, compression=$compression)")
    }

    private fun startParamRefresh() {
        paramJob?.cancel()
        paramJob = scope.launch {
            while (true) {
                delay(2500)
                if (liveViewPaused || captureInFlight) continue
                runCatching { refreshRemoteInfo(quiet = true) }
            }
        }
    }

    @Volatile
    private var afBusy = false

    /** AF-ON: run autofocus without touching the shutter. */
    fun afOn() {
        if (afBusy) return
        scope.launch {
            afBusy = true
            liveViewPaused = true
            try {
                // The body answers DeviceBusy while a previous AF cycle runs.
                var result = runCatching { client.afDrive() }
                if (result.exceptionOrNull()?.let { humanError(it) }?.contains("DeviceBusy") == true) {
                    delay(350)
                    result = runCatching { client.afDrive() }
                }
                Diagnostics.log("AF-ON -> " + (result.exceptionOrNull()?.let { humanError(it) } ?: "OK"))
            } finally {
                liveViewPaused = false
                afBusy = false
            }
        }
    }

    /** Tap-to-focus: move the live-view AF point, then drive AF. */
    fun focusAt(normalizedX: Float, normalizedY: Float) {
        if (afBusy) return
        scope.launch {
            afBusy = true
            val x = (normalizedX.coerceIn(0f, 1f) * 1023).toInt()
            val y = (normalizedY.coerceIn(0f, 1f) * 767).toInt()
            liveViewPaused = true
            try {
                var move = runCatching { client.changeAfArea(x, y) }
                if (move.exceptionOrNull()?.let { humanError(it) }?.contains("DeviceBusy") == true) {
                    delay(350)
                    move = runCatching { client.changeAfArea(x, y) }
                }
                Diagnostics.log(
                    "点选对焦 ($x, $y) -> " +
                        (move.exceptionOrNull()?.let { humanError(it) } ?: "OK"),
                )
                if (move.isSuccess) {
                    val focus = runCatching { client.afDrive() }
                    Diagnostics.log(
                        "AF-ON -> " + (focus.exceptionOrNull()?.let { humanError(it) } ?: "OK"),
                    )
                }
            } finally {
                liveViewPaused = false
                afBusy = false
            }
        }
    }

    /**
     * Diagnostic: prints whether each shooting property advertises Get/Set.
     * Triggered by long-pressing the model-support entry on the remote page.
     */
    fun probeWritableProps() {
        scope.launch {
            val props = listOf(
                0x5007 to "光圈", 0x500D to "快门", 0x500F to "ISO", 0x5010 to "曝光补偿",
                0x5005 to "白平衡", 0x500A to "对焦模式", 0x5013 to "驱动模式", 0x500E to "挡位",
                0xD0B4 to "ISO(厂商)", 0xD100 to "快门(厂商)", 0xD1A6 to "取景选择器",
                0xD05D to "AF 区域", 0xD061 to "AF 模式",
            )
            for ((code, label) in props) {
                val data = runCatching { client.propDescriptor(code) }.getOrNull()
                if (data == null || data.size < 5) {
                    Diagnostics.log("属性 0x${code.toString(16)} $label -> 无描述")
                    continue
                }
                val writable = data[4].toInt() == 1
                val head = data.take(10).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                Diagnostics.log("属性 0x${code.toString(16)} $label -> ${if (writable) "可写" else "只读"} 头=$head")
                if (code == 0x5010 || code == 0x5005 || code == 0x5013) {
                    Diagnostics.log(
                        "描述 0x${code.toString(16)} 全量=" +
                            data.take(40).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) },
                    )
                }
            }
            Diagnostics.log("属性探测完成")
        }
    }

    /** Polls 0x9428 (fallback 0x9203) while the remote page is open. */
    private fun startLiveViewPolling() {
        liveViewJob?.cancel()
        _liveView.value = LiveViewState.Starting
        liveViewJob = scope.launch {
            var legacy = false
            var frames = 0
            var failures = 0
            var lastError: String? = null
            var windowStart = System.currentTimeMillis()
            var windowFrames = 0
            while (true) {
                if (liveViewPaused) {
                    delay(60)
                    continue
                }
                val attempt = runCatching {
                    if (legacy) client.liveViewImage() else client.liveViewImageEx()
                }
                if (!legacy && attempt.isFailure) {
                    val msg = attempt.exceptionOrNull()?.let { humanError(it) } ?: "?"
                    if (msg.contains("0x2005") || msg.contains("不支持")) {
                        legacy = true
                        Diagnostics.log("0x9428 不可用，改用 0x9203: $msg")
                    }
                }
                val jpeg = attempt.getOrNull()?.let { extractLiveViewJpeg(it) }
                if (jpeg != null) {
                    frames++
                    failures = 0
                    _preview.value = jpeg
                    if (frames == 1) {
                        _liveView.value = LiveViewState.Running
                        Diagnostics.log("取景首帧 ${jpeg.size} 字节")
                    }
                    windowFrames++
                    val now = System.currentTimeMillis()
                    if (now - windowStart >= 5000) {
                        val fps = windowFrames * 1000.0 / (now - windowStart)
                        Diagnostics.log("取景帧率 %.1f fps（%d 帧 / %d ms）".format(fps, windowFrames, now - windowStart))
                        windowStart = now
                        windowFrames = 0
                    }
                } else {
                    failures++
                    lastError = attempt.exceptionOrNull()?.let { humanError(it) }
                    if (failures == 1) Diagnostics.log("取景失败 #1: $lastError")
                    if (failures >= 20) {
                        _liveView.value = LiveViewState.Failed
                        Diagnostics.log("取景停止：连续 $failures 次无帧（$lastError）")
                        return@launch
                    }
                    delay(150)
                    continue
                }
                delay(40)
            }
        }
    }

    /** Nikon LV payloads may prepend a display-info header; keep everything from SOI on. */
    private fun extractLiveViewJpeg(bytes: ByteArray): ByteArray? {
        if (bytes.size < 4) return null
        for (i in 0 until bytes.size - 3) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD8.toByte() && bytes[i + 2] == 0xFF.toByte()) {
                return bytes.copyOfRange(i, bytes.size)
            }
        }
        return null
    }

    /** All object handles on every storage (card and SDRAM); empty when the body refuses. */
    private suspend fun snapshotHandles(): Set<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val out = HashSet<Int>()
            val storages = HashSet<Int>()
            client.storageIds().forEach { storages.add(it) }
            runCatching { client.vendorStorageIds().forEach { storages.add(it) } }
            for (storage in storages) {
                runCatching { client.objectHandles(storage) }.getOrNull()?.forEach { out.add(it) }
            }
            out
        }.getOrDefault(emptySet())
    }

    /**
     * Fires one shutter release and then pulls events with 0x90C7 (modern Nikon
     * bodies queue ObjectAddedInSDRAM there). Returns the new handle or null.
     */
    private suspend fun shootAndWait(before: Set<Int>, label: String, send: () -> Unit): Pair<Boolean, Int?> {
        val latch = CompletableDeferred<Int?>()
        sdramLatch = latch
        try {
            send()
            Diagnostics.log("$label 指令已返回")
        } catch (e: Exception) {
            Diagnostics.log("$label 失败：${humanError(e)}")
            return false to null
        }
        // Long exposures make the body sit in noise reduction for many seconds.
        repeat(40) {
            delay(400)
            if (latch.isCompleted) latch.await()?.let { return true to it }
            val raw = runCatching { client.getEventRaw() }.getOrNull()
            if (raw != null && raw.isNotEmpty()) {
                val events = parseNikonEvents(raw)
                val summary = events.joinToString(" ") { "0x${it.first.toString(16)}:${it.second}" }
                Diagnostics.log("$label 0x90C7 事件 [$summary]")
                val maxBefore = before.maxOrNull() ?: 0
                val candidates = events.map { it.second }.filter { it > maxBefore }
                candidates.maxOrNull()?.let { return true to it }
            }
        }
        // Last resort: the shot may also show up in a normal storage.
        val now = snapshotHandles()
        val fresh = now.filter { it !in before }.maxOrNull()
        if (fresh != null) Diagnostics.log("$label 句柄列表发现新对象 $fresh")
        return true to fresh
    }


    private var thumbLargeUnsupported = false
    private var extrasLoaded = false
    private var allHandles: List<Int> = emptyList()
    private val kindByHandle = java.util.concurrent.ConcurrentHashMap<Int, com.achooluv.nikonlink.camera.FileKind>()
    private var loadedPages = 0
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var keepAliveJob: Job? = null
    private var userDisconnected = false
    private var currentHost: String = ""

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
                // Hold the Wi-Fi radio for the life of the session: without this,
                // Doze/screen-off lets the link sleep and the body keeps the old
                // session, so reconnects fail until Wi-Fi is toggled.
                wifi.acquireLock()
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
                settings.setHost(mode, resolved)

                _state.value = ConnState.Connected(info.model.ifBlank { "Nikon 相机" })
                // Android 12+ forbids starting a foreground service from the
                // background (screen-off reconnect). Never let that crash the app.
                runCatching {
                    ConnectionService.start(app, "已连接 ${info.model.ifBlank { "Nikon 相机" }} · 可传输")
                }.onFailure {
                    Diagnostics.log("前台服务启动被拒：${it.javaClass.simpleName}")
                }
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

    /** Last camera address for STA, when it still answers — makes repeat connects instant. */
    private fun rememberedHost(): String? =
        settings.hostFor(ConnectMode.STA).takeIf {
            it.isNotBlank() && CameraDiscovery.probe(it, Ptp.PORT, null, 900)
        }

    /** Rescans the current LAN for a camera (Wi-Fi station mode). */
    private suspend fun scanLanForCamera(): String? {
        val subnets = LanScanner.localSubnets(app)
        Diagnostics.log("STA 扫描网段：${subnets.ifEmpty { listOf("未取到本机网段") }.joinToString()}")
        val found = LanScanner.scan(
            context = app,
            port = Ptp.PORT,
            extraHost = settings.hostFor(ConnectMode.STA),
        ) { done ->
            // The scanner reports from IO threads: once the user cancels, the job is
            // no longer active and stale "正在连接" updates must not come back.
            if (connectJob?.isActive == true) {
                _state.value = ConnState.Connecting("正在扫描局域网…（已检查 $done 个地址）")
            }
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
        wifi.releaseLock()
        // Flip the UI immediately; the PTP CloseSession below is a network round trip.
        _state.value = ConnState.Idle
        _identity.value = null
        _files.value = emptyList()
        allHandles = emptyList()
        kindByHandle.clear()
        thumbnailCache.evictAll()
        extrasLoaded = false
        _extras.value = null
        _progress.value = _progress.value.copy(active = false)
        // Closing the session is a PTP round trip: keep it off the UI thread.
        scope.launch {
            suppressDisconnect = true
            runCatching { client.close() }
            suppressDisconnect = false
            client.socketFactory = javax.net.SocketFactory.getDefault()
            runCatching { wifi.release() }
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
                // A long exposure + noise reduction makes the body refuse everything
                // for seconds; a heartbeat "failure" there is not a dead link.
                if (captureInFlight) continue
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
        wifi.releaseLock()
        // Leave the busy state right away: scanner callbacks are silenced by the
        // cancelled job, and the socket teardown below runs in the background.
        _state.value = ConnState.Idle
        scope.launch {
            suppressDisconnect = true
            runCatching { client.close() }
            suppressDisconnect = false
            client.socketFactory = javax.net.SocketFactory.getDefault()
            runCatching { wifi.release() }
            ConnectionService.stop(app)
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
                    settings.hostFor(ConnectMode.STA).takeIf {
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

    /** Loads a JPEG preview for [handle]; returns null when the body has none. */
    suspend fun thumbnail(handle: Int): ByteArray? {
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

    private val videoPosterCache = HashMap<String, ByteArray>()

    /** Poster frame for an already-imported movie, used when the body has no thumb. */
    suspend fun videoPoster(name: String): ByteArray? = withContext(Dispatchers.IO) {
        videoPosterCache[name]?.let { return@withContext it }
        val uri = findVideoUri(name) ?: return@withContext null
        val bytes = runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(app, uri)
                val frame = retriever.getFrameAtTime(1_000_000) ?: retriever.getFrameAtTime(0)
                    ?: return@runCatching null
                java.io.ByteArrayOutputStream().use { out ->
                    frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                    out.toByteArray()
                }
            } finally {
                retriever.release()
            }
        }.getOrNull()
        if (bytes != null) videoPosterCache[name] = bytes
        bytes
    }

    private fun findVideoUri(name: String): android.net.Uri? = runCatching {
        val projection = arrayOf(android.provider.MediaStore.Video.Media._ID)
        val selection = "${android.provider.MediaStore.Video.Media.DISPLAY_NAME}=?"
        app.contentResolver.query(
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf(name),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else android.content.ContentUris.withAppendedId(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                cursor.getLong(0),
            )
        }
    }.getOrNull()

    // -------------------------------------------------------------- transfers

    private val pendingFiles = ArrayDeque<CameraFile>()
    private val pendingLock = Any()
    private var queueWorker: Job? = null

    private val _queueCount = MutableStateFlow(0)
    val queueCount: StateFlow<Int> = _queueCount

    /**
     * Adds [files] to the background transfer queue. The worker keeps running while
     * the user switches pages, and only one file hits the wire at a time.
     */
    fun download(files: List<CameraFile>, force: Boolean = false) {
        if (files.isEmpty()) return
        val expanded = if (settings.pairJpeg.value) expandRawJpegPairs(files) else files
        synchronized(pendingLock) {
            for (file in expanded) {
                if (pendingFiles.none { it.handle == file.handle }) pendingFiles.addLast(file)
            }
            _queueCount.value = pendingFiles.size
        }
        startQueueWorker()
    }

    private fun startQueueWorker() {
        if (queueWorker?.isActive == true) return
        queueWorker = scope.launch {
            downloadMutex.withLock {
                val chunk = settings.chunkSize.value
                val partial = _profile.value?.supportsPartialObject ?: true
                while (true) {
                    val next = synchronized(pendingLock) {
                        pendingFiles.removeFirstOrNull().also { _queueCount.value = pendingFiles.size }
                    } ?: break
                    val result = runCatching {
                        downloader.download(client, listOf(next), chunk, force = false, partial) { p ->
                            _progress.value = p
                        }
                    }.getOrNull()
                    if (result != null) {
                        if (result.failed.isNotEmpty()) {
                            _failedFiles.value = _failedFiles.value + result.failed
                        }
                        result.lastUri?.let { _lastUri.value = it }
                    } else {
                        _failedFiles.value = _failedFiles.value + next
                    }
                    _importsVersion.value++
                    refreshIdentityStorage()
                }
                _progress.value = _progress.value.copy(active = false, message = "传输队列已完成")
            }
        }
    }

    /** Resolves and opens an already-imported video; false when it is not on the phone. */
    fun openVideo(file: CameraFile): Boolean {
        val uri = findVideoUri(file.name) ?: return false
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { app.startActivity(intent); true }.getOrDefault(false)
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
        synchronized(pendingLock) {
            pendingFiles.clear()
            _queueCount.value = 0
        }
        val job = queueWorker
        if (job != null && job.isActive) {
            job.cancel()
            _progress.value = _progress.value.copy(active = false, message = "已取消，已下载部分保留为断点")
        } else {
            _progress.value = _progress.value.copy(message = "没有正在进行的传输")
        }
    }

    // ----------------------------------------------------------------- events

    private fun handleEvent(event: PtpEvent) {
        if (event.code == Ptp.EVENT_OBJECT_ADDED_IN_SDRAM) {
            val handle = event.params.firstOrNull()
            Diagnostics.log("SDRAM 新照片事件 0xC101 handle=$handle")
            sdramLatch?.complete(handle)
            return
        }
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
        // AP hosts only: the STA lease belongs to a different network and would just
        // waste a probe round trip here.
        settings.hostFor(ConnectMode.AP).takeIf { it.isNotBlank() }?.let(out::add)
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
        is java.net.ConnectException -> cannotReachHint()
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

    private fun timeoutHint(): String {
        val host = currentHost.ifBlank { CameraWifi.DEFAULT_CAMERA_HOST }
        return if (settings.mode.value == ConnectMode.STA) {
            "连接超时：没在 $host:${Ptp.PORT} 找到相机。确认手机和相机连的是同一个路由器 Wi-Fi，" +
                "且没有开启「AP 隔离」；或用「手动 IP」填写相机地址。"
        } else {
            "连接超时：没在 $host:${Ptp.PORT} 找到相机。确认手机 Wi-Fi 已连上 NIKON_ 网络；" +
                "若相机不在 192.168.1.x 网段，用「换 IP 重试」填写实际地址。"
        }
    }

    private fun cannotReachHint(): String =
        if (settings.mode.value == ConnectMode.STA) {
            "连不上相机端口：确认相机已加入同一个路由器 Wi-Fi，且路由器没有开启「AP 隔离」"
        } else {
            "连不上相机端口：网络不可达，确认手机 Wi-Fi 已连到 NIKON_ 网络"
        }

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
