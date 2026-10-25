package com.achooluv.nikonlink.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.achooluv.nikonlink.camera.formatSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class LibraryFilter(val label: String) {
    ALL("全部"),
    PHOTO("照片"),
    VIDEO("视频"),
    RAW("RAW"),
}

@Composable
fun BrowseScreen(repo: NikonLinkRepository) {
    val state by repo.state.collectAsStateWithLifecycle()
    val files by repo.files.collectAsStateWithLifecycle()
    val busy by repo.libraryBusy.collectAsStateWithLifecycle()
    val hasMore by repo.hasMore.collectAsStateWithLifecycle()
    val progress by repo.progress.collectAsStateWithLifecycle()

    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var selection by remember { mutableStateOf(setOf<Int>()) }
    val thumbs = remember { mutableStateMapOf<Int, Bitmap?>() }
    val gridState = rememberLazyGridState()

    val connected = state is ConnState.Connected
    val visible = remember(files, filter) {
        files.filter { file ->
            when (filter) {
                LibraryFilter.ALL -> true
                LibraryFilter.PHOTO -> file.kind == FileKind.JPEG
                LibraryFilter.VIDEO -> file.kind == FileKind.MOVIE
                LibraryFilter.RAW -> file.kind == FileKind.RAW
            }
        }
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
                        else -> "${files.size} 个文件 · 相册预览走 JPEG 缩略图"
                    },
                    color = NikonLinkTheme.colors.dim,
                    fontSize = 12.sp,
                )
            }
            IconButton(onClick = { repo.refreshLibrary() }, enabled = connected && !busy) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "刷新",
                    tint = if (connected) Paper else PaperDim,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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

        if (progress.active) {
            DownloadBanner(
                name = progress.fileName,
                fraction = progress.totalFraction,
                speed = progress.speed,
                index = progress.index,
                count = progress.count,
            )
        }

        Box(Modifier.weight(1f)) {
            if (!connected) {
                EmptyState("先连接相机", "连接成功后，这里会显示存储卡里的照片、视频与 RAW。")
            } else if (visible.isEmpty() && !busy) {
                EmptyState("没有可下载的文件", "换个筛选条件，或在相机上拍一张再刷新。")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 130.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(visible, key = { it.handle }) { file ->
                        ThumbCell(
                            file = file,
                            bitmap = thumbs[file.handle],
                            selected = file.handle in selection,
                            imported = repo.isImported(file),
                            onLoad = {
                                if (!thumbs.containsKey(file.handle)) {
                                    thumbs[file.handle] = null
                                    repo.thumbnail(file.handle)?.let { bytes ->
                                        thumbs[file.handle] = decodeThumbnail(bytes)
                                    }
                                }
                            },
                            onClick = {
                                selection = if (file.handle in selection) selection - file.handle else selection + file.handle
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
    bitmap: Bitmap?,
    selected: Boolean,
    imported: Boolean,
    onLoad: suspend () -> Unit,
    onClick: () -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(file.handle) { onLoad() }

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
            Text(
                formatBytes(file.size),
                color = PaperDim,
                fontSize = 9.sp,
            )
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
private fun DownloadBanner(
    name: String,
    fraction: Float,
    speed: Long,
    index: Int,
    count: Int,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(InkCard, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
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
            "${(fraction * 100).toInt()}% · ${formatSpeed(speed)}",
            color = NikonLinkTheme.colors.dim,
            fontSize = 10.5.sp,
        )
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
