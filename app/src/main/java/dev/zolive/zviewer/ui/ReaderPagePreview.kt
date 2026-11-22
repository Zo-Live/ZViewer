package dev.zolive.zviewer.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.zolive.zviewer.data.BookRepository
import dev.zolive.zviewer.data.BookSession
import dev.zolive.zviewer.data.ReaderPreviewStore
import dev.zolive.zviewer.reader.ImageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

internal data class ReaderPagePreview(val index: Int, val bitmap: Bitmap) {
    val ratio: Float get() = (bitmap.width.toFloat() / bitmap.height).coerceAtLeast(.03f)
}

internal data class ReaderPreviewViewport(
    val target: Int,
    val pages: List<ReaderPagePreview>,
    val alignBottom: Boolean,
)

internal val ReaderPageSpacing = 4.dp

private const val ImagePreviewMaxPixels = 280_000
private const val PdfPreviewMaxPixels = 180_000

@Composable
internal fun rememberReaderPagePreview(
    session: BookSession,
    repository: BookRepository,
    videoCovers: VideoCoverCache,
    continuous: Boolean,
    looping: Boolean,
    canDecode: () -> Boolean,
    idlePage: () -> Int?,
    pdfPages: Map<Int, Result<Bitmap>>?,
    requestedPage: () -> Int?,
): State<ReaderPreviewViewport?> {
    val resolver = LocalContext.current.contentResolver
    val viewport = LocalWindowInfo.current.containerSize
    val width = if (session.pdf != null) {
        (viewport.width / 3).coerceIn(240, 480)
    } else {
        (viewport.width * .45f).toInt().coerceIn(320, 640)
    }
    val spacing = with(LocalDensity.current) { ReaderPageSpacing.toPx() }
    val cache = remember(session, width) {
        object : LruCache<Int, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: Int, value: Bitmap) = value.allocationByteCount
        }
    }
    val previewState = remember(cache, viewport, continuous, looping) { mutableStateOf<ReaderPreviewViewport?>(null) }
    val latestPage by rememberUpdatedState(requestedPage)
    val latestIdlePage by rememberUpdatedState(idlePage)
    val latestCanDecode by rememberUpdatedState(canDecode)
    val hasVideos = remember(session) { session.pages.any { it.isVideo } }
    val store = remember(repository, session, width) {
        ReaderPreviewStore(repository.previewDirectory(session, width), session)
    }
    LaunchedEffect(cache, store, viewport, spacing, continuous, looping, pdfPages) {
        store.initialize()
        val failed = mutableSetOf<Int>()
        val count = session.pages.size
        val sampleStep = ((count + 63) / 64).coerceAtLeast(8)
        val anchors = (0 until count step sampleStep).toList()
        val spreadAnchors = sequence {
            yield(anchors.first())
            var stride = Integer.highestOneBit(anchors.lastIndex)
            while (stride > 0) {
                for (position in stride until anchors.size step stride * 2) yield(anchors[position])
                stride /= 2
            }
        }.toList()
        val preparedAnchors = mutableSetOf<Int>()
        var generationComplete = false
        fun cached(index: Int) = pdfPages?.get(index)?.getOrNull() ?: cache.get(index) ?: videoCovers[index]

        suspend fun decode(index: Int): Bitmap? {
            if (index in failed) return null
            val page = session.pages[index]
            val bitmap = try {
                withContext(Dispatchers.IO) {
                    when {
                        session.pdf != null -> session.pdf.render(index, width, PdfPreviewMaxPixels)
                        page.isVideo -> repository.videoPreviewFrame(session, index)
                        else -> ImageLoader.loadPreview(
                            page.filePath?.let { ImageDecoder.createSource(File(it)) }
                                ?: ImageDecoder.createSource(resolver, checkNotNull(page.uri).toUri()),
                            width,
                            ImagePreviewMaxPixels,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (bitmap == null) {
                if (!page.isVideo) failed.add(index)
            }
            return bitmap
        }

        fun next(index: Int): Int = if (looping && index + 1 == count) 0 else index + 1

        fun publish(frame: ReaderPreviewViewport, approximate: Boolean = false) {
            val latest = latestPage() ?: latestIdlePage() ?: return
            val previous = previewState.value?.target
            val target = frame.target
            if (approximate) {
                if (!latestCanDecode() || abs(latest - target) > sampleStep ||
                    previous != null && abs(previous - latest) <= abs(target - latest)) return
            } else if (target != latest && (latestPage() == null || !latestCanDecode() || previous == latest ||
                    previous != null && target !in minOf(previous, latest)..maxOf(previous, latest))) return
            previewState.value = frame
        }

        suspend fun readViewport(target: Int, allowSource: Boolean): ReaderPreviewViewport? {
            var remainingHeight = viewport.height.toFloat()
            var index = target
            val pages = mutableListOf<ReaderPagePreview>()
            suspend fun bitmapAt(index: Int): Bitmap? {
                cached(index)?.let { return it }
                val bitmap = store.read(index) ?: if (allowSource && (latestCanDecode() || latestIdlePage() != null)) {
                    decode(index)
                } else null
                if (bitmap != null) cache.put(index, bitmap)
                return bitmap
            }
            do {
                val bitmap = pages.firstOrNull { it.index == index }?.bitmap ?: bitmapAt(index) ?: return null
                val preview = ReaderPagePreview(index, bitmap)
                pages += preview
                remainingHeight -= viewport.width / preview.ratio + spacing
                index = next(index)
            } while (continuous && remainingHeight > 0 && index in session.pages.indices)
            val alignBottom = continuous && !looping && index == count && remainingHeight > 0
            if (alignBottom) {
                var before = target - 1
                while (remainingHeight > 0 && before >= 0) {
                    val preview = ReaderPagePreview(before, bitmapAt(before) ?: return null)
                    pages.add(0, preview)
                    remainingHeight -= viewport.width / preview.ratio + spacing
                    before--
                }
            }
            return ReaderPreviewViewport(target, pages, alignBottom && remainingHeight <= 0)
        }

        suspend fun showNearby(target: Int) {
            for (distance in 1..sampleStep) {
                val previous = previewState.value?.target
                if (previous != null && abs(previous - target) <= distance) break
                for (anchor in listOf(target - distance, target + distance)) {
                    if (anchor !in session.pages.indices || anchor !in store ||
                        session.pages[anchor].isVideo != session.pages[target].isVideo) continue
                    val nearby = readViewport(anchor, allowSource = false) ?: continue
                    publish(nearby, approximate = true)
                    return
                }
            }
        }

        val imageMisses = Channel<Int>(Channel.CONFLATED)
        val videoMisses = Channel<Int>(Channel.CONFLATED)
        launch {
            snapshotFlow { latestPage() }.filterNotNull().conflate().collect { target ->
                val exact = readViewport(target, allowSource = false)
                if (exact != null) publish(exact)
                else if (latestPage() == target && latestCanDecode()) {
                    val misses = if (session.pages[target].isVideo) videoMisses else imageMisses
                    misses.trySend(target)
                    showNearby(target)
                }
            }
        }
        launch {
            snapshotFlow { latestIdlePage() != null }.collectLatest { idle ->
                val origin = latestIdlePage()
                if (!idle || origin == null || count <= 1) return@collectLatest
                repository.withBookCache(session.book) {
                    withContext(Dispatchers.Main.immediate) {
                        suspend fun warm(index: Int): ReaderPagePreview? {
                            val existing = store.read(index)
                            if (existing != null) return ReaderPagePreview(index, existing)
                            val bitmap = cache.get(index) ?: decode(index) ?: return null
                            store.write(index, bitmap)
                            delay(12)
                            return ReaderPagePreview(index, bitmap)
                        }

                        readViewport(origin, allowSource = true)?.let { publish(it) }
                        if (generationComplete || store.full) return@withContext
                        val local = (origin - 4).coerceAtLeast(0)..(origin + 8).coerceAtMost(count - 1)
                        for (index in local) {
                            if (store.full) break
                            if (index !in store) warm(index)?.let { cache.put(index, it.bitmap) }
                        }
                        for (anchor in spreadAnchors) {
                            if (store.full) break
                            if (anchor in preparedAnchors) continue
                            var remainingHeight = viewport.height.toFloat()
                            var index = anchor
                            do {
                                val page = warm(index) ?: break
                                remainingHeight -= viewport.width / page.ratio + spacing
                                index = next(index)
                            } while (continuous && remainingHeight > 0 && index in session.pages.indices &&
                                index != anchor && !store.full)
                            preparedAnchors.add(anchor)
                        }
                        for (index in session.pages.indices) {
                            if (store.full) break
                            if (index !in store) warm(index)
                        }
                        generationComplete = !store.full
                    }
                }
            }
        }
        snapshotFlow { latestCanDecode() }.collectLatest { canDecode ->
            if (!canDecode) return@collectLatest
            coroutineScope {
                launch {
                    for (target in imageMisses) {
                        if (latestPage() == target) {
                            readViewport(target, allowSource = true)?.let { publish(it) }
                        }
                    }
                }
                if (hasVideos) launch {
                    for (target in videoMisses) {
                        if (latestPage() == target) {
                            readViewport(target, allowSource = true)?.let { publish(it) }
                        }
                    }
                }
            }
        }
    }
    return previewState
}
