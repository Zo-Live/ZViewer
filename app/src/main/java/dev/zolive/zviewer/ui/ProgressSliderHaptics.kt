package dev.zolive.zviewer.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.view.HapticFeedbackConstantsCompat
import kotlin.math.abs

private const val MinAdjacentStepIntervalMs = 80L

internal class ProgressSliderHaptics {
    private var lastStep = 0L
    private var lastStepAt: Long? = null
    private var lowerReached = false
    private var upperReached = false
    private var wasAtLower = false
    private var wasAtUpper = false
    var dragTime: Long? = null

    fun start(step: Long, atLower: Boolean, atUpper: Boolean) {
        dragTime = null
        lastStep = step
        lastStepAt = null
        lowerReached = false
        upperReached = false
        wasAtLower = atLower
        wasAtUpper = atUpper
    }

    fun update(step: Long, atLower: Boolean, atUpper: Boolean, haptic: (Int) -> Unit) {
        val now = dragTime ?: return
        val reachedEnd = atLower && !wasAtLower && !lowerReached || atUpper && !wasAtUpper && !upperReached
        wasAtLower = atLower
        wasAtUpper = atUpper
        if (reachedEnd) {
            haptic(HapticFeedbackConstantsCompat.GESTURE_THRESHOLD_ACTIVATE)
            lowerReached = lowerReached || atLower
            upperReached = upperReached || atUpper
        }
        if (step == lastStep) return
        val previousTime = lastStepAt
        if (!atLower && !atUpper && abs(step - lastStep) == 1L && previousTime != null &&
            now - previousTime >= MinAdjacentStepIntervalMs) {
            haptic(HapticFeedbackConstantsCompat.SEGMENT_FREQUENT_TICK)
        }
        lastStep = step
        lastStepAt = now
    }
}

internal fun Modifier.trackProgressSliderDrag(
    haptics: ProgressSliderHaptics, step: Long, atLower: Boolean, atUpper: Boolean,
): Modifier = composed {
    val current by rememberUpdatedState(Triple(step, atLower, atUpper))
    pointerInput(haptics) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val (initialStep, initialLower, initialUpper) = current
            haptics.start(initialStep, initialLower, initialUpper)
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val touch = event.changes.singleOrNull()?.takeIf { it.id == down.id && it.pressed } ?: break
                    if (haptics.dragTime != null || abs(touch.position.x - down.position.x) >= viewConfiguration.touchSlop) {
                        haptics.dragTime = touch.uptimeMillis
                    }
                }
            } finally {
                haptics.dragTime = null
            }
        }
    }
}
