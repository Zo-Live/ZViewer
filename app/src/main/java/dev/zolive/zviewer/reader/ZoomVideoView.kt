package dev.zolive.zviewer.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.ui.PlayerView
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.DiagnosticLog
import kotlin.math.abs

class ZoomVideoView(context: Context) : FrameLayout(context) {
    val playerView = LayoutInflater.from(context).inflate(R.layout.reader_video, this, false) as PlayerView

    /**
     * 首帧封面，位于播放器视图下方。播放器尚未渲染画面时 TextureView 是透明的，
     * 因此离屏、暂停或仍在准备中的视频会显示这一帧，而不是黑屏。
     */
    private val coverView = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    var onTap: () -> Unit = {}
    var onLayoutReady: (Boolean) -> Unit = {}
    @get:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val isSurfaceAvailable: Boolean
        get() {
            val surface = playerView.videoSurfaceView as? android.view.TextureView ?: return false
            return surface.isAvailable && surface.width > 0 && surface.height > 0
        }
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
    private var page = -1
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var multiTouch = false
    private var lastCommand = 0
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
    })

    init {
        addView(coverView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Media3 的黑色快门会盖住封面；设为透明后，首帧渲染前的空白 TextureView 能让封面透出。
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_shutter)?.apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = GONE
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun bindPage(index: Int) {
        if (page == index) return
        DiagnosticLog.log("View", "bindPage index=$index view=${hashCode()}")
        page = index
        zoom = 1f
        offsetX = 0f
        offsetY = 0f
        updateTransform()
        contentDescription = "第 ${index + 1} 个视频，轻点显示阅读控制，双击缩放"
    }

    /** 设置或清除首帧封面；同一 Bitmap 重复设置时不做无谓刷新。 */
    fun bindCover(bitmap: Bitmap?) {
        val current = (coverView.drawable as? BitmapDrawable)?.bitmap
        if (current === bitmap) return
        DiagnosticLog.log("View", "bindCover index=$page view=${hashCode()} " +
            (bitmap?.let { "${it.width}x${it.height}" } ?: "null"))
        coverView.setImageBitmap(bitmap)
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
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        onLayoutReady(false)
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false
                // 未放大时把手势交给外层 Pager，实现跟手的翻页过渡；放大后自行处理平移。
                parent?.requestDisallowInterceptTouchEvent(zoom > 1f)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        if (event.pointerCount > 1) multiTouch = true
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean { super.performClick(); onTap(); return true }
}
