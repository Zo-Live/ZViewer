package dev.zolive.zviewer.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.common.Player
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.AnimationSpeed
import dev.zolive.zviewer.data.DiagnosticLog
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class SpeedHoldEnd { Release, Up, Down, Cancel }
internal data class VideoZoom(val scale: Float, val offsetX: Float, val offsetY: Float)

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
    internal var onSpeedHoldStart: () -> Boolean = { false }
    internal var onSpeedHoldEnd: (SpeedHoldEnd) -> Unit = {}
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
    private var fillScreen = false
    private var panLimitX = 0f
    private var panLimitY = 0f
    private var pendingZoom: VideoZoom? = null
    private val zoomAnimator = ZoomAnimator()
    private var multiTouch = false
    private var lastCommand = 0
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var speedHeld = false
    private var ignoreTouch = false
    private val zoomed: Boolean get() = (zoomAnimator.target ?: zoom) > 1f || zoom > 1f
    private val hasCrop: Boolean get() = fillScreen && (panLimitX > 1f || panLimitY > 1f)
    internal val acceptsSwipeSeek: Boolean
        get() = !zoomed && !hasCrop && !multiTouch && !speedHeld && !ignoreTouch && !scaleDetector.isInProgress
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
        override fun onLongPress(event: MotionEvent) {
            if (!multiTouch && !scaleDetector.isInProgress && onSpeedHoldStart()) {
                speedHeld = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        override fun onScroll(first: MotionEvent?, second: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (zoom > 1f && !multiTouch && !scaleDetector.isInProgress) {
                zoomAnimator.cancel()
                offsetX -= distanceX
                offsetY -= distanceY
                updateTransform()
            } else if (hasCrop && !multiTouch && !scaleDetector.isInProgress && first != null) {
                val horizontal = abs(second.x - first.x) > abs(second.y - first.y)
                val distance = if (horizontal) distanceX else distanceY
                val offset = if (horizontal) offsetX else offsetY
                val limit = if (horizontal) panLimitX else panLimitY
                if (distance < 0f && offset < limit || distance > 0f && offset > -limit) {
                    offsetX -= distanceX
                    offsetY -= distanceY
                    updateTransform()
                } else {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
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
    internal var zoomAnimationSpeed = AnimationSpeed.Moderate

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
        pendingZoom = null
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
        if (fillScreen) updateTransform()
    }

    fun setCoverBackground(color: Int) {
        coverView.setBackgroundColor(color)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    internal fun setFillScreen(fill: Boolean) {
        if (fillScreen == fill) return
        fillScreen = fill
        playerView.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        coverView.scaleType = if (fill) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        // 只在阅读视口裁切，保留播放器与封面各自超出的部分供平移。
        var container = textureView.parent as? ViewGroup
        while (container != null) {
            container.clipChildren = !fill
            if (container === this) break
            container = container.parent as? ViewGroup
        }
        offsetX = 0f
        offsetY = 0f
        updateTransform()
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

    internal fun zoomPosition() = pendingZoom ?: VideoZoom(zoom, offsetX, offsetY)

    internal fun restoreZoom(position: VideoZoom) {
        pendingZoom = position
        updateTransform()
    }

    internal fun captureVideoFrame(): Bitmap? {
        if (coverView.visibility == VISIBLE || !textureView.isAvailable || width <= 0 || height <= 0) return null
        val textureScale = minOf(1f, 1600f / maxOf(textureView.width, textureView.height).coerceAtLeast(1))
        val source = textureView.getBitmap((textureView.width * textureScale).roundToInt().coerceAtLeast(1),
            (textureView.height * textureScale).roundToInt().coerceAtLeast(1)) ?: return null
        val scale = minOf(1f, 1600f / maxOf(width, height))
        val frame = Bitmap.createBitmap((width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val centerX = width / 2f + offsetX
        val centerY = height / 2f + offsetY
        val halfWidth = textureView.width * zoom / 2f
        val halfHeight = textureView.height * zoom / 2f
        val rect = RectF(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
        Canvas(frame).apply {
            scale(scale, scale)
            drawBitmap(source, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return frame
    }

    fun command(sequence: Int, action: Int) {
        if (sequence == lastCommand) return
        lastCommand = sequence
        if (action == 0 && fillScreen) {
            offsetX = 0f
            offsetY = 0f
            updateTransform()
        }
        val target = zoomAnimator.target ?: zoom
        animateZoom(when (action) { 1 -> target * 1.5f; -1 -> target / 1.5f; else -> 1f }, width / 2f, height / 2f)
    }

    private fun animateZoom(value: Float, focusX: Float, focusY: Float) {
        zoomAnimator.animate(zoom, value, zoomAnimationSpeed) { setZoom(it, focusX, focusY) }
    }

    private fun setZoom(value: Float, focusX: Float, focusY: Float) {
        val previous = zoom
        zoom = value.coerceIn(1f, 5f)
        offsetX = (offsetX - (focusX - width / 2f)) * zoom / previous + (focusX - width / 2f)
        offsetY = (offsetY - (focusY - height / 2f)) * zoom / previous + (focusY - height / 2f)
        updateTransform()
    }

    private fun updateTransform() {
        if (width > 0 && height > 0 && (!fillScreen || isVideoLayoutReady)) {
            pendingZoom?.let { position ->
                zoom = position.scale
                offsetX = position.offsetX
                offsetY = position.offsetY
                pendingZoom = null
            }
        }
        var frameWidth = width
        var frameHeight = height
        if (fillScreen) {
            val cover = coverView.drawable
            if (isVideoLayoutReady) {
                frameWidth = maxOf(width, textureView.width)
                frameHeight = maxOf(height, textureView.height)
            } else if (cover != null && cover.intrinsicWidth > 0 && cover.intrinsicHeight > 0) {
                val ratio = cover.intrinsicWidth.toFloat() / cover.intrinsicHeight
                frameWidth = maxOf(width, (height * ratio).roundToInt())
                frameHeight = maxOf(height, (width / ratio).roundToInt())
            }
        }
        panLimitX = (frameWidth * zoom - width) / 2f
        panLimitY = (frameHeight * zoom - height) / 2f
        offsetX = offsetX.coerceIn(-panLimitX, panLimitX)
        offsetY = offsetY.coerceIn(-panLimitY, panLimitY)
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
        if (fillScreen) updateTransform()
        onLayoutReady(isVideoLayoutReady)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        retainedTexture?.let(textureView::setSurfaceTexture)
        retainedTexture = null
    }

    override fun onDetachedFromWindow() {
        cancelSpeedHold()
        zoomAnimator.cancel()
        super.onDetachedFromWindow()
        if (retainedTexture == null) onLayoutReady(false)
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    internal fun cancelSpeedHold() {
        if (!speedHeld) return
        endSpeedHold(null)
        ignoreTouch = true
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchStartX = event.x
            touchStartY = event.y
            multiTouch = false
            ignoreTouch = false
            parent?.requestDisallowInterceptTouchEvent(zoomed || hasCrop)
        }
        if (ignoreTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (speedHeld) {
                    endSpeedHold(null)
                    ignoreTouch = true
                    cancelDetectors(event)
                    return true
                }
                multiTouch = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (speedHeld) return true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (speedHeld) {
                    endSpeedHold(event.takeIf { it.actionMasked == MotionEvent.ACTION_UP })
                    cancelDetectors(event)
                    return true
                }
            }
        }
        if (event.pointerCount > 1) multiTouch = true
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        return true
    }

    private fun endSpeedHold(up: MotionEvent?) {
        speedHeld = false
        val result = if (up == null) SpeedHoldEnd.Cancel else {
            val horizontal = up.x - touchStartX
            val vertical = up.y - touchStartY
            val threshold = maxOf(4 * touchSlop.toFloat(), 32 * resources.displayMetrics.density)
            when {
                abs(vertical) < threshold || abs(vertical) <= 1.5f * abs(horizontal) -> SpeedHoldEnd.Release
                vertical < 0 -> SpeedHoldEnd.Up
                else -> SpeedHoldEnd.Down
            }
        }
        onSpeedHoldEnd(result)
    }

    private fun cancelDetectors(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        gestureDetector.onTouchEvent(cancel)
        scaleDetector.onTouchEvent(cancel)
        cancel.recycle()
    }

    override fun performClick(): Boolean { super.performClick(); onTap(); return true }
}
