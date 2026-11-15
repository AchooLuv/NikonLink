package com.achooluv.nikonlink.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Small, dependency-free preference store. All values are backed by
 * SharedPreferences and mirrored into StateFlows for Compose.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("nikonlink.settings", Context.MODE_PRIVATE)
    private val imports = context.getSharedPreferences("nikonlink.imports", Context.MODE_PRIVATE)

    /**
     * Import records are read on every grid cell, so they live in memory and are
     * written back in batches instead of re-reading (and copying) the preference
     * set per call.
     */
    private val importLock = Any()
    private val importedKeys = HashSet<String>().apply {
        addAll(imports.getStringSet(KEY_DONE, emptySet()) ?: emptySet())
    }
    private var pendingImportWrites = 0

    init {
        // v2: every tool starts hidden and the preview starts at 流畅.
        if (!prefs.getBoolean(KEY_TOOLS_V2, false)) {
            prefs.edit()
                .putBoolean(KEY_SHOW_GRID, false)
                .putBoolean(KEY_SHOW_PEAK, false)
                .putBoolean(KEY_SHOW_WB, false)
                .putBoolean(KEY_SHOW_DRIVE, false)
                .putBoolean(KEY_SHOW_INTERVAL, false)
                .putBoolean(KEY_SHOW_TIMER, false)
                .putInt(KEY_LV_QUALITY, 0)
                .putBoolean(KEY_TOOLS_V2, true)
                .apply()
        }
        migrateLegacyHost()
    }

    val autoPull = MutableStateFlow(prefs.getBoolean(KEY_AUTO_PULL, false))
    val keepAlive = MutableStateFlow(prefs.getBoolean(KEY_KEEP_ALIVE, true))
    val showRawThumbnails = MutableStateFlow(prefs.getBoolean(KEY_RAW_THUMBS, true))
    val pairJpeg = MutableStateFlow(prefs.getBoolean(KEY_PAIR_JPEG, true))
    val chunkSize = MutableStateFlow(prefs.getInt(KEY_CHUNK, DEFAULT_CHUNK))
    val keepAliveSeconds = MutableStateFlow(prefs.getInt(KEY_KEEPALIVE_SECONDS, 15))

    /** Remote shutter self-timer in seconds (0 = immediate). */
    val selfTimerSeconds = MutableStateFlow(prefs.getInt(KEY_SELF_TIMER, 0))

    fun setSelfTimerSeconds(seconds: Int) {
        val safe = seconds.coerceIn(0, 30)
        selfTimerSeconds.value = safe
        prefs.edit().putInt(KEY_SELF_TIMER, safe).apply()
    }

    val mode = MutableStateFlow(ConnectMode.from(prefs.getString(KEY_MODE, null)))

    fun setMode(value: ConnectMode) {
        mode.value = value
        prefs.edit().putString(KEY_MODE, value.key).apply()
    }

    fun setKeepAliveSeconds(seconds: Int) {
        val safe = seconds.coerceIn(5, 120)
        keepAliveSeconds.value = safe
        prefs.edit().putInt(KEY_KEEPALIVE_SECONDS, safe).apply()
    }

    var lastSsid: String
        get() = prefs.getString(KEY_SSID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SSID, value).apply()

    var lastPassphrase: String
        get() = prefs.getString(KEY_PASS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASS, value).apply()

    var lastHost: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    /**
     * Camera address per connection mode: the AP body answers on 192.168.1.1 while
     * the same body on a router gets a DHCP lease (e.g. 192.168.1.70), so the two
     * must never share one value.
     */
    fun hostFor(mode: ConnectMode): String = prefs.getString(hostKey(mode), "") ?: ""

    fun setHost(mode: ConnectMode, value: String) {
        prefs.edit().putString(hostKey(mode), value).apply()
        // Kept for older builds / diagnostics; nothing reads it for connection setup.
        lastHost = value
    }

    private fun hostKey(mode: ConnectMode): String = when (mode) {
        ConnectMode.AP -> KEY_HOST_AP
        ConnectMode.STA -> KEY_HOST_STA
    }

    /**
     * One-time migration: builds before 0.1.22 kept a single [lastHost]. It is only
     * meaningful for the STA case (the AP address is fixed), so seed STA and let AP
     * fall back to its standard 192.168.1.1 candidate.
     */
    private fun migrateLegacyHost() {
        val legacy = prefs.getString(KEY_HOST, "") ?: ""
        if (legacy.isBlank()) return
        if (prefs.contains(KEY_HOST_AP) || prefs.contains(KEY_HOST_STA)) return
        prefs.edit().putString(KEY_HOST_STA, legacy).apply()
    }

    /**
     * Highest object handle that existed when auto-pull was switched on. Objects
     * with a handle above this one were shot afterwards, so only those are pulled
     * automatically — enabling the toggle never re-downloads the whole card.
     */
    var autoPullBaseline: Int
        get() = prefs.getInt(KEY_BASELINE, -1)
        set(value) = prefs.edit().putInt(KEY_BASELINE, value).apply()

    fun setAutoPull(enabled: Boolean) {
        autoPull.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_PULL, enabled).apply()
    }

    fun setKeepAlive(enabled: Boolean) {
        keepAlive.value = enabled
        prefs.edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply()
    }

    fun setShowRawThumbnails(enabled: Boolean) {
        showRawThumbnails.value = enabled
        prefs.edit().putBoolean(KEY_RAW_THUMBS, enabled).apply()
    }

    fun setPairJpeg(enabled: Boolean) {
        pairJpeg.value = enabled
        prefs.edit().putBoolean(KEY_PAIR_JPEG, enabled).apply()
    }

    fun setChunkSize(bytes: Int) {
        chunkSize.value = bytes
        prefs.edit().putInt(KEY_CHUNK, bytes).apply()
    }

    fun importKey(handle: Int, size: Long): String = "$handle:$size"

    fun isImported(handle: Int, size: Long): Boolean =
        synchronized(importLock) { importKey(handle, size) in importedKeys }

    fun markImported(handle: Int, size: Long) {
        var snapshot: Set<String>? = null
        synchronized(importLock) {
            importedKeys += importKey(handle, size)
            if (importedKeys.size > 5000) {
                // Keep the newest entries only; the set is a convenience index, not a ledger.
                val trimmed = importedKeys.toList().takeLast(4000).toSet()
                importedKeys.clear()
                importedKeys.addAll(trimmed)
            }
            pendingImportWrites++
            if (pendingImportWrites >= IMPORTS_WRITE_BATCH) {
                pendingImportWrites = 0
                snapshot = importedKeys.toSet()
            }
        }
        snapshot?.let { imports.edit().putStringSet(KEY_DONE, it).apply() }
    }

    /** Flushes buffered import records (call once a transfer batch finishes). */
    fun flushImports() {
        val snapshot = synchronized(importLock) {
            pendingImportWrites = 0
            importedKeys.toSet()
        }
        imports.edit().putStringSet(KEY_DONE, snapshot).apply()
    }

    /** Drops every stored preference and import record (the "reset" button). */
    fun clearAll() {
        prefs.edit().clear().apply()
        imports.edit().clear().apply()
        synchronized(importLock) {
            importedKeys.clear()
            pendingImportWrites = 0
        }
        autoPull.value = false
        keepAlive.value = true
        showRawThumbnails.value = true
        pairJpeg.value = true
        chunkSize.value = DEFAULT_CHUNK
        keepAliveSeconds.value = 15
        mode.value = ConnectMode.AP
    }

        /** 0 = 流畅 (VGA), 1 = 标准 (XGA), 2 = 高画质 (XGA Fine). */
    val liveViewQuality = MutableStateFlow(prefs.getInt(KEY_LV_QUALITY, 0))
    val focusPeaking = MutableStateFlow(prefs.getBoolean(KEY_PEAKING, false))
    val gridLines = MutableStateFlow(prefs.getBoolean(KEY_GRID, false))

    fun setLiveViewQuality(value: Int) {
        val safe = value.coerceIn(0, 2)
        liveViewQuality.value = safe
        prefs.edit().putInt(KEY_LV_QUALITY, safe).apply()
    }

    fun setFocusPeaking(enabled: Boolean) {
        focusPeaking.value = enabled
        prefs.edit().putBoolean(KEY_PEAKING, enabled).apply()
    }

    val showGrid = MutableStateFlow(prefs.getBoolean(KEY_SHOW_GRID, false))
    val showPeaking = MutableStateFlow(prefs.getBoolean(KEY_SHOW_PEAK, false))
    val showWb = MutableStateFlow(prefs.getBoolean(KEY_SHOW_WB, false))
    val showDrive = MutableStateFlow(prefs.getBoolean(KEY_SHOW_DRIVE, false))
    val showInterval = MutableStateFlow(prefs.getBoolean(KEY_SHOW_INTERVAL, false))
    val showTimer = MutableStateFlow(prefs.getBoolean(KEY_SHOW_TIMER, false))

    /** One-shot reset (v3): hide every tool and start previews at 流畅. */
    fun applyToolDefaultsOnce() {
        if (prefs.getBoolean(KEY_TOOLS_V3, false)) return
        showGrid.value = false
        showPeaking.value = false
        showWb.value = false
        showDrive.value = false
        showInterval.value = false
        showTimer.value = false
        liveViewQuality.value = 0
        prefs.edit()
            .putBoolean(KEY_SHOW_GRID, false)
            .putBoolean(KEY_SHOW_PEAK, false)
            .putBoolean(KEY_SHOW_WB, false)
            .putBoolean(KEY_SHOW_DRIVE, false)
            .putBoolean(KEY_SHOW_INTERVAL, false)
            .putBoolean(KEY_SHOW_TIMER, false)
            .putInt(KEY_LV_QUALITY, 0)
            .putBoolean(KEY_TOOLS_V3, true)
            .apply()
    }

    fun setShowGrid(value: Boolean) { showGrid.value = value; prefs.edit().putBoolean(KEY_SHOW_GRID, value).apply() }
    fun setShowPeaking(value: Boolean) { showPeaking.value = value; prefs.edit().putBoolean(KEY_SHOW_PEAK, value).apply() }
    fun setShowWb(value: Boolean) { showWb.value = value; prefs.edit().putBoolean(KEY_SHOW_WB, value).apply() }
    fun setShowDrive(value: Boolean) { showDrive.value = value; prefs.edit().putBoolean(KEY_SHOW_DRIVE, value).apply() }
    fun setShowInterval(value: Boolean) { showInterval.value = value; prefs.edit().putBoolean(KEY_SHOW_INTERVAL, value).apply() }
    fun setShowTimer(value: Boolean) { showTimer.value = value; prefs.edit().putBoolean(KEY_SHOW_TIMER, value).apply() }

    fun setGridLines(enabled: Boolean) {
        gridLines.value = enabled
        prefs.edit().putBoolean(KEY_GRID, enabled).apply()
    }

