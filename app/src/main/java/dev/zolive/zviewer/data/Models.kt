package dev.zolive.zviewer.data

import java.security.MessageDigest
import java.io.Closeable
import java.util.Locale

val imageExtensions = setOf("jpg", "jpeg", "png", "apng", "webp", "bmp", "heif", "heic", "gif", "avif")
val videoExtensions = setOf("mp4", "m4v", "mkv", "webm", "avi", "mov", "3gp", "3g2", "mpg", "mpeg", "mpe", "ts", "mts", "m2ts", "mpegts", "flv", "wmv", "ogv", "vob", "rm", "rmvb")
val archiveExtensions = setOf("zip", "cbz", "rar", "cbr", "7z")

fun String.extensionLower(): String = substringAfterLast('.', "").lowercase(Locale.ROOT)
private const val HEX_DIGITS = "0123456789abcdef"

fun String.stableId(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return buildString(32) {
        for (index in 0 until 16) {
            val byte = digest[index].toInt() and 0xff
            append(HEX_DIGITS[byte shr 4])
            append(HEX_DIGITS[byte and 0x0f])
        }
    }
}
fun isImage(name: String): Boolean = name.extensionLower() in imageExtensions &&
    name.replace('\\', '/').split('/').none { it.startsWith('.') || it == "__MACOSX" }
fun isVideo(name: String): Boolean = name.extensionLower() in videoExtensions &&
    name.replace('\\', '/').split('/').none { it.startsWith('.') || it == "__MACOSX" }
fun isPdf(name: String): Boolean = name.extensionLower() == "pdf" &&
    name.replace('\\', '/').split('/').none { it.startsWith('.') || it == "__MACOSX" }

data class Book(
    val id: String,
    val uri: String,
    val title: String,
    val kind: String,
    val modified: Long,
    val size: Long,
    val contentSignature: String = "",
) {
    val cacheKey: String by lazy { ("$id-$modified-$size" +
        if (contentSignature.isEmpty()) "" else "-$contentSignature").stableId() }
}

data class ReadingProgress(val page: Int = 0, val total: Int = 0, val updated: Long = 0L) {
    val fraction: Float get() = if (total > 0) (page + 1).toFloat() / total else 0f
}

enum class AutoLockInterval(val seconds: Int) {
    Immediately(0), FifteenSeconds(15), OneMinute(60), FiveMinutes(300),
}

enum class AnimationSpeed(val durationScale: Float) {
    Moderate(1f), Enhanced(0.6f),
}

enum class PagedImageFit(val key: String) {
    Screen("screen"), Width("width"), Height("height"),
}

internal const val DefaultThemeAccent = 0xFF49684DL

data class ReaderSettings(
    val theme: String = "system",
    val accent: Long = DefaultThemeAccent,
    val readerDark: Boolean = true,
    val vertical: Boolean = true,
    val paged: Boolean = false,
    val pagedImageFit: PagedImageFit = PagedImageFit.Screen,
    val continuousImageFitScreen: Boolean = false,
    val videoFillScreen: Boolean = false,
    val rightToLeft: Boolean = false,
    val horizontalVideoSeek: Boolean = true,
    val keepScreenOn: Boolean = true,
    val volumeKeyPaging: Boolean = false,
    val doubleTapZoom: Boolean = true,
    val gridColumns: Int = 3,
    val showFileType: Boolean = false,
    val showFavoriteIcon: Boolean = true,
    val videoLoopSingle: Boolean = false,
    val videoPreview: Boolean = false,
    val loopMode: Boolean = false,
    val diagnosticLog: Boolean = false,
    val logDirectory: String? = null,
    val checkUpdatesOnStartup: Boolean = false,
    val clearCovers: Boolean = false,
    val currentLibraryCacheOnly: Boolean = false,
    val rebuildLibraryCovers: Boolean = false,
    val cacheLimitMiB: Int = CacheLimits.DEFAULT_MIB,
    val clearCacheOnTaskRemoval: Boolean = false,
    val clearProgressOnTaskRemoval: Boolean = false,
    val language: AppLanguage = AppLanguage.SIMPLIFIED_CHINESE,
    val dynamicColor: Boolean = false,
    val verifyOnStartup: Boolean = false,
    val hideRecentPreview: Boolean = false,
    val verifyAfterBackground: Boolean = false,
    val autoLockInterval: AutoLockInterval = AutoLockInterval.FifteenSeconds,
    val hideRecentCard: Boolean = false,
    val hideGalleryImages: Boolean = false,
    val animationSpeed: AnimationSpeed = AnimationSpeed.Moderate,
    val hapticFeedback: Boolean = true,
    val disabledAnimations: Set<AnimationEffect> = emptySet(),
    val disabledHaptics: Set<HapticEvent> = emptySet(),
    val verificationFollowsTheme: Boolean = true,
    val showPageNumber: Boolean = true,
) {
    internal fun animationSpeedFor(effect: AnimationEffect): AnimationSpeed =
        if (effect in disabledAnimations) AnimationSpeed.Moderate else animationSpeed

    internal fun hapticsEnabledFor(event: HapticEvent): Boolean =
        hapticFeedback && event !in disabledHaptics
}

data class PageSource(
    val name: String,
    val filePath: String? = null,
    val uri: String? = null,
    val mediaType: String = "image",
) {
    val isVideo: Boolean get() = mediaType == "video"
}
data class BookSession(val book: Book, val pages: List<PageSource>, internal val pdf: PdfDocument? = null,
    internal val cacheLease: Closeable? = null) {
    internal val mediaKeys = if (pdf != null) emptyList() else run {
        val occurrences = mutableMapOf<String, Int>()
        pages.map { page ->
            val source = page.uri ?: page.name
            val occurrence = occurrences.getOrDefault(source, 0)
            occurrences[source] = occurrence + 1
            "$occurrence:$source".stableId()
        }
    }
}

class ReaderException(message: String, cause: Throwable? = null) : Exception(message, cause)
