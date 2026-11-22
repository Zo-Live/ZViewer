package dev.zolive.zviewer.reader

import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import dev.zolive.zviewer.data.AnimationSpeed
import kotlin.math.roundToLong

internal class ZoomAnimator {
    private var animator: ValueAnimator? = null
    var target: Float? = null
        private set

    fun animate(current: Float, destination: Float, speed: AnimationSpeed, update: (Float) -> Unit) {
        cancel()
        val end = destination.coerceIn(1f, 5f)
        if (current == end) return
        target = end
        animator = ValueAnimator.ofFloat(current, end).apply {
            duration = (220 * speed.durationScale).roundToLong()
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                update(it.animatedValue as Float)
                if (it.animatedFraction == 1f) target = null
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
