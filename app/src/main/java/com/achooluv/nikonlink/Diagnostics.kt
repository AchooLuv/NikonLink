package com.achooluv.nikonlink

import android.content.Context
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer of protocol/transfer notes. The Settings screen can copy
 * a dump of it, which is usually all that is needed to diagnose a body that
 * refuses a handshake or a chunk size.
 */
object Diagnostics {

    private const val MAX_LINES = 150
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val lock = Any()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(message: String) {
        val line = "${stamp.format(Date())} $message"
        synchronized(lock) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
        }
        Log.d("NikonLink", message)
    }

    fun clear() {
        synchronized(lock) { lines.clear() }
    }

    fun lines(): List<String> = synchronized(lock) { lines.toList() }

    fun dump(context: Context): String = buildString {
        appendLine("NikonLink 诊断信息")
        appendLine("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("abi=${Build.SUPPORTED_ABIS.joinToString(",")}")
        appendLine("---- 最近日志 ----")
        val snapshot = lines()
        if (snapshot.isEmpty()) {
            appendLine("(无)")
        } else {
            snapshot.forEach { appendLine(it) }
        }
    }
}
