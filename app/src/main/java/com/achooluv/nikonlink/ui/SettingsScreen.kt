@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.achooluv.nikonlink.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.achooluv.nikonlink.BuildConfig
import com.achooluv.nikonlink.NikonLinkRepository
import com.achooluv.nikonlink.camera.formatBytes
import com.achooluv.nikonlink.data.SettingsStore
import com.achooluv.nikonlink.update.UpdateChecker
import com.achooluv.nikonlink.update.UpdateInfo
import com.achooluv.nikonlink.update.UpdateManager

@Composable
fun SettingsScreen(repo: NikonLinkRepository) {
    val settings = repo.settings
    val autoPull by settings.autoPull.collectAsStateWithLifecycle()
    val keepAlive by settings.keepAlive.collectAsStateWithLifecycle()
    val keepAliveSeconds by settings.keepAliveSeconds.collectAsStateWithLifecycle()
    val rawThumbs by settings.showRawThumbnails.collectAsStateWithLifecycle()
    val pairJpeg by settings.pairJpeg.collectAsStateWithLifecycle()
    val chunk by settings.chunkSize.collectAsStateWithLifecycle()
    val eventsSupported by repo.eventsSupported.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var confirmClear by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    // All panels start collapsed; the header summary tells you what is inside.
    var openTransport by rememberSaveable { mutableStateOf(false) }
    var openConnection by rememberSaveable { mutableStateOf(false) }
    var openStorage by rememberSaveable { mutableStateOf(false) }
    var openDiagnostics by rememberSaveable { mutableStateOf(false) }

    val versionName = BuildConfig.VERSION_NAME
    val updates = remember { UpdateManager.get(context) }
    val updateState by updates.state.collectAsStateWithLifecycle()
    var showUpdate by remember { mutableStateOf(false) }

    // Quiet check once per session; tapping the version row forces a new request.
    LaunchedEffect(Unit) { updates.check(versionName) }

    // The moment the APK lands, hand it to the system installer (user still confirms).
    // Keyed on the file, not the whole state, so download progress does not restart it.
    val readyFile = (updateState as? UpdateManager.State.Ready)?.file
    LaunchedEffect(readyFile) {
        if (readyFile != null) updates.installPending(context)
    }

    // Coming back from "install unknown apps" settings: continue the download.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                updates.state.value is UpdateManager.State.NeedsPermission
            ) {
                updates.download(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(6.dp))
            Text("设置", color = Paper, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text("只保留必要选项", color = NikonLinkTheme.colors.dim, fontSize = 12.sp)
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 120.dp),
        ) {
            Spacer(Modifier.height(18.dp))

            CollapsibleSection(
                title = "传输",
                summary = "自动拉取${if (autoPull) "开启" else "关闭"} · 分块 ${SettingsStore.chunkLabel(chunk)}",
                expanded = openTransport,
                onToggle = { openTransport = !openTransport },
            ) {
                NikonCard {
                    SettingSwitch(
                        title = "拍摄后自动拉取",
                        subtitle = if (eventsSupported) {
                            "相机拍到新照片时自动下载到手机。默认关闭；开启时只拉之后拍的照片，不会把卡里旧照片一起下载。"
                        } else {
                            "这台相机没有开放 PTP/IP 事件通道，自动拉取不可用；仍可在「浏览」里手动下载。"
                        },
                        checked = autoPull,
                        onCheckedChange = { repo.setAutoPull(it) },
                    )
                    HorizontalDivider(
                        color = NikonLinkTheme.colors.line,
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                    Caption("分块大小")
                    Spacer(Modifier.height(8.dp))
                    // Four equal segments: never wraps, even on narrow screens or large fonts.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SettingsStore.CHUNK_CHOICES.forEach { size ->
                            val selected = chunk == size
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(if (selected) NikonYellow else Ink)
                                    .border(
                                        width = 1.dp,
                                        color = if (selected) NikonYellow else NikonLinkTheme.colors.line,
                                        shape = RoundedCornerShape(11.dp),
                                    )
                                    .clickable { settings.setChunkSize(size) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = SettingsStore.chunkLabel(size),
                                    color = if (selected) Color(0xFF161200) else PaperDim,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "块越大往返次数越少、速度越快；网络不稳时用 1–2 MiB 更平滑。断点续传始终按块边界恢复。",
                        color = PaperDim,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }

            CollapsibleSection(
                title = "连接",
                summary = if (keepAlive) {
                    "心跳 ${keepAliveSeconds}s · 配对 JPEG ${if (pairJpeg) "开" else "关"}"
                } else {
                    "心跳关闭 · 掉线不自动重连"
                },
                expanded = openConnection,
                onToggle = { openConnection = !openConnection },
            ) {
                NikonCard {
                    SettingSwitch(
                        title = "保持连接活跃",
                        subtitle = "定期发一次轻量心跳，掉线后自动重连（指数退避，最长 30 秒一次）。信号弱时把间隔调大更省电。",
                        checked = keepAlive,
                        onCheckedChange = {
                            settings.setKeepAlive(it)
                            repo.restartKeepAlive()
                        },
                    )
                    if (keepAlive) {
                        Spacer(Modifier.height(10.dp))
                        Caption("心跳间隔")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(10, 15, 30, 60).forEach { seconds ->
                                FilterChip(
                                    selected = keepAliveSeconds == seconds,
                                    onClick = {
                                        settings.setKeepAliveSeconds(seconds)
                                        repo.restartKeepAlive()
                                    },
                                    label = { Text("${seconds}s", fontSize = 12.sp) },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = Ink,
                                        labelColor = PaperDim,
                                        selectedContainerColor = NikonYellow,
                                        selectedLabelColor = Color(0xFF161200),
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = keepAliveSeconds == seconds,
                                        borderColor = NikonLinkTheme.colors.line,
                                        selectedBorderColor = NikonYellow,
                                    ),
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        color = NikonLinkTheme.colors.line,
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                    SettingSwitch(
                        title = "RAW 使用相机缩略图",
                        subtitle = "RAW 只下载完整文件不解码；预览统一用相机内嵌 JPEG 缩略图。",
                        checked = rawThumbs,
                        onCheckedChange = { settings.setShowRawThumbnails(it) },
                    )
                    HorizontalDivider(
                        color = NikonLinkTheme.colors.line,
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                    SettingSwitch(
                        title = "RAW 顺带下载配对 JPEG",
                        subtitle = "选中 DSC_0001.NEF 时，如果卡里有同名的 DSC_0001.JPG 就一起下载，方便直接预览与分享。",
                        checked = pairJpeg,
                        onCheckedChange = { settings.setPairJpeg(it) },
                    )
                }
            }

            CollapsibleSection(
                title = "存储",
                summary = "Pictures / Movies · 下载记录",
                expanded = openStorage,
                onToggle = { openStorage = !openStorage },
            ) {
                NikonCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("保存位置", color = Paper, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Text("Pictures/NikonLink · Movies/NikonLink", color = PaperDim, fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = NikonLinkTheme.colors.line)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "下载记录（用于跳过已下载文件）",
                        color = PaperDim,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { confirmClear = true }.padding(vertical = 4.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "重置连接与全部记录",
                        color = PaperDim,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { confirmReset = true }.padding(vertical = 4.dp),
                    )
                }
            }

            CollapsibleSection(
                title = "诊断",
                summary = "复制诊断信息 · 清空日志",
                expanded = openDiagnostics,
                onToggle = { openDiagnostics = !openDiagnostics },
            ) {
                NikonCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("复制诊断信息", color = Paper, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "包含相机型号、固件、连接步骤与最近 150 条协议日志。出问题时贴到 GitHub Issue 即可定位。",
                                color = PaperDim,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "复制到剪贴板",
                        color = NikonYellow,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            clipboard.setText(
                                androidx.compose.ui.text.AnnotatedString(
                                    com.achooluv.nikonlink.Diagnostics.dump(context),
                                ),
                            )
                            android.widget.Toast.makeText(context, "诊断信息已复制", android.widget.Toast.LENGTH_SHORT).show()
                        }.padding(vertical = 4.dp),
                    )
                    HorizontalDivider(color = NikonLinkTheme.colors.line, modifier = Modifier.padding(vertical = 10.dp))
                    Text(
                        "清空日志",
                        color = PaperDim,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            com.achooluv.nikonlink.Diagnostics.clear()
                            android.widget.Toast.makeText(context, "日志已清空", android.widget.Toast.LENGTH_SHORT).show()
                        }.padding(vertical = 4.dp),
                    )
                }
            }

        Spacer(Modifier.height(20.dp))
        SectionTitle("关于")
        Spacer(Modifier.height(10.dp))
        NikonCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("NikonLink", color = Paper, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Mono(
                        "v$versionName",
                        color = PaperDim,
                        size = 12,
                    )
                    UpdateBadge(
                        state = updateState,
                        onClick = {
                            if (updateState is UpdateManager.State.Idle) {
                                updates.check(versionName, force = true)
                            } else {
                                showUpdate = true
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "尼康 Z 系列无线图传 · PTP/IP 直连 · 仅 arm64",
                color = PaperDim,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "github.com/AchooLuv/NikonLink",
                color = NikonYellow,
                fontSize = 12.sp,
                modifier = Modifier.clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/AchooLuv/NikonLink")),
                        )
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "协议实现参考了开源项目 AeroShutter（MIT）对 PTP/IP 与 Nikon 厂商操作的逆向成果。",
                color = PaperDim.copy(alpha = 0.75f),
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
            )
        }

        Spacer(Modifier.height(40.dp))
        }
    }

    if (showUpdate && updateState !is UpdateManager.State.Idle) {
        UpdateDialog(
            currentVersion = versionName,
            state = updateState,
            onDismiss = { showUpdate = false },
            onStartDownload = { updates.download(context) },
            onCancelDownload = { updates.cancelDownload() },
            onInstallReady = {
                val ready = updateState as? UpdateManager.State.Ready
                if (ready != null) updates.install(context, ready.file)
            },
            onGrantPermission = { updates.openInstallPermission(context) },
            onOpenReleases = { updates.openReleasesPage(context) },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = InkRaised,
            title = { Text("重置连接与记录？", color = Paper, fontSize = 16.sp) },
            text = {
                Text(
                    "会断开当前连接，并清空保存的 SSID/密码、偏好设置和「已下载」记录，同时删除未完成的断点文件。" +
                        "不会删除相册里已经下载好的照片。",
                    color = PaperDim,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    repo.resetAll()
                }) { Text("重置", color = NikonYellow) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("取消", color = PaperDim) }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = InkRaised,
            title = { Text("清除下载记录？", color = Paper, fontSize = 16.sp) },
            text = {
                Text(
                    "只会忘记「哪些照片已经下载过」，不会删除手机里已保存的文件。",
                    color = PaperDim,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmClear = false }) { Text("知道了", color = NikonYellow) }
            },
        )
    }
}

/** Kept as a helper so the clear-records row reads as destructive but stays calm. */
@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Paper, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = PaperDim, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF161200),
                checkedTrackColor = NikonYellow,
                uncheckedThumbColor = PaperDim,
                uncheckedTrackColor = InkLine,
            ),
        )
    }
}

