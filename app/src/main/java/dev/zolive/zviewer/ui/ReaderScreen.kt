@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.zolive.zviewer.ui

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.WindowManager
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable as AnimationValue
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zolive.zviewer.data.BookRepository
import dev.zolive.zviewer.data.BookSession
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
import kotlin.math.roundToInt

private data class VideoTransition(
    val fromPage: Int,
    val fromState: VideoPlaybackState,
    val toPage: Int,
    val toState: VideoPlaybackState,
    val direction: Int,
)

@Composable
fun ReaderScreen(session: BookSession, initialPage: Int, settings: ReaderSettings, repository: BookRepository,
    onSettings: (ReaderSettings) -> Unit, onProgress: (Int) -> Unit,
    videoPosition: (Int) -> Long = { 0L }, onVideoProgress: (Int, Long) -> Unit = { _, _ -> }, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var currentPage by rememberSaveable(session.book.id) { mutableIntStateOf(initialPage.coerceIn(session.pages.indices)) }
    var controls by rememberSaveable(session.book.id) { mutableStateOf(false) }
    var preferences by remember { mutableStateOf(false) }
    var jumpDialog by remember { mutableStateOf(false) }
    var zoomSequence by remember { mutableIntStateOf(0) }
    var zoomAction by remember { mutableIntStateOf(0) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    val paging = remember(session.pages.size, settings.loopMode) { ReaderPaging(session.pages.size, settings.loopMode) }
    val listState = rememberLazyListState(paging.anchor(currentPage))
    val pagerState = rememberPagerState(initialPage = paging.anchor(currentPage)) { paging.itemCount }
    var jumpJob by remember { mutableStateOf<Job?>(null) }
    val ratios = remember(session.book.cacheKey) { mutableStateMapOf<Int, Float>() }
    val background = if (settings.readerDark) Color(0xFF101110) else Color(0xFFFAF9F6)
    val foreground = if (settings.readerDark) Color(0xFFE6E5E1) else Color(0xFF262724)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val activity = LocalActivity.current ?: return
    val context = LocalContext.current
    val videoBook = session.pages.any(PageSource::isVideo)
    val verticalReading = settings.vertical
    val pageUnit = if (videoBook) "个视频" else "页"
    var currentVideoState by remember(session.book.cacheKey, videoBook) {
        mutableStateOf(if (videoBook) createVideoPlaybackState(context) else null)
    }
    var videoTransition by remember(session.book.cacheKey, videoBook) { mutableStateOf<VideoTransition?>(null) }
    val transitionProgress = remember(videoTransition) { AnimationValue(0f) }
    val latestVideoState = rememberUpdatedState(currentVideoState)
    val latestVideoTransition = rememberUpdatedState(videoTransition)

    DisposableEffect(session.book.cacheKey, videoBook) {
        onDispose {
            latestVideoState.value?.release()
            latestVideoTransition.value?.let { transition ->
                transition.fromState.release()
                transition.toState.release()
            }
        }
    }

    PredictiveBackHandler(enabled = !preferences && !jumpDialog) { events ->
        try {
            events.collect { backProgress = it.progress }
            onProgress(currentPage)
            onBack()
        } catch (error: CancellationException) { backProgress = 0f }
    }

    DisposableEffect(settings.keepScreenOn) {
        if (settings.keepScreenOn) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val lightSystemBars = if (controls) MaterialTheme.colorScheme.surface.luminance() > .5f else !settings.readerDark
    DisposableEffect(controls, lightSystemBars) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = lightSystemBars
        controller.isAppearanceLightNavigationBars = lightSystemBars
        if (controls) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }

    LaunchedEffect(verticalReading, paging, videoBook) {
        if (videoBook) return@LaunchedEffect
        jumpJob?.cancel()
        val item = paging.anchor(currentPage)
        if (verticalReading) listState.scrollToItem(item) else pagerState.scrollToPage(item)
        snapshotFlow {
            if (verticalReading) {
                if (!paging.looping && !listState.canScrollForward && listState.canScrollBackward) paging.pageCount - 1
                else paging.pageAt(listState.firstVisibleItemIndex)
            } else paging.pageAt(pagerState.settledPage)
        }.distinctUntilChanged().collect { page -> currentPage = page; onProgress(page) }
    }
    LaunchedEffect(videoTransition) {
        val transition = videoTransition ?: return@LaunchedEffect
        snapshotFlow {
            transition.toState.firstFrameReady && transition.toState.surfaceReady || transition.toState.failed
        }.first { it }
        transitionProgress.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
        if (videoTransition === transition) {
            transition.fromState.release()
            currentVideoState = transition.toState
            currentPage = transition.toPage
            videoTransition = null
            onProgress(transition.toPage)
        }
    }
    val jump: (Int) -> Unit = { target ->
        val page = paging.destination(target)
        if (videoBook) {
            val current = currentVideoState
            if (current != null && videoTransition == null && page != currentPage) {
                current.pause()
                videoTransition = VideoTransition(
                    fromPage = currentPage,
                    fromState = current,
                    toPage = page,
                    toState = createVideoPlaybackState(context),
                    direction = if (target > currentPage) 1 else -1,
                )
            }
        } else {
            currentPage = page
            onProgress(page)
            jumpJob?.cancel()
            jumpJob = scope.launch {
                if (verticalReading) listState.scrollToItem(paging.nearestItem(page, listState.firstVisibleItemIndex))
                else pagerState.scrollToPage(paging.nearestItem(page, pagerState.currentPage))
            }
        }
    }
    val videoState = if (videoBook) currentVideoState else null

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationX = size.width * backProgress * .24f
            scaleX = 1f - backProgress * .08f
            scaleY = 1f - backProgress * .08f
            alpha = 1f - backProgress * .25f
        }.background(background)) {
            if (videoState != null) {
                val transition = videoTransition
                val slots = if (transition == null) listOf(currentPage to videoState)
                    else listOf(transition.fromPage to transition.fromState, transition.toPage to transition.toState)
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    for ((page, state) in slots) {
                        key(state) {
                            VideoPlaybackSlot(state, page, session, active && transition == null, transition == null, settings,
                                videoPosition, onVideoProgress, foreground, zoomSequence, zoomAction,
                                modifier = Modifier.graphicsLayer {
                                    val distance = if (verticalReading) size.height else size.width
                                    val incomingSign = (transition?.direction ?: 0) * if (!verticalReading && settings.rightToLeft) -1 else 1
                                    val fraction = if (state === transition?.fromState) -transitionProgress.value
                                        else 1f - transitionProgress.value
                                    val offset = incomingSign * distance * fraction
                                    translationX = if (verticalReading) 0f else offset
                                    translationY = if (verticalReading) offset else 0f
                                }, onTap = { controls = !controls }, onPage = { jump(currentPage + it) },
                                onEnded = {
                                    if (!settings.videoLoopSingle && (settings.loopMode || currentPage < session.pages.lastIndex)) {
                                        jump(currentPage + 1)
                                    }
                                })
                        }
                    }
                }
            } else if (verticalReading) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(paging.itemCount, key = { "${session.book.cacheKey}-$it" }) { item ->
                        val index = paging.pageAt(item)
                        val visible by remember(item) { derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.index == item } } }
                        val current by remember(item) { derivedStateOf { item == listState.firstVisibleItemIndex } }
                        ReaderPage(session, index, repository, vertical = true, active = active && visible,
                            foreground = foreground, zoomSequence = if (current) zoomSequence else 0,
                            zoomAction = zoomAction, initialRatio = ratios[index], onRatio = { ratios[index] = it },
                            onTap = { controls = !controls })
                    }
                }
            } else {
                HorizontalPager(state = pagerState, reverseLayout = settings.rightToLeft, modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1, key = { "${session.book.cacheKey}-$it" }) { item ->
                    val index = paging.pageAt(item)
                    ReaderPage(session, index, repository, vertical = false, active = active && pagerState.currentPage == item,
                        foreground = foreground, zoomSequence = if (item == pagerState.currentPage) zoomSequence else 0,
                        zoomAction = zoomAction, onTap = { controls = !controls })
                }
            }
            if (!controls) {
                Surface(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(12.dp),
                    color = background.copy(alpha = .72f), contentColor = foreground.copy(alpha = .65f), shape = RoundedCornerShape(12.dp)) {
                    Text("${currentPage + 1} / ${session.pages.size} $pageUnit", Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                TopAppBar(title = {
                    Column {
                        Text(session.book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(if (verticalReading) { if (videoBook) "垂直翻页" else "垂直连续" } else if (settings.rightToLeft) "水平翻页 · 从右向左" else "水平翻页",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }, navigationIcon = {
                    IconButton(onClick = { onProgress(currentPage); onBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回书库") }
                }, actions = { IconButton(onClick = { preferences = true }) { Icon(Icons.Outlined.Tune, "阅读设置") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .97f)))
            }
            AnimatedVisibility(visible = controls, modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .98f), tonalElevation = 3.dp,
                    shadowElevation = 8.dp, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Box(Modifier.align(Alignment.CenterHorizontally).size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant))
                        if (videoState != null) VideoProgressControls(videoState)
                        var slider by remember { mutableFloatStateOf(currentPage.toFloat()) }
                        var dragging by remember { mutableStateOf(false) }
                        LaunchedEffect(currentPage) { if (!dragging) slider = currentPage.toFloat() }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { jumpDialog = true }) { Text("第 ${if (dragging) slider.roundToInt() + 1 else currentPage + 1} / ${session.pages.size} $pageUnit") }
                            Spacer(Modifier.weight(1f))
                            Text("${((currentPage + 1f) / session.pages.size * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                        }
                        Slider(value = if (dragging) slider else currentPage.toFloat(),
                            onValueChange = { dragging = true; slider = it },
                            onValueChangeFinished = { jump(slider.roundToInt()); dragging = false },
                            valueRange = 0f..maxOf(1, session.pages.lastIndex).toFloat(), enabled = session.pages.size > 1,
                            modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { jump(currentPage - 1) }, enabled = settings.loopMode || currentPage > 0) { Icon(Icons.AutoMirrored.Outlined.NavigateBefore, "上一页") }
                            IconButton(onClick = { zoomAction = -1; zoomSequence++ }) { Icon(Icons.Outlined.ZoomOut, if (videoBook) "缩小视频" else "缩小图片") }
                            TextButton(onClick = { zoomAction = 0; zoomSequence++ }) { Text("适合屏幕") }
                            IconButton(onClick = { zoomAction = 1; zoomSequence++ }) { Icon(Icons.Outlined.ZoomIn, if (videoBook) "放大视频" else "放大图片") }
                            IconButton(onClick = { jump(currentPage + 1) }, enabled = settings.loopMode || currentPage < session.pages.lastIndex) { Icon(Icons.AutoMirrored.Outlined.NavigateNext, "下一页") }
                        }
                        Text(if (videoBook) "轻点画面收起 · 双击缩放，拖动播放进度条定位" else "轻点画面收起 · 双指缩放，双击缩放图片",
                            Modifier.align(Alignment.CenterHorizontally).padding(bottom = 2.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (preferences) ModalBottomSheet(onDismissRequest = { preferences = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("阅读设置", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(24.dp))
            ReaderPreferences(settings, onSettings)
            Spacer(Modifier.height(20.dp))
        }
    }
    if (jumpDialog) {
        var text by remember { mutableStateOf((currentPage + 1).toString()) }
        val page = text.toIntOrNull()
        AlertDialog(onDismissRequest = { jumpDialog = false }, title = { Text("跳转到指定$pageUnit") }, text = {
            OutlinedTextField(text, { text = it.filter(Char::isDigit).take(6) }, label = { Text("${if (videoBook) "视频序号" else "页码"}（1–${session.pages.size}）") },
                singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        }, confirmButton = { TextButton(enabled = page != null && page in 1..session.pages.size,
            onClick = { jump(page!! - 1); jumpDialog = false }) { Text("跳转") } },
            dismissButton = { TextButton(onClick = { jumpDialog = false }) { Text("取消") } })
    }
}

@Composable
private fun VideoPlaybackSlot(
    state: VideoPlaybackState, index: Int, session: BookSession, active: Boolean, allowPlayback: Boolean,
    settings: ReaderSettings, videoPosition: (Int) -> Long, onProgress: (Int, Long) -> Unit,
    foreground: Color, zoomSequence: Int, zoomAction: Int, modifier: Modifier = Modifier,
    onTap: () -> Unit, onPage: (Int) -> Unit, onEnded: () -> Unit,
) {
    rememberVideoPlayback(state, session, index, active, allowPlayback, settings, videoPosition, onProgress, onEnded)
    VideoPage(state, index, settings, foreground, zoomSequence, zoomAction, onTap, onPage, modifier)
}

@Composable
private fun ReaderPage(session: BookSession, index: Int, repository: BookRepository, vertical: Boolean, active: Boolean,
    foreground: Color, zoomSequence: Int, zoomAction: Int, initialRatio: Float? = null,
    onRatio: (Float) -> Unit = {}, onTap: () -> Unit) {
    val width = (LocalWindowInfo.current.containerSize.width * 2).coerceIn(480, 3200)
    var retry by remember { mutableIntStateOf(0) }
    var failure by remember(session.book.cacheKey, index) { mutableStateOf<String?>(null) }
    val drawable by produceState<Drawable?>(null, session.book.cacheKey, index, width, retry) {
        failure = null
        try {
            val file = repository.pageFile(session, index, width)
            value = withContext(Dispatchers.IO) { ImageLoader.load(file, width) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { failure = "这一页暂时无法解码。请确认图片完整，且设备支持此编码。" }
    }
    DisposableEffect(drawable) { onDispose { (drawable as? Animatable)?.stop() } }
    val ratio = drawable?.let { it.intrinsicWidth.toFloat() / it.intrinsicHeight.coerceAtLeast(1) } ?: initialRatio ?: .70f
    LaunchedEffect(drawable) { if (drawable != null) onRatio(ratio) }
    val layout = if (vertical) Modifier.fillMaxWidth().aspectRatio(ratio.coerceAtLeast(.03f)) else Modifier.fillMaxSize()
    Box(layout, contentAlignment = Alignment.Center) {
        val image = drawable
        if (image != null) {
            AndroidView(factory = { context -> ZoomImageView(context) }, modifier = Modifier.fillMaxSize(),
                onRelease = { view -> (view.drawable as? Animatable)?.stop(); view.setImageDrawable(null) },
                update = { view ->
                    view.onTap = onTap
                    view.bind(image, "第 ${index + 1} 页，轻点显示阅读控制，双击缩放", active)
                    if (zoomSequence > 0) view.command(zoomSequence, zoomAction)
                })
        } else if (failure != null) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.BrokenImage, null, tint = foreground)
                Text(failure!!, Modifier.padding(vertical = 16.dp), color = foreground)
                Row {
                    TextButton(onClick = { retry++ }) { Text("重试") }
                    TextButton(onClick = onTap) { Text("显示阅读控制") }
                }
            }
        } else CircularProgressIndicator(Modifier.size(28.dp), color = foreground)
    }
}
