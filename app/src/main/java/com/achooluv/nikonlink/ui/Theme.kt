package com.achooluv.nikonlink.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Nikon's signal yellow, used sparingly as the single accent. */
val NikonYellow = Color(0xFFFFCD00)
val NikonYellowDim = Color(0xFF6B5700)

val Ink = Color(0xFF0B0B0C)
val InkRaised = Color(0xFF141416)
val InkCard = Color(0xFF1A1A1D)
val InkLine = Color(0xFF2A2A2E)
val Paper = Color(0xFFF4F1E8)
val PaperDim = Color(0xFF9A968A)
val Mint = Color(0xFF6FE0A8)
val Ember = Color(0xFFFF7A6B)

private val scheme = darkColorScheme(
    primary = NikonYellow,
    onPrimary = Color(0xFF161200),
    primaryContainer = Color(0xFF3D3300),
    onPrimaryContainer = NikonYellow,
    secondary = Paper,
    onSecondary = Ink,
    secondaryContainer = InkCard,
    onSecondaryContainer = Paper,
    tertiary = Mint,
    background = Ink,
    onBackground = Paper,
    surface = InkRaised,
    onSurface = Paper,
    surfaceVariant = InkCard,
    onSurfaceVariant = PaperDim,
    outline = InkLine,
    outlineVariant = InkLine,
    error = Ember,
    onError = Color(0xFF2B0B08),
)

data class NikonLinkColors(
    val card: Color = InkCard,
    val raised: Color = InkRaised,
    val line: Color = InkLine,
    val dim: Color = PaperDim,
    val accent: Color = NikonYellow,
    val success: Color = Mint,
    val danger: Color = Ember,
)

private val LocalColors = staticCompositionLocalOf { NikonLinkColors() }

object NikonLinkTheme {
    val colors: NikonLinkColors
        @Composable get() = LocalColors.current
}

@Composable
fun ProvideNikonLinkTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalColors provides NikonLinkColors()) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
