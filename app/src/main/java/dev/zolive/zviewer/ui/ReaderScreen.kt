@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.zolive.zviewer.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.WindowManager
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zolive.zviewer.MainActivity
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.BookRepository
import dev.zolive.zviewer.data.BookSession
import dev.zolive.zviewer.data.DiagnosticLog
import dev.zolive.zviewer.data.ReaderSettings
import dev.zolive.zviewer.data.PageSource
import dev.zolive.zviewer.reader.ImageLoader
import dev.zolive.zviewer.reader.ReaderPaging
import dev.zolive.zviewer.reader.ZoomImageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(session: BookSession, initialPage: Int, settings: ReaderSettings, repository: BookRepository,
    onSettings: (ReaderSettings) -> Unit, onProgress: (Int) -> Unit,
    videoPosition: (Int) -> Long = { 0L }, onVideoProgress: (Int, Long) -> Unit = { _, _ -> }, onBack: () -> Unit,
    visible: Boolean = true, onVideoEntryReady: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var currentPage by rememberSaveable(session.book.id) { mutableIntStateOf(initialPage.coerceIn(session.pages.indices)) }
    var controls by rememberSaveable(session.book.id) { mutableStateOf(false) }
    var preferences by remember { mutableStateOf(false) }
    var jumpDialog by remember { mutableStateOf(false) }
    var zoomSequence by remember { mutableIntStateOf(0) }
    var zoomAction by remember { mutableIntStateOf(0) }
    var zoomPage by remember { mutableIntStateOf(-1) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    val paging = remember(session.pages.size, settings.loopMode) { ReaderPaging(session.pages.size, settings.loopMode) }
    val listState = rememberLazyListState(paging.anchor(currentPage))
    val pagerState = rememberPagerState(initialPage = paging.anchor(currentPage)) { paging.itemCount }
    var jumpJob by remember { mutableStateOf<Job?>(null) }
    val ratios = remember(session.book.cacheKey) { mutableStateMapOf<Int, Float>() }
    var initialImageLoaded by remember(session) { mutableStateOf(false) }
    val onInitialImageLoaded = { initialImageLoaded = true }
    val background = if (settings.readerDark) DarkReaderBackground else LightReaderBackground
    val foreground = if (settings.readerDark) Color(0xFFE6E5E1) else Color(0xFF262724)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val activity = LocalActivity.current ?: return
    val context = LocalContext.current
    val videoBook = remember(session) { session.pages.any(PageSource::isVideo) }
    val mixedBook = remember(session) { videoBook && session.pages.any { !it.isVideo } }
    val verticalReading = settings.vertical
    val pagerReading = videoBook || !verticalReading
    val pdfPages = if (session.pdf != null) rememberPdfReaderPages(session, active) {
        val first = if (verticalReading) listState.firstVisibleItemIndex else pagerState.currentPage
        val visible = if (verticalReading) listState.layoutInfo.visibleItemsInfo.map { it.index } else emptyList()
        val last = visible.lastOrNull() ?: first
        (listOf(first) + visible + listOf(last + 1, first - 1))
            .filter { it in 0 until paging.itemCount }
            .map(paging::pageAt)
            .distinct()
    } else null
    val videoPool = remember(session.book.cacheKey, videoBook) { if (videoBook) VideoPlaybackPool(context) else null }
    // 会话级封面缓存：同一真实页在循环模式下可能被多个虚拟项复用，
    // 重建视图时直接取已解码的封面，避免异步解码造成一帧空档。
    val videoCovers = remember(session.book.cacheKey) { VideoCoverCache() }

    DisposableEffect(videoPool) {
        onDispose { videoPool?.releaseAll() }
    }

    LaunchedEffect(session.book.cacheKey) {
        DiagnosticLog.log("Reader", "会话开始 book=${session.book.id.take(8)} kind=${session.book.kind} pages=${session.pages.size} " +
            "video=$videoBook mixed=$mixedBook vertical=${settings.vertical} rtl=${settings.rightToLeft} " +
            "loop=${settings.loopMode} preview=${settings.videoPreview}")
    }

    PredictiveBackHandler(enabled = visible && !preferences && !jumpDialog) { events ->
        try {
            events.collect { backProgress = it.progress }
            onProgress(currentPage)
            onBack()
        } catch (error: CancellationException) { backProgress = 0f }
    }

    DisposableEffect(visible, settings.keepScreenOn) {
        if (visible && settings.keepScreenOn) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val lightSystemBars = if (controls) MaterialTheme.colorScheme.surface.luminance() > .5f else !settings.readerDark
    if (visible) {
        DisposableEffect(controls, lightSystemBars) {
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.isAppearanceLightStatusBars = lightSystemBars
            controller.isAppearanceLightNavigationBars = lightSystemBars
            if (controls) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
        }
    }

    LaunchedEffect(pagerReading, paging) {
        jumpJob?.cancel()
        val item = paging.anchor(currentPage)
        if (pagerReading) pagerState.scrollToPage(item) else listState.scrollToItem(item)
        snapshotFlow {
            if (pagerReading) paging.pageAt(pagerState.settledPage)
            else if (!paging.looping && !listState.canScrollForward && listState.canScrollBackward) paging.pageCount - 1
            else paging.pageAt(listState.firstVisibleItemIndex)
        }.distinctUntilChanged().collect { page -> currentPage = page; onProgress(page)
            DiagnosticLog.log("Reader", "停靠到第 ${page + 1}/${session.pages.size} 项")
        }
    }
    LaunchedEffect(videoBook, pagerState.settledPage, active) {
        if (!videoBook || !active) return@LaunchedEffect
        videoPool?.snapshot()?.forEach { (item, state) -> if (item != pagerState.settledPage) state.pause() }
    }
    val jump: (Int) -> Unit = { target ->
        val page = paging.destination(target)
        jumpJob?.cancel()
        if (videoBook) {
            DiagnosticLog.log("Reader", "跳转到第 ${page + 1} 项")
            // 视频统一交给 Pager 平滑过渡：跟手拖动、动画期间当前视频继续播放，停靠后切换播放器。
            jumpJob = scope.launch { pagerState.animateScrollToPage(paging.nearestItem(page, pagerState.currentPage)) }
        } else if (verticalReading) {
            currentPage = page
            onProgress(page)
            jumpJob = scope.launch { listState.scrollToItem(paging.nearestItem(page, listState.firstVisibleItemIndex)) }
        } else {
            currentPage = page
            onProgress(page)
            jumpJob = scope.launch { pagerState.scrollToPage(paging.nearestItem(page, pagerState.currentPage)) }
        }
    }
    val volumePage by rememberUpdatedState<(Int) -> Unit> { direction ->
        if (paging.looping || currentPage + direction in session.pages.indices) {
            jump(currentPage + direction)
        }
    }
    DisposableEffect(activity, active, visible, settings.volumeKeyPaging, preferences, jumpDialog) {
        val mainActivity = activity as? MainActivity
        if (visible && active && settings.volumeKeyPaging && !preferences && !jumpDialog) {
            mainActivity?.onVolumePage = { volumePage(it) }
        }
        onDispose { mainActivity?.onVolumePage = null }
    }
    val currentPageIsVideo = session.pages.getOrNull(currentPage)?.isVideo == true
    val toolbarVideoState = if (currentPageIsVideo) videoPool?.get(pagerState.settledPage) else null
    val onVideoEnded: (VideoPlaybackState) -> Unit = { state ->
        DiagnosticLog.log("Reader", "视频播放结束 index=${state.index} scrolling=${pagerState.isScrollInProgress} page=$currentPage")
        if (pagerState.isScrollInProgress) {
            // 切换尚未完成时当前视频播完就循环播放，而不是停在结尾等待下一次手势。
            state.replay()
        } else if (!settings.videoLoopSingle && (settings.loopMode || currentPage < session.pages.lastIndex)) {
            jump(currentPage + 1)
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
        .then(if (visible) Modifier else Modifier.semantics { hideFromAccessibility() })) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationX = size.width * backProgress * .24f
            scaleX = 1f - backProgress * .08f
            scaleY = 1f - backProgress * .08f
            alpha = 1f - backProgress * .25f
        }.background(background)) {
            if (videoBook) {
                val itemContent: @Composable (Int) -> Unit = { item ->
                    VideoPagerPage(item, session, paging, pagerState, repository, videoPool!!, videoCovers,
                        active, allowPlayback = visible, settings, foreground,
                        videoPosition, onVideoProgress, zoomSequence, zoomAction, zoomPage,
                        onEntryReady = if (visible) null else onVideoEntryReady,
                        onTap = { controls = !controls }, onEnded = onVideoEnded)
                }
                if (verticalReading) {
                    VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1,
                        key = { "${session.book.cacheKey}-$it" }) { item -> itemContent(item) }
                } else {
                    HorizontalPager(state = pagerState, reverseLayout = settings.rightToLeft, modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 1, key = { "${session.book.cacheKey}-$it" }) { item -> itemContent(item) }
                }
            } else if (verticalReading) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(paging.itemCount, key = { "${session.book.cacheKey}-$it" }) { item ->
                        val index = paging.pageAt(item)
                        val visible by remember(item) { derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.index == item } } }
                        val current by remember(item) { derivedStateOf { item == listState.firstVisibleItemIndex } }
                        val zoomTarget = if (index == zoomPage && !paging.looping) visible else current && index == zoomPage
                        ReaderPage(session, index, repository, vertical = true, active = active && visible,
                            doubleTapZoomEnabled = settings.doubleTapZoom,
                            foreground = foreground, zoomSequence = if (zoomTarget) zoomSequence else 0,
                            zoomAction = zoomAction, initialRatio = ratios[index], onRatio = { ratios[index] = it },
                            loadEnabled = initialImageLoaded || current, onLoaded = onInitialImageLoaded, pdfPages = pdfPages,
                            onTap = { controls = !controls })
                    }
                }
            } else {
                HorizontalPager(state = pagerState, reverseLayout = settings.rightToLeft, modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1, key = { "${session.book.cacheKey}-$it" }) { item ->
                    val index = paging.pageAt(item)
                    ReaderPage(session, index, repository, vertical = false, active = active && pagerState.currentPage == item,
                        doubleTapZoomEnabled = settings.doubleTapZoom,
                        foreground = foreground, zoomSequence = if (item == pagerState.currentPage && index == zoomPage) zoomSequence else 0,
                        zoomAction = zoomAction, loadEnabled = initialImageLoaded || pagerState.currentPage == item,
                        onLoaded = onInitialImageLoaded, pdfPages = pdfPages, onTap = { controls = !controls })
                }
            }
            if (!controls) {
                Surface(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(12.dp),
                    color = background.copy(alpha = OverlayBackgroundAlpha),
                    contentColor = foreground.copy(alpha = .65f), shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(
                        when {
                            mixedBook -> R.string.compact_item_progress
                            videoBook -> R.string.compact_video_progress
                            else -> R.string.card_page_progress
                        },
                        currentPage + 1,
                        session.pages.size,
                    ), Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                TopAppBar(title = {
                    Text(session.book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                }, navigationIcon = {
                    IconButton(onClick = { onProgress(currentPage); onBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back_to_library)) }
                }, actions = { IconButton(onClick = { preferences = true }) { Icon(Icons.Outlined.Settings, stringResource(R.string.reader_settings)) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .97f)))
            }
            AnimatedVisibility(visible = controls, modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .98f), tonalElevation = 3.dp,
                    shadowElevation = 8.dp, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Box(Modifier.align(Alignment.CenterHorizontally).size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant))
                        if (toolbarVideoState != null) VideoProgressControls(toolbarVideoState)
                        var slider by remember { mutableFloatStateOf(currentPage.toFloat()) }
                        var dragging by remember { mutableStateOf(false) }
                        LaunchedEffect(currentPage) { if (!dragging) slider = currentPage.toFloat() }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { jumpDialog = true }) { Text(stringResource(
                                when {
                                    mixedBook -> R.string.item_progress
                                    videoBook -> R.string.video_progress
                                    else -> R.string.page_progress
                                },
                                if (dragging) slider.roundToInt() + 1 else currentPage + 1,
                                session.pages.size,
                            )) }
                            Spacer(Modifier.weight(1f))
                            Text("${((currentPage + 1f) / session.pages.size * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                        }
                        Slider(value = if (dragging) slider else currentPage.toFloat(),
                            onValueChange = { dragging = true; slider = it },
                            onValueChangeFinished = { jump(slider.roundToInt()); dragging = false },
                            valueRange = 0f..maxOf(1, session.pages.lastIndex).toFloat(), enabled = session.pages.size > 1,
                            modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { jump(currentPage - 1) }, enabled = settings.loopMode || currentPage > 0) { Icon(Icons.AutoMirrored.Outlined.NavigateBefore, stringResource(R.string.previous_page)) }
                            IconButton(onClick = { zoomPage = currentPage; zoomAction = -1; zoomSequence++ }) { Icon(Icons.Outlined.ZoomOut, stringResource(if (currentPageIsVideo) R.string.zoom_out_video else R.string.zoom_out_image)) }
                            TextButton(onClick = { zoomPage = currentPage; zoomAction = 0; zoomSequence++ }) { Text(stringResource(R.string.fit_screen)) }
                            IconButton(onClick = { zoomPage = currentPage; zoomAction = 1; zoomSequence++ }) { Icon(Icons.Outlined.ZoomIn, stringResource(if (currentPageIsVideo) R.string.zoom_in_video else R.string.zoom_in_image)) }
                            IconButton(onClick = { jump(currentPage + 1) }, enabled = settings.loopMode || currentPage < session.pages.lastIndex) { Icon(Icons.AutoMirrored.Outlined.NavigateNext, stringResource(R.string.next_page)) }
                        }
                    }
                }
            }
        }
    }
    if (preferences) ModalBottomSheet(onDismissRequest = { preferences = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.reader_settings), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(24.dp))
            ReaderPreferences(settings, onSettings)
            Spacer(Modifier.height(20.dp))
        }
    }
    if (jumpDialog) {
        var text by remember { mutableStateOf((currentPage + 1).toString()) }
        val page = text.toIntOrNull()
        val title = when {
            mixedBook -> R.string.jump_to_item
            videoBook -> R.string.jump_to_video
            else -> R.string.jump_to_page
        }
        val label = when {
            mixedBook -> R.string.item_number_range
            videoBook -> R.string.video_number_range
            else -> R.string.page_number_range
        }
        AlertDialog(onDismissRequest = { jumpDialog = false }, title = { Text(stringResource(title)) }, text = {
            OutlinedTextField(text, { text = it.filter(Char::isDigit).take(6) },
                label = { Text(stringResource(label, session.pages.size)) },
                singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        }, confirmButton = { TextButton(enabled = page != null && page in 1..session.pages.size,
            onClick = { jump(page!! - 1); jumpDialog = false }) { Text(stringResource(R.string.jump)) } },
            dismissButton = { TextButton(onClick = { jumpDialog = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

/**
 * 视频书的每个 Pager 页。视频页从池中取播放器、按页保存位置；图片页复用普通阅读页。
 *
 * 播放器按“停靠页 + 左右相邻页”预建：相邻视频在离屏时就把保存位置的首帧渲染到自己的
 * TextureView，滑动进入时画面已经就绪。播放器在页面仍处于 Pager 组合窗口期间不会被回收，
 * 因此拖动到一半再拉回、连续来回切换时不会反复销毁 / 重建 Surface 而闪屏。
 */
@Composable
private fun VideoPagerPage(
    item: Int, session: BookSession, paging: ReaderPaging, pagerState: PagerState, repository: BookRepository,
    pool: VideoPlaybackPool, covers: VideoCoverCache,
    active: Boolean, allowPlayback: Boolean, settings: ReaderSettings, foreground: Color,
    videoPosition: (Int) -> Long, onVideoProgress: (Int, Long) -> Unit,
    zoomSequence: Int, zoomAction: Int, zoomPage: Int, onEntryReady: (() -> Unit)?,
    onTap: () -> Unit, onEnded: (VideoPlaybackState) -> Unit,
) {
    val index = paging.pageAt(item)
    val page = session.pages[index]
    val settled = pagerState.settledPage == item
    val neighbor = abs(item - pagerState.settledPage) <= 1
    // 缩放指令只应用到当前页，避免相邻预载页被一起缩放。
    val sequence = if (settled && index == zoomPage) zoomSequence else 0
    if (page.isVideo) {
        val cover = rememberVideoCover(session, index, repository, covers)
        val state = if (neighbor) remember(item) { pool.acquire(item) } else null
        DisposableEffect(state) { onDispose { if (state != null) pool.release(item) } }
        val frameReady = settled && state != null && (state.failed || state.firstFrameReady && state.surfaceAttached)
        val entryReady = onEntryReady != null && frameReady
        LaunchedEffect(entryReady) {
            if (entryReady) {
                withFrameNanos { }
                onEntryReady?.invoke()
            }
        }
        VideoPage(state, index, session, cover, settings,
            videoPosition, onVideoProgress, foreground, active && settled, allowPlayback,
            sequence, zoomAction, onTap = onTap, onEnded = onEnded)
    } else {
        ReaderPage(session, index, repository, vertical = false, active = active && settled,
            doubleTapZoomEnabled = settings.doubleTapZoom,
            foreground = foreground, zoomSequence = sequence,
            zoomAction = zoomAction, onTap = onTap)
    }
}

private val imageDecodeDispatcher = Dispatchers.IO.limitedParallelism(2)

@Composable
private fun ReaderPage(session: BookSession, index: Int, repository: BookRepository, vertical: Boolean, active: Boolean,
    doubleTapZoomEnabled: Boolean,
    foreground: Color, zoomSequence: Int, zoomAction: Int, initialRatio: Float? = null,
    onRatio: (Float) -> Unit = {}, loadEnabled: Boolean = true, onLoaded: () -> Unit = {},
    pdfPages: SnapshotStateMap<Int, Result<Bitmap>>? = null, onTap: () -> Unit) {
    if (pdfPages != null) {
        val bitmap = pdfPages[index]?.getOrNull()
        val resources = LocalContext.current.resources
        val image = remember(bitmap, resources) { bitmap?.let { BitmapDrawable(resources, it) } }
        ReaderPageContent(index, image, pdfPages[index]?.isFailure == true, vertical, active,
            doubleTapZoomEnabled, foreground, zoomSequence, zoomAction, initialRatio, onRatio, onTap, onRetry = { pdfPages.remove(index) })
        return
    }
    val viewportWidth = LocalWindowInfo.current.containerSize.width
    val width = (viewportWidth * 2).coerceIn(480, 3200)
    val animationWidth = viewportWidth.coerceIn(480, 3200)
    var retry by remember { mutableIntStateOf(0) }
    var failure by remember(session.book.cacheKey, index) { mutableStateOf(false) }
    val drawable by produceState<Drawable?>(null, width, animationWidth, retry, loadEnabled) {
        if (!loadEnabled) return@produceState
        failure = false
        try {
            val file = repository.pageFile(session, index)
            value = withContext(imageDecodeDispatcher) { ImageLoader.load(file, width, animationWidth) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            DiagnosticLog.error("Image", "页面解码失败 book=${session.book.id.take(8)} page=${index + 1} format=${session.pages[index].name.substringAfterLast('.')} targetWidth=$width", error)
            failure = true
        }
    }
    LaunchedEffect(drawable, failure) {
        if (drawable != null || failure) onLoaded()
    }
    ReaderPageContent(index, drawable, failure, vertical, active, doubleTapZoomEnabled, foreground, zoomSequence, zoomAction,
        initialRatio, onRatio, onTap, onRetry = { retry++ })
}

@Composable
private fun ReaderPageContent(index: Int, image: Drawable?, failure: Boolean, vertical: Boolean, active: Boolean,
    doubleTapZoomEnabled: Boolean,
    foreground: Color, zoomSequence: Int, zoomAction: Int, initialRatio: Float?, onRatio: (Float) -> Unit,
    onTap: () -> Unit, onRetry: () -> Unit) {
    val imageDescription = stringResource(
        if (doubleTapZoomEnabled) R.string.image_accessibility else R.string.image_accessibility_without_zoom,
        index + 1,
    )
    val ratio = image?.let { it.intrinsicWidth.toFloat() / it.intrinsicHeight.coerceAtLeast(1) } ?: initialRatio ?: .70f
    LaunchedEffect(image) { if (image != null) onRatio(ratio) }
    val layout = if (vertical) Modifier.fillMaxWidth().aspectRatio(ratio.coerceAtLeast(.03f)) else Modifier.fillMaxSize()
    Box(layout, contentAlignment = Alignment.Center) {
        if (image != null) {
            AndroidView(factory = { context -> ZoomImageView(context) }, modifier = Modifier.fillMaxSize(),
                onRelease = ZoomImageView::clearImage,
                update = { view ->
                    view.onTap = onTap
                    view.doubleTapZoomEnabled = doubleTapZoomEnabled
                    view.bind(image, imageDescription, active)
                    if (zoomSequence > 0) view.command(zoomSequence, zoomAction)
                })
        } else if (failure) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.BrokenImage, null, tint = foreground)
                Text(stringResource(R.string.image_error), Modifier.padding(vertical = 16.dp), color = foreground)
                Row {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                    TextButton(onClick = onTap) { Text(stringResource(R.string.show_reader_controls)) }
                }
            }
        } else CircularProgressIndicator(Modifier.size(28.dp), color = foreground)
    }
}
