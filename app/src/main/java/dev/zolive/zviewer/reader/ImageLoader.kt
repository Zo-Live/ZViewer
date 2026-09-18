package dev.zolive.zviewer.reader

import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import com.github.penfeizhou.animation.FrameAnimationDrawable
import com.github.penfeizhou.animation.apng.APNGDrawable
import com.github.penfeizhou.animation.avif.AVIFDrawable
import dev.zolive.zviewer.data.ReaderException
import java.io.DataInputStream
import java.io.File
import kotlin.math.roundToInt

object ImageLoader {
    fun load(file: File, width: Int): Drawable {
        val drawable = when {
            isApng(file) -> APNGDrawable.fromFile(file.absolutePath).apply { setAutoPlay(false) }
            file.extension.equals("avif", true) -> AVIFDrawable.fromFile(file.absolutePath).apply { setAutoPlay(false) }
            else -> ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = minOf(1f, width.coerceAtMost(3200).toFloat() / info.size.width,
                    kotlin.math.sqrt(16_000_000f / (info.size.width.toFloat() * info.size.height)))
                decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1))
            }
        }
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) throw ReaderException("图片解码失败，文件可能损坏或编码不受设备支持。")
        if (drawable.intrinsicWidth.toLong() * drawable.intrinsicHeight > 100_000_000L) {
            throw ReaderException("图片分辨率过高，请先缩小图片。")
        }
        if (drawable is FrameAnimationDrawable<*>) {
            val scale = minOf(1f, width.toFloat() / drawable.intrinsicWidth,
                kotlin.math.sqrt(4_000_000f / (drawable.intrinsicWidth.toFloat() * drawable.intrinsicHeight)))
            if (scale < 1f) return SampledAnimationDrawable(drawable,
                (drawable.intrinsicWidth * scale).roundToInt().coerceAtLeast(1),
                (drawable.intrinsicHeight * scale).roundToInt().coerceAtLeast(1))
        }
        return drawable
    }

    fun isApng(file: File): Boolean = DataInputStream(file.inputStream().buffered()).use { input ->
        if (file.length() < 8 || input.readLong() != -8552249625308161526L) return false
        var inspected = 8L
        while (inspected + 12 <= file.length() && inspected < 1024 * 1024) {
            val size = input.readInt()
            val type = input.readInt()
            if (type == 0x6163544C) return true
            if (type == 0x49444154 || type == 0x49454E44 || size < 0) return false
            val skip = size.toLong() + 4
            if (skip > file.length() - inspected - 8) return false
            var remaining = skip
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped == 0L) return false
                remaining -= skipped
            }
            inspected += size.toLong() + 12
        }
        false
    }
}
