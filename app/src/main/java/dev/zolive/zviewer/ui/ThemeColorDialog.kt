@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.zolive.zviewer.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.view.HapticFeedbackConstantsCompat
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.HapticEvent
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val PickerWidth = 192.dp

@Composable
internal fun ThemeColorDialog(
    accent: Long,
    dark: Boolean,
    dynamicColor: Boolean,
    onDismiss: () -> Unit,
    onApply: (Long) -> Unit,
) {
    val systemScheme = if (dynamicColor) MaterialTheme.colorScheme else null
    val initialRgb = systemScheme?.primary?.toArgb() ?: accent.toInt()
    var rgb by remember(initialRgb) { mutableIntStateOf(initialRgb) }
    var hsv by remember(initialRgb) { mutableStateOf(Hsv.fromRgb(initialRgb)) }
    var hex by remember(initialRgb) { mutableStateOf(initialRgb.toHex()) }
    val valid = hex.length == 6
    val seed = rgb.toLong() and 0xFFFFFFFFL

    fun selectRgb(color: Int) {
        rgb = color
        hsv = Hsv.fromRgb(color, hsv.hue)
    }

    SettingsAlertDialog(
        onDismissRequest = onDismiss,
        resizeForKeyboard = true,
        title = { Text(stringResource(R.string.custom_theme_color)) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val scheme = if (systemScheme != null && rgb == initialRgb) systemScheme
                        else remember(seed, dark) { themeColorScheme(seed, dark) }
                    PalettePreview(scheme, stringResource(if (dark) R.string.theme_dark else R.string.theme_light))
                    ColorWheel(hsv, rgb) { next ->
                        hsv = next
                        rgb = AndroidColor.HSVToColor(floatArrayOf(next.hue, next.saturation, next.value))
                        hex = rgb.toHex()
                    }
                    Column(Modifier.fillMaxWidth()) {
                        ColorChannel.entries.forEach { channel ->
                            ColorSlider(channel, channel.get(rgb)) { value ->
                                selectRgb(channel.replace(rgb, value))
                                hex = rgb.toHex()
                            }
                        }
                    }
                }
                TextSelectionHaptics {
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { input ->
                            hex = input.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }.take(6)
                            if (hex.length == 6) selectRgb(0xFF000000.toInt() or hex.toInt(16))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.color_hex)) },
                        singleLine = true,
                        isError = !valid,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            keyboardType = KeyboardType.Ascii,
                            autoCorrectEnabled = false,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            val haptic = hapticFeedback(HapticEvent.ApplyThemeColor)
            TextButton(enabled = valid, onClick = {
                if (rgb == initialRgb) {
                    onDismiss()
                } else {
                    haptic(HapticFeedbackConstantsCompat.CONFIRM)
                    onApply(seed)
                }
            }) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun PalettePreview(scheme: ColorScheme, label: String) {
    Row(
        Modifier.widthIn(max = PickerWidth).fillMaxWidth().height(24.dp)
            .semantics { contentDescription = label }
            .clip(RoundedCornerShape(6.dp))
            .drawWithCache {
                val depth = 3.dp.toPx()
                val shadow = Brush.verticalGradient(listOf(Color.Black.copy(alpha = .20f), Color.Transparent), endY = depth)
                onDrawWithContent {
                    drawContent()
                    drawRect(shadow, size = Size(size.width, depth))
                }
            },
    ) {
        listOf(scheme.primary, scheme.secondary, scheme.primaryContainer, scheme.surface).forEach { color ->
            Box(Modifier.weight(1f).fillMaxHeight().background(color))
        }
    }
}

private enum class ColorChannel(val shift: Int, val tint: Color) {
    R(16, Color.Red),
    G(8, Color.Green),
    B(0, Color.Blue);

    fun get(rgb: Int): Int = (rgb shr shift) and 0xFF

    fun replace(rgb: Int, value: Int): Int = (rgb and (0xFF shl shift).inv()) or (value shl shift)
}

@Composable
private fun ColorSlider(channel: ColorChannel, value: Int, onValueChange: (Int) -> Unit) {
    val gradient = remember(channel) { Brush.horizontalGradient(listOf(Color.Black, channel.tint)) }
    val haptic = hapticFeedback(HapticEvent.ThemeColorSliders)
    val feedback = remember { ColorPickerFeedback() }
    val currentValue by rememberUpdatedState(value)
    var dragFeedback by remember { mutableStateOf<ColorAxisFeedback?>(null) }
    var released by remember { mutableStateOf(false) }
    val valueStyle = MaterialTheme.typography.bodyMedium
    val valueWidth = with(LocalDensity.current) { valueStyle.fontSize.toDp() * 2 }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(channel.name, style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value.toFloat(),
            onValueChange = {
                val next = it.roundToInt()
                dragFeedback?.let { axis -> feedback.play(axis.moveTo(next / 255f), haptic) }
                onValueChange(next)
            },
            onValueChangeFinished = {
                if (released) haptic(HapticFeedbackConstantsCompat.CONFIRM)
                released = false
            },
            modifier = Modifier.weight(1f).semantics { contentDescription = channel.name }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        released = false
                        dragFeedback = ColorAxisFeedback(currentValue / 255f, colorChannelStops)
                        try {
                            var event = awaitPointerEvent(PointerEventPass.Initial)
                            while (event.changes.any { it.pressed }) {
                                event = awaitPointerEvent(PointerEventPass.Initial)
                            }
                            released = event.changes.any { it.changedToUp() }
                        } finally {
                            dragFeedback = null
                        }
                    }
                },
            valueRange = 0f..255f,
            thumb = {
                Box(Modifier.size(16.dp).background(Color.White, CircleShape).border(1.dp, Color.DarkGray, CircleShape))
            },
            track = { Box(Modifier.fillMaxWidth().height(8.dp).background(gradient, CircleShape)) },
        )
        Text(value.toString(), Modifier.widthIn(min = valueWidth), style = valueStyle, textAlign = TextAlign.End)
    }
}

