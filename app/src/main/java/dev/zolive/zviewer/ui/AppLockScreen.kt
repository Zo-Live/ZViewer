package dev.zolive.zviewer.ui

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.ReaderSettings

private const val AdaptiveIconLayerScale = 1.5f

@Composable
internal fun AppLockScreen(settings: ReaderSettings, onVerify: () -> Unit) {
    val activity = LocalActivity.current
    val colors = MaterialTheme.colorScheme
    val lightBars = colors.surface.luminance() > .5f
    val accent = Color(settings.accent)
    val useDynamicColor = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val iconTint = if (lightBars && !useDynamicColor && accent.luminance() <= .18f) accent else colors.primary
    SideEffect {
        if (activity != null) {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.safeDrawingPadding().padding(horizontal = 24.dp)) {
            val iconSize = (maxHeight * .24f).coerceAtMost(208.dp)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(4f))
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_monochrome),
                    contentDescription = "ZViewer",
                    modifier = Modifier.size(iconSize).scale(AdaptiveIconLayerScale),
                    tint = iconTint,
                )
                Spacer(Modifier.weight(2f))
                Button(
                    onClick = onVerify,
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.secondaryContainer,
                        contentColor = colors.onSecondaryContainer,
                    ),
                ) { Text(stringResource(R.string.verify), style = MaterialTheme.typography.bodyLarge) }
                Spacer(Modifier.weight(4.5f))
            }
        }
    }
}
