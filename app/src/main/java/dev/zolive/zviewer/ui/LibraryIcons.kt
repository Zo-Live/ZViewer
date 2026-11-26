package dev.zolive.zviewer.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import dev.zolive.zviewer.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal enum class LibraryFillIcon(
    @DrawableRes val outline: Int,
    @DrawableRes val filled: Int,
    private val origin: Offset,
    private val bounds: Rect,
) {
    Book(R.drawable.ic_symbol_book, R.drawable.ic_symbol_book_filled, Offset(540f, 300f), Rect(160f, 80f, 800f, 880f)),
    Bookmark(R.drawable.ic_symbol_bookmark, R.drawable.ic_symbol_bookmark_filled, Offset(480f, 120f), Rect(200f, 120f, 760f, 800f)),
    MenuBook(R.drawable.ic_symbol_menu_book, R.drawable.ic_symbol_menu_book_filled, Offset(480f, 510f), Rect(40f, 160f, 920f, 779f)),
    Play(R.drawable.ic_symbol_play_arrow, R.drawable.ic_symbol_play_arrow_filled, Offset(480f, 480f), Rect(320f, 233f, 725f, 727f));

    fun fillBounds(size: Size, progress: Float): Rect {
        val widthScale = size.width / 960f
        val heightScale = size.height / 960f
        if (this == Bookmark) {
            return Rect(0f, 0f, size.width, lerp(bounds.top, bounds.bottom, progress) * heightScale)
        }
        return Rect(
            lerp(origin.x, bounds.left, progress) * widthScale,
            lerp(origin.y, bounds.top, progress) * heightScale,
            lerp(origin.x, bounds.right, progress) * widthScale,
            lerp(origin.y, bounds.bottom, progress) * heightScale,
        )
    }
}

@Composable
internal fun LibraryFillingIcon(
    icon: LibraryFillIcon,
    progress: State<Float>?,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    if (progress == null) {
        Icon(painterResource(icon.outline), description, modifier)
    } else {
        Box(modifier, propagateMinConstraints = true) {
            Icon(painterResource(icon.outline), description, Modifier.iconFillClip(icon, progress, filled = false))
            Icon(painterResource(icon.filled), null, Modifier.matchParentSize().iconFillClip(icon, progress, filled = true))
        }
    }
}

private fun Modifier.iconFillClip(icon: LibraryFillIcon, progress: State<Float>, filled: Boolean) = drawWithContent {
    val fill = FastOutSlowInEasing.transform(progress.value)
    when {
        fill <= 0f -> if (!filled) drawContent()
        fill >= 1f -> if (filled) drawContent()
        else -> {
            val bounds = icon.fillBounds(size, fill)
            clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom,
                clipOp = if (filled) ClipOp.Intersect else ClipOp.Difference) { this@drawWithContent.drawContent() }
        }
    }
}

@Composable
internal fun LibraryHistoryIcon(progress: State<Float>) {
    val color = LocalContentColor.current
    Box {
        Icon(painterResource(R.drawable.ic_symbol_history_arrow), null, Modifier.graphicsLayer {
            rotationZ = -360f * FastOutSlowInEasing.transform(progress.value)
        })
        Canvas(Modifier.size(24.dp)) {
            val unit = size.width / 24f
            val stroke = 2f * unit
            drawLine(color, center, center + Offset(2.8f, 2.8f) * unit, stroke, StrokeCap.Round)
            rotate(360f * FastOutSlowInEasing.transform(progress.value)) {
                drawLine(color, center, center - Offset(0f, 4f * unit), stroke, StrokeCap.Round)
            }
        }
    }
}

internal enum class LibraryActionIcon(@StringRes val label: Int) {
    Refresh(R.string.refresh_library),
    Sort(R.string.sort),
    Search(R.string.search_comics),
}

@Composable
internal fun LibraryActionIconButton(
    icon: LibraryActionIcon,
    enabled: Boolean = true,
    active: Boolean = true,
    onClick: () -> Unit,
) {
    val description = stringResource(icon.label)
    AnimatedIconButton(enabled = enabled, active = active, onClick = onClick) { progress ->
        when (icon) {
            LibraryActionIcon.Refresh -> Icon(painterResource(R.drawable.ic_symbol_refresh), description, Modifier.graphicsLayer {
                rotationZ = 360f * FastOutSlowInEasing.transform(progress.value)
            })
            LibraryActionIcon.Sort -> LibrarySortIcon(description) { progress.value }
            LibraryActionIcon.Search -> LibrarySearchIcon(description) { progress.value }
        }
    }
}

@Composable
private fun LibrarySearchIcon(description: String, progress: () -> Float) {
    val color = LocalContentColor.current
    Icon(painterResource(R.drawable.ic_symbol_search), description, Modifier.drawWithContent {
        val angle = PI * progress()
        val faceScale = abs(cos(angle)).toFloat()
        if (faceScale == 1f) {
            drawContent()
        } else {
            val unit = size.width / 960f
            val depth = 80f * unit * sin(angle).toFloat()
            // 当镜片开口被投影厚度盖住时，以等宽的圆头侧面连起轮廓。
            if (faceScale * 360f * unit <= depth) {
                drawLine(color, Offset(224f, 224f) * unit, Offset(784f, 784f) * unit,
                    strokeWidth = depth, cap = StrokeCap.Round)
            }
            withTransform({ rotate(45f) }) {
                // 将同一个 Rounded 矢量图沿厚度投影，保留未翻到侧面时的镜片开口。
                val slices = 8
                repeat(slices + 1) { slice ->
                    val offset = depth * (slice.toFloat() / slices - .5f)
                    translate(top = offset) {
                        withTransform({
                            scale(1f, faceScale.coerceAtLeast(.001f))
                            rotate(-45f)
                        }) { this@drawWithContent.drawContent() }
                    }
                }
            }
        }
    })
}

@Composable
private fun LibrarySortIcon(description: String, progress: () -> Float) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp).semantics { contentDescription = description }) {
        val unit = size.width / 24f
        val phase = progress()
        repeat(3) { row ->
            val time = ((phase - row * .18f) / .64f).coerceIn(0f, 1f)
            val widthFraction = when {
                time < .45f -> lerp(1f, .66f, FastOutSlowInEasing.transform(time / .45f))
                time < .8f -> lerp(.66f, 1.07f, FastOutSlowInEasing.transform((time - .45f) / .35f))
                else -> lerp(1.07f, 1f, FastOutSlowInEasing.transform((time - .8f) / .2f))
            }
            val lineWidth = (18f - row * 6f) * unit * widthFraction
            val start = if (layoutDirection == LayoutDirection.Ltr) 3f * unit else size.width - 3f * unit - lineWidth
            drawRoundRect(color, Offset(start, (6f + row * 5f) * unit), Size(lineWidth, 2f * unit), CornerRadius(unit))
        }
    }
}
