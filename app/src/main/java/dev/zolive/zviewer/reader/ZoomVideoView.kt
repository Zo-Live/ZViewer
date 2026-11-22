package dev.zolive.zviewer.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.ui.PlayerView
import androidx.media3.common.Player
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.DiagnosticLog
import kotlin.math.abs

class ZoomVideoView(context: Context) : FrameLayout(context) {
    val playerView = LayoutInflater.from(context).inflate(R.layout.reader_video, this, false) as PlayerView
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private val textureView = playerView.videoSurfaceView as TextureView
    private var retainedTexture: SurfaceTexture? = null
    private var playerSurfaceListener: TextureView.SurfaceTextureListener? = null
    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            playerSurfaceListener?.onSurfaceTextureAvailable(texture, width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            playerSurfaceListener?.onSurfaceTextureSizeChanged(texture, width, height)
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
            playerSurfaceListener?.onSurfaceTextureUpdated(texture)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            retainedTexture = texture
            return false
        }
    }

    /**
     * 首帧封面盖住尚未就绪的 TextureView，播放器仍可在封面下方准备画面。
     */
    private val coverView = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    var onTap: () -> Unit = {}
    var onLayoutReady: (Boolean) -> Unit = {}
    val isSurfaceAvailable: Boolean
        get() = (textureView.isAvailable || retainedTexture != null) && textureView.width > 0 && textureView.height > 0

    val isVideoLayoutReady: Boolean
        get() {
            val videoSize = playerView.player?.videoSize ?: return false
            if (width <= 0 || height <= 0 || textureView.width <= 0 || textureView.height <= 0 ||
                videoSize.width <= 0 || videoSize.height <= 0) return false
            val expectedRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            val expectedWidth = textureView.height * expectedRatio
            return abs(textureView.width - expectedWidth) <= maxOf(1f, expectedRatio, expectedWidth * .02f)
        }
    private var page = -1
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private val zoomAnimator = ZoomAnimator()
    private var multiTouch = false
    private var lastCommand = 0
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            zoomAnimator.cancel()
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
            if (doubleTapZoomEnabled) {
                multiTouch = true
                animateZoom(if ((zoomAnimator.target ?: zoom) > 1.1f) 1f else 2.5f, event.x, event.y)
            }
            return true
        }
        override fun onScroll(first: MotionEvent?, second: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (zoom > 1f && !multiTouch && !scaleDetector.isInProgress) {
                zoomAnimator.cancel()
                offsetX -= distanceX
                offsetY -= distanceY
                updateTransform()
            }
            return true
        }
    })
    var doubleTapZoomEnabled = true
        set(value) {
            if (field == value) return
            field = value
            scaleDetector.isQuickScaleEnabled = value
        }

    init {
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(coverView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
        zoomAnimator.cancel()
        zoom = 1f
        offsetX = 0f
        offsetY = 0f
        updateTransform()
    }

    /** 设置或清除首帧封面；同一 Bitmap 重复设置时不做无谓刷新。 */
    fun bindCover(bitmap: Bitmap?) {
        val current = (coverView.drawable as? BitmapDrawable)?.bitmap
        if (current === bitmap) return
        DiagnosticLog.log("View", "bindCover index=$page view=${hashCode()} " +
            (bitmap?.let { "${it.width}x${it.height}" } ?: "null"))
        coverView.setImageBitmap(bitmap)
    }

    fun setCoverBackground(color: Int) {
        coverView.setBackgroundColor(color)
    }

    fun bindPlayer(player: Player?) {
        if (playerView.player === player) return
        textureView.surfaceTextureListener = playerSurfaceListener
        playerView.player = player
        retainedTexture?.release()
        retainedTexture = null
        playerSurfaceListener = textureView.surfaceTextureListener
        if (player != null) textureView.surfaceTextureListener = surfaceListener
    }

    fun showVideoFrame(visible: Boolean) {
        coverView.visibility = if (visible) INVISIBLE else VISIBLE
    }

    fun command(sequence: Int, action: Int) {
        if (sequence == lastCommand) return
        lastCommand = sequence
        val target = zoomAnimator.target ?: zoom
        animateZoom(when (action) { 1 -> target * 1.5f; -1 -> target / 1.5f; else -> 1f }, width / 2f, height / 2f)
    }

    private fun animateZoom(value: Float, focusX: Float, focusY: Float) {
        zoomAnimator.animate(zoom, value) { setZoom(it, focusX, focusY) }
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
        coverView.scaleX = zoom
        coverView.scaleY = zoom
        coverView.translationX = offsetX
        coverView.translationY = offsetY
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        clipBounds = Rect(0, 0, width, height)
        updateTransform()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        onLayoutReady(isVideoLayoutReady)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        retainedTexture?.let(textureView::setSurfaceTexture)
        retainedTexture = null
    }

    override fun onDetachedFromWindow() {
        zoomAnimator.cancel()
        super.onDetachedFromWindow()
        if (retainedTexture == null) onLayoutReady(false)
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false
                // 未放大时把手势交给外层 Pager，实现跟手的翻页过渡；放大后自行处理平移。
                parent?.requestDisallowInterceptTouchEvent((zoomAnimator.target ?: zoom) > 1f || zoom > 1f)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        if (event.pointerCount > 1) multiTouch = true
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean { super.performClick(); onTap(); return true }
}
