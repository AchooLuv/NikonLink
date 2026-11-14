package com.achooluv.nikonlink

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Protocol/transfer notes. Lines are kept in a small in-memory ring buffer and, once
 * [attach] has been called, also appended to a file so a crash or a restart does not
 * wipe the evidence.
 */
object Diagnostics {

    private const val MAX_LINES = 150
    private const val MAX_FILE_BYTES = 256 * 1024
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val lock = Any()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var logFile: File? = null

    fun attach(context: Context) {
        synchronized(lock) {
            logFile = File(context.filesDir, "diagnostics.log")
        }
    }

    fun log(message: String) {
        val line = "${stamp.format(Date())} $message"
        synchronized(lock) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
            runCatching {
                val file = logFile ?: return@runCatching
                if (file.length() > MAX_FILE_BYTES) file.writeText("")
                file.appendText(line + "\n")
            }
        }
        Log.d("NikonLink", message)
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
            runCatching { logFile?.writeText("") }
        }
    }

    fun lines(): List<String> = synchronized(lock) { lines.toList() }

    fun dump(context: Context): String = buildString {
        appendLine("NikonLink 诊断信息")
        appendLine("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("abi=${Build.SUPPORTED_ABIS.joinToString(",")}")
        appendLine("---- 本次进程 ----")
        val snapshot = lines()
        if (snapshot.isEmpty()) appendLine("(无)") else snapshot.forEach { appendLine(it) }
        appendLine("---- 历史（文件） ----")
        val history = runCatching {
            logFile?.takeIf { it.exists() }?.readLines()?.takeLast(120).orEmpty()
        }.getOrDefault(emptyList())
        if (history.isEmpty()) appendLine("(无)") else history.forEach { appendLine(it) }
    }
}
