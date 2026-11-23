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
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListLayoutInfo
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import dev.zolive.zviewer.data.AnimationEffect
import dev.zolive.zviewer.data.BookRepository
import dev.zolive.zviewer.data.BookSession
import dev.zolive.zviewer.data.DiagnosticLog
import dev.zolive.zviewer.data.HapticEvent
import dev.zolive.zviewer.data.ReaderSettings
import dev.zolive.zviewer.data.PageSource
import dev.zolive.zviewer.reader.ImageLoader
import dev.zolive.zviewer.reader.ReaderPaging
import dev.zolive.zviewer.reader.ZoomImageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private const val PagePreviewHoldMillis = 120L
private const val FastPagePreviewHoldMillis = 250L
private typealias VideoPagerItem = @Composable (Int) -> Unit

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
    var preferences by remember { mutableStateOf(false) }
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
    val paging = remember(session.pages.size, settings.loopMode) { ReaderPaging(session.pages.size, settings.loopMode) }
    var zoomItem by remember(session, paging, settings.vertical) { mutableIntStateOf(-1) }
    val listState = rememberLazyListState(paging.anchor(currentPage))
    val pagerState = rememberPagerState(initialPage = paging.anchor(currentPage)) { paging.itemCount }
    val flingBehavior = pageFlingBehavior(pagerState, pagingSpeed)
    var jumpJob by remember { mutableStateOf<Job?>(null) }
    var pendingVolumeItem by remember(paging, settings.vertical) { mutableStateOf<Int?>(null) }
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
    val continuous = !pagerReading
    fun zoom(action: Int) {
        zoomItem = if (continuous) listState.layoutInfo.centerItem() ?: return else pagerState.settledPage
        zoomAction = action
        zoomSequence++
    }
    val loadedItems = remember(session, paging) { mutableStateMapOf<Int, Unit>() }
    val priorityPage = previewPage?.takeUnless { targetPageReady }
    val normalPageLoading = (!scrubbing && pendingPreviewPage == null) || targetPageReady
    val pdfPages = if (session.pdf != null) rememberPdfReaderPages(
        session, active && (normalPageLoading || priorityPage != null), priorityPage,
    ) {
        val first = if (verticalReading) listState.firstVisibleItemIndex else pagerState.currentPage
        val visible = if (verticalReading) listState.layoutInfo.visibleItemsInfo.map { it.index } else emptyList()
        val last = visible.lastOrNull() ?: first
        (listOf(first) + visible + listOf(last + 1, first - 1))
            .filter { it in 0 until paging.itemCount }
            .map(paging::pageAt)
            .distinct()
    } else null
    val videoCovers = remember(session) { VideoCoverCache() }
    val pagePreviews = rememberReaderPagePreview(
        session, repository, videoCovers, continuous, looping = paging.looping,
        canDecode = {
            active && controls && (initialImageLoaded || videoBook) && session.pages.size > 1 && previewPage == null
        },
        idlePage = { currentPage.takeIf { active && visible && (initialImageLoaded || videoBook) &&
            !scrubbing && pendingPreviewPage == null && !listState.isScrollInProgress && !pagerState.isScrollInProgress } },
        pdfPages = pdfPages,
    ) {
        if (active && controls) pendingPreviewPage ?: sliderTarget else null
    }
    val continuousPreviews by remember(pagePreviews) {
        derivedStateOf { pagePreviews.value?.pages.orEmpty().associateBy { it.index } }
    }
    val showingRenderedPreview by remember(session, paging, continuous, pagePreviews) {
        derivedStateOf {
            targetPageReady && (!continuous || listState.layoutInfo.visibleItemsInfo.all {
                it.index in loadedItems || paging.pageAt(it.index) in continuousPreviews
            })
        }
    }
    val onPreviewReady: (Int) -> Unit = { index ->
        if (!continuous && !scrubbing && pendingPreviewPage == index) {
            pendingPreviewPage = null
            pageSliderVideoState = null
        } else if (previewPage == index) {
            renderedPreviewPage = index
        }
    }
    LaunchedEffect(continuous, scrubbing, pendingPreviewPage, showingRenderedPreview) {
        if (continuous && !scrubbing && pendingPreviewPage != null && showingRenderedPreview) {
            pendingPreviewPage = null
            renderedPreviewPage = null
        }
    }
    val videoPool = remember(session.book.cacheKey, videoBook) { if (videoBook) VideoPlaybackPool(context) else null }
    val videoPagerItems = remember(session, paging) { mutableMapOf<Int, @Composable (VideoPagerItem) -> Unit>() }
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
            if (scrubbing) null
            else if (pagerReading) paging.pageAt(pagerState.settledPage)
            else if (!paging.looping && !listState.canScrollForward && listState.canScrollBackward) paging.pageCount - 1
            else paging.pageAt(listState.firstVisibleItemIndex)
        }.distinctUntilChanged().filterNotNull().collect { page -> currentPage = page; onProgress(page)
            DiagnosticLog.log("Reader", "停靠到第 ${page + 1}/${session.pages.size} 项")
        }
    }
    LaunchedEffect(videoBook, pagerState.settledPage, active) {
        if (!videoBook || !active) return@LaunchedEffect
        videoPool?.snapshot()?.forEach { (item, state) -> if (item != pagerState.settledPage) state.pause() }
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
    suspend fun scrollToReaderPage(page: Int, animate: Boolean, videoItem: Int? = null) {
        if (pagerReading) {
            val item = videoItem ?: paging.nearestItem(page, pagerState.currentPage)
            if (animate) {
                pagerState.animateScrollToPage(item, animationSpec = spring(stiffness = pagingSpeed.stiffness(Spring.StiffnessMedium)))
            } else {
                pagerState.scrollToPage(item)
            }
        } else {
            listState.scrollToItem(paging.nearestItem(page, listState.firstVisibleItemIndex))
        }
    }
    LaunchedEffect(session, paging, pagerReading, controls, active) {
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
    fun jump(target: Int, volumeItem: Int? = null) {
        val page = paging.destination(target)
        pendingPreviewPage = null
        pageSliderVideoState = null
        jumpJob?.cancel()
        pendingVolumeItem = volumeItem
        if (videoBook) {
            DiagnosticLog.log("Reader", "跳转到第 ${page + 1} 项")
            // 视频统一交给 Pager 平滑过渡：跟手拖动、动画期间当前视频继续播放，停靠后切换播放器。
        } else {
            currentPage = page
            onProgress(page)
        }
        jumpJob = scope.launch { scrollToReaderPage(page, animate = videoBook, videoItem = volumeItem) }
    }
    val volumePage by rememberUpdatedState<(Int) -> Unit> turn@{ direction ->
        if (scrubbing) return@turn
        if (videoBook) {
            val origin = pendingVolumeItem?.takeIf { jumpJob?.isActive == true } ?: pagerState.settledPage
            val item = origin + direction
            if (item in 0 until paging.itemCount) jump(paging.pageAt(item), volumeItem = item)
        } else if (paging.looping || currentPage + direction in session.pages.indices) {
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
    val currentPageIsVideo = session.pages[currentPage].isVideo
    val toolbarVideoState = if (scrubbing) pageSliderVideoState
        else if (currentPageIsVideo && paging.pageAt(pagerState.settledPage) == currentPage) {
            videoPool?.get(pagerState.settledPage) ?: pageSliderVideoState
        } else null
    val onVideoEnded: (VideoPlaybackState) -> Unit = onEnded@{ state ->
        if (scrubbing) return@onEnded
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
                val pageContent: VideoPagerItem = { item ->
                    VideoPagerPage(item, session, paging, pagerState, repository, videoPool!!, videoCovers,
                        active, allowPlayback = visible && !scrubbing, settings, foreground, background,
                        videoPosition, onVideoProgress, zoomSequence, zoomAction, zoomItem,
                        previewPage, normalPageLoading, onPreviewReady,
                        onEntryReady = if (visible) null else onVideoEntryReady,
                        onTap = { controls = !controls }, onEnded = onVideoEnded)
                }
                val itemContent: VideoPagerItem = { item ->
                    val content = videoPagerItems.getOrPut(item) {
                        movableContentOf<VideoPagerItem> { page ->
                            DisposableEffect(item) { onDispose { videoPagerItems.remove(item) } }
                            page(item)
                        }
                    }
                    content(pageContent)
                }
                if (verticalReading) {
                    VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1,
                        flingBehavior = flingBehavior,
                        key = { "${session.book.cacheKey}-$it" }) { item -> itemContent(item) }
                } else {
                    HorizontalPager(state = pagerState, reverseLayout = settings.rightToLeft, modifier = Modifier.fillMaxSize(),
                        flingBehavior = flingBehavior,
                        beyondViewportPageCount = 1, key = { "${session.book.cacheKey}-$it" }) { item -> itemContent(item) }
                }
            } else if (verticalReading) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(ReaderPageSpacing)) {
                    items(paging.itemCount, key = { "${session.book.cacheKey}-$it" }) { item ->
                        val index = paging.pageAt(item)
                        val visible by remember(item) { derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.index == item } } }
                        val current by remember(item) { derivedStateOf { item == listState.firstVisibleItemIndex } }
                        DisposableEffect(loadedItems, item) {
                            onDispose {
                                loadedItems.remove(item)
                                if (zoomItem == item) zoomItem = -1
                            }
                        }
                        ReaderPage(session, index, repository, vertical = true, active = active && visible && normalPageLoading,
                            doubleTapZoomEnabled = settings.doubleTapZoom,
                            foreground = foreground, zoomSequence = if (item == zoomItem) zoomSequence else 0,
                            zoomAction = zoomAction, initialRatio = ratios[index], onRatio = { ratios[index] = it },
                            loadEnabled = priorityPage == index || normalPageLoading && (initialImageLoaded || current),
                            onLoaded = { onInitialImageLoaded(); loadedItems[item] = Unit }, pdfPages = pdfPages,
                            waitingForPreview = previewPage == index && visible && (current || !listState.canScrollForward),
                            onPreviewReady = onPreviewReady,
                            preview = continuousPreviews[index],
                            onTap = { controls = !controls })
                    }
                }
            } else {
                HorizontalPager(state = pagerState, reverseLayout = settings.rightToLeft, modifier = Modifier.fillMaxSize(),
                    flingBehavior = flingBehavior,
                    beyondViewportPageCount = 1, key = { "${session.book.cacheKey}-$it" }) { item ->
                    val index = paging.pageAt(item)
                    ReaderPage(session, index, repository, vertical = false,
                        active = active && normalPageLoading && pagerState.currentPage == item,
                        doubleTapZoomEnabled = settings.doubleTapZoom,
                        foreground = foreground, zoomSequence = if (item == zoomItem) zoomSequence else 0,
                        zoomAction = zoomAction, loadEnabled = priorityPage == index ||
                            normalPageLoading && (initialImageLoaded || pagerState.currentPage == item),
                        onLoaded = onInitialImageLoaded, pdfPages = pdfPages,
                        waitingForPreview = previewPage == index && pagerState.currentPage == item,
                        onPreviewReady = onPreviewReady,
                        onTap = { controls = !controls })
                }
            }
            if (controls && (scrubbing || pendingPreviewPage != null) && !showingRenderedPreview) {
                ReaderPreviewOverlay(pagePreviews, session, pagerReading, background)
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
            val fadeSpec = spring<Float>(stiffness = toolbarSpeed.stiffness(Spring.StiffnessMediumLow))
            AnimatedVisibility(visible = controls, enter = fadeIn(fadeSpec), exit = fadeOut(fadeSpec),
                modifier = Modifier.align(Alignment.TopCenter).blockVideoGestures()) {
                TopAppBar(title = {
                    Text(session.book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                }, navigationIcon = {
                    IconButton(onClick = { onProgress(currentPage); onBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back_to_library)) }
                }, actions = { IconButton(onClick = { preferences = true }) { Icon(Icons.Outlined.Settings, stringResource(R.string.reader_settings)) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .97f)))
            }
            val slideSpec = spring(
                stiffness = toolbarSpeed.stiffness(Spring.StiffnessMediumLow), visibilityThreshold = IntOffset(1, 1),
            )
            AnimatedVisibility(visible = controls, modifier = Modifier.align(Alignment.BottomCenter).blockVideoGestures(),
                enter = slideInVertically(slideSpec) { it } + fadeIn(fadeSpec),
                exit = slideOutVertically(slideSpec) { it } + fadeOut(fadeSpec)) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .98f), tonalElevation = 3.dp,
                    shadowElevation = 8.dp, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Box(Modifier.align(Alignment.CenterHorizontally).size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant))
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
                                        if (!continuous) renderedPreviewPage = null
                                    }
                                }
                            },
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { jump(currentPage - 1) }, enabled = !scrubbing && (settings.loopMode || currentPage > 0)) { Icon(Icons.AutoMirrored.Outlined.NavigateBefore, stringResource(R.string.previous_page)) }
                            IconButton(onClick = { zoom(-1) }, enabled = !scrubbing) { Icon(Icons.Outlined.ZoomOut, stringResource(if (currentPageIsVideo) R.string.zoom_out_video else R.string.zoom_out_image)) }
                            TextButton(onClick = { zoom(0) }, enabled = !scrubbing) { Text(stringResource(R.string.fit_screen)) }
                            IconButton(onClick = { zoom(1) }, enabled = !scrubbing) { Icon(Icons.Outlined.ZoomIn, stringResource(if (currentPageIsVideo) R.string.zoom_in_video else R.string.zoom_in_image)) }
                            IconButton(onClick = { jump(currentPage + 1) }, enabled = !scrubbing && (settings.loopMode || currentPage < session.pages.lastIndex)) { Icon(Icons.AutoMirrored.Outlined.NavigateNext, stringResource(R.string.next_page)) }
                        }
                    }
                }
            }
        }
    }
    if (preferences) WithAnimationSpeed(settings.animationSpeedFor(AnimationEffect.ReaderQuickSettings), modifier = Modifier.size(0.dp)) {
        ModalBottomSheet(onDismissRequest = { preferences = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text(stringResource(R.string.reader_settings), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(24.dp))
                ReaderPreferences(settings, onSettings)
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
    active: Boolean, allowPlayback: Boolean, settings: ReaderSettings, foreground: Color, background: Color,
    videoPosition: (Int) -> Long, onVideoProgress: (Int, Long) -> Unit,
    zoomSequence: Int, zoomAction: Int, zoomItem: Int, previewPage: Int?, normalPageLoading: Boolean,
    onPreviewReady: (Int) -> Unit,
    onEntryReady: (() -> Unit)?,
    onTap: () -> Unit, onEnded: (VideoPlaybackState) -> Unit,
) {
    val index = paging.pageAt(item)
    val page = session.pages[index]
    val settled = pagerState.settledPage == item
    val neighbor = abs(item - pagerState.settledPage) <= 1
    // 缩放指令只应用到当前页，避免相邻预载页被一起缩放。
    val sequence = if (item == zoomItem) zoomSequence else 0
    if (page.isVideo) {
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
            sequence, zoomAction, onTap = onTap, onEnded = onEnded)
    } else {
        ReaderPage(session, index, repository, vertical = false, active = active && settled && normalPageLoading,
            doubleTapZoomEnabled = settings.doubleTapZoom,
            foreground = foreground, zoomSequence = sequence,
            zoomAction = zoomAction, loadEnabled = normalPageLoading || previewPage == index,
            waitingForPreview = previewPage == index && settled,
            onPreviewReady = onPreviewReady,
            onTap = onTap, clipPage = true)
    }
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
    previews: State<ReaderPreviewViewport?>, session: BookSession, pagerReading: Boolean, background: Color,
) {
    val frame = previews.value ?: return
    val pages = frame.pages
    if (pagerReading) {
        val preview = pages.first()
        ReaderPreviewImage(preview, Modifier.fillMaxSize().background(background),
            video = session.pages[preview.index].isVideo)
    } else {
        Column(Modifier.fillMaxSize().background(background).clipToBounds()
            .wrapContentHeight(if (frame.alignBottom) Alignment.Bottom else Alignment.Top, unbounded = true),
            verticalArrangement = Arrangement.spacedBy(ReaderPageSpacing)) {
            for (preview in pages) {
                ReaderPreviewImage(preview, Modifier.fillMaxWidth().aspectRatio(preview.ratio),
                    contentScale = ContentScale.FillWidth)
            }
        }
    }
}

private val imageDecodeDispatcher = Dispatchers.IO.limitedParallelism(2)

@Composable
private fun ReaderPreviewImage(preview: ReaderPagePreview, modifier: Modifier,
    video: Boolean = false, contentScale: ContentScale = ContentScale.Fit) {
    val bitmap = remember(preview.bitmap) { preview.bitmap.asImageBitmap() }
    Image(bitmap, stringResource(
        if (video) R.string.video_accessibility_without_zoom else R.string.image_accessibility_without_zoom,
        preview.index + 1,
    ), modifier, contentScale = contentScale)
}

@Composable
private fun ReaderPage(session: BookSession, index: Int, repository: BookRepository, vertical: Boolean, active: Boolean,
    doubleTapZoomEnabled: Boolean,
    foreground: Color, zoomSequence: Int, zoomAction: Int, initialRatio: Float? = null,
    onRatio: (Float) -> Unit = {}, loadEnabled: Boolean = true, onLoaded: () -> Unit = {},
    pdfPages: SnapshotStateMap<Int, Result<Bitmap>>? = null,
    waitingForPreview: Boolean, onPreviewReady: (Int) -> Unit, preview: ReaderPagePreview? = null, onTap: () -> Unit,
    clipPage: Boolean = vertical) {
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
        ReaderPageContent(index, image, pdfPages[index]?.isFailure == true, vertical, active,
            doubleTapZoomEnabled, foreground, zoomSequence, zoomAction, initialRatio, onRatio, onTap,
            onRetry = { pdfPages.remove(index) }, preview = preview, clipPage = clipPage)
        return
    }
    val viewportWidth = LocalWindowInfo.current.containerSize.width
    val width = (viewportWidth * 2).coerceIn(480, 3200)
    val animationWidth = viewportWidth.coerceIn(480, 3200)
    var retry by remember { mutableIntStateOf(0) }
    var failure by remember(session, index, width, animationWidth, retry) { mutableStateOf(false) }
    val drawable by key(session, index, width, animationWidth, retry) {
        produceState<Drawable?>(null, loadEnabled) {
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
    val loaded = drawable != null || failure
    LaunchedEffect(loaded, waitingForPreview) {
        if (loaded) {
            onLoaded()
            if (waitingForPreview) onPreviewReady(index)
        }
    }
    ReaderPageContent(index, drawable, failure, vertical, active, doubleTapZoomEnabled, foreground, zoomSequence, zoomAction,
        initialRatio, onRatio, onTap, onRetry = { retry++ }, preview = preview, clipPage = clipPage)
}

@Composable
private fun ReaderPageContent(index: Int, image: Drawable?, failure: Boolean, vertical: Boolean, active: Boolean,
    doubleTapZoomEnabled: Boolean, foreground: Color, zoomSequence: Int, zoomAction: Int, initialRatio: Float?, onRatio: (Float) -> Unit,
    onTap: () -> Unit, onRetry: () -> Unit, preview: ReaderPagePreview?, clipPage: Boolean) {
    val speed = animationSpeed(AnimationEffect.ReaderImageZoom)
    val imageDescription = stringResource(
        if (doubleTapZoomEnabled) R.string.image_accessibility else R.string.image_accessibility_without_zoom,
        index + 1,
    )
    val ratio = image?.let { it.intrinsicWidth.toFloat() / it.intrinsicHeight.coerceAtLeast(1) } ?: initialRatio ?: preview?.ratio ?: .70f
    LaunchedEffect(image) { if (image != null) onRatio(ratio) }
    val layout = if (vertical) Modifier.fillMaxWidth().aspectRatio(ratio.coerceAtLeast(.03f)) else Modifier.fillMaxSize()
    Box(if (clipPage) layout.clipToBounds() else layout, contentAlignment = Alignment.Center) {
        if (image != null) {
            AndroidView(factory = { context -> ZoomImageView(context) }, modifier = Modifier.fillMaxSize(),
                onRelease = ZoomImageView::clearImage,
                update = { view ->
                    view.onTap = onTap
                    view.doubleTapZoomEnabled = doubleTapZoomEnabled
                    view.zoomAnimationSpeed = speed
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
        } else if (preview != null) {
            ReaderPreviewImage(preview, Modifier.fillMaxSize(), contentScale = ContentScale.FillWidth)
        } else AppCircularProgressIndicator(Modifier.size(28.dp), color = foreground)
    }
}
