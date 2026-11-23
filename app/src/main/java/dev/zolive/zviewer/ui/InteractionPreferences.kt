package dev.zolive.zviewer.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import dev.zolive.zviewer.data.AnimationSpeed
import kotlin.math.roundToInt

internal val LocalAnimationSpeed = staticCompositionLocalOf { AnimationSpeed.Moderate }
internal val LocalHapticsEnabled = staticCompositionLocalOf { true }

internal fun AnimationSpeed.duration(millis: Int): Int = (millis * durationScale).roundToInt()

internal fun AnimationSpeed.stiffness(base: Float): Float = base / (durationScale * durationScale)

internal class PreferredMotionDurationScale(
    private val systemScale: MotionDurationScale?,
    private val speed: AnimationSpeed,
) : MotionDurationScale {
    override val scaleFactor: Float get() = (systemScale?.scaleFactor ?: 1f) * speed.durationScale
}

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
internal fun WithAnimationSpeed(
    speed: AnimationSpeed,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = rememberCoroutineScope().coroutineContext
    val inheritedScale = context[MotionDurationScale]
    if (speed == AnimationSpeed.Moderate || inheritedScale is PreferredMotionDurationScale) {
        Box(modifier) { content() }
        return
    }
    val recomposer = remember(context, speed) {
        Recomposer(context + PreferredMotionDurationScale(inheritedScale, speed))
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
        modifier = modifier,
        onRelease = ComposeView::disposeComposition,
    )
}

@Composable
internal fun AppCircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.circularColor,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
) {
    WithAnimationSpeed(LocalAnimationSpeed.current) {
        CircularProgressIndicator(modifier, color, strokeWidth)
    }
}

@Composable
internal fun hapticFeedback(): (Int) -> Unit {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    return { type -> if (enabled) ViewCompat.performHapticFeedback(view, type) }
}
