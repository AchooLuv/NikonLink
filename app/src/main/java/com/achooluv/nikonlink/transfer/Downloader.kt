package com.achooluv.nikonlink.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.achooluv.nikonlink.camera.CameraFile
import com.achooluv.nikonlink.camera.FileKind
import com.achooluv.nikonlink.data.SettingsStore
import com.achooluv.nikonlink.ptp.PtpException
import com.achooluv.nikonlink.ptp.PtpIpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Streams camera objects to disk in resumable 4 MiB chunks and hands the finished
 * file to MediaStore, so a 500 MB movie never has to fit in memory.
 */
class Downloader(
    private val context: Context,
    private val settings: SettingsStore,
) {

    data class Progress(
        val active: Boolean = false,
        val fileName: String = "",
        val index: Int = 0,
        val count: Int = 0,
        val fileBytes: Long = 0,
        val fileSize: Long = 0,
        val totalBytes: Long = 0,
        val totalSize: Long = 0,
        val speed: Long = 0,
        val message: String? = null,
        val doneCount: Int = 0,
        val failedCount: Int = 0,
    ) {
        val fileFraction: Float
            get() = if (fileSize <= 0) 0f else (fileBytes.toFloat() / fileSize).coerceIn(0f, 1f)

        val totalFraction: Float
            get() = if (totalSize <= 0) 0f else (totalBytes.toFloat() / totalSize).coerceIn(0f, 1f)
    }

    private fun incomingDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "incoming").apply { mkdirs() }
    }

    private fun partFile(file: CameraFile): File =
        File(incomingDir(), "${file.handle}_${file.size}.part")

    fun hasPartial(file: CameraFile): Boolean = runCatching { partFile(file).length() > 0 }.getOrDefault(false)

    /**
     * Downloads [files] sequentially. Existing `.part` files resume from their
     * current length (GetPartialObject), so an interrupted RAW or movie continues
     * where it stopped.
     */
    suspend fun download(
        client: PtpIpClient,
        files: List<CameraFile>,
        chunkSize: Int,
        force: Boolean = false,
        supportsPartial: Boolean = true,
        onProgress: (Progress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val queue = files.filter { force || !settings.isImported(it.handle, it.size) }
        if (queue.isEmpty()) {
            onProgress(Progress(active = false, message = "没有需要下载的文件", doneCount = 0))
            return@withContext
        }

        var totalBytes = 0L
        var totalSize = 0L
        var speed = 0L
        var lastTick = System.currentTimeMillis()
        var lastBytes = 0L
        var doneCount = 0
        var failedCount = 0

        queue.forEachIndexed { index, file ->
            coroutineContext.ensureActive()
            val part = partFile(file)
            var offset = if (part.exists()) part.length() else 0L
            if (offset > file.size) {
                part.delete()
                offset = 0
            }

            var fileBytes = offset
            var message: String? = null

            fun emit() {
                onProgress(
                    Progress(
                        active = true,
                        fileName = file.name,
                        index = index + 1,
                        count = queue.size,
                        fileBytes = fileBytes,
                        fileSize = file.size,
                        totalBytes = totalBytes + fileBytes,
                        totalSize = totalSize + file.size,
                        speed = speed,
                        message = message,
                        doneCount = doneCount,
                        failedCount = failedCount,
                    ),
                )
            }

            emit()

            try {
                if (!supportsPartial && fileBytes > 0L) {
                    part.delete()
                    fileBytes = 0L
                }

                fun tick() {
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastTick
                    if (elapsed >= 400) {
                        val rate = ((fileBytes - lastBytes) * 1000.0 / elapsed).toLong()
                        speed = if (speed == 0L) rate else (speed * 2 + rate) / 3
                        lastTick = now
                        lastBytes = fileBytes
                    }
                }

                RandomAccessFile(part, "rw").use { raf ->
                    val rafOut = object : java.io.OutputStream() {
                        override fun write(b: Int) = raf.write(b)
                        override fun write(b: ByteArray, off: Int, len: Int) = raf.write(b, off, len)
                    }
                    if (supportsPartial) {
                        raf.seek(fileBytes)
                        while (fileBytes < file.size) {
                            coroutineContext.ensureActive()
                            val want = minOf(chunkSize.toLong(), file.size - fileBytes).toInt()
                            val written = client.downloadRange(file.handle, fileBytes, want) { input, count ->
                                input.copyTo(rafOut, count)
                            }
                            if (written <= 0L) throw IOException("相机在偏移 $fileBytes 处返回了空数据块")
                            fileBytes += written
                            totalBytes += written
                            tick()
                            emit()
                        }
                    } else {
                        // Firmware without GetPartialObject: one streaming pass, no resume.
                        raf.setLength(0)
                        client.downloadObject(file.handle) { input, count ->
                            input.copyTo(rafOut, count)
                            fileBytes += count
                            totalBytes += count
                            tick()
                            emit()
                        }
                    }
                }

                if (part.length() != file.size) {
                    throw IOException("大小校验失败：${part.length()} ≠ ${file.size}")
                }

                // Publish to the system gallery (Pictures/Movies → NikonLink).
                publish(file, part)
                part.delete()
                settings.markImported(file.handle, file.size)
                doneCount++
                emit()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                failedCount++
                message = describe(e)
                emit()
            }
        }

        onProgress(
            Progress(
                active = false,
                doneCount = doneCount,
                failedCount = failedCount,
                totalBytes = totalBytes,
                totalSize = totalSize,
                message = when {
                    failedCount > 0 -> "完成 $doneCount 个，失败 $failedCount 个（已保留断点，可重试）"
                    else -> "已下载 $doneCount 个文件"
                },
            ),
        )
    }

    private fun describe(e: Exception): String = when (e) {
        is PtpException -> "相机返回错误：${e.message}"
        is IOException -> e.message ?: "网络中断，已保留断点"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Copies the finished part file into MediaStore under Pictures|Movies/NikonLink. */
    private fun publish(file: CameraFile, part: File): Uri {
        val resolver = context.contentResolver
        val isVideo = file.kind == FileKind.MOVIE
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) +
            File.separator + "NikonLink"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(file))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            file.captureTime?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
        }

        val uri = resolver.insert(collection, values)
            ?: throw IOException("无法在相册中创建文件")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                part.inputStream().use { input -> input.copyTo(out, 256 * 1024) }
            } ?: throw IOException("无法写入相册")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    private fun mimeFor(file: CameraFile): String = when (file.extension) {
        "JPG", "JPEG" -> "image/jpeg"
        "NEF" -> "image/x-nikon-nef"
        "MOV" -> "video/quicktime"
        "MP4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    fun savedFolderHint(): String = "Pictures/NikonLink · Movies/NikonLink"

    fun describeSize(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}
