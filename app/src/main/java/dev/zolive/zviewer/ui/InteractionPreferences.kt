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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import dev.zolive.zviewer.data.AnimationEffect
import dev.zolive.zviewer.data.AnimationSpeed
import dev.zolive.zviewer.data.HapticEvent
import dev.zolive.zviewer.data.ReaderSettings
import kotlin.math.roundToInt

internal val LocalInteractionSettings = compositionLocalOf { ReaderSettings() }

@Composable
internal fun animationSpeed(effect: AnimationEffect): AnimationSpeed =
    LocalInteractionSettings.current.animationSpeedFor(effect)

internal fun AnimationSpeed.duration(millis: Int): Int = (millis * durationScale).roundToInt()

internal fun AnimationSpeed.stiffness(base: Float): Float = base / (durationScale * durationScale)

internal class PreferredMotionDurationScale(
    inheritedScale: MotionDurationScale?,
    val speed: AnimationSpeed,
) : MotionDurationScale {
    private val systemScale: MotionDurationScale? = if (inheritedScale is PreferredMotionDurationScale) {
        inheritedScale.systemScale
    } else {
        inheritedScale
    }

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
    val inheritedSpeed = (inheritedScale as? PreferredMotionDurationScale)?.speed ?: AnimationSpeed.Moderate
    if (speed == inheritedSpeed) {
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
    WithAnimationSpeed(animationSpeed(AnimationEffect.CircularProgress)) {
        CircularProgressIndicator(modifier, color, strokeWidth)
    }
}

@Composable
internal fun hapticFeedback(event: HapticEvent): (Int) -> Unit {
    val view = LocalView.current
    val enabled = LocalInteractionSettings.current.hapticsEnabledFor(event)
    return { type -> if (enabled) ViewCompat.performHapticFeedback(view, type) }
}

private val SilentHapticFeedback = object : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

@Composable
internal fun TextSelectionHaptics(content: @Composable () -> Unit) {
    val feedback = if (LocalInteractionSettings.current.hapticsEnabledFor(HapticEvent.TextSelection)) {
        LocalHapticFeedback.current
    } else {
        SilentHapticFeedback
    }
    CompositionLocalProvider(LocalHapticFeedback provides feedback, content = content)
}
