package com.achooluv.nikonlink.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Owns the "check → download → install" flow. The download keeps running in the
 * app's scope, so the update dialog can be closed and the rest of the app used
 * while the APK arrives.
 */
class UpdateManager private constructor(private val app: Context) {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class Available(val info: UpdateInfo) : State
        data class Downloading(val info: UpdateInfo, val bytes: Long) : State
        data class Ready(val info: UpdateInfo, val file: File) : State
        data class Failed(val info: UpdateInfo?, val message: String) : State
        /** The system refuses to install: the user has to allow it in settings. */
        data class NeedsPermission(val info: UpdateInfo) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private val checking = AtomicBoolean(false)

    /** Checks once per app session; a manual tap forces a fresh request. */
    fun check(currentVersion: String, force: Boolean = false) {
        if (!force) {
            when (_state.value) {
                is State.Available, is State.Downloading, is State.Ready, is State.NeedsPermission -> return
                else -> Unit
            }
        }
        if (!checking.compareAndSet(false, true)) return
        checkJob = scope.launch {
            if (force) _state.value = State.Checking
            val found = runCatching { UpdateChecker.latest(currentVersion) }.getOrNull()
            _state.value = if (found != null) State.Available(found) else State.Idle
            checking.set(false)
        }
    }

    fun download(context: Context) {
        val info = when (val s = _state.value) {
            is State.Available -> s.info
            is State.Failed -> s.info
            is State.NeedsPermission -> s.info
            is State.Downloading -> s.info
            else -> null
        } ?: return
        if (downloadJob?.isActive == true) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedsPermission(info)
            return
        }
        downloadJob = scope.launch {
            _state.value = State.Downloading(info, 0)
            val target = File(updateDir(app), "nikonlink-${info.versionName}.apk")
            val ok = runCatching { fetch(info, target) }
                .onFailure { _state.value = State.Failed(info, human(it)) }
                .isSuccess
            if (!ok) return@launch
            if (info.sha256 != null && !sha256(target).equals(info.sha256, ignoreCase = true)) {
                runCatching { target.delete() }
                _state.value = State.Failed(info, "安装包校验失败，请重试")
                return@launch
            }
            _state.value = State.Ready(info, target)
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        val info = (_state.value as? State.Downloading)?.info
        _state.value = if (info != null) State.Available(info) else State.Idle
    }

    /** True when the system installer was actually opened. */
    fun install(context: Context, file: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            openInstallPermission(context)
            return false
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        }.getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun openInstallPermission(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun openReleasesPage(context: Context) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.releasesPage()))
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun fetch(info: UpdateInfo, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NikonLink-Android")
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastEmit = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 120 || written == total) {
                            lastEmit = now
                            val bytes = written
                            scope.launch { _state.value = State.Downloading(info, bytes) }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun human(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "无法连接 GitHub，请检查网络"
        is java.net.SocketTimeoutException -> "下载超时，请重试"
        else -> e.message ?: "下载失败"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        @Volatile
        private var instance: UpdateManager? = null

        fun get(context: Context): UpdateManager =
            instance ?: synchronized(this) {
                instance ?: UpdateManager(context.applicationContext).also { instance = it }
            }

        private fun updateDir(context: Context): File = File(context.cacheDir, "updates")
    }
}