private data class Hsv(val hue: Float, val saturation: Float, val value: Float) {
    companion object {
        fun fromRgb(rgb: Int, previousHue: Float = 0f): Hsv {
            val hsv = FloatArray(3)
            AndroidColor.colorToHSV(rgb, hsv)
            return Hsv(if (hsv[1] == 0f) previousHue else hsv[0], hsv[1], hsv[2])
        }
    }
}

private fun Int.toHex(): String = (this and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

private class ColorWheelGeometry(size: Size) {
    val center = size.center
    val radius = size.minDimension / 2
    val ringRadius = radius * .86f
    val ringWidth = radius * .20f
    val fieldSize = radius * .96f
    val fieldStart = center - Offset(fieldSize, fieldSize) / 2f

    fun huePosition(hue: Float): Offset {
        val angle = Math.toRadians(hue.toDouble())
        return center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * ringRadius
    }

    fun hueAt(position: Offset): Float {
        val offset = position - center
        return ((Math.toDegrees(atan2(offset.y, offset.x).toDouble()) + 360) % 360).toFloat()
    }
}

private val wheelColors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

@Composable
private fun ColorWheel(hsv: Hsv, rgb: Int, onChange: (Hsv) -> Unit) {
    val description = stringResource(R.string.color_wheel)
    val selection by rememberUpdatedState(hsv)
    val selectedColor by rememberUpdatedState(Color(rgb))
    val updateColor by rememberUpdatedState(onChange)
    val haptic by rememberUpdatedState(hapticFeedback(HapticEvent.ThemeColorWheel))
    val feedback = remember { ColorPickerFeedback() }
    val hueColor = Color.hsv(hsv.hue, 1f, 1f)
    Box(
        Modifier.widthIn(max = PickerWidth).fillMaxWidth().aspectRatio(1f)
            .semantics {
                contentDescription = description
                stateDescription = rgb.toHex()
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val wheel = ColorWheelGeometry(size.toSize())
                    val distance = (down.position - wheel.center).getDistance()
                    if (distance > wheel.radius) return@awaitEachGesture
                    val adjustHue = distance >= wheel.ringRadius - wheel.ringWidth / 2
                    val start = selection
                    fun selectionAt(position: Offset): Hsv =
                        if (adjustHue) {
                            start.copy(
                                hue = wheel.hueAt(position),
                                saturation = if (start.saturation == 0f) 1f else start.saturation,
                                value = if (start.value == 0f) 1f else start.value,
                            )
                        } else {
                            val offset = (position - wheel.fieldStart) / wheel.fieldSize
                            start.copy(saturation = offset.x.coerceIn(0f, 1f), value = 1f - offset.y.coerceIn(0f, 1f))
                        }
                    down.consume()
                    val initial = selectionAt(down.position)
                    updateColor(initial)
                    val pulseFor = if (adjustHue) {
                        val hue = ColorHueFeedback(initial.hue)
                        fun(color: Hsv) = hue.moveTo(color.hue)
                    } else {
                        val field = ColorFieldFeedback(initial.saturation, initial.value)
                        fun(color: Hsv) = field.moveTo(color.saturation, color.value)
                    }
                    val released = drag(down.id) {
                        it.consume()
                        val next = selectionAt(it.position)
                        updateColor(next)
                        feedback.play(pulseFor(next), haptic)
                    }
                    if (released) haptic(HapticFeedbackConstantsCompat.CONFIRM)
                }
            }
            .drawWithCache {
                val wheel = ColorWheelGeometry(size)
                val ring = Brush.sweepGradient(wheelColors, wheel.center)
                val saturation = Brush.horizontalGradient(
                    listOf(Color.White, hueColor), wheel.fieldStart.x, wheel.fieldStart.x + wheel.fieldSize,
                )
                val value = Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black), wheel.fieldStart.y, wheel.fieldStart.y + wheel.fieldSize,
                )
                val field = Size(wheel.fieldSize, wheel.fieldSize)
                onDrawBehind {
                    drawCircle(ring, wheel.ringRadius, wheel.center, style = Stroke(wheel.ringWidth))
                    drawRect(saturation, wheel.fieldStart, field)
                    drawRect(value, wheel.fieldStart, field)
                    drawColorIndicator(wheel.huePosition(selection.hue), hueColor)
                    val offset = Offset(selection.saturation, 1f - selection.value) * wheel.fieldSize
                    drawColorIndicator(wheel.fieldStart + offset, selectedColor)
                }
            },
    )
}

private fun DrawScope.drawColorIndicator(position: Offset, color: Color) {
    drawCircle(Color.Black, 8.dp.toPx(), position)
    drawCircle(Color.White, 7.dp.toPx(), position)
    drawCircle(color, 5.dp.toPx(), position)
}
