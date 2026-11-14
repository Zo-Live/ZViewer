@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.zolive.zviewer.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zolive.zviewer.LibraryState
import dev.zolive.zviewer.LibraryViewModel
import dev.zolive.zviewer.data.Book
import dev.zolive.zviewer.data.BookRepository
import dev.zolive.zviewer.data.NaturalOrder
import dev.zolive.zviewer.data.ReadingProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.core.view.WindowCompat

@Composable
fun ZViewerApp(model: LibraryViewModel, state: LibraryState) {
    val activity = LocalActivity.current
    val lightBars = MaterialTheme.colorScheme.surface.luminance() > .5f
    SideEffect {
        if (state.session == null && activity != null) {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var settingsDragging by remember { mutableStateOf(false) }
    var settingsDragProgress by remember { mutableFloatStateOf(if (settingsOpen) 1f else 0f) }
    var viewportWidth by remember { mutableFloatStateOf(1f) }
    val settingsProgress = remember { Animatable(if (settingsOpen) 1f else 0f) }
    val navigationScope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val swipeThreshold = with(density) { 96.dp.toPx() }
    fun navigateSettings(open: Boolean, startProgress: Float? = null) {
        settingsOpen = open
        navigationScope.launch {
            settingsProgress.stop()
            if (startProgress != null) settingsProgress.snapTo(startProgress.coerceIn(0f, 1f))
            settingsProgress.animateTo(
                if (open) 1f else 0f,
                animationSpec = tween(360, easing = FastOutSlowInEasing)
            )
        }
    }
    val libraryStateHolder = rememberSaveableStateHolder()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.selectLibrary(uri)
    }
    val chooseLibrary = { picker.launch(state.treeUri?.let(Uri::parse)) }
    Surface(Modifier.fillMaxSize()) {
        when {
            state.session != null -> ReaderScreen(state.session, state.initialPage, state.settings,
                model.repository, model::settings, model::saveProgress, model::videoPosition,
                { page, position -> model.saveVideoProgress(state.session.book.id, page, position) }, model::closeBook)
            else -> {
                val progress = (if (settingsDragging) settingsDragProgress else settingsProgress.value).coerceIn(0f, 1f)
                val navigationAnimating = !settingsDragging && settingsProgress.isRunning
                val showLibraryLayer = !settingsOpen || settingsDragging || progress < .999f
                val showSettingsLayer = settingsOpen || progress > .001f
                Box(Modifier.fillMaxSize().clipToBounds().background(MaterialTheme.colorScheme.background)
                    .onSizeChanged { viewportWidth = it.width.toFloat().coerceAtLeast(1f) }) {
                    Box(Modifier.fillMaxSize()
                        .graphicsLayer {
                            val scale = 1f - .2f * progress
                            translationX = -size.width * progress
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin.Center
                        }
                        .then(if (progress < .999f) Modifier.testTag("library-screen") else Modifier)
                        .then(if (settingsOpen) Modifier.clearAndSetSemantics {} else Modifier)) {
                        if (showLibraryLayer) {
                            libraryStateHolder.SaveableStateProvider("library") {
                                LibraryScreen(state, model, active = !settingsOpen,
                                    onSettings = { navigateSettings(true) }, onChooseLibrary = chooseLibrary)
                            }
                        }
                    }
                    if (showSettingsLayer) {
                        Box(Modifier.fillMaxSize()
                            .graphicsLayer { translationX = size.width * (1f - progress) }
                            .then(if (progress > .001f) Modifier.testTag("settings-screen") else Modifier)
                            .then(if (!settingsOpen) Modifier.clearAndSetSemantics {} else Modifier)) {
                            SettingsScreen(
                                state,
                                model,
                                onBack = { navigateSettings(false) },
                                onChooseLibrary = chooseLibrary,
                                onSwipeStart = {
                                    settingsDragging = true
                                    settingsDragProgress = settingsProgress.value
                                    navigationScope.launch { settingsProgress.stop() }
                                },
                                onSwipe = { dragAmount ->
                                    settingsDragProgress =
                                        (settingsDragProgress - dragAmount / viewportWidth).coerceIn(0f, 1f)
                                },
                                onSwipeEnd = {
                                    val draggedDistance = (1f - settingsDragProgress) * viewportWidth
                                    val shouldClose = draggedDistance >= swipeThreshold
                                    settingsDragging = false
                                    if (shouldClose) navigateSettings(false, settingsDragProgress)
                                    else navigateSettings(true, settingsDragProgress)
                                },
                                onSwipeCancel = {
                                    val currentProgress = settingsDragProgress
                                    settingsDragging = false
                                    navigateSettings(true, currentProgress)
                                }
                            )
                        }
                    }
                    if (navigationAnimating) {
                        Box(Modifier.matchParentSize().pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        })
                    }
                }
                BackHandler(enabled = settingsOpen) { navigateSettings(false) }
            }
        }
    }
    if (state.opening != null) {
        AlertDialog(onDismissRequest = model::cancelOpen, icon = { CircularProgressIndicator(Modifier.size(36.dp)) },
            title = { Text("准备阅读") }, text = { Text(state.opening) },
            confirmButton = { TextButton(onClick = model::cancelOpen) { Text("取消") } })
    }
    if (state.error != null) {
        AlertDialog(onDismissRequest = model::dismissError, icon = { Icon(Icons.Outlined.Info, null) },
            title = { Text("暂时无法读取") }, text = { Text(state.error) },
            confirmButton = { TextButton(onClick = model::dismissError) { Text("知道了") } },
            dismissButton = { TextButton(onClick = { model.dismissError(); chooseLibrary() }) { Text("选择书库") } })
    }
}

