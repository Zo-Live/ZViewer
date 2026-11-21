package dev.zolive.zviewer.ui

import android.app.ActivityManager
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import dev.zolive.zviewer.data.BookSession
import dev.zolive.zviewer.data.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

@Composable
internal fun rememberPdfReaderPages(
    session: BookSession,
    active: Boolean,
    priorityPage: Int?,
    visibleAndAdjacentPages: () -> List<Int>,
): SnapshotStateMap<Int, Result<Bitmap>> {
    val pdf = checkNotNull(session.pdf)
    val width = (LocalWindowInfo.current.containerSize.width * 2).coerceIn(480, 2560)
    val context = LocalContext.current
    val maxPixels = remember(context) {
        val heapMiB = context.getSystemService(ActivityManager::class.java).memoryClass
        // 单页最多占应用内存额度的八分之一，为可见页和相邻页留出空间。
        (heapMiB * 1024L * 1024 / 32).coerceAtMost(12_000_000).toInt()
    }
    val results = remember(pdf, width) { mutableStateMapOf<Int, Result<Bitmap>>() }
    val requestedPages by rememberUpdatedState(visibleAndAdjacentPages)
    LaunchedEffect(pdf, width, active, priorityPage) {
        if (!active) return@LaunchedEffect
        while (true) {
            val (_, nextPage) = snapshotFlow {
                val pages = priorityPage?.let(::listOf) ?: requestedPages()
                pages to pages.firstOrNull { it !in results }
            }.first { (pages, missingPage) ->
                val retained = if (priorityPage == null) pages else requestedPages() + priorityPage
                results.keys.retainAll(retained.toSet())
                missingPage != null
            }
            val index = checkNotNull(nextPage)
            val started = SystemClock.elapsedRealtime()
            val result = try {
                Result.success(pdf.render(index, width, maxPixels)).also {
                    DiagnosticLog.log("PDF", "页面渲染完成 book=${session.book.id.take(8)} page=${index + 1} elapsedMs=${SystemClock.elapsedRealtime() - started}")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DiagnosticLog.error("PDF", "页面渲染失败 book=${session.book.id.take(8)} page=${index + 1} targetWidth=$width", error)
                Result.failure(error)
            }
            // 滚动或跳页后重新读取视口，跳过已经离开预载范围的任务。
            if (index == priorityPage || index in requestedPages()) results[index] = result
            else result.getOrNull()?.recycle()
        }
    }
    return results
}
