@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.zolive.zviewer.ui

import android.graphics.drawable.Animatable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
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
import dev.zolive.zviewer.reader.ImageLoader
import dev.zolive.zviewer.reader.ZoomImageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(session: BookSession, initialPage: Int, settings: ReaderSettings, repository: BookRepository,
    onSettings: (ReaderSettings) -> Unit, onProgress: (Int) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var currentPage by rememberSaveable(session.book.id) { mutableIntStateOf(initialPage) }
    var controls by rememberSaveable(session.book.id) { mutableStateOf(false) }
    var preferences by remember { mutableStateOf(false) }
    var jumpDialog by remember { mutableStateOf(false) }
    var zoomSequence by remember { mutableIntStateOf(0) }
    var zoomAction by remember { mutableIntStateOf(0) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState(initialPage)
    val pagerState = rememberPagerState(initialPage = initialPage) { session.pages.size }
    val background = if (settings.readerDark) Color(0xFF101110) else Color(0xFFFAF9F6)
    val foreground = if (settings.readerDark) Color(0xFFE6E5E1) else Color(0xFF262724)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val activity = LocalActivity.current ?: return

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

    LaunchedEffect(settings.vertical) {
        if (settings.vertical) listState.scrollToItem(currentPage) else pagerState.scrollToPage(currentPage)
        snapshotFlow {
            if (settings.vertical) {
                if (!listState.canScrollForward && listState.canScrollBackward) session.pages.lastIndex
                else listState.firstVisibleItemIndex
            } else pagerState.settledPage
        }
            .distinctUntilChanged().collect { page -> currentPage = page; onProgress(page) }
    }
    val jump: (Int) -> Unit = { target ->
        val page = target.coerceIn(session.pages.indices)
        currentPage = page
        onProgress(page)
        scope.launch { if (settings.vertical) listState.scrollToItem(page) else pagerState.scrollToPage(page) }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationX = size.width * backProgress * .24f
            scaleX = 1f - backProgress * .08f
            scaleY = 1f - backProgress * .08f
            alpha = 1f - backProgress * .25f
        }.background(background)) {
            if (settings.vertical) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(session.pages, key = { index, _ -> "${session.book.cacheKey}-$index" }) { index, _ ->
                        val visible by remember(index) { derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.index == index } } }
                        ReaderPage(session, index, repository, vertical = true, active = active && visible,
                            foreground = foreground, zoomSequence = if (index == currentPage) zoomSequence else 0,
                            zoomAction = zoomAction, onTap = { controls = !controls })
                    }
                }
            } else {
                HorizontalPager(state = pagerState, reverseLayout = settings.rightToLeft, modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 0, key = { "${session.book.cacheKey}-$it" }) { index ->
                    ReaderPage(session, index, repository, vertical = false, active = active && pagerState.currentPage == index,
                        foreground = foreground, zoomSequence = if (index == currentPage) zoomSequence else 0,
                        zoomAction = zoomAction, onTap = { controls = !controls })
                }
            }
            if (!controls) {
                Surface(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(12.dp),
                    color = background.copy(alpha = .72f), contentColor = foreground.copy(alpha = .65f), shape = RoundedCornerShape(12.dp)) {
                    Text("${currentPage + 1} / ${session.pages.size}", Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                TopAppBar(title = {
                    Column {
                        Text(session.book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(if (settings.vertical) "垂直连续" else if (settings.rightToLeft) "水平翻页 · 从右向左" else "水平翻页",
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
                        var slider by remember { mutableFloatStateOf(currentPage.toFloat()) }
                        var dragging by remember { mutableStateOf(false) }
                        LaunchedEffect(currentPage) { if (!dragging) slider = currentPage.toFloat() }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { jumpDialog = true }) { Text("第 ${if (dragging) slider.roundToInt() + 1 else currentPage + 1} / ${session.pages.size} 页") }
                            Spacer(Modifier.weight(1f))
                            Text("${((currentPage + 1f) / session.pages.size * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                        }
                        Slider(value = if (dragging) slider else currentPage.toFloat(),
                            onValueChange = { dragging = true; slider = it },
                            onValueChangeFinished = { jump(slider.roundToInt()); dragging = false },
                            valueRange = 0f..maxOf(1, session.pages.lastIndex).toFloat(), enabled = session.pages.size > 1,
                            modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { jump(currentPage - 1) }, enabled = currentPage > 0) { Icon(Icons.AutoMirrored.Outlined.NavigateBefore, "上一页") }
                            IconButton(onClick = { zoomAction = -1; zoomSequence++ }) { Icon(Icons.Outlined.ZoomOut, "缩小图片") }
                            TextButton(onClick = { zoomAction = 0; zoomSequence++ }) { Text("适合屏幕") }
                            IconButton(onClick = { zoomAction = 1; zoomSequence++ }) { Icon(Icons.Outlined.ZoomIn, "放大图片") }
                            IconButton(onClick = { jump(currentPage + 1) }, enabled = currentPage < session.pages.lastIndex) { Icon(Icons.AutoMirrored.Outlined.NavigateNext, "下一页") }
                        }
                        Text("轻点画面收起 · 双指或双击缩放", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 2.dp),
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
        AlertDialog(onDismissRequest = { jumpDialog = false }, title = { Text("跳转到指定页") }, text = {
            OutlinedTextField(text, { text = it.filter(Char::isDigit).take(6) }, label = { Text("页码（1–${session.pages.size}）") },
                singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        }, confirmButton = { TextButton(enabled = page != null && page in 1..session.pages.size,
            onClick = { jump(page!! - 1); jumpDialog = false }) { Text("跳转") } },
            dismissButton = { TextButton(onClick = { jumpDialog = false }) { Text("取消") } })
    }
}

@Composable
private fun ReaderPage(session: BookSession, index: Int, repository: BookRepository, vertical: Boolean, active: Boolean,
    foreground: Color, zoomSequence: Int, zoomAction: Int, onTap: () -> Unit) {
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
    val ratio = drawable?.let { it.intrinsicWidth.toFloat() / it.intrinsicHeight.coerceAtLeast(1) } ?: .70f
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
