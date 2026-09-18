package dev.zolive.zviewer.reader

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.abs

class ZoomImageView(context: Context) : ImageView(context) {
    var onTap: () -> Unit = {}
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastCommand = 0
    private val transform = Matrix()
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            setZoom(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean { performClick(); return true }
        override fun onDoubleTap(event: MotionEvent): Boolean {
            setZoom(if (zoom > 1.1f) 1f else 2.5f, event.x, event.y)
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun bind(image: Drawable, description: String, animate: Boolean) {
        contentDescription = description
        if (drawable !== image) {
            (drawable as? Animatable)?.stop()
            setImageDrawable(image)
            zoom = 1f; offsetX = 0f; offsetY = 0f
            updateMatrix()
        }
        val animation = image as? Animatable
        if (animate && animation?.isRunning == false) animation.start()
        if (!animate && animation?.isRunning == true) animation.stop()
    }

    fun command(sequence: Int, action: Int) {
        if (sequence == lastCommand) return
        lastCommand = sequence
        setZoom(when (action) { 1 -> zoom * 1.5f; -1 -> zoom / 1.5f; else -> 1f }, width / 2f, height / 2f)
    }

    private fun setZoom(value: Float, focusX: Float, focusY: Float) {
        val previous = zoom
        zoom = value.coerceIn(1f, 5f)
        offsetX = (offsetX - (focusX - width / 2f)) * zoom / previous + (focusX - width / 2f)
        offsetY = (offsetY - (focusY - height / 2f)) * zoom / previous + (focusY - height / 2f)
        updateMatrix()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) { updateMatrix() }

    private fun updateMatrix() {
        val image = drawable ?: return
        val imageWidth = image.intrinsicWidth.coerceAtLeast(1)
        val imageHeight = image.intrinsicHeight.coerceAtLeast(1)
        val scale = minOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight) * zoom
        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale
        val limitX = ((scaledWidth - width) / 2).coerceAtLeast(0f)
        val limitY = ((scaledHeight - height) / 2).coerceAtLeast(0f)
        offsetX = offsetX.coerceIn(-limitX, limitX)
        offsetY = offsetY.coerceIn(-limitY, limitY)
        transform.setScale(scale, scale)
        transform.postTranslate((width - scaledWidth) / 2 + offsetX, (height - scaledHeight) / 2 + offsetY)
        imageMatrix = transform
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; parent?.requestDisallowInterceptTouchEvent(zoom > 1f) }
            MotionEvent.ACTION_POINTER_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_MOVE -> {
                if (zoom > 1f && !scaleDetector.isInProgress && event.pointerCount == 1) {
                    offsetX += event.x - lastX; offsetY += event.y - lastY; updateMatrix()
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); onTap(); return true }
    override fun onDetachedFromWindow() { (drawable as? Animatable)?.stop(); super.onDetachedFromWindow() }
}
