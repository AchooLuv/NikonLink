package com.achooluv.nikonlink.camera

import com.achooluv.nikonlink.ptp.ObjectInfo
import com.achooluv.nikonlink.ptp.Ptp
import java.util.Locale

/** What a camera object is, from the user's point of view. */
enum class FileKind { RAW, JPEG, MOVIE, OTHER }

/** A downloadable object on the camera. */
data class CameraFile(
    val handle: Int,
    val name: String,
    val format: Int,
    val size: Long,
    val captureTime: Long?,
) {
    val kind: FileKind
        get() = when {
            format == Ptp.FORMAT_NEF || format == Ptp.FORMAT_UNDEFINED ||
                name.uppercase(Locale.US).endsWith(".NEF") -> FileKind.RAW
            format == Ptp.FORMAT_JPEG || name.uppercase(Locale.US).let { it.endsWith(".JPG") || it.endsWith(".JPEG") } -> FileKind.JPEG
            format == Ptp.FORMAT_MOV ||
                name.uppercase(Locale.US).let { it.endsWith(".MOV") || it.endsWith(".MP4") } -> FileKind.MOVIE
            else -> FileKind.OTHER
        }

    val extension: String
        get() = name.substringAfterLast('.', "").uppercase(Locale.US)

    companion object {
        fun from(info: ObjectInfo, handle: Int): CameraFile = CameraFile(
            handle = handle,
            name = info.filename,
            format = info.format,
            size = info.compressedSize,
            captureTime = info.captureTime,
        )
    }
}

/** Capability knobs that vary by body. */
data class CameraProfile(
    val displayName: String,
    val supportsPartialObject: Boolean,
    val chunkSize: Int,
    val supportsLargeThumb: Boolean,
    val supportsTransferList: Boolean,
) {
    companion object {
        /** Sensible defaults for Nikon Z bodies on Wi-Fi. */
        fun forModel(model: String): CameraProfile {
            val m = model.uppercase(Locale.US)
            return CameraProfile(
                displayName = model.ifBlank { "Nikon" },
                supportsPartialObject = true,
                chunkSize = 4 * 1024 * 1024,
                supportsLargeThumb = m.contains("Z") || m.contains("D"),
                supportsTransferList = true,
            )
        }
    }
}

/** Identity of the connected body. */
data class CameraIdentity(
    val model: String,
    val manufacturer: String,
    val serial: String,
    val firmware: String,
    val battery: Int?,
    val storageFree: Long?,
    val storageTotal: Long?,
)

/** Formats sizes the way a camera LCD would. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

fun formatSpeed(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0) "—" else formatBytes(bytesPerSecond) + "/s"

fun formatEta(remainingBytes: Long, bytesPerSecond: Long): String {
    if (bytesPerSecond <= 0 || remainingBytes <= 0) return ""
    val seconds = remainingBytes / bytesPerSecond
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}
