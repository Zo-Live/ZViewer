package dev.zolive.zviewer.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal class ReaderPreviewStore(
    private val directory: File,
    session: BookSession,
) {
    private companion object {
        const val MaxBytes = 96L * 1024 * 1024
    }

    private val keys = session.mediaKeys.ifEmpty { session.pages.indices.map(Int::toString) }
    private val available = mutableMapOf<String, Long>()
    private var bytes = 0L
    var full = false
        private set

    suspend fun initialize() {
        available.putAll(withContext(Dispatchers.IO) {
            directory.listFiles().orEmpty().filter { it.extension == "webp" }
                .associate { it.name to it.length() }.filterValues { it > 0L }
        })
        bytes = available.values.sum()
        full = bytes >= MaxBytes
    }

    private fun name(index: Int) = "${keys[index]}.webp"

    operator fun contains(index: Int): Boolean = name(index) in available

    suspend fun read(index: Int): Bitmap? {
        if (index !in this) return null
        val file = File(directory, name(index))
        val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
        if (bitmap == null) {
            bytes -= available.remove(file.name) ?: 0
            full = bytes >= MaxBytes
        }
        return bitmap
    }

    suspend fun write(index: Int, bitmap: Bitmap) {
        val name = name(index)
        if (name in available || full) return
        val remaining = MaxBytes - bytes
        val written = withContext(Dispatchers.IO) {
            try {
                directory.mkdirs()
                val temporary = File.createTempFile("preview-", ".part", directory)
                try {
                    @Suppress("DEPRECATION")
                    val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                    else Bitmap.CompressFormat.WEBP
                    val encoded = temporary.outputStream().buffered().use {
                        bitmap.compress(format, 92, it)
                    }
                    currentCoroutineContext().ensureActive()
                    val size = temporary.length()
                    when {
                        !encoded -> 0L
                        size > remaining -> -1L
                        temporary.renameTo(File(directory, name)) -> size
                        else -> 0L
                    }
                } finally {
                    temporary.delete()
                }
            } catch (_: IOException) {
                0L
            }
        }
        if (written > 0L) {
            available[name] = written
            bytes += written
        }
        full = written < 0 || bytes >= MaxBytes
    }
}
