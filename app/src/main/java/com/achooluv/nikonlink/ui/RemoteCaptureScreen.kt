package com.achooluv.nikonlink.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Remote shutter page: live view fills a 3:2 frame, camera parameters sit in an
 * aligned on-image overlay (tap one to drag a ruler), shutter below.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
    val controls by repo.controls.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    var showModels by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<NikonLinkRepository.CameraControl?>(null) }
    var editingDelay by remember { mutableStateOf(false) }

    BackHandler { onClose() }
    LaunchedEffect(Unit) { repo.beginRemote() }
    DisposableEffect(Unit) {
        onDispose {
            repo.endRemote()
            repo.resetCaptureState()
        }
    }

    val busy = capture == NikonLinkRepository.CaptureState.Busy
    val frame by produceState<ImageBitmap?>(initialValue = null, shot) {
        value = shot?.let { bytes ->
            withContext(Dispatchers.Default) { runCatching { decodeSampled(bytes, 1400)?.asImageBitmap() }.getOrNull() }
        }
    }
    val liveFrame by produceState<ImageBitmap?>(initialValue = null, previewBytes) {
        value = previewBytes?.let { bytes ->
            withContext(Dispatchers.Default) { runCatching { decodeSampled(bytes, 1200)?.asImageBitmap() }.getOrNull() }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
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

        Spacer(Modifier.height(12.dp))
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
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (liveState) {
                            NikonLinkRepository.LiveViewState.Starting -> "正在启动取景…"
                            NikonLinkRepository.LiveViewState.Failed -> "未收到取景画面"
                            else -> "等待取景"
                        },
                        color = Paper,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (liveState == NikonLinkRepository.LiveViewState.Failed) {
                            "可点右上角「机型支持」查看支持列表"
                        } else {
                            "相机就绪后自动显示"
                        },
                        color = NikonLinkTheme.colors.dim,
                        fontSize = 11.sp,
                    )
                }
            }

            val modeControl = controls.find { it.key == "mode" }
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .then(
                        if (modeControl?.writable == true) Modifier.clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            editing = modeControl
                        } else Modifier,
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    modeControl?.let { controlLabel(it) } ?: (info?.mode ?: "—"),
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            SelfTimerPill(
                seconds = selfTimer,
                onClick = { editingDelay = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )

            // Camera-style OSD: five equal columns, label on top, value below.
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))),
                    )
                    .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                val cells = listOf(
                    Triple("aperture", "光圈", info?.aperture),
                    Triple("iso", "ISO", info?.iso),
                    Triple("shutter", "快门", info?.shutter),
                )
                cells.forEach { (key, label, fallback) ->
                    val control = controls.find { it.key == key }
                    val writable = control?.writable == true
                    OsdCell(
                        label = label,
                        value = control?.let { controlLabel(it) } ?: (fallback ?: "—"),
                        enabled = writable,
                        onClick = if (writable) {
                            {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                editing = control
                            }
                        } else null,
                        modifier = Modifier.weight(1f),
                    )
                }
                OsdCell(
                    label = "焦段",
                    value = info?.focal ?: "—",
                    enabled = false,
                    onClick = null,
                    modifier = Modifier.weight(1f),
                )
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
        Spacer(Modifier.height(14.dp))

        Box(
            Modifier.fillMaxWidth().padding(bottom = 26.dp),
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
    editing?.let { control ->
        ModalBottomSheet(
            onDismissRequest = { editing = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = InkRaised,
        ) {
            RulerPicker(
                label = control.label,
                options = control.options,
                currentRaw = control.current,
                haptics = haptics,
                onCommit = { raw ->
                    repo.setControl(control, raw)
                    editing = null
                },
            )
        }
    }
    if (editingDelay) {
        ModalBottomSheet(
            onDismissRequest = { editingDelay = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = InkRaised,
        ) {
            RulerPicker(
                label = "延迟拍摄",
                options = listOf(
                    NikonLinkRepository.ParamOption(0, "关闭"),
                    NikonLinkRepository.ParamOption(3, "3 秒"),
                    NikonLinkRepository.ParamOption(5, "5 秒"),
                    NikonLinkRepository.ParamOption(10, "10 秒"),
                ),
                currentRaw = selfTimer,
                haptics = haptics,
                onCommit = { raw ->
                    repo.settings.setSelfTimerSeconds(raw)
                    editingDelay = false
                },
            )
        }
    }
}

/** Self-timer entry: timer icon when off, the chosen seconds otherwise. */
@Composable
private fun SelfTimerPill(seconds: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (seconds == 0) {
            Icon(
                Icons.Rounded.Timer,
                contentDescription = "延迟拍摄",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text(
                "${seconds}s",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun OsdCell(
    label: String,
    value: String,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val alpha = if (enabled) 1f else 0.35f
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Color(0x99FFFFFF).copy(alpha = alpha), fontSize = 9.sp)
        Text(
            value,
            color = Color.White.copy(alpha = alpha),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

private fun controlLabel(control: NikonLinkRepository.CameraControl?): String {
    if (control == null) return "—"
    control.options.firstOrNull { it.raw == control.current }?.label?.let { return it }
    val raw = control.current
    if (raw < 0) return "—"
    return when (control.key) {
        "aperture" -> "f/%.1f".format(raw / 100.0)
        "shutter" -> {
            val num = (raw ushr 16) and 0xFFFF
            val den = raw and 0xFFFF
            if (num <= 0 || den <= 0) "—" else if (den == 1) "${num}s" else "$num/$den"
        }
        "mode" -> when (raw) {
            1 -> "M"; 2 -> "AUTO"; 3 -> "A"; 4 -> "S"; 5 -> "P"; else -> raw.toString()
        }
        else -> raw.toString()
    }
}


/** Decodes a JPEG down to roughly [maxDim] px, so a 24 MP shot is never fully inflated. */
private fun decodeSampled(bytes: ByteArray, maxDim: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

/** Ruler-style picker: drag the tick strip, release to write. */
@Composable
private fun RulerPicker(
    label: String,
    options: List<NikonLinkRepository.ParamOption>,
    currentRaw: Int,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onCommit: (Int) -> Unit,
) {
    if (options.isEmpty()) return
    val initial = options.indexOfFirst { it.raw == currentRaw }.let { if (it < 0) 0 else it }
    var position by remember(label, options) { mutableFloatStateOf(initial.toFloat()) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val labelEvery = if (options.size <= 12) 1 else 5

    val centered = position.roundToInt().coerceIn(0, options.lastIndex)
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Few options need wider spacing so every label stays readable.
    val stepDp = if (options.size <= 6) 56.dp else 22.dp
    val stepPx = with(density) { stepDp.toPx() }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = NikonLinkTheme.colors.dim, fontSize = 12.sp)
            Text(
                "完成",
                color = NikonYellow,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onCommit(options[centered].raw) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Text(
            options[centered].label,
            color = Paper,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .pointerInput(label, options) {
                    detectDragGestures(
                        onDragStart = { },
                        onDragEnd = {
                            val target = position.roundToInt().coerceIn(0, options.lastIndex)
                            scope.launch {
                                animate(position, target.toFloat(), animationSpec = tween(120)) { value, _ ->
                                    position = value
                                }
                            }
                        },
                        onDragCancel = { },
                    ) { change, dragAmount ->
                        change.consume()
                        position = (position - dragAmount.x / stepPx)
                            .coerceIn(0f, options.lastIndex.toFloat())
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val centerX = size.width / 2f
                val visible = (size.width / stepPx / 2f).toInt() + 2
                val base = position.roundToInt()
                val frac = position - base
                val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 9.sp.toPx()
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                    alpha = 160
                }
                for (offset in -visible..visible) {
                    val i = base + offset
                    if (i !in options.indices) continue
                    val x = centerX + (offset - frac) * stepPx
                    val major = i % labelEvery == 0
                    drawLine(
                        color = if (i == centered) NikonYellow else Color(0x66FFFFFF),
                        start = androidx.compose.ui.geometry.Offset(x, if (major) 18f else 30f),
                        end = androidx.compose.ui.geometry.Offset(x, size.height - 22f),
                        strokeWidth = if (major) 2f else 1.5f,
                    )
                    if (major) {
                        drawContext.canvas.nativeCanvas.drawText(
                            options[i].label, x, size.height - 4f, textPaint,
                        )
                    }
                }
                drawLine(
                    color = NikonYellow,
                    start = androidx.compose.ui.geometry.Offset(centerX, 0f),
                    end = androidx.compose.ui.geometry.Offset(centerX, size.height),
                    strokeWidth = 3f,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "拖动刻度选择，点右上角「完成」生效",
            color = NikonLinkTheme.colors.dim,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(20.dp))
    }
}

private val LIVE_VIEW_SUPPORTED = listOf(
    "Z9", "Z8", "Z7II", "Z7", "Z6III", "Z6II", "Z6",
    "Z5II", "Z5IIC", "Z50II", "Z30", "Zf", "ZR", "D6", "D780",
)

private val LIVE_VIEW_UNSUPPORTED = listOf("Z5", "Z50", "Zfc")

@Composable
private fun LiveViewModelsDialog(onDismiss: () -> Unit) {
    AlertDialog(
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
            Text("知道了", color = NikonYellow, modifier = Modifier.padding(8.dp).clickable { onDismiss() })
        },
    )
}
