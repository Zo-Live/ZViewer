package dev.zolive.zviewer.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import dev.zolive.zviewer.data.AnimationSpeed
import kotlin.math.abs
import kotlin.math.sign

/** 图片滚动到边缘后，把同一次拖动交给外层分页，松手时最多切换一个区域。 */
internal class ReaderImageRunScroll(
    private val pager: PagerState,
    private val vertical: Boolean,
    reverseDirection: Boolean,
    private val flingThreshold: Float,
    private val speed: AnimationSpeed,
) : NestedScrollConnection {
    private var origin by mutableStateOf<Int?>(null)
    val settledPage: Int get() = origin ?: pager.settledPage
    val transitioning: Boolean get() = origin != null

    private val direction = if (reverseDirection) -1f else 1f
    private fun axis(offset: Offset): Float = (if (vertical) offset.y else offset.x) * direction
    private fun offset(delta: Float): Offset =
        if (vertical) Offset(0f, delta * direction) else Offset(delta * direction, 0f)

    private fun positionFrom(start: Int): Float = pager.currentPage - start + pager.currentPageOffsetFraction

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val start = origin ?: return Offset.Zero
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        val position = positionFrom(start)
        if (position == 0f) return Offset.Zero
        val delta = axis(available)
        val pageSize = pager.layoutInfo.pageSize
        val bounds = if (position > 0f) 0f..1f else -1f..0f
        val consumed = pager.dispatchRawDelta(delta.coerceIn(
            (bounds.start - position) * pageSize, (bounds.endInclusive - position) * pageSize,
        ))
        // 到达相邻页后保留该页，直到松手或反向拖回。
        return offset(if (delta * position > 0f) delta else consumed)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val delta = axis(available)
        if (delta == 0f || source != NestedScrollSource.UserInput || pager.isScrollInProgress) {
            return offset(delta)
        }
        val start = origin ?: pager.settledPage
        if (delta > 0f && start == pager.pageCount - 1 || delta < 0f && start == 0) return offset(delta)
        origin = start
        val pageSize = pager.layoutInfo.pageSize.toFloat()
        val position = positionFrom(start) * pageSize
        pager.dispatchRawDelta(delta.coerceIn(-pageSize - position, pageSize - position))
        return offset(delta)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val start = origin ?: return Velocity.Zero
        val consumed = if (vertical) Velocity(0f, available.y) else Velocity(available.x, 0f)
        val velocity = (if (vertical) available.y else available.x) * direction
        val position = positionFrom(start)
        val step = when {
            position == 0f -> 0
            abs(velocity) >= flingThreshold -> if (velocity * position > 0f) position.sign.toInt() else 0
            abs(position) >= .5f -> position.sign.toInt()
            else -> 0
        }
        try {
            pager.animateScrollToPage(start + step, animationSpec = spring(
                stiffness = speed.stiffness(Spring.StiffnessMediumLow), visibilityThreshold = 1f,
            ))
        } finally {
            origin = null
        }
        return consumed
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (vertical) Velocity(0f, available.y) else Velocity(available.x, 0f)
}
