package dev.zolive.zviewer.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.util.lerp
import dev.zolive.zviewer.R

private const val ZoomViewport = 960f
private const val LensCenter = 380f
private const val LensScaleChange = .12f
private const val HandleTravel = 16f

@Composable
internal fun ReaderZoomIconButton(zoomIn: Boolean, video: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(
        when {
            zoomIn && video -> R.string.zoom_in_video
            zoomIn -> R.string.zoom_in_image
            video -> R.string.zoom_out_video
            else -> R.string.zoom_out_image
        },
    )
    val mark = if (zoomIn) R.drawable.ic_symbol_zoom_in_mark else R.drawable.ic_symbol_zoom_out_mark
    val direction = if (zoomIn) 1f else -1f
    AnimatedIconButton(enabled = enabled, onClick = onClick) { progress ->
        val color = LocalContentColor.current
        Icon(painterResource(mark), description, Modifier.drawWithCache {
            val unit = size.width / ZoomViewport
            val pivot = Offset(LensCenter * unit, LensCenter * unit)
            val outline = Path().apply { fillType = PathFillType.EvenOdd }
            onDrawWithContent {
                val motion = direction * zoomPulse(progress.value)
                val lensScale = 1f + motion * LensScaleChange
                scale(unit, unit, pivot = Offset.Zero) {
                    outline.updateZoomOutline(lensScale, motion * HandleTravel)
                    drawPath(outline, color)
                }
                scale(lensScale, lensScale, pivot) { this@onDrawWithContent.drawContent() }
            }
        })
    }
}

private fun zoomPulse(progress: Float): Float {
    val peak = .32f
    val rebound = .72f
    fun ease(time: Float) = FastOutSlowInEasing.transform(time)
    return when {
        progress < peak -> ease(progress / peak)
        progress < rebound -> lerp(1f, -.22f, ease((progress - peak) / (rebound - peak)))
        else -> lerp(-.22f, 0f, ease((progress - rebound) / (1f - rebound)))
    }
}

private fun Path.updateZoomOutline(lensScale: Float, handleOffset: Float) {
    // 沿用 Rounded 矢量的轮廓。镜片两侧的曲线随镜片缩放，镜柄尾部的圆角只沿对角线移动。
    fun lens(value: Float) = LensCenter + (value - LensCenter) * lensScale
    fun lensCurve(x1: Float, y1: Float, x2: Float, y2: Float) =
        quadraticTo(lens(x1), lens(y1), lens(x2), lens(y2))
    fun handle(value: Float) = value + handleOffset
    fun handleCurve(x1: Float, y1: Float, x2: Float, y2: Float) =
        quadraticTo(handle(x1), handle(y1), handle(x2), handle(y2))

    rewind()
    moveTo(lens(380f), lens(640f))
    lensCurve(271f, 640f, 195.5f, 564.5f)
    lensCurve(120f, 489f, 120f, 380f)
    lensCurve(120f, 271f, 195.5f, 195.5f)
    lensCurve(271f, 120f, 380f, 120f)
    lensCurve(489f, 120f, 564.5f, 195.5f)
    lensCurve(640f, 271f, 640f, 380f)
    lensCurve(640f, 424f, 626f, 463f)
    lensCurve(612f, 502f, 588f, 532f)
    lineTo(handle(812f), handle(756f))
    handleCurve(823f, 767f, 823f, 784f)
    handleCurve(823f, 801f, 812f, 812f)
    handleCurve(801f, 823f, 784f, 823f)
    handleCurve(767f, 823f, 756f, 812f)
    lineTo(lens(532f), lens(588f))
    lensCurve(502f, 612f, 463f, 626f)
    lensCurve(424f, 640f, 380f, 640f)
    close()

    moveTo(lens(380f), lens(560f))
    lensCurve(455f, 560f, 507.5f, 507.5f)
    lensCurve(560f, 455f, 560f, 380f)
    lensCurve(560f, 305f, 507.5f, 252.5f)
    lensCurve(455f, 200f, 380f, 200f)
    lensCurve(305f, 200f, 252.5f, 252.5f)
    lensCurve(200f, 305f, 200f, 380f)
    lensCurve(200f, 455f, 252.5f, 507.5f)
    lensCurve(305f, 560f, 380f, 560f)
    close()
}
