package com.achooluv.nikonlink.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.achooluv.nikonlink.NikonLinkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Remote shutter page: header, camera parameters, a fixed 3:2 viewfinder area and
 * the shutter. Live view frames are not available yet, so the viewfinder shows the
 * last shot that was pulled back from the body.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RemoteCaptureScreen(repo: NikonLinkRepository, onClose: () -> Unit) {
    val identity by repo.identity.collectAsStateWithLifecycle()
    val capture by repo.captureState.collectAsStateWithLifecycle()
    val note by repo.captureNote.collectAsStateWithLifecycle()
    val shot by repo.lastShot.collectAsStateWithLifecycle()
    val info by repo.remoteInfo.collectAsStateWithLifecycle()
    val previewBytes by repo.preview.collectAsStateWithLifecycle()
    val liveState by repo.liveView.collectAsStateWithLifecycle()
    val selfTimer by repo.settings.selfTimerSeconds.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var showModels by remember { mutableStateOf(false) }

    BackHandler { onClose() }
    // Remote capture on Nikon bodies lives inside the live-view session: arm it on
    // entry (0x90C2 + 0x9201) and hand the body back on exit.
    LaunchedEffect(Unit) { repo.beginRemote() }
    DisposableEffect(Unit) {
        onDispose {
            repo.endRemote()
            repo.resetCaptureState()
        }
    }

    val busy = capture == NikonLinkRepository.CaptureState.Busy
    // Decoding a few-hundred-KB thumbnail belongs off the main thread, and only when
    // the bytes actually change (not on every recomposition).
    val frame by produceState<ImageBitmap?>(initialValue = null, shot) {
        value = shot?.let { bytes ->
            withContext(Dispatchers.Default) {
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                    .getOrNull()
            }
        }
    }
    val liveFrame by produceState<ImageBitmap?>(initialValue = null, previewBytes) {
        value = previewBytes?.let { bytes ->
            withContext(Dispatchers.Default) {
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                    .getOrNull()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = Paper)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "遥控拍摄",
                    color = Paper,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                )
                Text(
                    identity?.model.orEmpty(),
                    color = NikonLinkTheme.colors.dim,
                    fontSize = 12.sp,
                )
            }
            val context = LocalContext.current
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .combinedClickable(
                        onClick = { showModels = true },
                        onLongClick = {
                            repo.probeWritableProps()
                            android.widget.Toast.makeText(context, "正在探测可写参数，结果见日志", android.widget.Toast.LENGTH_SHORT).show()
                        },
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("机型支持", color = NikonLinkTheme.colors.dim, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ParamCell("挡位", info?.mode ?: "—")
            ParamCell("光圈", info?.aperture ?: "—")
            ParamCell("ISO", info?.iso ?: "—")
            ParamCell("快门", info?.shutter ?: "—")
            ParamCell("焦段", info?.focal ?: "—")
        }

        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 2f)
                .clip(RoundedCornerShape(16.dp))
                .background(InkRaised),
            contentAlignment = Alignment.Center,
        ) {
            val live = liveFrame
            val still = frame
            when {
                live != null -> Image(
                    bitmap = live,
                    contentDescription = "实时取景",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                still != null -> Image(
                    bitmap = still,
                    contentDescription = "刚拍摄的照片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.PhotoCamera,
                        contentDescription = null,
                        tint = PaperDim,
                        modifier = Modifier.size(44.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when (liveState) {
                            NikonLinkRepository.LiveViewState.Starting -> "正在启动取景…"
                            NikonLinkRepository.LiveViewState.Failed -> "未收到取景画面"
                            else -> "等待取景"
                        },
                        color = Paper,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (liveState) {
                            NikonLinkRepository.LiveViewState.Failed ->
                                "这台机型可能不在取景支持列表内，点右上角「机型支持」查看。"
                            else -> "取景画面会在相机就绪后自动出现。"
                        },
                        color = NikonLinkTheme.colors.dim,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("延迟", color = NikonLinkTheme.colors.dim, fontSize = 11.sp)
            listOf(0, 3, 5, 10).forEach { seconds ->
                val selected = selfTimer == seconds
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) NikonYellow else InkRaised)
                        .clickable { repo.settings.setSelfTimerSeconds(seconds) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        if (seconds == 0) "关闭" else "${seconds}s",
                        color = if (selected) Color(0xFF161200) else PaperDim,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            note ?: "轻触快门拍摄",
            modifier = Modifier.fillMaxWidth(),
            color = when (capture) {
                NikonLinkRepository.CaptureState.Failed -> Ember
                NikonLinkRepository.CaptureState.Done -> Mint
                else -> NikonLinkTheme.colors.dim
            },
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))

        Box(
            Modifier.fillMaxWidth().padding(bottom = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(92.dp)
                    .clip(CircleShape)
                    .border(3.dp, if (busy) PaperDim else NikonYellow, CircleShape)
                    .clickable(enabled = !busy) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        repo.capture()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(InkRaised),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.PhotoCamera,
                        contentDescription = "拍摄",
                        tint = if (busy) PaperDim else NikonYellow,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
    }

    if (showModels) LiveViewModelsDialog(onDismiss = { showModels = false })
}

private val LIVE_VIEW_SUPPORTED = listOf(
    "Z9", "Z8", "Z7II", "Z7", "Z6III", "Z6II", "Z6",
    "Z5II", "Z5IIC", "Z50II", "Z30", "Zf", "ZR", "D6", "D780",
)

private val LIVE_VIEW_UNSUPPORTED = listOf("Z5", "Z50", "Zfc")

@Composable
private fun LiveViewModelsDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = InkRaised,
        titleContentColor = Paper,
        textContentColor = PaperDim,
        title = { Text("实时取景支持机型", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("支持（尼康 NX Tether 官方列表）", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(LIVE_VIEW_SUPPORTED.joinToString(" · "), color = PaperDim, fontSize = 12.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                Text("不支持", color = Ember, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(LIVE_VIEW_UNSUPPORTED.joinToString(" · "), color = PaperDim, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "未列出的机型多半可以连接与拍摄，但取景画面可能无法获取。",
                    color = NikonLinkTheme.colors.dim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了", color = NikonYellow) }
        },
    )
}

@Composable
private fun ParamCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, color = NikonLinkTheme.colors.dim, fontSize = 10.sp)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            color = Color(0xFFF2EFE6),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
    }
}
