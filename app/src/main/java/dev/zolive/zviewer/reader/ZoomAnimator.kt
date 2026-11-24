package dev.zolive.zviewer.reader

import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import dev.zolive.zviewer.data.AnimationSpeed
import kotlin.math.roundToLong

internal const val MinReaderZoom = .2f
internal const val MaxReaderZoom = 5f

private val ZoomLevels = floatArrayOf(MinReaderZoom, 1f / 3.375f, 1f / 2.25f, 1f / 1.5f,
    1f, 1.5f, 2.25f, 3.375f, MaxReaderZoom)

internal class ZoomAnimator {
    private var animator: ValueAnimator? = null
    var target: Float? = null
        private set

    fun stepTarget(current: Float, action: Int): Float {
        val scale = target ?: current
        return when (action) {
            1 -> ZoomLevels.firstOrNull { it > scale + .0001f } ?: MaxReaderZoom
            -1 -> ZoomLevels.lastOrNull { it < scale - .0001f } ?: MinReaderZoom
            else -> 1f
        }
    }

    fun doubleTapTarget(current: Float): Float = if ((target ?: current) == 1f) 2.5f else 1f

    fun animate(current: Float, destination: Float, speed: AnimationSpeed, update: (Float) -> Unit) {
        cancel()
        val end = destination.coerceIn(MinReaderZoom, MaxReaderZoom)
        if (current == end) return
        target = end
        animator = ValueAnimator.ofFloat(current, end).apply {
            duration = (220 * speed.durationScale).roundToLong()
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                if (it.animatedFraction == 1f) {
                    update(end)
                    target = null
                } else {
                    update(it.animatedValue as Float)
                }
            }
            start()
        }
    }

    fun cancel() {
        animator?.cancel()
        animator = null
        target = null
    }
}