@Composable
private fun LibraryScreen(state: LibraryState, model: LibraryViewModel, active: Boolean,
    onSettings: () -> Unit, onChooseLibrary: () -> Unit) {
    val titles = listOf("书库", "收藏", "最近阅读")
    val pagerState = rememberPagerState { titles.size }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var managingFavorites by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = active && (managingFavorites || searching)) {
        if (managingFavorites) managingFavorites = false
        else { searching = false; query = "" }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                    Text("ZViewer", fontWeight = FontWeight.SemiBold, fontSize = 21.sp)
                }
            }, actions = {
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Outlined.Search, "搜索漫画") }
                IconButton(onClick = { managingFavorites = false; onSettings() }) { Icon(Icons.Outlined.Tune, "设置") }
            })
        },
        bottomBar = {
            NavigationBar {
                val icons = listOf(Icons.Outlined.CollectionsBookmark, Icons.Outlined.BookmarkBorder, Icons.Outlined.History)
                titles.forEachIndexed { index, title ->
                    NavigationBarItem(selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        icon = { Icon(icons[index], null) }, label = { Text(title) })
                }
            }
        },
        floatingActionButton = {
            if (state.treeUri != null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (managingFavorites) SmallFloatingActionButton(onClick = { managingFavorites = false },
                    shape = CircleShape, containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(Icons.Outlined.Close, "退出收藏管理")
                }
                SmallFloatingActionButton(onClick = { managingFavorites = false; onChooseLibrary() },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer) { Icon(Icons.Outlined.FolderOpen, "切换书库目录") }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (searching) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    placeholder = { Text("搜索书名") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { IconButton(onClick = { query = ""; searching = false }) { Icon(Icons.Outlined.Close, "关闭搜索") } },
                    shape = RoundedCornerShape(28.dp), singleLine = true)
            }
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().testTag("library-pager"),
                key = { it }) { tab ->
                var sortMenu by remember { mutableStateOf(false) }
                val books = remember(state.books, state.favorites, state.progress, tab, query, filter, sort) {
                    state.books.filter { book ->
                        val progress = state.progress[book.id] ?: ReadingProgress()
                        val matchesTab = when (tab) { 1 -> book.id in state.favorites; 2 -> progress.updated > 0; else -> true }
                        val matchesFilter = when (filter) {
                            1 -> progress.updated > 0 && progress.fraction < 1f
                            2 -> progress.updated == 0L
                            3 -> progress.total > 0 && progress.fraction >= 1f
                            else -> true
                        }
                        matchesTab && matchesFilter && book.title.contains(query, ignoreCase = true)
                    }.let { filtered ->
                        when {
                            tab == 2 || sort == 2 -> filtered.sortedByDescending { state.progress[it.id]?.updated ?: 0 }
                            sort == 1 -> filtered.sortedByDescending { it.modified }
                            else -> filtered.sortedWith { first, second -> NaturalOrder.compare(first.title, second.title) }
                        }
                    }
                }
                Column(Modifier.fillMaxSize()
                    .then(if (pagerState.currentPage != tab) Modifier.clearAndSetSemantics {} else Modifier)) {
                    if (state.treeUri == null) WelcomeScreen(onChooseLibrary)
                    else {
                        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(titles[tab], style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                                Text("${books.size} 本漫画", Modifier.padding(top = 5.dp),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { managingFavorites = false; model.refresh() }, enabled = !state.scanning) { Icon(Icons.Outlined.Refresh, "刷新书库") }
                            Box {
                                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "排序") }
                                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                    listOf("按名称", "最近修改", "最近阅读").forEachIndexed { index, label ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = { sort = index; sortMenu = false },
                                            trailingIcon = { if (sort == index) Icon(Icons.Outlined.Check, null) })
                                    }
                                }
                            }
                        }
                        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("全部", "在读", "未读", "已读").forEachIndexed { index, label ->
                                FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(label) })
                            }
                        }
                        if (books.isEmpty()) {
                            EmptyLibrary(state.scanning, state.books.isEmpty(), tab == 0, onChooseLibrary)
                        } else {
                            LazyVerticalGrid(columns = GridCells.Fixed(state.settings.gridColumns),
                                modifier = Modifier.fillMaxSize().testTag("library-grid-$tab"), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 88.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                                val recent = if (tab == 0 && query.isEmpty() && filter == 0) books
                                    .filter { (state.progress[it.id]?.updated ?: 0) > 0 && (state.progress[it.id]?.fraction ?: 0f) < 1f }
                                    .maxByOrNull { state.progress[it.id]?.updated ?: 0 } else null
                                if (recent != null) item(span = { GridItemSpan(maxLineSpan) }) {
                                    ContinueCard(recent, state.progress.getValue(recent.id), onClick = { model.open(recent) })
                                }
                                items(books, key = { it.id }) { book ->
                                    BookCard(book, state.progress[book.id] ?: ReadingProgress(), book.id in state.favorites,
                                        model.repository, state.cacheRevision, state.settings.showFileType,
                                        showFavoriteIcon = state.settings.showFavoriteIcon || managingFavorites,
                                        onClick = { if (managingFavorites) model.toggleFavorite(book) else model.open(book) },
                                        onLongClick = { managingFavorites = true },
                                        onFavorite = { model.toggleFavorite(book) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeScreen(onChooseLibrary: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 30.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        val primary = MaterialTheme.colorScheme.primary
        val container = MaterialTheme.colorScheme.primaryContainer
        val secondary = MaterialTheme.colorScheme.secondaryContainer
        val onPrimary = MaterialTheme.colorScheme.onPrimary
        Canvas(Modifier.size(220.dp, 186.dp)) {
            drawRoundRect(secondary, Offset(size.width * .04f, size.height * .14f), Size(size.width * .92f, size.height * .82f), CornerRadius(52f))
            drawRoundRect(primary, Offset(size.width * .16f, size.height * .12f), Size(size.width * .24f, size.height * .70f), CornerRadius(12f))
            drawRoundRect(container, Offset(size.width * .44f, size.height * .04f), Size(size.width * .18f, size.height * .78f), CornerRadius(12f))
            drawRoundRect(primary.copy(alpha = .65f), Offset(size.width * .66f, size.height * .23f), Size(size.width * .18f, size.height * .59f), CornerRadius(12f))
            drawLine(onPrimary.copy(alpha = .7f), Offset(size.width * .21f, size.height * .29f), Offset(size.width * .35f, size.height * .29f), 4f)
            drawLine(onPrimary.copy(alpha = .7f), Offset(size.width * .21f, size.height * .35f), Offset(size.width * .35f, size.height * .35f), 4f)
            drawCircle(primary, 6f, Offset(size.width * .53f, size.height * .66f))
        }
        Spacer(Modifier.height(30.dp))
        Button(onClick = onChooseLibrary, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Outlined.CreateNewFolder, null); Spacer(Modifier.width(10.dp)); Text("选择书库文件夹", fontSize = 16.sp)
        }
    }
}

@Composable
private fun EmptyLibrary(scanning: Boolean, directoryEmpty: Boolean, showChooseButton: Boolean, onChooseLibrary: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
        Text(when {
            scanning -> "正在整理你的书库…"
            directoryEmpty -> "此目录无漫画"
            else -> "没有符合条件的漫画"
        }, Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleMedium)
        if (!directoryEmpty) Text("尝试其他筛选条件", Modifier.fillMaxWidth().padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (directoryEmpty && !scanning && showChooseButton) {
            TextButton(onClick = onChooseLibrary, Modifier.padding(top = 12.dp)) { Text("选择其他目录") }
        }
    }
}

@Composable
private fun ContinueCard(book: Book, progress: ReadingProgress, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(30.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text("继续阅读", style = MaterialTheme.typography.labelMedium)
                Text(book.title, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("第 ${progress.page + 1} / ${progress.total} 页", Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Outlined.PlayArrow, "继续阅读")
        }
    }
}

@Composable
private fun BookCard(book: Book, progress: ReadingProgress, favorite: Boolean, repository: BookRepository,
    revision: Int, showFileType: Boolean, showFavoriteIcon: Boolean, onClick: () -> Unit,
    onLongClick: () -> Unit, onFavorite: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onClick,
        onLongClickLabel = "管理收藏", onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.69f).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            BookCover(book, repository, revision)
            if (showFileType) {
                Surface(Modifier.align(Alignment.TopStart).padding(7.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .92f),
                    shape = RoundedCornerShape(6.dp)) {
                    Text(book.formatLabel, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (showFavoriteIcon) IconButton(onClick = onFavorite, modifier = Modifier.align(Alignment.BottomEnd).size(48.dp)) {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .92f)) {
                    Icon(if (favorite) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                        if (favorite) "取消收藏 ${book.title}" else "收藏 ${book.title}", Modifier.padding(6.dp).size(18.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (progress.total > 0) LinearProgressIndicator(progress = { progress.fraction },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp))
        }
        Text(book.title, Modifier.padding(top = 9.dp, start = 1.dp, end = 1.dp), style = MaterialTheme.typography.titleSmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
        Text(when { progress.total == 0 -> "未开始"; progress.fraction >= 1f -> "已读完 · ${progress.total} 页"; else -> "${progress.page + 1} / ${progress.total} 页" },
            Modifier.padding(top = 3.dp, start = 1.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BookCover(book: Book, repository: BookRepository, revision: Int) {
    var failed by remember(book.cacheKey, revision) { mutableStateOf(false) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, book.cacheKey, revision) {
        try {
            value = repository.withBookCache(book) {
                android.graphics.BitmapFactory.decodeFile(repository.cover(book).absolutePath)
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { failed = true }
    }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), book.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    else Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(if (failed) Icons.Outlined.ImageNotSupported else Icons.AutoMirrored.Outlined.MenuBook, null,
            Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (failed) Text("轻点尝试阅读", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall)
    }
}
