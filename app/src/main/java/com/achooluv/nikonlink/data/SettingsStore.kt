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

    val autoPull = MutableStateFlow(prefs.getBoolean(KEY_AUTO_PULL, false))
    val keepAlive = MutableStateFlow(prefs.getBoolean(KEY_KEEP_ALIVE, true))
    val showRawThumbnails = MutableStateFlow(prefs.getBoolean(KEY_RAW_THUMBS, true))
    val pairJpeg = MutableStateFlow(prefs.getBoolean(KEY_PAIR_JPEG, true))
    val chunkSize = MutableStateFlow(prefs.getInt(KEY_CHUNK, DEFAULT_CHUNK))
    val keepAliveSeconds = MutableStateFlow(prefs.getInt(KEY_KEEPALIVE_SECONDS, 15))

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
        imports.getStringSet(KEY_DONE, emptySet())?.contains(importKey(handle, size)) == true

    fun markImported(handle: Int, size: Long) {
        val current = imports.getStringSet(KEY_DONE, emptySet())?.toMutableSet() ?: mutableSetOf()
        current += importKey(handle, size)
        if (current.size > 5000) {
            // Keep the newest entries only; the set is a convenience index, not a ledger.
            val trimmed = current.toList().takeLast(4000).toSet()
            imports.edit().putStringSet(KEY_DONE, trimmed).apply()
        } else {
            imports.edit().putStringSet(KEY_DONE, current).apply()
        }
    }

    companion object {
        private const val KEY_AUTO_PULL = "auto_pull"
        private const val KEY_KEEP_ALIVE = "keep_alive"
        private const val KEY_RAW_THUMBS = "raw_thumbs"
        private const val KEY_PAIR_JPEG = "pair_jpeg"
        private const val KEY_CHUNK = "chunk_size"
        private const val KEY_KEEPALIVE_SECONDS = "keepalive_seconds"
        private const val KEY_SSID = "last_ssid"
        private const val KEY_PASS = "last_pass"
        private const val KEY_HOST = "last_host"
        private const val KEY_BASELINE = "auto_pull_baseline"
        private const val KEY_DONE = "done"

        const val DEFAULT_CHUNK = 4 * 1024 * 1024

        val CHUNK_CHOICES = listOf(
            1 * 1024 * 1024,
            2 * 1024 * 1024,
            4 * 1024 * 1024,
            8 * 1024 * 1024,
        )

        fun chunkLabel(bytes: Int): String = "${bytes / (1024 * 1024)} MiB"
    }
}
