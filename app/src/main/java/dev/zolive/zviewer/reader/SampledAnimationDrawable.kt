package dev.zolive.zviewer.reader

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import com.github.penfeizhou.animation.FrameAnimationDrawable

class SampledAnimationDrawable(
    private val animation: FrameAnimationDrawable<*>,
    private val targetWidth: Int,
    private val targetHeight: Int,
) : Drawable(), Animatable, Drawable.Callback {
    init { animation.callback = this }
    override fun getIntrinsicWidth() = targetWidth
    override fun getIntrinsicHeight() = targetHeight
    override fun onBoundsChange(bounds: Rect) { animation.bounds = bounds }
    override fun draw(canvas: Canvas) { animation.draw(canvas) }
    override fun setAlpha(alpha: Int) { animation.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { animation.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun start() { animation.start() }
    override fun stop() { animation.stop() }
    override fun isRunning() = animation.isRunning
    override fun invalidateDrawable(drawable: Drawable) { invalidateSelf() }
    override fun scheduleDrawable(drawable: Drawable, action: Runnable, time: Long) { scheduleSelf(action, time) }
    override fun unscheduleDrawable(drawable: Drawable, action: Runnable) { unscheduleSelf(action) }
}
