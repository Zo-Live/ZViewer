package dev.zolive.zviewer.data

import java.security.MessageDigest
import java.util.Locale

val imageExtensions = setOf("jpg", "jpeg", "png", "apng", "webp", "bmp", "heif", "heic", "gif", "avif")
val archiveExtensions = setOf("zip", "cbz", "rar", "cbr", "7z")

fun String.extensionLower(): String = substringAfterLast('.', "").lowercase(Locale.ROOT)
fun String.stableId(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
fun isImage(name: String): Boolean = name.extensionLower() in imageExtensions &&
    name.replace('\\', '/').split('/').none { it.startsWith('.') || it == "__MACOSX" }

object NaturalOrder : Comparator<String> {
    private val parts = Regex("[0-9]+|[^0-9]+")
    override fun compare(first: String, second: String): Int {
        val left = parts.findAll(first.lowercase(Locale.ROOT)).map { it.value }.toList()
        val right = parts.findAll(second.lowercase(Locale.ROOT)).map { it.value }.toList()
        for (index in 0 until minOf(left.size, right.size)) {
            val leftPart = left[index]
            val rightPart = right[index]
            val result = if (leftPart.first().isDigit() && rightPart.first().isDigit()) {
                val leftNumber = leftPart.trimStart('0').ifEmpty { "0" }
                val rightNumber = rightPart.trimStart('0').ifEmpty { "0" }
                leftNumber.length.compareTo(rightNumber.length).takeIf { it != 0 }
                    ?: leftNumber.compareTo(rightNumber)
            } else leftPart.compareTo(rightPart)
            if (result != 0) return result
        }
        return left.size.compareTo(right.size).takeIf { it != 0 } ?: first.compareTo(second)
    }
}

data class Book(
    val id: String,
    val uri: String,
    val title: String,
    val kind: String,
    val modified: Long,
    val size: Long,
) {
    val cacheKey: String get() = "$id-$modified-$size".stableId()
    val formatLabel: String get() = if (kind == "folder") "图片集" else kind.uppercase(Locale.ROOT)
}

data class ReadingProgress(val page: Int = 0, val total: Int = 0, val updated: Long = 0L) {
    val fraction: Float get() = if (total > 0) (page + 1).toFloat() / total else 0f
}

data class ReaderSettings(
    val theme: String = "system",
    val accent: Long = 0xFF49684D,
    val readerDark: Boolean = true,
    val vertical: Boolean = true,
    val rightToLeft: Boolean = false,
    val keepScreenOn: Boolean = true,
    val gridColumns: Int = 3,
)

data class PageSource(val name: String, val filePath: String? = null, val uri: String? = null, val pdfPage: Int? = null)
data class BookSession(val book: Book, val pages: List<PageSource>, val pdfPath: String? = null)

class ReaderException(message: String, cause: Throwable? = null) : Exception(message, cause)
