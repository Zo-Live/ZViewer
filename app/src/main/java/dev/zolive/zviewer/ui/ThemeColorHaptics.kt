package dev.zolive.zviewer.ui

import android.os.SystemClock
import androidx.core.view.HapticFeedbackConstantsCompat
import kotlin.math.floor

private const val MinTickIntervalMs = 45L

internal enum class ColorPickerPulse(val effect: Int) {
    None(HapticFeedbackConstantsCompat.NO_HAPTICS),
    Tick(HapticFeedbackConstantsCompat.SEGMENT_FREQUENT_TICK),
    Landmark(HapticFeedbackConstantsCompat.GESTURE_THRESHOLD_DEACTIVATE),
    Boundary(HapticFeedbackConstantsCompat.GESTURE_THRESHOLD_ACTIVATE),
}

internal class ColorPickerFeedback {
    private var lastPulseAt = -MinTickIntervalMs

    fun play(pulse: ColorPickerPulse, haptic: (Int) -> Unit) {
        if (pulse == ColorPickerPulse.None) return
        val now = SystemClock.uptimeMillis()
        if (pulse == ColorPickerPulse.Tick && now - lastPulseAt < MinTickIntervalMs) return
        lastPulseAt = now
        haptic(pulse.effect)
    }
}

private class ColorDetents(start: Float, private val hysteresis: Float, private val segmentAt: (Float) -> Int) {
    private var segment = segmentAt(start)

    fun cross(value: Float): Boolean {
        val next = segment.coerceIn(segmentAt(value - hysteresis), segmentAt(value + hysteresis))
        if (next == segment) return false
        segment = next
        return true
    }
}

internal class ColorHueFeedback(start: Float) {
    private var angle = start
    private var previousHue = start
    private val detents = ColorDetents(start, 1.5f) { floor(it / 30f).toInt() }
    private val temperature = ColorDetents(start, 1.5f) { floor((it + 90f) / 180f).toInt() }

    fun moveTo(hue: Float): ColorPickerPulse {
        angle += (hue - previousHue + 540f) % 360f - 180f
        previousHue = hue
        val crossedDetent = detents.cross(angle)
        return when {
            temperature.cross(angle) -> ColorPickerPulse.Landmark
            crossedDetent -> ColorPickerPulse.Tick
            else -> ColorPickerPulse.None
        }
    }
}

private enum class ColorBoundary { Interior, Minimum, Maximum }

internal class ColorAxisFeedback(start: Float, stops: FloatArray) {
    private val detents = linearDetents(start, stops)
    private var boundary = boundaryAt(start)

    fun moveTo(value: Float): ColorPickerPulse {
        val crossedDetent = detents.cross(value)
        val next = when {
            boundary == ColorBoundary.Minimum && value < .02f -> ColorBoundary.Minimum
            boundary == ColorBoundary.Maximum && value > .98f -> ColorBoundary.Maximum
            else -> boundaryAt(value)
        }
        val reachedBoundary = next != ColorBoundary.Interior && next != boundary
        boundary = next
        return when {
            reachedBoundary -> ColorPickerPulse.Boundary
            crossedDetent -> ColorPickerPulse.Tick
            else -> ColorPickerPulse.None
        }
    }

    private fun boundaryAt(value: Float): ColorBoundary = when (value) {
        0f -> ColorBoundary.Minimum
        1f -> ColorBoundary.Maximum
        else -> ColorBoundary.Interior
    }
}

private fun linearDetents(start: Float, stops: FloatArray) =
    ColorDetents(start, .012f) { value -> stops.count { it <= value } }

private val saturationStops = floatArrayOf(.28f, .50f, .72f)
private val darkSaturationStops = floatArrayOf(.14f, .86f)
private val valueStops = floatArrayOf(.10f, .22f, .36f, .54f, .76f)
internal val colorChannelStops = FloatArray(7) { (it + 1) * 32f / 255f }

internal class ColorFieldFeedback(saturation: Float, value: Float) {
    private val saturationAxis = ColorAxisFeedback(saturation, saturationStops)
    private val valueAxis = ColorAxisFeedback(value, valueStops)
    private val darkSaturation = linearDetents(saturation, darkSaturationStops)
    private val neutralZone = ColorDetents(saturation, .015f) { if (it >= .06f) 1 else 0 }

    fun moveTo(saturation: Float, value: Float): ColorPickerPulse {
        val saturationPulse = saturationAxis.moveTo(saturation)
        val valuePulse = valueAxis.moveTo(value)
        val darkPulse = if (darkSaturation.cross(saturation) && value < .36f) {
            ColorPickerPulse.Tick
        } else {
            ColorPickerPulse.None
        }
        val neutralPulse = if (neutralZone.cross(saturation)) ColorPickerPulse.Landmark else ColorPickerPulse.None
        return maxOf(saturationPulse, valuePulse, darkPulse, neutralPulse)
    }
}
