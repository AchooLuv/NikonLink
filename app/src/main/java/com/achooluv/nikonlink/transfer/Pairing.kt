package com.achooluv.nikonlink.transfer

import com.achooluv.nikonlink.camera.CameraFile
import com.achooluv.nikonlink.camera.FileKind

/**
 * Adds the JPEG sibling of every selected RAW (DSC_0001.NEF → DSC_0001.JPG) so a
 * RAW shoot also lands a preview-friendly JPEG. Already-selected files win.
 */
fun expandRawJpegPairs(files: List<CameraFile>): List<CameraFile> {
    if (files.isEmpty()) return files
    val byBase = files.groupBy { it.baseName }
    val result = LinkedHashMap<Int, CameraFile>()
    files.forEach { result[it.handle] = it }
    files.filter { it.kind == FileKind.RAW }.forEach { raw ->
        byBase[raw.baseName]
            ?.firstOrNull { it.kind == FileKind.JPEG }
            ?.let { result[it.handle] = it }
    }
    return result.values.toList()
}

/**
 * Picks a display name that does not collide inside a MediaStore folder.
 * "DSC_0001.JPG" → "DSC_0001 (1).JPG" when the original already exists.
 */
fun uniqueDisplayName(desired: String, exists: (String) -> Boolean): String {
    if (desired.isBlank()) return desired
    if (!exists(desired)) return desired
    val dot = desired.lastIndexOf('.')
    val stem = if (dot > 0) desired.substring(0, dot) else desired
    val extension = if (dot > 0) desired.substring(dot) else ""
    for (index in 1..99) {
        val candidate = "$stem ($index)$extension"
        if (!exists(candidate)) return candidate
    }
    return "$stem-${System.currentTimeMillis()}$extension"
}
