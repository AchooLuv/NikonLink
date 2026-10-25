package com.achooluv.nikonlink.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.achooluv.nikonlink.ConnState
import com.achooluv.nikonlink.NikonLinkRepository
import com.achooluv.nikonlink.camera.CameraFile
import com.achooluv.nikonlink.camera.FileKind
import com.achooluv.nikonlink.camera.formatBytes
import com.achooluv.nikonlink.camera.formatEta
import com.achooluv.nikonlink.camera.formatSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class LibraryFilter(val label: String) {
    ALL("全部"),
    NEW("未下载"),
    PHOTO("照片"),
    VIDEO("视频"),
    RAW("RAW"),
    MARKED("相机标记"),
}

/**
 * Bounded bitmap cache for the grid. Evicted cells fall back to the repository's
 * byte cache and are decoded again on demand, so scrolling a 5000-shot card
 * cannot balloon into hundreds of megabytes of bitmaps.
 */
private class ThumbStore(private val maxBytes: Long = 32L * 1024 * 1024) {
    val entries = mutableStateMapOf<Int, Bitmap?>()
    private val order = LinkedHashMap<Int, Long>(32, 0.75f, true)
    private var used = 0L

    operator fun get(handle: Int): Bitmap? = entries[handle]

    fun put(handle: Int, bitmap: Bitmap) {
        val size = bitmap.byteCount.toLong()
        if (size > maxBytes) {
            entries[handle] = null
            return
        }
        used += size - (order[handle] ?: 0L)
        order[handle] = size
        entries[handle] = bitmap
        trim()
    }

    fun markEmpty(handle: Int) {
        entries[handle] = null
        order.remove(handle)?.let { used -= it }
    }

    private fun trim() {
        val iterator = order.entries.iterator()
        while (used > maxBytes && order.size > 1 && iterator.hasNext()) {
            val oldest = iterator.next()
            used -= oldest.value
            entries.remove(oldest.key)
            iterator.remove()
        }
    }
}

