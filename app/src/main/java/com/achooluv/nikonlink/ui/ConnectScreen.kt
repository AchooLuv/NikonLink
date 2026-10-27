package com.achooluv.nikonlink.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.achooluv.nikonlink.ConnState
import com.achooluv.nikonlink.NikonLinkRepository
import com.achooluv.nikonlink.camera.formatBytes
import com.achooluv.nikonlink.data.ConnectMode
import com.achooluv.nikonlink.data.SettingsStore

@Composable
fun ConnectScreen(
    repo: NikonLinkRepository,
    onTutorial: () -> Unit,
    onBrowse: () -> Unit,
) {
    val state by repo.state.collectAsStateWithLifecycle()
    val identity by repo.identity.collectAsStateWithLifecycle()
    val profile by repo.profile.collectAsStateWithLifecycle()
    val settings = repo.settings
    val chunkSize by settings.chunkSize.collectAsStateWithLifecycle()
    val extras by repo.extras.collectAsStateWithLifecycle()
    val mode by settings.mode.collectAsStateWithLifecycle()

    var manualDialog by remember { mutableStateOf(false) }
    val connected = state is ConnState.Connected
    val busy = state is ConnState.Connecting || state is ConnState.Reconnecting
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(6.dp))
        ScreenHeader("连接", "尼康 Z 系列无线图传 · 相机直连，不经过电脑")

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 120.dp),
        ) {
            Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            StatusEmblem(state)
        }

        Spacer(Modifier.height(26.dp))
        Text(
            statusTitle(state),
            modifier = Modifier.fillMaxWidth(),
            color = if (state is ConnState.Error) Ember else Paper,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            statusSubtitle(state, mode),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp),
            color = NikonLinkTheme.colors.dim,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(18.dp))
        ModeSelectorRow(
            mode = mode,
            onChange = {
                settings.setMode(it)
                // Drop a stale error from the previous mode so the page starts clean.
                if (state is ConnState.Error) repo.cancelConnect()
            },
            enabled = !connected && !busy,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        if (connected || busy) {
            Spacer(Modifier.height(6.dp))
            Text(
                "断开连接后可切换模式",
                modifier = Modifier.fillMaxWidth(),
                color = NikonLinkTheme.colors.dim,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(22.dp))
        Column(Modifier.padding(horizontal = 20.dp)) {
            Button(
                onClick = {
                    when {
                        busy -> repo.cancelConnect()
                        connected -> repo.disconnect()
                        mode == ConnectMode.STA -> repo.connect(mode = mode)
                        settings.lastSsid.isBlank() -> manualDialog = true
                        else -> repo.connect(mode = mode)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp),
                enabled = true,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (busy) NikonLinkTheme.colors.card else NikonYellow,
                    contentColor = if (busy) Paper else Color(0xFF161200),
                    disabledContainerColor = NikonYellowDim,
                    disabledContentColor = PaperDim,
                ),
            ) {
                Text(
                    when {
                        state is ConnState.Reconnecting -> "停止重连"
                        state is ConnState.Connecting -> "取消连接"
                        connected -> "断开连接"
                        mode == ConnectMode.STA -> "扫描并连接"
                        else -> "连接相机"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onTutorial,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, NikonLinkTheme.colors.line),
                ) {
                    Text("连接教程", color = Paper, fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { manualDialog = true },
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, NikonLinkTheme.colors.line),
                ) {
                    Text(
                        if (mode == ConnectMode.STA) "手动 IP" else "手动 SSID",
                        color = Paper,
                        fontSize = 13.sp,
                    )
                }
            }

            if (state is ConnState.Error) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { manualDialog = true },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, NikonLinkTheme.colors.line),
                    ) {
                        Text("换 IP 重试", color = Paper, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(
                                androidx.compose.ui.text.AnnotatedString(
                                    com.achooluv.nikonlink.Diagnostics.dump(context),
                                ),
                            )
                            android.widget.Toast.makeText(context, "诊断信息已复制", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, NikonLinkTheme.colors.line),
                    ) {
                        Text("复制诊断信息", color = Paper, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            if (connected && identity != null) {
                val id = identity!!
                NikonCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Caption("已连接相机")
                            Spacer(Modifier.height(4.dp))
                            Text(id.model, color = Paper, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.BatteryFull,
                                contentDescription = null,
                                tint = if ((id.battery ?: 100) > 20) Mint else Ember,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                id.battery?.let { "$it%" } ?: "—",
                                color = if ((id.battery ?: 100) > 20) Mint else Ember,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = NikonLinkTheme.colors.line)
                    Spacer(Modifier.height(14.dp))
                    InfoRow("固件", id.firmware.ifBlank { "—" })
                    InfoRow("序列号", id.serial.ifBlank { "—" })
                    extras?.lens?.let { InfoRow("镜头", it) }
                    extras?.exposure?.let { InfoRow("光圈/焦距", it) }
                    extras?.shutterCount?.let { InfoRow("快门数", "%,d".format(it)) }
                    InfoRow("传输分块", SettingsStore.chunkLabel(chunkSize))
                    InfoRow(
                        "断点续传",
                        if (profile?.supportsPartialObject == true) "支持" else "相机不支持",
                    )
                    val free = id.storageFree
                    if (free != null) {
                        InfoRow(
                            "存储卡剩余",
                            buildString {
                                append(formatBytes(free))
                                id.storageTotal?.let { append(" / ").append(formatBytes(it)) }
                            },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { repo.refreshLibrary() },
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, NikonLinkTheme.colors.line),
                    ) {
                        Icon(Icons.Rounded.Refresh, null, tint = Paper, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("刷新相册", color = Paper, fontSize = 13.sp)
                    }
                    Button(
                        onClick = onBrowse,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NikonLinkTheme.colors.card,
                            contentColor = Paper,
                        ),
                    ) {
                        Text("浏览与下载", fontSize = 13.sp)
                    }
                }
            } else if (!connected) {
                NikonCard {
                    if (mode == ConnectMode.STA) {
                        Caption("三步连上相机（同一 Wi-Fi）")
                        Spacer(Modifier.height(10.dp))
                        StepLine("1", "相机：网络菜单 → 连接至智能设备 → Wi-Fi 连接（STA 模式）")
                        StepLine("2", "让相机加入家里的路由器，手机连同一个 Wi-Fi")
                        StepLine("3", "回到这里点「扫描并连接」")
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "STA 模式下手机仍可上网；App 会扫描当前网段里的相机，" +
                                "找不到时可用「手动 IP」直接填相机地址。",
                            color = NikonLinkTheme.colors.dim,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    } else {
                        Caption("三步连上相机")
                        Spacer(Modifier.height(10.dp))
                        StepLine("1", "相机菜单打开「连接至智能设备 → Wi-Fi」")
                        StepLine("2", "手机连上相机显示的 NIKON_ 网络")
                        StepLine("3", "回到这里点「连接相机」")
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "全程不经过电脑；连上后照片、视频与 RAW 都可以直接下载。",
                            color = NikonLinkTheme.colors.dim,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        }
        }
    }

    if (manualDialog) {
        ManualConnectDialog(
            mode = mode,
            initialSsid = settings.lastSsid,
            initialPass = settings.lastPassphrase,
            initialHost = settings.hostFor(mode).ifBlank {
                if (mode == ConnectMode.STA) "" else "192.168.1.1"
            },
            onDismiss = { manualDialog = false },
            onConnect = { ssid, pass, host ->
                manualDialog = false
                repo.connect(ssid = ssid, passphrase = pass, host = host, mode = mode)
            },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = NikonLinkTheme.colors.dim, fontSize = 12.sp)
        Mono(value, color = Paper, size = 12)
    }
}

@Composable
private fun StepLine(number: String, text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(20.dp)
                .background(NikonYellow.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(number, color = NikonYellow, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(10.dp))
        Text(text, color = Paper, fontSize = 12.5.sp)
    }
}

@Composable
private fun StatusEmblem(state: ConnState) {
    val connected = state is ConnState.Connected
    val busy = state is ConnState.Connecting || state is ConnState.Reconnecting
    val failed = state is ConnState.Error

    // Only animate while a connection is actually in flight: an infinite
    // transition would keep invalidating this subtree (and redrawing the ring)
    // every frame even on the idle screen.
    var spin = 0f
    var pulse = 0f
    if (busy) {
        val transition = rememberInfiniteTransition(label = "emblem")
        spin = transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
            label = "spin",
        ).value
        pulse = transition.animateFloat(
            initialValue = 0.10f,
            targetValue = 0.26f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
            label = "pulse",
        ).value
    }

    val ringColor = when {
        connected -> NikonYellow
        failed -> Ember
        busy -> NikonYellow.copy(alpha = 0.85f)
        else -> InkLine
    }

    Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 7.dp.toPx()
            val d = size.minDimension - stroke
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            val arcSize = Size(d, d)

            if (connected || busy) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(ringColor.copy(alpha = if (busy) pulse else 0.18f), Color.Transparent),
                        center = center,
                        radius = size.minDimension / 2f,
                    ),
                    radius = size.minDimension / 2f,
                )
            }

            drawArc(
                color = InkLine,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke),
            )

            when {
                connected -> drawArc(
                    brush = Brush.sweepGradient(
                        listOf(NikonYellow, Color(0xFFFFE45C), NikonYellow),
                    ),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                busy -> {
                    drawArc(
                        color = NikonYellow,
                        startAngle = spin - 90f,
                        sweepAngle = 110f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                failed -> drawArc(
                    color = Ember.copy(alpha = 0.7f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }

        Surface(
            modifier = Modifier.size(150.dp),
            shape = RoundedCornerShape(75.dp),
            color = InkRaised,
            border = BorderStroke(1.dp, NikonLinkTheme.colors.line),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.Link,
                        contentDescription = null,
                        tint = if (connected) NikonYellow else PaperDim,
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            connected -> "LINKED"
                            busy -> "LINKING"
                            failed -> "OFFLINE"
                            else -> "STANDBY"
                        },
                        color = if (connected) NikonYellow else PaperDim,
                        fontSize = 10.sp,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ManualConnectDialog(
    mode: ConnectMode,
    initialSsid: String,
    initialPass: String,
    initialHost: String,
    onDismiss: () -> Unit,
    onConnect: (String, String, String?) -> Unit,
) {
    var ssid by remember { mutableStateOf(initialSsid) }
    var pass by remember { mutableStateOf(initialPass) }
    var host by remember { mutableStateOf(initialHost) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = InkRaised,
        title = {
            Text(
                if (mode == ConnectMode.STA) "手动 IP" else "手动连接",
                color = Paper,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                Text(
                    if (mode == ConnectMode.STA) {
                        "填相机在局域网里的地址。可以在相机的 STA 连接设置里看到它分配到（或手动设置）的 IP。"
                    } else {
                        "相机屏幕上的网络名与密码。留空密码会直接连接已保存的网络。"
                    },
                    color = PaperDim,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(14.dp))
                if (mode == ConnectMode.AP) {
                    OutlinedTextField(
                        value = ssid,
                        onValueChange = { ssid = it },
                        label = { Text("SSID", fontSize = 12.sp) },
                        placeholder = { Text("NIKON_Z50II_12345678", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("密码", fontSize = 12.sp) },
                        placeholder = { Text("8 位数字", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = {
                        Text(if (mode == ConnectMode.STA) "相机 IP" else "相机 IP（可选）", fontSize = 12.sp)
                    },
                    placeholder = { Text("192.168.1.50", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (mode == ConnectMode.STA) {
                        "STA 模式下相机通常由路由器分配地址，形如 192.168.1.x。"
                    } else {
                        "相机不在 192.168.1.x 网段时，在这里填它的实际地址。"
                    },
                    color = PaperDim.copy(alpha = 0.8f),
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConnect(ssid.trim(), pass, host.trim().takeIf { it.isNotBlank() }) }) {
                Text("连接", color = NikonYellow)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = PaperDim) }
        },
    )
}

private fun statusTitle(state: ConnState): String = when (state) {
    is ConnState.Idle -> "未连接"
    is ConnState.Connecting -> "正在连接"
    is ConnState.Connected -> state.model
    is ConnState.Reconnecting -> "连接中断，正在恢复"
    is ConnState.Error -> "连接失败"
}

private fun statusSubtitle(state: ConnState, mode: ConnectMode): String = when (state) {
    is ConnState.Idle -> if (mode == ConnectMode.STA) {
        "让相机连上同一个路由器 Wi-Fi，再点「扫描并连接」；找不到就用「手动 IP」。"
    } else {
        "打开相机 Wi-Fi，让手机连上 NIKON_ 网络后点「连接相机」。"
    }
    is ConnState.Connecting -> state.step
    is ConnState.Connected -> "相机会话已建立 · 可以浏览与下载了"
    is ConnState.Reconnecting ->
        "第 ${state.attempt} 次重试，${state.waitSeconds} 秒后开始 · 点上方按钮可停止"
    is ConnState.Error -> state.message
}
