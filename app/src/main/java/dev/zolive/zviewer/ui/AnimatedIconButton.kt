package dev.zolive.zviewer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import dev.zolive.zviewer.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val IconDurationMillis = 300

@Composable
internal fun iconAnimationSpec(easing: Easing = LinearEasing) = tween<Float>(
    durationMillis = LocalInteractionSettings.current.animationSpeed.duration(IconDurationMillis),
    easing = easing,
)

@Composable
internal fun AnimatedIconButton(
    enabled: Boolean = true,
    active: Boolean = true,
    onClick: () -> Unit,
    content: @Composable (State<Float>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val progressState = remember(progress) { progress.asState() }
    val spec = iconAnimationSpec()
    var motion by remember { mutableStateOf<Job?>(null) }
    IconButton(
        enabled = enabled,
        onClick = {
            if (active) {
                motion?.cancel()
                motion = scope.launch {
                    progress.snapTo(0f)
                    progress.animateTo(1f, spec)
                }
                onClick()
            }
        },
    ) {
        content(progressState)
    }
}

@Composable
internal fun settingsIconProgress(settingsOpen: Boolean): State<Float> = animateFloatAsState(
    targetValue = if (settingsOpen) 1f else 0f,
    animationSpec = iconAnimationSpec(if (settingsOpen) FastOutSlowInEasing else LinearOutSlowInEasing),
    label = "Settings icon rotation",
)

@Composable
internal fun SettingsIconButton(description: String, progress: State<Float>, active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = { if (active) onClick() }) {
        Icon(painterResource(R.drawable.ic_symbol_settings), description, Modifier.graphicsLayer {
            rotationZ = 30f * progress.value
        })
    }
}