companion object {
        private const val KEY_AUTO_PULL = "auto_pull"
        private const val KEY_KEEP_ALIVE = "keep_alive"
        private const val KEY_RAW_THUMBS = "raw_thumbs"
        private const val KEY_PAIR_JPEG = "pair_jpeg"
        private const val KEY_CHUNK = "chunk_size"
        private const val KEY_KEEPALIVE_SECONDS = "keepalive_seconds"
    private const val KEY_SELF_TIMER = "self_timer_seconds"
    private const val KEY_LV_QUALITY = "lv_quality"
    private const val KEY_TOOLS_V2 = "tools_defaults_v2"
    private const val KEY_TOOLS_V3 = "tools_defaults_v3"
    private const val KEY_SHOW_GRID = "show_grid"
    private const val KEY_SHOW_PEAK = "show_peaking"
    private const val KEY_SHOW_WB = "show_wb"
    private const val KEY_SHOW_DRIVE = "show_drive"
    private const val KEY_SHOW_INTERVAL = "show_interval"
    private const val KEY_SHOW_TIMER = "show_timer"
    private const val KEY_PEAKING = "focus_peaking"
    private const val KEY_GRID = "grid_lines"
        private const val KEY_SSID = "last_ssid"
        private const val KEY_PASS = "last_pass"
        private const val KEY_HOST = "last_host"
        private const val KEY_HOST_AP = "last_host_ap"
        private const val KEY_HOST_STA = "last_host_sta"
        private const val KEY_BASELINE = "auto_pull_baseline"
        private const val KEY_DONE = "done"
        private const val KEY_MODE = "connect_mode"

        const val DEFAULT_CHUNK = 4 * 1024 * 1024
        private const val IMPORTS_WRITE_BATCH = 20

        val CHUNK_CHOICES = listOf(
            1 * 1024 * 1024,
            2 * 1024 * 1024,
            4 * 1024 * 1024,
            8 * 1024 * 1024,
        )

        fun chunkLabel(bytes: Int): String = "${bytes / (1024 * 1024)} MiB"
    }
}
