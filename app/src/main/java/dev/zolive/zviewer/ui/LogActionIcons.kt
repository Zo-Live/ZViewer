package dev.zolive.zviewer.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.util.lerp
import dev.zolive.zviewer.R
import kotlin.math.abs

// Material Symbols Rounded 的 960 × 960 视口坐标。
private const val SymbolViewport = 960f
private val ShareDotCenters = listOf(Offset(720f, 200f), Offset(240f, 480f), Offset(720f, 760f))

@Composable
internal fun LogShareIcon(progress: State<Float>) {
    val color = LocalContentColor.current
    Icon(
        painterResource(R.drawable.ic_symbol_share),
        stringResource(R.string.share_log),
        Modifier.drawWithContent {
            drawContent()
            val dotCount = ShareDotCenters.size
            val phase = progress.value * (2 * dotCount)
            val scale = size.width / SymbolViewport
            ShareDotCenters.forEachIndexed { index, dot ->
                val expansion = phase - index
                val contraction = 1f - (phase - dotCount - index)
                val fraction = minOf(expansion, contraction).coerceIn(0f, 1f)
                if (fraction > 0f) {
                    // 略盖过半径 40 的内孔边缘，填满矢量描边的抗锯齿像素。
                    val radius = 56f * scale * FastOutSlowInEasing.transform(fraction)
                    drawCircle(color, radius, dot * scale)
                }
            }
        },
    )
}

@Composable
internal fun LogCopyIcon(progress: State<Float>) {
    val color = LocalContentColor.current
    Icon(
        painterResource(R.drawable.ic_symbol_content_copy),
        stringResource(R.string.copy_log),
        Modifier.drawWithCache {
            val reveal = Path()
            val scale = size.width / SymbolViewport
            // 填充样式补齐前页内框，略盖过原描边内侧的抗锯齿边缘。
            val fillOrigin = Offset(336f, 136f) * scale
            val fillSize = Size(408f * scale, 528f * scale)
            onDrawWithContent {
                drawContent()
                val fraction = FastOutSlowInEasing.transform(1f - abs(2f * progress.value - 1f))
                if (fraction == 1f) {
                    drawRect(color, fillOrigin, fillSize)
                } else if (fraction > 0f) {
                    // 斜线 x - y 从前页内框的右上角扫到左下角。
                    val offset = lerp(720f - 160f, 360f - 640f, fraction) / SymbolViewport
                    reveal.rewind()
                    reveal.moveTo(offset * size.width, 0f)
                    reveal.lineTo(size.width, 0f)
                    reveal.lineTo(size.width, (1f - offset) * size.height)
                    reveal.close()
                    clipPath(reveal) {
                        drawRect(color, fillOrigin, fillSize)
                    }
                }
            }
        },
    )
}
