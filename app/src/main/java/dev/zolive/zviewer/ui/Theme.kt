package dev.zolive.zviewer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import dev.zolive.zviewer.data.ReaderSettings

private fun tone(seed: Long, lightness: Float, saturation: Float = 1f): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seed.toInt(), hsl)
    hsl[1] = (hsl[1] * saturation).coerceIn(0f, 1f)
    hsl[2] = lightness
    return Color(ColorUtils.HSLToColor(hsl))
}

@Composable
fun ZViewerTheme(settings: ReaderSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val seed = settings.accent
    val colors = if (dark) darkColorScheme(
        primary = tone(seed, .77f), onPrimary = tone(seed, .16f),
        primaryContainer = tone(seed, .28f), onPrimaryContainer = tone(seed, .90f),
        secondary = tone(seed, .78f, .45f), onSecondary = tone(seed, .18f, .45f),
        secondaryContainer = tone(seed, .29f, .45f), onSecondaryContainer = tone(seed, .91f, .45f),
        background = tone(seed, .065f, .22f), onBackground = tone(seed, .91f, .15f),
        surface = tone(seed, .065f, .22f), onSurface = tone(seed, .91f, .15f),
        surfaceVariant = tone(seed, .25f, .22f), onSurfaceVariant = tone(seed, .80f, .22f),
        surfaceContainerLowest = tone(seed, .045f, .18f), surfaceContainerLow = tone(seed, .10f, .20f),
        surfaceContainer = tone(seed, .13f, .20f), surfaceContainerHigh = tone(seed, .16f, .20f),
        surfaceContainerHighest = tone(seed, .20f, .20f), outline = tone(seed, .57f, .18f),
        outlineVariant = tone(seed, .28f, .18f),
    ) else lightColorScheme(
        primary = tone(seed, .32f), onPrimary = Color.White,
        primaryContainer = tone(seed, .87f), onPrimaryContainer = tone(seed, .13f),
        secondary = tone(seed, .35f, .45f), onSecondary = Color.White,
        secondaryContainer = tone(seed, .89f, .45f), onSecondaryContainer = tone(seed, .15f, .45f),
        background = tone(seed, .975f, .35f), onBackground = tone(seed, .12f, .20f),
        surface = tone(seed, .975f, .35f), onSurface = tone(seed, .12f, .20f),
        surfaceVariant = tone(seed, .90f, .25f), onSurfaceVariant = tone(seed, .35f, .25f),
        surfaceContainerLowest = Color.White, surfaceContainerLow = tone(seed, .955f, .30f),
        surfaceContainer = tone(seed, .935f, .30f), surfaceContainerHigh = tone(seed, .915f, .30f),
        surfaceContainerHighest = tone(seed, .89f, .30f), outline = tone(seed, .47f, .18f),
        outlineVariant = tone(seed, .79f, .20f),
    )
    MaterialTheme(colorScheme = colors, content = content)
}
