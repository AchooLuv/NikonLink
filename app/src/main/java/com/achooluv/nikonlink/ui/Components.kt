@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.achooluv.nikonlink.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Rounded card with a hairline border — the app's only container. */
@Composable
fun NikonCard(
    modifier: Modifier = Modifier,
    contentPadding: Int = 20,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = NikonLinkTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = colors.card,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.line),
    ) {
        Column(Modifier.padding(contentPadding.dp), content = content)
    }
}

/** Small uppercase-ish label used above values. */
@Composable
fun Caption(text: String, color: Color = NikonLinkTheme.colors.dim) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
fun SectionTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            color = Paper,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
        )
        trailing?.invoke()
    }
}

/** Yellow pill used for file-type badges. */
@Composable
fun Badge(text: String, filled: Boolean = false) {
    val colors = NikonLinkTheme.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (filled) colors.accent else Color(0xCC101012))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            color = if (filled) Color(0xFF161200) else colors.accent,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
        )
    }
}

/** Slowly breathing halo used behind the connect emblem. */
@Composable
fun breathingAlpha(active: Boolean): Float {
    if (!active) return 0.35f
    val transition = rememberInfiniteTransition(label = "breath")
    val alpha by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    return alpha
}

/** Thin generic progress bar. */
@Composable
fun ThinProgress(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(InkLine),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .background(Brush.horizontalGradient(listOf(NikonYellow, Color(0xFFFFE45C)))),
        )
    }
}

@Composable
fun VerticalGap(height: Int) = Spacer(Modifier.height(height.dp))

@Composable
fun HorizontalGap(width: Int) = Spacer(Modifier.size(width.dp))

@Composable
fun ScreenHeader(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(title, color = Paper, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = NikonLinkTheme.colors.dim, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
fun Mono(text: String, color: Color = Paper, size: Int = 13) {
    Text(
        text = text,
        color = color,
        fontSize = size.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
    )
}

/** Two-segment switch: camera hotspot (AP) vs same-network (STA). */
@Composable
fun ModeSelectorRow(
    mode: com.achooluv.nikonlink.data.ConnectMode,
    onChange: (com.achooluv.nikonlink.data.ConnectMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(InkRaised, RoundedCornerShape(14.dp))
            .padding(4.dp),
    ) {
        com.achooluv.nikonlink.data.ConnectMode.entries.forEach { option ->
            val selected = option == mode
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (selected) NikonYellow else Color.Transparent)
                    .clickable { onChange(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.shortLabel,
                    color = if (selected) Color(0xFF161200) else PaperDim,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
