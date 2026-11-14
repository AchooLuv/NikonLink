package com.achooluv.nikonlink.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.achooluv.nikonlink.NikonLinkRepository

/**
 * Full-screen remote shutter. Live view is not exposed yet — the body returns
 * "not ready" for 0x9203 — so this screen focuses on the one action that works:
 * release the shutter, then let the normal auto-pull flow fetch the frame.
 */
@Composable
fun RemoteCaptureScreen(repo: NikonLinkRepository, onClose: () -> Unit) {
    val identity by repo.identity.collectAsStateWithLifecycle()
    val capture by repo.captureState.collectAsStateWithLifecycle()
    val note by repo.captureNote.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    BackHandler { onClose() }
    DisposableEffect(Unit) {
        onDispose { repo.resetCaptureState() }
    }

    val busy = capture == NikonLinkRepository.CaptureState.Busy

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = Paper)
            }
            Text("遥控拍摄", color = Paper, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                identity?.model.orEmpty(),
                color = NikonLinkTheme.colors.dim,
                fontSize = 12.sp,
            )
        }

        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.PhotoCamera,
                    contentDescription = null,
                    tint = PaperDim,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text("取景暂不可用", color = Paper, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "本版先支持遥控快门；拍摄后若开启自动拉取，照片会回到手机。",
                    color = NikonLinkTheme.colors.dim,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }

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
        Spacer(Modifier.height(18.dp))

        Box(
            Modifier.fillMaxWidth().padding(bottom = 36.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(96.dp)
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
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(InkRaised),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.PhotoCamera,
                        contentDescription = "拍摄",
                        tint = if (busy) PaperDim else NikonYellow,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
        }
    }
}
