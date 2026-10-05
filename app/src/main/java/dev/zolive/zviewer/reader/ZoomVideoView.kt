package dev.zolive.zviewer.reader

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.media3.ui.PlayerView
import dev.zolive.zviewer.R
import kotlin.math.abs

class ZoomVideoView(context: Context) : FrameLayout(context) {
    val playerView = LayoutInflater.from(context).inflate(R.layout.reader_video, this, false) as PlayerView
    var onTap: () -> Unit = {}
    var onPage: (Int) -> Unit = {}
    var onLayoutReady: (Boolean) -> Unit = {}
    @get:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val isVideoLayoutReady: Boolean
        get() {
            val surface = playerView.videoSurfaceView ?: return false
            val videoSize = playerView.player?.videoSize ?: return false
            if (width <= 0 || height <= 0 || surface.width <= 0 || surface.height <= 0 ||
                videoSize.width <= 0 || videoSize.height <= 0) return false
            val expectedRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            return abs(surface.width.toFloat() / surface.height - expectedRatio) <= expectedRatio * .02f
        }
    var vertical = true
    var rightToLeft = false
    private var page = -1
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var downX = 0f
    private var downY = 0f
    private var multiTouch = false
    private var gestureAxis = 0
    private var fling = false
    private var lastCommand = 0
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val swipeDistance = 48f * resources.displayMetrics.density
    private val flingVelocity = 600f * resources.displayMetrics.density
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            setZoom(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean { performClick(); return true }
        override fun onDoubleTap(event: MotionEvent): Boolean {
            multiTouch = true
            setZoom(if (zoom > 1.1f) 1f else 2.5f, event.x, event.y)
            return true
        }
        override fun onScroll(first: MotionEvent?, second: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (zoom > 1f && !multiTouch && !scaleDetector.isInProgress) {
                offsetX -= distanceX
                offsetY -= distanceY
                updateTransform()
            }
            return true
        }
        override fun onFling(first: MotionEvent?, second: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            fling = abs(if (vertical) velocityY else velocityX) >= flingVelocity
            return true
        }
    })

    init {
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun bindPage(index: Int) {
        if (page == index) return
        page = index
        zoom = 1f
        offsetX = 0f
        offsetY = 0f
        updateTransform()
        contentDescription = "第 ${index + 1} 个视频，轻点显示阅读控制，双击缩放"
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
        updateTransform()
    }

    private fun updateTransform() {
        offsetX = offsetX.coerceIn(-width * (zoom - 1f) / 2f, width * (zoom - 1f) / 2f)
        offsetY = offsetY.coerceIn(-height * (zoom - 1f) / 2f, height * (zoom - 1f) / 2f)
        playerView.scaleX = zoom
        playerView.scaleY = zoom
        playerView.translationX = offsetX
        playerView.translationY = offsetY
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        updateTransform()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        onLayoutReady(isVideoLayoutReady)
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            downY = event.y
            multiTouch = false
            gestureAxis = 0
            fling = false
        }
        if (event.pointerCount > 1) multiTouch = true
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        val distanceX = event.x - downX
        val distanceY = event.y - downY
        if (gestureAxis == 0 && maxOf(abs(distanceX), abs(distanceY)) > touchSlop) {
            gestureAxis = if (abs(distanceY) > abs(distanceX)) 1 else 2
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && !multiTouch && zoom <= 1f &&
            gestureAxis == if (vertical) 1 else 2) {
            val distance = if (vertical) distanceY else distanceX
            if (abs(distance) >= swipeDistance || fling && abs(distance) > touchSlop) {
                val direction = if (distance < 0f) 1 else -1
                onPage(if (!vertical && rightToLeft) -direction else direction)
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); onTap(); return true }
}
