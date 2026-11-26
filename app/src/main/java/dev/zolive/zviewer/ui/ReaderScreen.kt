@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.zolive.zviewer.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.WindowManager
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zolive.zviewer.MainActivity
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.AnimationEffect
import dev.zolive.zviewer.data.BookRepository
import dev.zolive.zviewer.data.BookSession
import dev.zolive.zviewer.data.DiagnosticLog
import dev.zolive.zviewer.data.HapticEvent
import dev.zolive.zviewer.data.PagedImageFit
import dev.zolive.zviewer.data.ReaderSettings
import dev.zolive.zviewer.data.PageSource
import dev.zolive.zviewer.reader.ImageLoader
import dev.zolive.zviewer.reader.ReaderMediaItem
import dev.zolive.zviewer.reader.ReaderPageGroups
import dev.zolive.zviewer.reader.ReaderPaging
import dev.zolive.zviewer.reader.ZoomImageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

private const val PagePreviewHoldMillis = 120L
private const val FastPagePreviewHoldMillis = 250L
private typealias ReaderPagerItem = @Composable (Int) -> Unit
private data class ReaderItem(val panel: Int, val index: Int)
private data class ImageRunJump(val item: ReaderItem, val anchor: Pair<Int, Int>? = null)
private data class VideoModeTransition(val state: VideoPlaybackState, val frame: Bitmap)

private fun LazyListState.anchor() = firstVisibleItemIndex to firstVisibleItemScrollOffset

private fun LazyListLayoutInfo.centerItem(): Int? {
    val viewportCenter = viewportStartOffset + viewportEndOffset
    return visibleItemsInfo.minWithOrNull(
        compareBy({ abs(2 * it.offset + it.size - viewportCenter) }, { it.offset }),
    )?.index
}

