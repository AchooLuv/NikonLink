package com.achooluv.nikonlink.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A newer release published on GitHub. */
data class UpdateInfo(
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
    /** Lowercase hex SHA-256 of the asset, when GitHub reports one. */
    val sha256: String?,
)

/**
 * Reads the latest GitHub release. Unauthenticated, so the call is kept to one
 * request per app session and fails quietly: an unreachable GitHub must never
 * disturb normal tethering.
 */
object UpdateChecker {

    private const val API = "https://api.github.com/repos/AchooLuv/NikonLink/releases/latest"
    private const val RELEASES_PAGE = "https://github.com/AchooLuv/NikonLink/releases/latest"

    /** Compares dotted numeric versions; a missing part counts as 0. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate)
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun parse(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-')
            .split('.')
            .map { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }

    /** Returns null when offline, rate limited, or already up to date. */
    suspend fun latest(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val body = runCatching { request(API) }.getOrNull() ?: return@withContext null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null

        val tag = json.optString("tag_name").ifBlank { return@withContext null }
        val version = tag.removePrefix("v").removePrefix("V")
        if (!isNewer(version, currentVersion)) return@withContext null

        val assets = json.optJSONArray("assets")
        var apkUrl: String? = null
        var size = 0L
        var digest: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                size = asset.optLong("size")
                digest = asset.optString("digest")
                    .takeIf { it.startsWith("sha256:") }
                    ?.removePrefix("sha256:")
                    ?.lowercase()
                break
            }
        }
        if (apkUrl == null) return@withContext null

        UpdateInfo(
            versionName = version,
            notes = json.optString("body").trim(),
            apkUrl = apkUrl,
            sizeBytes = size,
            sha256 = digest,
        )
    }

    fun releasesPage(): String = RELEASES_PAGE

    /**
     * Release bodies are GitHub-flavoured markdown; the dialog shows plain text, so
     * strip the inline markers and heading hashes instead of showing them verbatim.
     * Install / keyword lines are release-page boilerplate and are dropped here.
     */
    fun readableNotes(raw: String): String = raw
        .replace("**", "")
        .replace("`", "")
        .lineSequence()
        .map { line ->
            line.trimStart()
                .removePrefix("##### ").removePrefix("#### ")
                .removePrefix("### ").removePrefix("## ").removePrefix("# ")
        }
        .filterNot { BOILERPLATE.containsMatchIn(it) }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private val BOILERPLATE = Regex(
        "^(安装|安装包|安装方式|keywords|keyword|关键词|tags?)\\s*[:：]",
        RegexOption.IGNORE_CASE,
    )

    private fun request(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 8_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "NikonLink-Android")
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