@Composable
fun BrowseScreen(repo: NikonLinkRepository) {
    val state by repo.state.collectAsStateWithLifecycle()
    val files by repo.files.collectAsStateWithLifecycle()
    val busy by repo.libraryBusy.collectAsStateWithLifecycle()
    val hasMore by repo.hasMore.collectAsStateWithLifecycle()
    val importsRev by repo.importsVersion.collectAsStateWithLifecycle()
    val queue by repo.transferQueue.collectAsStateWithLifecycle()
    val queueBusy by repo.queueBusy.collectAsStateWithLifecycle()
    val queueSupported by repo.queueSupported.collectAsStateWithLifecycle()

    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var selection by remember { mutableStateOf(setOf<Int>()) }
    val thumbs = remember { ThumbStore() }

    val connected = state is ConnState.Connected
    val imported = remember(files, importsRev) {
        files.filter { repo.isImported(it) }.map { it.handle }.toSet()
    }
    val visible = remember(files, filter, queue, imported) {
        val source = if (filter == LibraryFilter.MARKED) queue else files
        source.filter { file ->
            when (filter) {
                LibraryFilter.ALL, LibraryFilter.MARKED -> true
                LibraryFilter.NEW -> file.handle !in imported
                LibraryFilter.PHOTO -> file.kind == FileKind.JPEG
                LibraryFilter.VIDEO -> file.kind == FileKind.MOVIE
                LibraryFilter.RAW -> file.kind == FileKind.RAW
            }
        }
    }

    LaunchedEffect(filter, connected) {
        if (connected && filter == LibraryFilter.MARKED) repo.refreshTransferQueue()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("浏览", color = Paper, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(
                    when {
                        !connected -> "相机未连接"
                        busy && files.isEmpty() -> "正在读取存储卡…"
                        filter == LibraryFilter.MARKED ->
                            if (queueSupported) "相机上标记了 ${queue.size} 个 · 按相机 i 键可标记" else "这台相机不支持标记队列"
                        filter == LibraryFilter.NEW -> "${visible.size} 个未下载 · 可全选后一键下载"
                        else -> "${files.size} 个文件 · 预览使用相机 JPEG 缩略图"
                    },
                    color = NikonLinkTheme.colors.dim,
                    fontSize = 12.sp,
                )
            }
            if (connected && visible.isNotEmpty()) {
                TextButton(onClick = { selection = visible.map { it.handle }.toSet() }) {
                    Text("全选", color = PaperDim, fontSize = 12.sp)
                }
            }
            IconButton(
                onClick = {
                    if (filter == LibraryFilter.MARKED) repo.refreshTransferQueue() else repo.refreshLibrary()
                },
                enabled = connected && !busy && !queueBusy,
            ) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "刷新",
                    tint = if (connected) Paper else PaperDim,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label, fontSize = 12.sp) },
                    shape = RoundedCornerShape(20.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = NikonLinkTheme.colors.card,
                        labelColor = PaperDim,
                        selectedContainerColor = NikonYellow,
                        selectedLabelColor = Color(0xFF161200),
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = filter == f,
                        borderColor = NikonLinkTheme.colors.line,
                        selectedBorderColor = NikonYellow,
                    ),
                )
            }
        }

        // Collected in its own leaf so chunk-level progress updates never recompose
        // the grid (that was the main source of scroll jank).
        TransferStatusArea(repo)

        Box(Modifier.weight(1f)) {
            if (!connected) {
                EmptyState("先连接相机", "连接成功后，这里会显示存储卡里的照片、视频与 RAW。")
            } else if (visible.isEmpty() && !busy) {
                if (filter == LibraryFilter.MARKED) {
                    EmptyState(
                        if (queueSupported) "相机上没有标记" else "这台相机不支持标记队列",
                        if (queueSupported) {
                            "在相机上按 i 键（或回放菜单）选择「发送到智能设备」，再回来点右上角刷新。"
                        } else {
                            "改用「全部」手动挑选，或在设置里打开自动拉取。"
                        },
                    )
                } else {
                    EmptyState("没有可下载的文件", "换个筛选条件，或在相机上拍一张再刷新。")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 130.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(visible, key = { it.handle }) { file ->
                        ThumbCell(
                            file = file,
                            store = thumbs,
                            selected = file.handle in selection,
                            imported = file.handle in imported,
                            load = { repo.thumbnail(file.handle) },
                            onClick = {
                                selection = if (file.handle in selection) {
                                    selection - file.handle
                                } else {
                                    selection + file.handle
                                }
                            },
                        )
                    }

                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            if (hasMore) {
                                TextButton(onClick = { repo.loadMore() }, enabled = !busy) {
                                    Text(if (busy) "加载中…" else "加载更多", color = NikonYellow, fontSize = 13.sp)
                                }
                            } else if (visible.isNotEmpty()) {
                                Text("已经是全部文件", color = PaperDim, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            if (selection.isNotEmpty()) {
                SelectionBar(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    count = selection.size,
                    bytes = visible.filter { it.handle in selection }.sumOf { it.size },
                    onClear = { selection = emptySet() },
                    onDownload = {
                        val chosen = visible.filter { it.handle in selection }
                        repo.download(chosen)
                        selection = emptySet()
                    },
                )
            }
        }
    }
}

@Composable
private fun ThumbCell(
    file: CameraFile,
    store: ThumbStore,
    selected: Boolean,
    imported: Boolean,
    load: suspend () -> ByteArray?,
    onClick: () -> Unit,
) {
    val bitmap = store[file.handle]

    // Re-runs when the entry was never attempted (null + no record) or when it was
    // evicted from the bounded cache; failures are recorded so they do not loop.
    LaunchedEffect(file.handle, bitmap == null) {
        if (bitmap == null) {
            val bytes = load()
            val decoded = bytes?.let { decodeThumbnail(it) }
            if (decoded != null) store.put(file.handle, decoded) else store.markEmpty(file.handle)
        }
    }

    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(InkCard)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) NikonYellow else NikonLinkTheme.colors.line,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = file.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    file.extension.ifBlank { "IMG" },
                    color = PaperDim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val badge = when (file.kind) {
                FileKind.RAW -> "RAW"
                FileKind.MOVIE -> "MOV"
                FileKind.JPEG -> "JPG"
                FileKind.OTHER -> file.extension
            }
            Badge(badge, filled = selected)
            if (imported) {
                Box(
                    Modifier
                        .size(18.dp)
                        .background(Color(0xCC0B0B0C), CircleShape)
                        .border(1.dp, Mint.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = "已下载",
                        tint = Mint,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0xB3000000))
                .padding(horizontal = 7.dp, vertical = 4.dp),
        ) {
            Text(
                file.name,
                color = Paper,
                fontSize = 9.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(formatBytes(file.size), color = PaperDim, fontSize = 9.sp)
        }
    }
}

@Composable
private fun SelectionBar(
    modifier: Modifier = Modifier,
    count: Int,
    bytes: Long,
    onClear: () -> Unit,
    onDownload: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        shape = RoundedCornerShape(20.dp),
        color = InkRaised,
        border = androidx.compose.foundation.BorderStroke(1.dp, NikonLinkTheme.colors.line),
        shadowElevation = 12.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("已选 $count 个", color = Paper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("共 ${formatBytes(bytes)}", color = PaperDim, fontSize = 11.sp)
            }
            TextButton(onClick = onClear) { Text("清空", color = PaperDim, fontSize = 12.sp) }
            Button(
                onClick = onDownload,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NikonYellow,
                    contentColor = Color(0xFF161200),
                ),
            ) {
                Icon(Icons.Rounded.Download, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("下载", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TransferStatusArea(repo: NikonLinkRepository) {
    val progress by repo.progress.collectAsStateWithLifecycle()
    val failed by repo.failedFiles.collectAsStateWithLifecycle()
    val lastUri by repo.lastUri.collectAsStateWithLifecycle()

    if (progress.active) {
        DownloadBanner(
            name = progress.fileName,
            fraction = progress.totalFraction,
            speed = progress.speed,
            eta = formatEta(progress.totalSize - progress.totalBytes, progress.speed),
            index = progress.index,
            count = progress.count,
            onCancel = { repo.cancelDownloads() },
        )
    } else if (progress.message != null && (progress.doneCount > 0 || failed.isNotEmpty())) {
        TransferResultBar(
            message = progress.message ?: "",
            failed = failed.size,
            galleryUri = lastUri,
            onRetry = { repo.retryFailed() },
            onDismiss = { repo.clearTransferResult() },
        )
    }
}

@Composable
private fun DownloadBanner(
    name: String,
    fraction: Float,
    speed: Long,
    eta: String,
    index: Int,
    count: Int,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(InkCard, RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        name,
                        color = Paper,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text("$index / $count", color = PaperDim, fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                ThinProgress(fraction)
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append("${(fraction * 100).toInt()}% · ${formatSpeed(speed)}")
                        if (eta.isNotBlank()) append(" · 剩余 $eta")
                    },
                    color = NikonLinkTheme.colors.dim,
                    fontSize = 10.5.sp,
                )
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Rounded.Close, contentDescription = "取消", tint = PaperDim, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun TransferResultBar(
    message: String,
    failed: Int,
    galleryUri: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(InkCard, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Text(message, color = Paper, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (galleryUri != null) {
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(galleryUri),
                                ),
                            )
                        }
                    },
                ) {
                    Text("打开相册", color = NikonYellow, fontSize = 12.sp)
                }
            }
            if (failed > 0) {
                TextButton(onClick = onRetry) {
                    Text("重试失败项 ($failed)", color = Mint, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("关闭", color = PaperDim, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = Paper, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(body, color = PaperDim, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

private suspend fun decodeThumbnail(bytes: ByteArray): Bitmap? = withContext(Dispatchers.Default) {
    if (bytes.isEmpty()) return@withContext null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    val target = 360
    while (bounds.outWidth / sample > target) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
}
