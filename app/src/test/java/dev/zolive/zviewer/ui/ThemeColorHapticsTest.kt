package dev.zolive.zviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeColorHapticsTest {
    @Test fun hueWrapsAcrossRedInBothDirections() {
        val hue = ColorHueFeedback(358f)
        assertEquals(ColorPickerPulse.None, hue.moveTo(.5f))
        assertEquals(ColorPickerPulse.Tick, hue.moveTo(2f))
        assertEquals(ColorPickerPulse.None, hue.moveTo(359.5f))
        assertEquals(ColorPickerPulse.Tick, hue.moveTo(358f))
    }

    @Test fun temperatureCrossingsTakePriorityOverHueTicks() {
        val hue = ColorHueFeedback(50f)
        assertEquals(ColorPickerPulse.Tick, hue.moveTo(64f))
        assertEquals(ColorPickerPulse.None, hue.moveTo(91f))
        assertEquals(ColorPickerPulse.Landmark, hue.moveTo(92f))
        assertEquals(ColorPickerPulse.None, hue.moveTo(89f))
        assertEquals(ColorPickerPulse.Landmark, hue.moveTo(88f))
    }

    @Test fun rgbTicksAndEndpointsRearmAfterMovingAway() {
        val channel = ColorAxisFeedback(0f, colorChannelStops)
        assertEquals(ColorPickerPulse.None, channel.moveTo(0f))
        assertEquals(ColorPickerPulse.Tick, channel.moveTo(36f / 255f))
        assertEquals(ColorPickerPulse.None, channel.moveTo(31f / 255f))
        assertEquals(ColorPickerPulse.Boundary, channel.moveTo(0f))
        assertEquals(ColorPickerPulse.None, channel.moveTo(2f / 255f))
        assertEquals(ColorPickerPulse.None, channel.moveTo(0f))
        assertEquals(ColorPickerPulse.Boundary, channel.moveTo(1f))
        assertEquals(ColorPickerPulse.None, channel.moveTo(.99f))
        assertEquals(ColorPickerPulse.None, channel.moveTo(1f))
        assertEquals(ColorPickerPulse.None, channel.moveTo(.96f))
        assertEquals(ColorPickerPulse.Boundary, channel.moveTo(1f))
    }

    @Test fun squareCoalescesAxisTicksAndPrioritizesEdges() {
        val field = ColorFieldFeedback(.10f, .08f)
        assertEquals(ColorPickerPulse.Tick, field.moveTo(.20f, .12f))
        assertEquals(ColorPickerPulse.None, field.moveTo(.21f, .13f))
        assertEquals(ColorPickerPulse.Boundary, field.moveTo(0f, 0f))
        assertEquals(ColorPickerPulse.None, field.moveTo(.01f, .01f))
        assertEquals(ColorPickerPulse.None, field.moveTo(0f, 0f))
    }

    @Test fun squareSeparatesTheNeutralZoneFromGridAndEdges() {
        val field = ColorFieldFeedback(.15f, .45f)
        assertEquals(ColorPickerPulse.Landmark, field.moveTo(.04f, .45f))
        assertEquals(ColorPickerPulse.None, field.moveTo(.07f, .45f))
        assertEquals(ColorPickerPulse.Landmark, field.moveTo(.08f, .45f))
        assertEquals(ColorPickerPulse.Boundary, field.moveTo(0f, .45f))
    }

    @Test fun equalValueChangesCrossMoreRowsInDarkColors() {
        val field = ColorFieldFeedback(.45f, .02f)
        assertEquals(ColorPickerPulse.Tick, field.moveTo(.45f, .14f))
        assertEquals(ColorPickerPulse.Tick, field.moveTo(.45f, .26f))
        val brighter = ColorFieldFeedback(.45f, .56f)
        assertEquals(ColorPickerPulse.None, brighter.moveTo(.45f, .68f))
        assertEquals(ColorPickerPulse.Tick, brighter.moveTo(.45f, .80f))
    }

    @Test fun darkColorsAddSaturationDetentsWithoutTickingWhenValueChangesRows() {
        val field = ColorFieldFeedback(.10f, .39f)
        assertEquals(ColorPickerPulse.None, field.moveTo(.18f, .39f))
        assertEquals(ColorPickerPulse.Tick, field.moveTo(.18f, .34f))
        assertEquals(ColorPickerPulse.None, field.moveTo(.18f, .30f))
        assertEquals(ColorPickerPulse.Tick, field.moveTo(.10f, .30f))
        assertEquals(ColorPickerPulse.Tick, field.moveTo(.18f, .30f))
    }
}
