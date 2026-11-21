package dev.zolive.zviewer.reader

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.util.Size
import com.github.penfeizhou.animation.FrameAnimationDrawable
import com.github.penfeizhou.animation.apng.APNGDrawable
import com.github.penfeizhou.animation.apng.decode.APNGDecoder
import com.github.penfeizhou.animation.avif.AVIFDrawable
import com.github.penfeizhou.animation.loader.FileLoader
import dev.zolive.zviewer.data.ReaderException
import java.io.DataInputStream
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

object ImageLoader {
    private const val MAX_ANIMATION_PIXELS = 4_000_000
    private const val MAX_SOURCE_PIXELS = 100_000_000L

    fun loadPreview(source: ImageDecoder.Source, width: Int, maxPixels: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val size = info.size
            validateSize(size.width, size.height)
            val scale = targetScale(size.width, size.height, width, maxPixels)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(scaled(size.width, scale), scaled(size.height, scale))
        }

    fun load(file: File, width: Int, animationWidth: Int = width): Drawable {
        val apngSize = apngSize(file)
        val drawable = when {
            apngSize != null -> APNGDrawable(ReaderApngDecoder(file, animationSample(apngSize, animationWidth)))
                .apply { setAutoPlay(false) }
            file.extension.equals("avif", true) -> AVIFDrawable.fromFile(file.absolutePath).apply { setAutoPlay(false) }
            else -> ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val targetWidth = if (info.isAnimated) animationWidth else width
                val maxPixels = if (info.isAnimated) MAX_ANIMATION_PIXELS else 16_000_000
                val scale = targetScale(info.size.width, info.size.height, targetWidth, maxPixels)
                decoder.setTargetSize(scaled(info.size.width, scale), scaled(info.size.height, scale))
            }
        }
        validateSize(drawable.intrinsicWidth, drawable.intrinsicHeight)
        if (drawable is FrameAnimationDrawable<*>) {
            val scale = targetScale(drawable.intrinsicWidth, drawable.intrinsicHeight,
                if (apngSize != null) animationWidth else width, MAX_ANIMATION_PIXELS)
            if (scale < 1f) return SampledAnimationDrawable(drawable,
                scaled(drawable.intrinsicWidth, scale), scaled(drawable.intrinsicHeight, scale))
        }
        return drawable
    }

    private fun targetScale(width: Int, height: Int, targetWidth: Int, maxPixels: Int): Float =
        minOf(1f, targetWidth.coerceAtMost(3200).toFloat() / width, sqrt(maxPixels / (width.toFloat() * height)))

    private fun scaled(dimension: Int, scale: Float): Int = (dimension * scale).roundToInt().coerceAtLeast(1)

    private fun validateSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) throw ReaderException("图片解码失败，文件可能损坏或编码不受设备支持。")
        if (width.toLong() * height > MAX_SOURCE_PIXELS) throw ReaderException("图片分辨率过高，请先缩小图片。")
    }

    private fun animationSample(size: Size, targetWidth: Int): Int {
        val width = size.width
        val height = size.height
        validateSize(width, height)
        val scale = targetScale(width, height, targetWidth, MAX_ANIMATION_PIXELS)
        val ratio = minOf(width / scaled(width, scale), height / scaled(height, scale))
        var sample = Integer.highestOneBit(ratio)
        while (sample <= minOf(width, height) / 2 &&
            (width / sample).toLong() * (height / sample) > MAX_ANIMATION_PIXELS) {
            sample *= 2
        }
        return sample
    }

    private class ReaderApngDecoder(file: File, sample: Int) :
        APNGDecoder(FileLoader(file.absolutePath), null) {
        // APNGDecoder 读取帧表时就会分配缓冲区，采样倍数必须在读取前设置。
        init { sampleSize = sample }

        override fun getDesiredSample(width: Int, height: Int): Int = sampleSize
    }

    fun isApng(file: File): Boolean = apngSize(file) != null

    private fun apngSize(file: File): Size? = DataInputStream(file.inputStream().buffered()).use { input ->
        val fileSize = file.length()
        if (fileSize < 33 || input.readLong() != -8552249625308161526L) return null
        if (input.readInt() != 13 || input.readInt() != 0x49484452) return null // IHDR
        val dimensions = Size(input.readInt(), input.readInt())
        if (input.skipBytes(9) != 9) return null
        var inspected = 33L
        while (inspected + 12 <= fileSize && inspected < 1024 * 1024) {
            val size = input.readInt()
            val type = input.readInt()
            if (type == 0x6163544C) return dimensions // acTL
            if (type == 0x49444154 || type == 0x49454E44 || size < 0) return null
            val skip = size.toLong() + 4
            if (skip > fileSize - inspected - 8) return null
            var remaining = skip
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped == 0L) return null
                remaining -= skipped
            }
            inspected += size.toLong() + 12
        }
        null
    }
}
