package dev.zolive.zviewer.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.zolive.zviewer.data.AnimationSpeed
import kotlin.math.roundToInt

internal val LocalAnimationSpeed = staticCompositionLocalOf { AnimationSpeed.Moderate }

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