/** Version pill: solid when a new release is waiting, tinted while downloading. */
@Composable
private fun UpdateBadge(state: UpdateManager.State, onClick: () -> Unit) {
    val (label, bg, fg) = when (state) {
        is UpdateManager.State.Available ->
            Triple("有新版本 v${state.info.versionName}", NikonYellow, Color(0xFF161200))
        is UpdateManager.State.Downloading ->
            Triple("下载中 ${"%.0f".format(downloadFraction(state) * 100)}%", NikonYellow.copy(alpha = 0.16f), NikonYellow)
        is UpdateManager.State.Ready -> Triple("待安装", Mint.copy(alpha = 0.16f), Mint)
        is UpdateManager.State.NeedsPermission -> Triple("需授权", Ember.copy(alpha = 0.16f), Ember)
        is UpdateManager.State.Failed -> Triple("下载失败", Ember.copy(alpha = 0.16f), Ember)
        else -> return
    }
    Spacer(Modifier.width(8.dp))
    Text(
        label,
        color = fg,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun UpdateDialog(
    currentVersion: String,
    state: UpdateManager.State,
    onDismiss: () -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstallReady: () -> Unit,
    onGrantPermission: () -> Unit,
    onOpenReleases: () -> Unit,
) {
    val info: UpdateInfo = when (state) {
        is UpdateManager.State.Available -> state.info
        is UpdateManager.State.Downloading -> state.info
        is UpdateManager.State.Ready -> state.info
        is UpdateManager.State.Failed -> state.info ?: return
        is UpdateManager.State.NeedsPermission -> state.info
        else -> return
    }
    // Parsing the release body is pure but not free; do it once per version, not on
    // every progress tick.
    val notes = remember(info.versionName) { UpdateChecker.readableNotes(info.notes) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 400.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(InkRaised)
                .padding(20.dp),
        ) {
            Text("版本更新", color = Paper, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Mono("v$currentVersion", color = PaperDim, size = 13)
                Text("  →  ", color = PaperDim, fontSize = 13.sp)
                Mono("v${info.versionName}", color = NikonYellow, size = 13)
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = NikonLinkTheme.colors.line)
            Spacer(Modifier.height(12.dp))
            Text("更新内容", color = PaperDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.heightIn(max = 200.dp)) {
                item {
                    Text(
                        notes.ifBlank { "该版本没有填写更新说明。" },
                        color = Paper,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = NikonLinkTheme.colors.line)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (info.sizeBytes > 0) "安装包 ${formatBytes(info.sizeBytes)}" else "安装包",
                    color = PaperDim,
                    fontSize = 11.sp,
                )
                Text(progressLabel(state), color = PaperDim, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            // Hand-drawn bar: M3's indicator paints a stop dot even at 0%, which
            // reads as a stray dot on the "waiting to download" state.
            val fraction = if (state is UpdateManager.State.Ready) 1f else downloadFraction(state)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(InkLine),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(if (state is UpdateManager.State.Failed) Ember else NikonYellow),
                )
            }

            val note = when (state) {
                is UpdateManager.State.Failed -> state.message
                is UpdateManager.State.NeedsPermission -> "需要在系统设置里允许「NikonLink」安装应用，返回后会自动继续"
                else -> null
            }
            if (note != null) {
                Spacer(Modifier.height(8.dp))
                Text(note, color = Ember, fontSize = 11.sp, lineHeight = 16.sp)
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val secondary = when (state) {
                    is UpdateManager.State.Downloading -> "取消下载" to onCancelDownload
                    is UpdateManager.State.Failed -> "打开发布页" to onOpenReleases
                    else -> "取消" to onDismiss
                }
                TextButton(onClick = secondary.second) {
                    Text(secondary.first, color = PaperDim, fontSize = 13.sp)
                }
                Spacer(Modifier.width(4.dp))
                val primary = when (state) {
                    is UpdateManager.State.Available -> "更新并安装" to onStartDownload
                    is UpdateManager.State.Downloading -> "后台下载" to onDismiss
                    is UpdateManager.State.Ready -> "安装" to onInstallReady
                    is UpdateManager.State.Failed -> "重试" to onStartDownload
                    is UpdateManager.State.NeedsPermission -> "去设置" to onGrantPermission
                    else -> "关闭" to onDismiss
                }
                Button(
                    onClick = primary.second,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NikonYellow,
                        contentColor = Color(0xFF161200),
                    ),
                ) {
                    Text(primary.first, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun downloadFraction(state: UpdateManager.State): Float {
    val downloading = state as? UpdateManager.State.Downloading ?: return 0f
    val total = downloading.info.sizeBytes
    if (total <= 0) return 0f
    return (downloading.bytes.toFloat() / total).coerceIn(0f, 1f)
}

private fun progressLabel(state: UpdateManager.State): String = when (state) {
    is UpdateManager.State.Available -> "等待下载"
    is UpdateManager.State.Downloading -> "已下载 ${formatBytes(state.bytes)}"
    is UpdateManager.State.Ready -> "下载完成"
    is UpdateManager.State.Failed -> "下载失败"
    is UpdateManager.State.NeedsPermission -> "等待授权"
    else -> ""
}

/**
 * A settings group that starts folded, showing a one-line summary of its current
 * state so the page stays scannable without hiding what is configured.
 */
@Composable
private fun CollapsibleSection(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onToggle)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = Paper,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                )
                if (!expanded) {
                    Spacer(Modifier.height(3.dp))
                    Text(summary, color = NikonLinkTheme.colors.dim, fontSize = 11.sp)
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = PaperDim,
                modifier = Modifier.size(22.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                content()
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}
