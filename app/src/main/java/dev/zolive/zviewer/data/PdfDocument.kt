package dev.zolive.zviewer.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

class PdfDocument internal constructor(renderer: PdfRenderer) {
    private val lock = Mutex()
    private var renderer: PdfRenderer? = renderer
    internal val pageCount = renderer.pageCount

    internal suspend fun render(index: Int, width: Int, maxPixels: Int): Bitmap {
        var rendered: Bitmap? = null
        try {
            return withContext(Dispatchers.IO) {
                lock.withLock {
                    currentCoroutineContext().ensureActive()
                    val openRenderer = renderer ?: throw CancellationException("PDF 阅读会话已关闭")
                    renderPdfPage(openRenderer, index, width, maxPixels).also { rendered = it }
                }
            }
        } catch (error: CancellationException) {
            rendered?.recycle()
            throw error
        }
    }

    internal suspend fun close() = lock.withLock {
        renderer?.close()
        renderer = null
    }
}

internal fun renderPdfPage(renderer: PdfRenderer, index: Int, width: Int, maxPixels: Int = 12_000_000): Bitmap =
    renderer.openPage(index).use { page ->
        val renderWidth = width.coerceIn(480, 2560)
        val renderHeight = renderWidth.toDouble() * page.height / page.width
        val scale = minOf(1.0, sqrt(maxPixels / (renderWidth * renderHeight)), 8192 / renderHeight)
        val bitmap = Bitmap.createBitmap(
            (renderWidth * scale).roundToInt().coerceAtLeast(1),
            (renderHeight * scale).roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }
