package dev.zolive.zviewer.ui

import androidx.core.view.HapticFeedbackConstantsCompat
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressSliderHapticsTest {
    private val slider = ProgressSliderHaptics()
    private val pulses = mutableListOf<Int>()

    private fun move(step: Long, time: Long, lower: Boolean = false, upper: Boolean = false) {
        slider.dragTime = time
        slider.update(step, lower, upper, pulses::add)
    }

    @Test fun adjacentCrossingsVibrateAtOrAboveTheMinimumInterval() {
        slider.start(0, atLower = true, atUpper = false)
        move(1, 100)
        move(2, 179)
        assertEquals(emptyList<Int>(), pulses)

        val tick = HapticFeedbackConstantsCompat.SEGMENT_FREQUENT_TICK
        move(3, 259)
        move(4, 400)
        assertEquals(listOf(tick, tick), pulses)

        move(3, 440)
        move(6, 560)
        assertEquals(listOf(tick, tick), pulses)

        move(5, 660)
        assertEquals(listOf(tick, tick, tick), pulses)
    }

    @Test fun bookendsPulseOncePerDragAndRequireMovementIntoTheEnd() {
        slider.start(0, atLower = true, atUpper = false)
        move(0, 100, lower = true)
        move(10, 110, upper = true)
        move(10, 120, upper = true)
        move(0, 130, lower = true)
        move(10, 140, upper = true)
        assertEquals(List(2) { HapticFeedbackConstantsCompat.GESTURE_THRESHOLD_ACTIVATE }, pulses)
    }

    @Test fun tapsAndNewDragsDoNotInheritThePreviousDragTiming() {
        slider.start(0, atLower = true, atUpper = false)
        move(1, 100)
        slider.dragTime = null
        slider.update(5, atLower = false, atUpper = true, haptic = pulses::add)
        slider.start(1, atLower = false, atUpper = false)
        move(2, 200)
        assertEquals(emptyList<Int>(), pulses)
    }
}