@Composable
fun ReaderScreen(session: BookSession, initialPage: Int, settings: ReaderSettings, repository: BookRepository,
    onSettings: (ReaderSettings) -> Unit, onProgress: (Int) -> Unit,
    videoPosition: (Int) -> Long = { 0L }, onVideoProgress: (Int, Long) -> Unit = { _, _ -> }, onBack: () -> Unit,
    visible: Boolean = true, onVideoEntryReady: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val pagingSpeed = settings.animationSpeedFor(AnimationEffect.ReaderPaging)
    val toolbarSpeed = settings.animationSpeedFor(AnimationEffect.ReaderToolbar)
    var currentPage by rememberSaveable(session.book.id) { mutableIntStateOf(initialPage.coerceIn(session.pages.indices)) }
    var controls by rememberSaveable(session.book.id) { mutableStateOf(false) }
    var toolbarDragOffset by remember { mutableFloatStateOf(0f) }
    var toolbarDragJob by remember { mutableStateOf<Job?>(null) }
    val toolbarDismissDistance = with(LocalDensity.current) { 48.dp.toPx() }
    val toolbarSpring = spring<Float>(stiffness = toolbarSpeed.stiffness(Spring.StiffnessMediumLow))
    LaunchedEffect(controls) {
        if (controls) {
            toolbarDragJob?.cancel()
            toolbarDragOffset = 0f
        }
    }
    fun restoreToolbarPosition() {
        toolbarDragJob?.cancel()
        toolbarDragJob = scope.launch {
            animate(toolbarDragOffset, 0f, animationSpec = toolbarSpring) { value, _ ->
                toolbarDragOffset = value
            }
        }
    }
    var preferences by remember { mutableStateOf(false) }
    val preferencesSheet = if (preferences) rememberModalBottomSheetState() else null
    val preferencesTargetVisible = preferencesSheet != null && preferencesSheet.targetValue != SheetValue.Hidden
    var preferencesHadVisibleTarget by remember(preferencesSheet) { mutableStateOf(false) }
    // 面板初始也以 Hidden 为目标，首次展开后才将 Hidden 视为关闭。
    if (preferencesTargetVisible && !preferencesHadVisibleTarget) {
        SideEffect { preferencesHadVisibleTarget = true }
    }
    val settingsIcon = settingsIconProgress(
        settingsOpen = preferences && (preferencesTargetVisible || !preferencesHadVisibleTarget),
    )
    var jumpDialog by remember { mutableStateOf(false) }
    var zoomSequence by remember { mutableIntStateOf(0) }
    var zoomAction by remember { mutableIntStateOf(0) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    var pageSliderPosition by remember(session) { mutableStateOf<Float?>(null) }
    val sliderTarget by remember(session) { derivedStateOf { pageSliderPosition?.roundToInt() } }
    val scrubbing by remember(session) { derivedStateOf { sliderTarget != null } }
    var pageSliderVideoState by remember(session) { mutableStateOf<VideoPlaybackState?>(null) }
    var pendingPreviewPage by remember(session) { mutableStateOf<Int?>(null) }
    var heldPreviewPage by remember(session) { mutableStateOf<Int?>(null) }
    var renderedPreviewPage by remember(session) { mutableStateOf<Int?>(null) }
    val previewPage by remember(session) {
        derivedStateOf { pendingPreviewPage ?: heldPreviewPage?.takeIf { it == sliderTarget } }
    }
    val targetPageReady by remember(session) {
        derivedStateOf { renderedPreviewPage != null && renderedPreviewPage == (sliderTarget ?: pendingPreviewPage) }
    }
    val videoBook = remember(session) { session.pages.any(PageSource::isVideo) }
    val mixedBook = remember(session) { videoBook && session.pages.any { !it.isVideo } }
    val verticalReading = settings.vertical
    val reverseReading = !verticalReading && settings.rightToLeft
    val continuous = !settings.paged
    var measuredViewport by remember { mutableStateOf(IntSize.Zero) }
    val viewport = if (measuredViewport.width > 0 && measuredViewport.height > 0) {
        measuredViewport
    } else {
        val window = LocalWindowInfo.current.containerSize
        IntSize(window.width.coerceAtLeast(1), window.height.coerceAtLeast(1))
    }
    val groups = remember(session, settings.loopMode, continuous) {
        if (continuous && mixedBook) ReaderPageGroups(session.pages.map(PageSource::isVideo), settings.loopMode) else null
    }
    val pagerReading = settings.paged || videoBook
    val pagePaging = remember(session.pages.size, settings.loopMode) { ReaderPaging(session.pages.size, settings.loopMode) }
    val paging = remember(pagePaging, groups) { groups?.let { ReaderPaging(it.size, settings.loopMode) } ?: pagePaging }
    fun panelOf(page: Int) = groups?.groupAt(page) ?: page
    fun pageAt(item: Int, offset: Int = 0): Int {
        val panel = paging.pageAt(item)
        return groups?.pageAt(panel, offset) ?: panel
    }
    fun mediaItem(item: Int, index: Int) = ReaderMediaItem(index,
        if (paging.looping) paging.cycleAt(item) + (groups?.cycleOffsetOf(index) ?: 0) else 0)
    var currentCycle by rememberSaveable(session.book.id) { mutableIntStateOf(0) }
    var zoomItem by remember(session, paging, pagerReading, verticalReading, reverseReading) { mutableStateOf<ReaderItem?>(null) }
    val listState = key(pagePaging, pagerReading) { rememberLazyListState(pagePaging.itemInCycle(currentPage, currentCycle)) }
    val currentPagerItem = paging.itemInCycle(
        panelOf(currentPage), currentCycle - (groups?.cycleOffsetOf(currentPage) ?: 0),
    )
    val pagerState = rememberPagerState(initialPage = currentPagerItem) { paging.itemCount }
    var positionedPaging by remember { mutableStateOf(paging) }
    val targetItem = currentPagerItem.takeIf { positionedPaging !== paging }
    if (targetItem != null) SideEffect {
        if (pagerReading) pagerState.requestScrollToPage(targetItem)
        positionedPaging = paging
    }
    val flingBehavior = pageFlingBehavior(pagerState, pagingSpeed)
    val orientation = if (verticalReading) Orientation.Vertical else Orientation.Horizontal
    val reverseDirection = ScrollableDefaults.reverseDirection(LocalLayoutDirection.current, orientation, reverseReading)
    val flingThreshold = with(LocalDensity.current) { 400.dp.toPx() }
    val runScroll = if (groups != null) remember(pagerState, orientation, reverseDirection, flingThreshold, pagingSpeed) {
        ReaderImageRunScroll(pagerState, verticalReading, reverseDirection, flingThreshold, pagingSpeed)
    } else null
    val latestRunScroll by rememberUpdatedState(runScroll)
    val actualSettledItem by remember(pagerState) { derivedStateOf { latestRunScroll?.settledPage ?: pagerState.settledPage } }
    val settledItem = targetItem ?: actualSettledItem
    val runLists = remember(groups) { mutableStateMapOf<Int, LazyListState>() }
    var imageJump by remember(groups) { mutableStateOf<ImageRunJump?>(null) }
    val currentList = if (pagerReading) runLists[settledItem] else listState
    var jumpJob by remember { mutableStateOf<Job?>(null) }
    var pendingJumpPage by remember(paging, verticalReading, reverseReading) { mutableStateOf<Int?>(null) }
    val ratios = remember(session.book.cacheKey) { mutableStateMapOf<Int, Float>() }
    var initialImageLoaded by remember(session) { mutableStateOf(false) }
    val onInitialImageLoaded = { initialImageLoaded = true }
    val background = if (settings.readerDark) DarkReaderBackground else LightReaderBackground
    val foreground = if (settings.readerDark) Color(0xFFE6E5E1) else Color(0xFF262724)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val activity = LocalActivity.current ?: return
    val context = LocalContext.current
    fun zoom(action: Int) {
        val list = currentList
        zoomItem = if (list != null) ReaderItem(if (pagerReading) settledItem else -1, list.layoutInfo.centerItem() ?: return)
            else ReaderItem(settledItem, 0)
        zoomAction = action
        zoomSequence++
    }
    val loadedItems = remember(session, paging, pagerReading) { mutableStateMapOf<ReaderItem, Unit>() }
    val priorityPage = previewPage?.takeUnless { targetPageReady }
    val normalPageLoading = (!scrubbing && pendingPreviewPage == null) || targetPageReady
    val pdfPages = if (session.pdf != null) rememberPdfReaderPages(
        session, active && (normalPageLoading || priorityPage != null), priorityPage,
    ) {
        val first = if (pagerReading) pagerState.currentPage else listState.firstVisibleItemIndex
        val visible = if (pagerReading) emptyList() else listState.layoutInfo.visibleItemsInfo.map { it.index }
        val last = visible.lastOrNull() ?: first
        val adjacent = (listOf(first) + visible + listOf(last + 1, first - 1))
            .filter { it in 0 until paging.itemCount }
            .map(paging::pageAt)
        (listOf(currentPage) + adjacent).distinct()
    } else null
    val videoCovers = remember(session) { VideoCoverCache() }
    val pagePreviews = rememberReaderPagePreview(
        session, repository, videoCovers, viewport, continuous, verticalReading, settings.continuousImageFitScreen,
        groups, looping = paging.looping,
        canDecode = {
            active && controls && (initialImageLoaded || videoBook) && session.pages.size > 1 && previewPage == null
        },
        idlePage = { currentPage.takeIf { active && visible && (initialImageLoaded || videoBook) &&
            !scrubbing && pendingPreviewPage == null && currentList?.isScrollInProgress != true &&
            !pagerState.isScrollInProgress && runScroll?.transitioning != true } },
        pdfPages = pdfPages,
    ) {
        if (active && controls) pendingPreviewPage ?: sliderTarget else null
    }
    val continuousPreviews by remember(pagePreviews) {
        derivedStateOf { pagePreviews.value?.pages.orEmpty().associateBy { it.index } }
    }
    val showingRenderedPreview by remember(pagerReading, paging, runScroll, pagePreviews, loadedItems) {
        derivedStateOf {
            val panel = if (pagerReading) settledItem else -1
            val list = if (pagerReading) runLists[panel] else listState
            targetPageReady && (list == null || list.layoutInfo.visibleItemsInfo.all {
                val index = if (pagerReading) pageAt(panel, it.index) else pageAt(it.index)
                ReaderItem(panel, it.index) in loadedItems || index in continuousPreviews
            })
        }
    }
    val onPreviewReady: (Int) -> Unit = { index ->
        if ((!continuous || session.pages[index].isVideo) && !scrubbing && pendingPreviewPage == index) {
            pendingPreviewPage = null
            renderedPreviewPage = null
            pageSliderVideoState = null
        } else if (previewPage == index) {
            renderedPreviewPage = index
        }
    }
    LaunchedEffect(scrubbing, pendingPreviewPage, showingRenderedPreview) {
        if (!scrubbing && pendingPreviewPage != null && showingRenderedPreview) {
            pendingPreviewPage = null
            renderedPreviewPage = null
            pageSliderVideoState = null
        }
    }
    val videoPool = if (videoBook) {
        val speedLock = rememberSaveable(session.book.cacheKey) { mutableStateOf<Float?>(null) }
        remember(session, context) { VideoPlaybackPool(context, speedLock) }
    } else null
    // 视频页在切页模式变化后可能对应新的虚拟位置，按媒体及其所在的循环保留同一页。
    fun pagerKey(item: Int): String {
        val index = pageAt(item)
        val media = mediaItem(item, index)
        val kind = when {
            session.pages[index].isVideo -> "video"
            groups != null -> "group"
            else -> "image"
        }
        return "${session.book.cacheKey}-$kind-$index-${media.cycle}"
    }
    val pagerItems = remember(session) { mutableMapOf<String, @Composable (ReaderPagerItem, Int) -> Unit>() }
    val currentImage = remember(session) { CurrentReaderImage() }
    DisposableEffect(videoPool) {
        onDispose { videoPool?.releaseAll() }
    }

    LaunchedEffect(session.book.cacheKey) {
        DiagnosticLog.log("Reader", "会话开始 book=${session.book.id.take(8)} kind=${session.book.kind} pages=${session.pages.size} " +
            "video=$videoBook mixed=$mixedBook vertical=${settings.vertical} paged=${settings.paged} rtl=${settings.rightToLeft} " +
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

    LaunchedEffect(paging, listState, pagerReading, verticalReading, reverseReading, videoPool, continuous && settings.continuousImageFitScreen) {
        jumpJob?.cancel()
        val panel = panelOf(currentPage)
        val item = if (pagerReading) targetItem ?: paging.nearestItem(panel, pagerState.settledPage)
            else paging.nearestItem(currentPage, listState.firstVisibleItemIndex)
        if (pagerReading) {
            if (groups != null && !session.pages[currentPage].isVideo) {
                val offset = groups.offsetOf(currentPage)
                imageJump = ImageRunJump(ReaderItem(item, offset))
                runLists[item]?.requestScrollToItem(offset)
            }
            if (pagerState.currentPage != item || pagerState.currentPageOffsetFraction != 0f) pagerState.scrollToPage(item)
        } else listState.scrollToItem(item)
        var lastItem by mutableIntStateOf(item)
        snapshotFlow {
            if (scrubbing) null
            else if (pagerReading) {
                val panel = actualSettledItem
                val first = pageAt(panel)
                if (groups == null || session.pages[first].isVideo) panel to first
                else {
                    val list = runLists[panel]?.takeIf { it.layoutInfo.visibleItemsInfo.isNotEmpty() } ?: return@snapshotFlow null
                    val last = groups.pageCount(paging.pageAt(panel)) - 1
                    val requested = imageJump?.takeIf { it.item.panel == panel && (it.anchor == null || it.anchor == list.anchor()) }
                    val offset = requested?.item?.index ?: when {
                        list.canScrollForward -> list.firstVisibleItemIndex
                        list.canScrollBackward || panel < lastItem -> last
                        panel == lastItem && panelOf(currentPage) == paging.pageAt(panel) -> groups.offsetOf(currentPage)
                        else -> 0
                    }
                    panel to pageAt(panel, offset)
                }
            }
            else -1 to if (!paging.looping && !listState.canScrollForward && listState.canScrollBackward) paging.pageCount - 1
                else paging.pageAt(listState.firstVisibleItemIndex)
        }.filterNotNull().collect { (item, page) ->
            lastItem = item
            if (imageJump?.item?.panel != item) imageJump = null
            videoPool?.onPageSettled(page)
            val occurrence = if (pagerReading) item else listState.firstVisibleItemIndex
            currentCycle = mediaItem(occurrence, page).cycle
            currentPage = page
            onProgress(page)
            DiagnosticLog.log("Reader", "停靠到第 ${page + 1}/${session.pages.size} 项")
        }
    }
    val settledMediaItem = if (videoBook) mediaItem(settledItem, pageAt(settledItem)) else null
    val settledVideoItem = settledMediaItem?.takeIf { session.pages[it.page].isVideo }
    DisposableEffect(videoPool, settledVideoItem) {
        // 视图换到新的 Pager 位置时，停靠的视频仍持有同一个播放器。
        if (videoPool != null && settledVideoItem != null) videoPool.acquire(settledVideoItem)
        onDispose { if (videoPool != null && settledVideoItem != null) videoPool.release(settledVideoItem) }
    }
    var videoModeTransition by remember(session) { mutableStateOf<VideoModeTransition?>(null) }
    LaunchedEffect(videoModeTransition, settings.paged) {
        val transition = videoModeTransition ?: return@LaunchedEffect
        withFrameNanos { }
        withTimeoutOrNull(1500L) {
            snapshotFlow { transition.state.frameReady || transition.state.failed || transition.state.isReleased() }.first { it }
        }
        withFrameNanos { }
        if (videoModeTransition === transition) videoModeTransition = null
    }
    LaunchedEffect(settledMediaItem, active) {
        if (!videoBook || !active) return@LaunchedEffect
        videoPool?.snapshot()?.forEach { (item, state) -> if (item != settledMediaItem) state.pause() }
    }
    val pageInteractions = if (pagerReading) pagerState.interactionSource else listState.interactionSource
    LaunchedEffect(pageInteractions) {
        pageInteractions.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                pendingPreviewPage = null
                pageSliderVideoState = null
            }
        }
    }
    LaunchedEffect(currentList, groups) {
        if (groups == null || currentList == null) return@LaunchedEffect
        currentList.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                imageJump = null
                pendingPreviewPage = null
                pageSliderVideoState = null
            }
        }
    }
    LaunchedEffect(currentList, settledItem, imageJump) {
        val jump = imageJump ?: return@LaunchedEffect
        if (currentList == null || jump.item.panel != settledItem || jump.anchor != null) return@LaunchedEffect
        snapshotFlow { currentList.layoutInfo.visibleItemsInfo.any { it.index == jump.item.index } }.first { it }
        imageJump = jump.copy(anchor = currentList.anchor())
    }
    suspend fun scrollToReaderPage(page: Int, animate: Boolean, step: Int = 0) {
        if (pagerReading) {
            val panel = panelOf(page)
            val origin = settledItem
            val nearest = paging.nearestItem(panel, if (step == 0) pagerState.currentPage else origin)
            val item = when {
                !paging.looping || step == 0 || panel == paging.pageAt(origin) -> nearest
                step > 0 && nearest < origin && nearest + paging.pageCount < paging.itemCount -> nearest + paging.pageCount
                step < 0 && nearest > origin && nearest >= paging.pageCount -> nearest - paging.pageCount
                else -> nearest
            }
            if (groups != null && !session.pages[page].isVideo) {
                val offset = groups.offsetOf(page)
                val list = runLists[item]
                imageJump = if (item == origin && list != null) {
                    list.scrollToItem(offset)
                    ImageRunJump(ReaderItem(item, offset), list.anchor())
                } else {
                    list?.requestScrollToItem(offset)
                    ImageRunJump(ReaderItem(item, offset))
                }
            }
            if (animate) {
                pagerState.animateScrollToPage(item, animationSpec = spring(stiffness = pagingSpeed.stiffness(Spring.StiffnessMedium)))
            } else {
                pagerState.scrollToPage(item)
            }
        } else {
            listState.scrollToItem(paging.nearestItem(page, listState.firstVisibleItemIndex))
        }
    }
    LaunchedEffect(session, paging, pagerReading, verticalReading, reverseReading, controls, active) {
        if (!controls || !active) return@LaunchedEffect
        var previousPosition: Float? = null
        snapshotFlow { pageSliderPosition.takeIf { pendingPreviewPage == null } }.collectLatest { position ->
            val previous = previousPosition
            previousPosition = position
            val target = position?.roundToInt() ?: return@collectLatest
            if (heldPreviewPage != target) {
                heldPreviewPage = null
                renderedPreviewPage = null
                val fast = previous != null && abs(position - previous) >= 2f
                delay(if (fast) FastPagePreviewHoldMillis else PagePreviewHoldMillis)
                if (pageSliderPosition != position || pendingPreviewPage != null) return@collectLatest
                heldPreviewPage = target
            }
            if (!showingRenderedPreview) scrollToReaderPage(target, animate = false)
        }
    }
    fun jump(target: Int, step: Int = 0) {
        val page = pagePaging.destination(target)
        pendingPreviewPage = null
        pageSliderVideoState = null
        jumpJob?.cancel()
        pendingJumpPage = page
        if (videoBook) {
            DiagnosticLog.log("Reader", "跳转到第 ${page + 1} 项")
        } else {
            currentPage = page
            onProgress(page)
        }
        jumpJob = scope.launch { scrollToReaderPage(page, animate = videoBook, step = step) }
    }
    val volumePage by rememberUpdatedState<(Int) -> Unit> turn@{ direction ->
        if (scrubbing) return@turn
        val origin = pendingJumpPage?.takeIf { jumpJob?.isActive == true } ?: currentPage
        if (pagePaging.looping || origin + direction in session.pages.indices) {
            jump(origin + direction, step = direction)
        }
    }
    DisposableEffect(activity, active, visible, settings.volumeKeyPaging, preferences, jumpDialog) {
        val mainActivity = activity as? MainActivity
        if (visible && active && settings.volumeKeyPaging && !preferences && !jumpDialog) {
            mainActivity?.onVolumePage = { volumePage(it) }
        }
        onDispose { mainActivity?.onVolumePage = null }
    }
    val currentPageIsVideo = session.pages[currentPage].isVideo
    val toolbarVideoState = if (scrubbing) pageSliderVideoState
        else if (currentPageIsVideo && pageAt(settledItem) == currentPage) {
            settledMediaItem?.let { videoPool?.get(it) } ?: pageSliderVideoState
        } else null
    val onVideoEnded: (VideoPlaybackState) -> Unit = onEnded@{ state ->
        if (scrubbing) return@onEnded
        DiagnosticLog.log("Reader", "视频播放结束 index=${state.index} scrolling=${pagerState.isScrollInProgress} page=$currentPage")
        if (pagerState.isScrollInProgress || runScroll?.transitioning == true) {
            // 切换尚未完成时当前视频播完就循环播放，而不是停在结尾等待下一次手势。
            state.replay()
        } else if (!settings.videoLoopSingle && (settings.loopMode || currentPage < session.pages.lastIndex)) {
            jump(currentPage + 1, step = 1)
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
        .then(if (visible) Modifier else Modifier.semantics { hideFromAccessibility() })) {
        Box(Modifier.fillMaxSize().onSizeChanged { measuredViewport = it }.graphicsLayer {
            translationX = size.width * backProgress * .24f
            scaleX = 1f - backProgress * .08f
            scaleY = 1f - backProgress * .08f
            alpha = 1f - backProgress * .25f
        }.background(background)) {
            val imageList: @Composable (LazyListState, Int, Int) -> Unit = { state, panel, count ->
                val items: LazyListScope.() -> Unit = {
                    items(count, key = { it }) { offset ->
                        val index = if (panel == -1) pageAt(offset) else pageAt(panel, offset)
                        val item = ReaderItem(panel, offset)
                        val displayed by remember(state, offset) {
                            derivedStateOf { state.layoutInfo.visibleItemsInfo.any { it.index == offset } }
                        }
                        val first by remember(state, offset) { derivedStateOf { state.firstVisibleItemIndex == offset } }
                        val settled = panel == -1 || panel == settledItem
                        DisposableEffect(loadedItems, item) {
                            onDispose {
                                loadedItems.remove(item)
                                if (zoomItem == item) zoomItem = null
                            }
                        }
                        ReaderPage(session, index, repository, settings, viewport, currentImage, index == currentPage,
                            active = active && settled && displayed && normalPageLoading,
                            foreground = foreground, zoomSequence = if (item == zoomItem) zoomSequence else 0,
                            zoomAction = zoomAction, initialRatio = ratios[index], onRatio = { ratios[index] = it },
                            loadEnabled = priorityPage == index || normalPageLoading && (initialImageLoaded || videoBook || first),
                            onLoaded = { onInitialImageLoaded(); loadedItems[item] = Unit }, pdfPages = pdfPages,
                            waitingForPreview = previewPage == index && settled && displayed,
                            onPreviewReady = onPreviewReady, preview = continuousPreviews[index],
                            onTap = { controls = !controls }, clipPage = true)
                    }
                }
                val modifier = Modifier.fillMaxSize()
                if (verticalReading) {
                    LazyColumn(state = state, modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally,
                        overscrollEffect = if (panel == -1) rememberOverscrollEffect() else null, content = items)
                } else {
                    LazyRow(state = state, modifier = modifier, reverseLayout = reverseReading, verticalAlignment = Alignment.CenterVertically,
                        overscrollEffect = if (panel == -1) rememberOverscrollEffect() else null, content = items)
                }
            }
            if (pagerReading) {
                val pageContent: ReaderPagerItem = { item ->
                    val index = pageAt(item)
                    when {
                        groups != null && !session.pages[index].isVideo -> {
                            val count = groups.pageCount(paging.pageAt(item))
                            val target = imageJump?.item?.takeIf { it.panel == item }?.index
                            val fromEnd = item < settledItem
                            val first = target ?: when {
                                fromEnd -> count - 1
                                item == settledItem && panelOf(currentPage) == paging.pageAt(item) -> groups.offsetOf(currentPage)
                                else -> 0
                            }
                            // LazyList は末尾を超えるオフセットを、実際にスクロール可能な末尾に収める。
                            val endOffset = Int.MAX_VALUE / 2
                            val state = remember(item) { LazyListState(first, if (target == null && fromEnd) endOffset else 0) }
                            DisposableEffect(item, state) {
                                runLists[item] = state
                                onDispose { runLists.remove(item) }
                            }
                            val endRatio = if (fromEnd) ratios[pageAt(item, count - 1)] else null
                            LaunchedEffect(settledItem, endRatio) {
                                if (item != settledItem && imageJump?.item?.panel != item) {
                                    state.scrollToItem(if (fromEnd) count - 1 else 0, if (fromEnd) endOffset else 0)
                                }
                            }
                            imageList(state, item, count)
                        }
                        videoPool != null && session.pages[index].isVideo -> {
                            ReaderVideoPage(mediaItem(item, index), session, settled = settledItem == item,
                                neighbor = abs(item - settledItem) <= 1, repository, videoPool, videoCovers,
                                active, allowPlayback = visible && !scrubbing, settings, foreground, background,
                                videoPosition, onVideoProgress,
                                if (zoomItem == ReaderItem(item, 0)) zoomSequence else 0, zoomAction,
                                previewPage, onPreviewReady, onEntryReady = if (visible) null else onVideoEntryReady,
                                onTap = { controls = !controls }, onEnded = onVideoEnded)
                        }
                        else -> {
                            val current = if (videoBook) settledItem == item else pagerState.currentPage == item
                            ReaderPage(session, index, repository, settings, viewport, currentImage, index == currentPage,
                                active = active && normalPageLoading && current,
                                foreground = foreground, zoomSequence = if (zoomItem == ReaderItem(item, 0)) zoomSequence else 0,
                                zoomAction = zoomAction, loadEnabled = priorityPage == index ||
                                    normalPageLoading && (initialImageLoaded || videoBook || current),
                                onLoaded = onInitialImageLoaded, pdfPages = pdfPages,
                                waitingForPreview = previewPage == index && current, onPreviewReady = onPreviewReady,
                                onTap = { controls = !controls }, clipPage = videoBook || settings.pagedImageFit != PagedImageFit.Screen)
                        }
                    }
                }
                val itemContent: ReaderPagerItem = { item ->
                    if (session.pages[pageAt(item)].isVideo) {
                        val key = pagerKey(item)
                        val content = remember(key) {
                            pagerItems.getOrPut(key) {
                                movableContentOf<ReaderPagerItem, Int> { page, position ->
                                    DisposableEffect(key) { onDispose { pagerItems.remove(key) } }
                                    page(position)
                                }
                            }
                        }
                        content(pageContent, item)
                    } else {
                        pageContent(item)
                    }
                }
                val nestedScroll = runScroll ?: PagerDefaults.pageNestedScrollConnection(pagerState, orientation)
                val pagerModifier = runScroll?.trackTouchVelocity(Modifier.fillMaxSize()) ?: Modifier.fillMaxSize()
                if (verticalReading) {
                    VerticalPager(state = pagerState, modifier = pagerModifier, beyondViewportPageCount = 1,
                        flingBehavior = flingBehavior, pageNestedScrollConnection = nestedScroll,
                        key = ::pagerKey) { item -> itemContent(item) }
                } else {
                    HorizontalPager(state = pagerState, reverseLayout = reverseReading, modifier = pagerModifier,
                        flingBehavior = flingBehavior, pageNestedScrollConnection = nestedScroll,
                        beyondViewportPageCount = 1, key = ::pagerKey) { item -> itemContent(item) }
                }
            } else {
                imageList(listState, -1, paging.itemCount)
            }
            if (controls && (scrubbing || pendingPreviewPage != null) && !showingRenderedPreview) {
                ReaderPreviewOverlay(pagePreviews, session, settings, viewport, reverseReading, background)
            }
            videoModeTransition?.takeIf { it.state.index == currentPage }?.let { transition ->
                Image(remember(transition.frame) { transition.frame.asImageBitmap() }, null,
                    Modifier.fillMaxSize().background(background), contentScale = ContentScale.FillBounds)
            }
            if (!controls && settings.showPageNumber) {
                Surface(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(12.dp),
                    color = background.copy(alpha = OverlayBackgroundAlpha),
                    contentColor = foreground, shape = RoundedCornerShape(12.dp)) {
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
            AnimatedVisibility(visible = controls, enter = fadeIn(toolbarSpring), exit = fadeOut(toolbarSpring),
                modifier = Modifier.align(Alignment.TopCenter).blockVideoGestures()) {
                TopAppBar(title = {
                    Text(session.book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                }, navigationIcon = {
                    IconButton(onClick = { onProgress(currentPage); onBack() }) { Icon(painterResource(R.drawable.ic_symbol_arrow_back), stringResource(R.string.back_to_library)) }
                }, actions = {
                    SettingsIconButton(stringResource(R.string.reader_settings), settingsIcon, active = !preferences) {
                        preferences = true
                    }
                },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .97f)))
            }
            val slideSpec = spring(
                stiffness = toolbarSpeed.stiffness(Spring.StiffnessMediumLow), visibilityThreshold = IntOffset(1, 1),
            )
            AnimatedVisibility(
                visible = controls,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .offset { IntOffset(0, toolbarDragOffset.roundToInt()) }
                    .blockVideoGestures(),
                enter = slideInVertically(slideSpec) { it } + fadeIn(toolbarSpring),
                exit = slideOutVertically(slideSpec) { it } + fadeOut(toolbarSpring),
            ) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .98f), tonalElevation = 3.dp,
                    shadowElevation = 8.dp, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
                        val hideControls = stringResource(R.string.hide_reader_controls)
                        Box(
                            Modifier.align(Alignment.CenterHorizontally).size(width = 96.dp, height = 48.dp)
                                .semantics { contentDescription = hideControls }
                                .clickable(enabled = controls, role = Role.Button) { controls = false }
                                .pointerInput(controls, toolbarDismissDistance, toolbarSpring) {
                                    if (!controls) return@pointerInput
                                    detectVerticalDragGestures(
                                        onDragStart = { toolbarDragJob?.cancel() },
                                        onDragEnd = {
                                            if (toolbarDragOffset >= toolbarDismissDistance) controls = false
                                            else restoreToolbarPosition()
                                        },
                                        onDragCancel = ::restoreToolbarPosition,
                                        onVerticalDrag = { _, distance ->
                                            toolbarDragOffset = (toolbarDragOffset + distance).coerceAtLeast(0f)
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.outlineVariant))
                        }
                        if (toolbarVideoState != null) {
                            VideoProgressControls(toolbarVideoState, enabled = !scrubbing && pendingPreviewPage == null)
                        }
                        ReaderPageSlider(
                            progressLabel = when {
                                mixedBook -> R.string.item_progress
                                videoBook -> R.string.video_progress
                                else -> R.string.page_progress
                            },
                            pageCount = session.pages.size,
                            currentPage = currentPage,
                            scrubbing = scrubbing,
                            sliderPosition = { pageSliderPosition },
                            onJump = { jumpDialog = true },
                            onValueChange = { value ->
                                if (pageSliderPosition == null) {
                                    jumpJob?.cancel()
                                    pendingPreviewPage = null
                                    heldPreviewPage = null
                                    renderedPreviewPage = null
                                    pageSliderVideoState = toolbarVideoState
                                }
                                pageSliderPosition = value
                            },
                            onValueChangeFinished = {
                                pageSliderPosition?.let { value ->
                                    jumpJob?.cancel()
                                    val target = value.roundToInt()
                                    pendingPreviewPage = target
                                    jumpJob = scope.launch {
                                        if (!showingRenderedPreview) scrollToReaderPage(target, animate = false)
                                        currentPage = target
                                        onProgress(target)
                                        pageSliderPosition = null
                                        heldPreviewPage = null
                                        if (renderedPreviewPage == target) onPreviewReady(target)
                                    }
                                }
                            },
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { jump(currentPage - 1, step = -1) }, enabled = !scrubbing && (settings.loopMode || currentPage > 0)) { Icon(painterResource(R.drawable.ic_symbol_navigate_before), stringResource(R.string.previous_page)) }
                            IconButton(onClick = { zoom(-1) }, enabled = !scrubbing) { Icon(painterResource(R.drawable.ic_symbol_zoom_out), stringResource(if (currentPageIsVideo) R.string.zoom_out_video else R.string.zoom_out_image)) }
                            TextButton(onClick = { zoom(0) }, enabled = !scrubbing) { Text(stringResource(R.string.fit_screen)) }
                            IconButton(onClick = { zoom(1) }, enabled = !scrubbing) { Icon(painterResource(R.drawable.ic_symbol_zoom_in), stringResource(if (currentPageIsVideo) R.string.zoom_in_video else R.string.zoom_in_image)) }
                            IconButton(onClick = { jump(currentPage + 1, step = 1) }, enabled = !scrubbing && (settings.loopMode || currentPage < session.pages.lastIndex)) { Icon(painterResource(R.drawable.ic_symbol_keyboard_arrow_right), stringResource(R.string.next_page)) }
                        }
                    }
                }
            }
        }
    }
    if (preferencesSheet != null) WithAnimationSpeed(settings.animationSpeedFor(AnimationEffect.ReaderQuickSettings), modifier = Modifier.size(0.dp)) {
        ModalBottomSheet(sheetState = preferencesSheet, onDismissRequest = { preferences = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text(stringResource(R.string.reader_settings), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(24.dp))
                ReaderPreferences(settings) { updated ->
                    if (mixedBook && updated.paged != settings.paged && settledVideoItem?.page == currentPage) {
                        val playback = videoPool?.get(settledVideoItem)
                        val frame = playback?.ownerView?.captureVideoFrame()
                        if (playback != null && frame != null) videoModeTransition = VideoModeTransition(playback, frame)
                    }
                    onSettings(updated)
                }
                Spacer(Modifier.height(20.dp))
            }
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
        AppAlertDialog(onDismissRequest = { jumpDialog = false }, title = { Text(stringResource(title)) }, text = {
            TextSelectionHaptics {
                OutlinedTextField(text, { text = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(label, session.pages.size)) },
                    singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            }
        }, confirmButton = { TextButton(enabled = page != null && page in 1..session.pages.size,
            onClick = { jump(page!! - 1); jumpDialog = false }) { Text(stringResource(R.string.jump)) } },
            dismissButton = { TextButton(onClick = { jumpDialog = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

/**
 * 停靠视频及其相邻视频预建播放器。拖动到一半再拉回时复用视图和 Surface。
 */
@Composable
private fun ReaderVideoPage(
    item: ReaderMediaItem, session: BookSession, settled: Boolean, neighbor: Boolean, repository: BookRepository,
    pool: VideoPlaybackPool, covers: VideoCoverCache,
    active: Boolean, allowPlayback: Boolean, settings: ReaderSettings, foreground: Color, background: Color,
    videoPosition: (Int) -> Long, onVideoProgress: (Int, Long) -> Unit,
    zoomSequence: Int, zoomAction: Int, previewPage: Int?, onPreviewReady: (Int) -> Unit,
    onEntryReady: (() -> Unit)?,
    onTap: () -> Unit, onEnded: (VideoPlaybackState) -> Unit,
) {
    val index = item.page
    val cover = rememberVideoCover(session, index, repository, covers)
    val state = if (neighbor) remember(item) { pool.acquire(item) } else null
    DisposableEffect(state) { onDispose { if (state != null) pool.release(item) } }
    val frameReady = settled && state != null && (state.failed || state.frameReady)
    val entryReady = onEntryReady != null && frameReady
    LaunchedEffect(entryReady) {
        if (entryReady) {
            withFrameNanos { }
            onEntryReady?.invoke()
        }
    }
    val previewReady = previewPage == index && frameReady
    LaunchedEffect(previewReady) {
        if (previewReady) {
            withFrameNanos { }
            onPreviewReady(index)
        }
    }
    VideoPage(state, index, session, cover, settings,
        videoPosition, onVideoProgress, foreground, background, active && settled, allowPlayback,
        zoomSequence, zoomAction, onTap = onTap, onEnded = onEnded)
}

@Composable
private fun ReaderPageSlider(
    progressLabel: Int, pageCount: Int, currentPage: Int, scrubbing: Boolean,
    sliderPosition: () -> Float?, onJump: () -> Unit,
    onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit,
) {
    val position = sliderPosition()
    val pageNumber = (position?.roundToInt() ?: currentPage) + 1
    val sliderHaptics = remember(pageCount) { ProgressSliderHaptics() }
    val haptic = hapticFeedback(HapticEvent.ReaderPageSlider)
    val lastPage = (pageCount - 1).toFloat()
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onJump, enabled = !scrubbing) {
            Text(stringResource(progressLabel, pageNumber, pageCount))
        }
        Spacer(Modifier.weight(1f))
        Text("${(pageNumber.toFloat() / pageCount * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
    }
    Slider(value = position ?: currentPage.toFloat(), onValueChange = { value ->
            sliderHaptics.update(value.roundToInt().toLong(), value <= 0f, value >= lastPage, haptic)
            onValueChange(value)
        },
        onValueChangeFinished = onValueChangeFinished, valueRange = 0f..maxOf(1, pageCount - 1).toFloat(),
        enabled = pageCount > 1, modifier = Modifier.fillMaxWidth().trackProgressSliderDrag(
            sliderHaptics, (pageNumber - 1).toLong(), (position ?: currentPage.toFloat()) == 0f,
            (position ?: currentPage.toFloat()) == lastPage,
        ))
}

private fun Modifier.blockVideoGestures(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}

@Composable
private fun ReaderPreviewOverlay(
    previews: State<ReaderPreviewViewport?>, session: BookSession, settings: ReaderSettings,
    viewport: IntSize, reverse: Boolean, background: Color,
) {
    val frame = previews.value ?: return
    val pages = frame.pages
    val video = session.pages[frame.target].isVideo
    if (settings.paged || video) {
        val preview = pages.first()
        ReaderPreviewImage(preview, Modifier.fillMaxSize().background(background).clipToBounds(),
            video = video, fit = settings.pagedImageFit, reverse = reverse, videoFill = settings.videoFillScreen)
    } else if (settings.vertical) {
        Column(Modifier.fillMaxSize().background(background).clipToBounds()
            .wrapContentHeight(if (frame.alignEnd) Alignment.Bottom else Alignment.Top, unbounded = true),
            horizontalAlignment = Alignment.CenterHorizontally) {
            for (preview in pages) {
                ReaderPreviewImage(preview, continuousPageLayout(viewport, preview.ratio, settings))
            }
        }
    } else {
        val direction = LocalLayoutDirection.current
        val readingDirection = if (!reverse) direction else if (direction == LayoutDirection.Ltr) LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalLayoutDirection provides readingDirection) {
            Row(Modifier.fillMaxSize().background(background).clipToBounds()
                .wrapContentWidth(if (frame.alignEnd) Alignment.End else Alignment.Start, unbounded = true),
                verticalAlignment = Alignment.CenterVertically) {
                for (preview in pages) {
                    ReaderPreviewImage(preview, continuousPageLayout(viewport, preview.ratio, settings))
                }
            }
        }
    }
}

private val imageDecodeDispatcher = Dispatchers.IO.limitedParallelism(2)

@Composable
private fun continuousPageLayout(viewport: IntSize, ratio: Float, settings: ReaderSettings): Modifier {
    val size = continuousPageSize(viewport, ratio, settings.vertical, settings.continuousImageFitScreen)
    return with(LocalDensity.current) { Modifier.size(size.width.toDp(), size.height.toDp()) }
}

@Composable
private fun ReaderPreviewImage(preview: ReaderPagePreview, modifier: Modifier, video: Boolean = false,
    fit: PagedImageFit = PagedImageFit.Screen, reverse: Boolean = false, videoFill: Boolean = false) {
    val bitmap = remember(preview.bitmap) { preview.bitmap.asImageBitmap() }
    val scale = if (video) {
        if (videoFill) ContentScale.Crop else ContentScale.Fit
    } else when (fit) {
        PagedImageFit.Screen -> ContentScale.Fit
        PagedImageFit.Width -> ContentScale.FillWidth
        PagedImageFit.Height -> ContentScale.FillHeight
    }
    val alignment = if (video || fit == PagedImageFit.Screen) Alignment.Center else remember(fit, reverse) {
        Alignment { size, space, direction ->
            val anchor = when {
                fit == PagedImageFit.Width && size.height > space.height -> Alignment.TopCenter
                fit == PagedImageFit.Height && size.width > space.width ->
                    if (reverse) AbsoluteAlignment.CenterRight else AbsoluteAlignment.CenterLeft
                else -> Alignment.Center
            }
            anchor.align(size, space, direction)
        }
    }
    Image(bitmap, stringResource(
        if (video) R.string.video_accessibility_without_zoom else R.string.image_accessibility_without_zoom,
        preview.index + 1,
    ), modifier, alignment = alignment, contentScale = scale)
}

private data class ReaderImageKey(val index: Int, val width: Int, val animationWidth: Int)

private class CurrentReaderImage {
    private var key: ReaderImageKey? = null
    private var drawable: Drawable? = null

    operator fun get(key: ReaderImageKey): Drawable? = drawable.takeIf { this.key == key }

    fun retain(key: ReaderImageKey, image: Drawable) {
        this.key = key
        drawable = image
    }
}

@Composable
private fun ReaderPage(session: BookSession, index: Int, repository: BookRepository, settings: ReaderSettings,
    viewport: IntSize, currentImage: CurrentReaderImage, retainImage: Boolean, active: Boolean,
    foreground: Color, zoomSequence: Int, zoomAction: Int, initialRatio: Float? = null,
    onRatio: (Float) -> Unit = {}, loadEnabled: Boolean = true, onLoaded: () -> Unit = {},
    pdfPages: SnapshotStateMap<Int, Result<Bitmap>>? = null,
    waitingForPreview: Boolean, onPreviewReady: (Int) -> Unit, preview: ReaderPagePreview? = null, onTap: () -> Unit,
    clipPage: Boolean) {
    if (pdfPages != null) {
        val bitmap = pdfPages[index]?.getOrNull()
        val resources = LocalContext.current.resources
        val image = remember(bitmap, resources) { bitmap?.let { BitmapDrawable(resources, it) } }
        val loaded = pdfPages[index] != null
        LaunchedEffect(loaded, waitingForPreview) {
            if (loaded) {
                onLoaded()
                if (waitingForPreview) onPreviewReady(index)
            }
        }
        ReaderPageContent(index, image, pdfPages[index]?.isFailure == true, settings, viewport, active,
            foreground, zoomSequence, zoomAction, initialRatio, onRatio, onTap,
            onRetry = { pdfPages.remove(index) }, preview = preview, clipPage = clipPage)
        return
    }
    val viewportWidth = LocalWindowInfo.current.containerSize.width
    val width = (viewportWidth * 2).coerceIn(480, 3200)
    val animationWidth = viewportWidth.coerceIn(480, 3200)
    var retry by remember { mutableIntStateOf(0) }
    var failure by remember(session, index, width, animationWidth, retry) { mutableStateOf(false) }
    val imageKey = ReaderImageKey(index, width, animationWidth)
    val retained = remember(session, imageKey) { currentImage[imageKey].takeIf { retainImage } }
    val drawable by key(session, imageKey, retry) {
        val bitmap = remember { (retained as? BitmapDrawable)?.constantState?.newDrawable() }
        produceState(bitmap, loadEnabled) {
            if (!loadEnabled || value != null || failure) return@produceState
            try {
                val file = repository.pageFile(session, index)
                value = withContext(imageDecodeDispatcher) { ImageLoader.load(file, width, animationWidth) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                DiagnosticLog.error("Image", "页面解码失败 book=${session.book.id.take(8)} page=${index + 1} format=${session.pages[index].name.substringAfterLast('.')} targetWidth=$width", error)
                failure = true
            }
        }
    }
    if (retainImage && active) SideEffect { drawable?.let { currentImage.retain(imageKey, it) } }
    val retainedPreview = if (drawable == null && retained != null && retained !is BitmapDrawable) remember(retained) {
        // 动图从当前帧接替，新的页面在后台准备自己的动画实例。
        val scale = minOf(1f, 1600f / maxOf(retained.intrinsicWidth, retained.intrinsicHeight))
        val frameWidth = (retained.intrinsicWidth * scale).roundToInt().coerceAtLeast(1)
        val frameHeight = (retained.intrinsicHeight * scale).roundToInt().coerceAtLeast(1)
        ReaderPagePreview(index, retained.toBitmap(frameWidth, frameHeight))
    } else null
    val loaded = drawable != null || failure
    LaunchedEffect(loaded, waitingForPreview) {
        if (loaded) {
            onLoaded()
            if (waitingForPreview) onPreviewReady(index)
        }
    }
    ReaderPageContent(index, drawable, failure, settings, viewport, active, foreground, zoomSequence, zoomAction,
        initialRatio, onRatio, onTap, onRetry = { retry++ }, preview = retainedPreview ?: preview, clipPage = clipPage)
}

@Composable
private fun ReaderPageContent(index: Int, image: Drawable?, failure: Boolean, settings: ReaderSettings, viewport: IntSize, active: Boolean,
    foreground: Color, zoomSequence: Int, zoomAction: Int, initialRatio: Float?, onRatio: (Float) -> Unit,
    onTap: () -> Unit, onRetry: () -> Unit, preview: ReaderPagePreview?, clipPage: Boolean) {
    val speed = animationSpeed(AnimationEffect.ReaderImageZoom)
    val imageDescription = stringResource(
        if (settings.doubleTapZoom) R.string.image_accessibility else R.string.image_accessibility_without_zoom,
        index + 1,
    )
    val ratio = image?.let { it.intrinsicWidth.coerceAtLeast(1).toFloat() / it.intrinsicHeight.coerceAtLeast(1) }
        ?: initialRatio ?: preview?.ratio ?: .70f
    LaunchedEffect(image) { if (image != null) onRatio(ratio) }
    val fit = if (settings.paged) settings.pagedImageFit else PagedImageFit.Screen
    val reverse = fit == PagedImageFit.Height && !settings.vertical && settings.rightToLeft
    val layout = if (settings.paged) Modifier.fillMaxSize() else continuousPageLayout(viewport, ratio, settings)
    Box(if (clipPage) layout.clipToBounds() else layout, contentAlignment = Alignment.Center) {
        if (image != null) {
            AndroidView(factory = { context -> ZoomImageView(context) }, modifier = Modifier.fillMaxSize(),
                onRelease = ZoomImageView::clearImage,
                update = { view ->
                    view.onTap = onTap
                    view.doubleTapZoomEnabled = settings.doubleTapZoom
                    view.zoomAnimationSpeed = speed
                    view.bind(image, imageDescription, active, fit, reverse, viewport.width, viewport.height)
                    if (zoomSequence > 0) view.command(zoomSequence, zoomAction)
                })
        } else if (failure) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(R.drawable.ic_symbol_broken_image), null, tint = foreground)
                Text(stringResource(R.string.image_error), Modifier.padding(vertical = 16.dp), color = foreground)
                Row {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                    TextButton(onClick = onTap) { Text(stringResource(R.string.show_reader_controls)) }
                }
            }
        } else if (preview != null) {
            ReaderPreviewImage(preview, Modifier.fillMaxSize().clipToBounds(), fit = fit, reverse = reverse)
        } else AppCircularProgressIndicator(Modifier.size(28.dp), color = foreground)
    }
}
