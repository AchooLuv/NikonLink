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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.zIndex
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
    /**
     * One snapshot state per handle: a freshly decoded bitmap only invalidates the
     * cell that owns it, instead of every visible cell (a single SnapshotStateMap
     * would invalidate all readers on each put).
     */
    private val states = HashMap<Int, MutableState<Bitmap?>>()
    private val order = LinkedHashMap<Int, Long>(32, 0.75f, true)
    private var used = 0L

    fun state(handle: Int): MutableState<Bitmap?> =
        states.getOrPut(handle) { mutableStateOf(null) }

    fun put(handle: Int, bitmap: Bitmap) {
        val size = bitmap.byteCount.toLong()
        used += size - (order[handle] ?: 0L)
        order[handle] = size
        state(handle).value = bitmap
        trim()
    }

    fun markEmpty(handle: Int) {
        state(handle).value = null
        order.remove(handle)?.let { used -= it }
    }

    private fun trim() {
        val iterator = order.entries.iterator()
        while (used > maxBytes && order.size > 1 && iterator.hasNext()) {
            val oldest = iterator.next()
            used -= oldest.value
            states[oldest.key]?.value = null
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
    val deleteState by repo.deleteState.collectAsStateWithLifecycle()
    val libraryTotal by repo.libraryTotal.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var selection by remember { mutableStateOf(setOf<Int>()) }
    var pendingDelete by remember { mutableStateOf<List<CameraFile>?>(null) }
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
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 6.dp),
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
                        else -> "预览使用相机 JPEG 缩略图"
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
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = NikonYellow,
                    )
                } else {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = "刷新",
                        tint = if (connected) Paper else PaperDim,
                    )
                }
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

        Box(Modifier.weight(1f)) {
            if (!connected) {
                EmptyState("先连接相机", "连接成功后，这里会显示存储卡里的照片、视频与 RAW。")
            } else if (visible.isEmpty() && busy) {
                SkeletonGrid()
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
                            load = {
                                repo.thumbnail(file.handle)
                                    ?: if (file.kind == FileKind.MOVIE && repo.isImported(file)) {
                                        repo.videoPoster(file.name)
                                    } else null
                            },
                            enabled = !busy && !deleteState.active,
                            onClick = {
                                selection = if (file.handle in selection) {
                                    selection - file.handle
                                } else {
                                    selection + file.handle
                                }
                            },
                            onPlay = if (repo.isImported(file) && !busy) {
                                {
                                    if (!repo.openMedia(file)) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "本机找不到这个文件，请重新下载",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            } else null,
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

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .zIndex(2f)
                    .padding(bottom = if (selection.isNotEmpty()) 92.dp else 8.dp),
            ) {
                TransferStatusArea(repo)
                DeleteStatusArea(deleteState) { repo.clearDeleteState() }
            }

            if (selection.isNotEmpty()) {
                SelectionBar(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    count = selection.size,
                    bytes = visible.filter { it.handle in selection }.sumOf { it.size },
                    onClear = { selection = emptySet() },
                    enabled = !busy && !deleteState.active,
                    onDownload = {
                        val chosen = visible.filter { it.handle in selection }
                        repo.download(chosen)
                        selection = emptySet()
                    },
                    onDelete = {
                        val chosen = visible.filter { it.handle in selection }
                        pendingDelete = chosen
                    },
                )
            }


            pendingDelete?.let { files ->
                AlertDialog(
                    onDismissRequest = { pendingDelete = null },
                    containerColor = InkRaised,
                    title = {
                        Text(
                            "从相机删除 ${files.size} 个文件？",
                            color = Paper, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            files.take(8).chunked(2).forEach { pair ->
                                Row(Modifier.fillMaxWidth()) {
                                    pair.forEach { file ->
                                        Text(
                                            file.name,
                                            color = PaperDim, fontSize = 12.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                            if (files.size > 8) {
                                Text("等 ${files.size} 个文件", color = PaperDim, fontSize = 11.sp)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            repo.deleteFromCamera(files, repo.settings.deleteLocalCopies.value)
                            selection = emptySet()
                            pendingDelete = null
                        }) {
                            Text("删除", color = Ember, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDelete = null }) {
                            Text("取消", color = PaperDim)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 130.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        userScrollEnabled = false,
    ) {
        items(9) {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(InkCard),
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.6f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(InkCard),
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
    enabled: Boolean = true,
    onClick: () -> Unit,
    onPlay: (() -> Unit)? = null,
) {
    val bitmap = store.state(file.handle).value

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
            .clickable(enabled = enabled, onClick = onClick),
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
                .padding(start = 7.dp, end = 6.dp, top = 6.dp, bottom = 3.dp),
        ) {
            Text(
                file.name,
                color = Paper,
                fontSize = 9.sp,
                lineHeight = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatBytes(file.size), color = PaperDim, fontSize = 8.5.sp, lineHeight = 8.5.sp)
                Spacer(Modifier.weight(1f))
                if (onPlay != null) {
                    Box(
                        Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color(0x33FFFFFF))
                            .clickable { onPlay() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (file.kind == FileKind.MOVIE) Icons.Rounded.PlayArrow else Icons.Rounded.Visibility,
                            contentDescription = if (file.kind == FileKind.MOVIE) "播放视频" else "查看图片",
                            tint = NikonYellow,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
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
    onDelete: () -> Unit,
    enabled: Boolean = true,
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
            TextButton(onClick = onClear, enabled = enabled) { Text("清空", color = PaperDim, fontSize = 12.sp) }
            TextButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Rounded.Delete, null, modifier = Modifier.size(16.dp), tint = Ember)
                Spacer(Modifier.size(4.dp))
                Text("删除", color = Ember, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = onDownload,
                enabled = enabled,
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
private fun DeleteStatusArea(
    state: NikonLinkRepository.DeleteState,
    onClose: () -> Unit,
) {
    if (!state.active && state.message == null) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(InkCard, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.active) "正在删除" else "删除完成",
                color = Paper, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (state.total > 0) "${state.done}/${state.total}" else "",
                color = PaperDim, fontSize = 11.sp,
            )
            if (!state.active) {
                TextButton(onClick = onClose) { Text("关闭", color = PaperDim, fontSize = 12.sp) }
            }
        }
        if (state.active && state.currentName.isNotBlank()) {
            Text(state.currentName, color = PaperDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        if (state.active) {
            ThinProgress(if (state.total > 0) state.done.toFloat() / state.total else 0f)
        } else {
            Text(
                state.message.orEmpty(),
                color = if (state.failed > 0) Ember else Mint,
                fontSize = 12.sp,
            )
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
            done = progress.doneCount,
            total = progress.doneCount + progress.failedCount,
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
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(InkCard, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "正在下载",
                color = Paper, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text("$index / $count", color = PaperDim, fontSize = 11.sp)
            TextButton(onClick = onCancel) { Text("取消", color = PaperDim, fontSize = 12.sp) }
        }
        if (name.isNotBlank()) {
            Text(name, color = PaperDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        ThinProgress(fraction)
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                append("${(fraction * 100).toInt()}% · ${formatSpeed(speed)}")
                if (eta.isNotBlank()) append(" · 剩余 $eta")
            },
            color = NikonLinkTheme.colors.dim,
            fontSize = 10.5.sp,
        )
    }
}

@Composable
private fun TransferResultBar(
    message: String,
    done: Int,
    total: Int,
    failed: Int,
    galleryUri: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(InkCard, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "下载完成",
                color = Paper, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (total > 0) Text("$done/$total", color = PaperDim, fontSize = 11.sp)
            TextButton(onClick = onDismiss) { Text("关闭", color = PaperDim, fontSize = 12.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                message,
                color = if (failed > 0) Ember else Mint,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f, fill = false),
            )
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
                    Text("重试失败项", color = Mint, fontSize = 12.sp)
                }
            }
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
