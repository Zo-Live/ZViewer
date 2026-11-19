package dev.zolive.zviewer.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

@Composable
internal fun LibraryFilters(
    options: List<Pair<Int, String>>,
    selected: Int,
    contentInset: Dp,
    onSelect: (Int) -> Unit,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val boundary = remember(scope) { FilterOverscroll(scope) }
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    Row(
        Modifier.fillMaxWidth()
            .testTag("library-filters")
            .graphicsLayer {
                // 先裁剪和淡出，再拉伸到留白内。拖得越远，增加的幅度越小。
                compositingStrategy = CompositingStrategy.Offscreen
                val inset = contentInset.roundToPx().toFloat().coerceAtMost(size.width / 2)
                val limit = minOf(inset * .9f, size.width * .045f)
                val pull = boundary.pull
                val distance = abs(pull) * .15f
                val stretch = if (limit > 0f) limit * distance / (limit + distance) else 0f
                scaleX = if (size.width > 0f) 1f + stretch / size.width else 1f
                transformOrigin = TransformOrigin(if (pull < 0f) 1f else 0f, 0.5f)
            }
            .drawWithContent {
                val inset = contentInset.roundToPx().toFloat().coerceAtMost(size.width / 2)
                clipRect(left = inset, right = size.width - inset) {
                    this@drawWithContent.drawContent()
                }
                val maxFadeWidth = 24.dp.toPx().coerceAtMost(size.width / 2 - inset)
                if (scroll.canScrollBackward) {
                    fadeFilterEdge(isLtr, inset, minOf(maxFadeWidth, scroll.value.toFloat()))
                }
                if (scroll.canScrollForward) {
                    fadeFilterEdge(!isLtr, inset, minOf(maxFadeWidth, (scroll.maxValue - scroll.value).toFloat()))
                }
            }
            .nestedScroll(boundary)
            .horizontalScroll(scroll, overscrollEffect = null)
            .padding(horizontal = contentInset),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label, maxLines = 1) })
        }
    }
}

private class FilterOverscroll(private val scope: CoroutineScope) : NestedScrollConnection {
    var pull by mutableFloatStateOf(0f)
        private set
    private var rebound: Job? = null

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.x == 0f) return Offset.Zero
        rebound?.cancel()
        if (available.x * pull >= 0f) return Offset.Zero
        // 反向拖动先收回拉伸，再滚动筛选项。
        val consumed = available.x.coerceIn(minOf(0f, -pull), maxOf(0f, -pull))
        pull += consumed
        return Offset(consumed, 0f)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput &&
            scope.coroutineContext[MotionDurationScale]?.scaleFactor != 0f
        ) {
            pull += available.x
        }
        // 即使所有选项都可见，到达边界的手势也留在筛选行。
        return Offset(available.x, 0f)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val velocity = available.x
        if (pull != 0f || velocity != 0f) {
            rebound?.cancel()
            val direction = if (pull != 0f) sign(pull) else sign(velocity)
            rebound = scope.launch {
                AnimationState(initialValue = pull, initialVelocity = velocity).animateTo(
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 600f),
                ) {
                    pull = (value * direction).coerceAtLeast(0f) * direction
                }
            }
        }
        return Velocity(velocity, 0f)
    }
}

private fun DrawScope.fadeFilterEdge(left: Boolean, inset: Float, width: Float) {
    if (width <= 0f) return
    val start = if (left) inset else size.width - inset - width
    drawRect(
        brush = Brush.horizontalGradient(
            colors = if (left) listOf(Color.Transparent, Color.Black) else listOf(Color.Black, Color.Transparent),
            startX = start,
            endX = start + width,
        ),
        topLeft = Offset(start, 0f),
        size = Size(width, size.height),
        blendMode = BlendMode.DstIn,
    )
}
