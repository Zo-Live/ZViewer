package dev.zolive.zviewer.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import dev.zolive.zviewer.data.AnimationSpeed
import kotlin.math.roundToInt

internal val LocalAnimationSpeed = staticCompositionLocalOf { AnimationSpeed.Moderate }
internal val LocalHapticsEnabled = staticCompositionLocalOf { true }

internal fun AnimationSpeed.duration(millis: Int): Int = (millis * durationScale).roundToInt()

internal fun AnimationSpeed.stiffness(base: Float): Float = base / (durationScale * durationScale)

@Composable
internal fun pageFlingBehavior(state: PagerState, speed: AnimationSpeed): TargetedFlingBehavior =
    if (speed == AnimationSpeed.Moderate) {
        PagerDefaults.flingBehavior(state)
    } else {
        PagerDefaults.flingBehavior(
            state,
            snapAnimationSpec = spring(stiffness = speed.stiffness(Spring.StiffnessMediumLow), visibilityThreshold = 1f),
        )
    }

@Composable
internal fun WithAnimationSpeed(speed: AnimationSpeed, content: @Composable () -> Unit) {
    if (speed == AnimationSpeed.Moderate) {
        content()
        return
    }
    val context = rememberCoroutineScope().coroutineContext
    val recomposer = remember(context, speed) {
        val systemScale = context[MotionDurationScale]
        val scale = object : MotionDurationScale {
            override val scaleFactor: Float get() = (systemScale?.scaleFactor ?: 1f) * speed.durationScale
        }
        Recomposer(context + scale)
    }
    val locals by rememberUpdatedState(currentCompositionLocalContext)
    val currentContent by rememberUpdatedState(content)
    LaunchedEffect(recomposer) { recomposer.runRecomposeAndApplyChanges() }
    DisposableEffect(recomposer) { onDispose { recomposer.cancel() } }
    AndroidView(
        factory = { context ->
            ComposeView(context).apply {
                setParentCompositionContext(recomposer)
                setContent { CompositionLocalProvider(locals) { currentContent() } }
            }
        },
        modifier = Modifier.size(0.dp),
        onRelease = ComposeView::disposeComposition,
    )
}

@Composable
internal fun hapticFeedback(): (Int) -> Unit {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    return { type -> if (enabled) ViewCompat.performHapticFeedback(view, type) }
}
