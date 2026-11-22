@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.zolive.zviewer.ui

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size as PixelSize
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.runtime.saveable.listSaver
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.zolive.zviewer.LibraryState
import dev.zolive.zviewer.LibraryViewModel
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.Book
import dev.zolive.zviewer.data.BookRepository
import dev.zolive.zviewer.data.DiagnosticLog
import dev.zolive.zviewer.data.ReadingProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.core.view.WindowCompat
import androidx.core.view.HapticFeedbackConstantsCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private sealed class AppPage(val tag: String) {
    data object Library : AppPage("library-screen")
    data object Settings : AppPage("settings-screen")
    data class Section(val section: SettingsSection) : AppPage(section.tag)
    data object Logs : AppPage("log-list-screen")
    data class LogDetail(val file: DiagnosticLog.LogFile) : AppPage("log-detail-screen")

    fun parent(): AppPage = when (this) {
        Library, Settings -> Library
        is Section -> Settings
        Logs -> Section(SettingsSection.Diagnostics)
        is LogDetail -> Logs
    }

    companion object {
        val saver = listSaver<AppPage, Any>(
            save = { page ->
                if (page is LogDetail) listOf(page.tag, page.file.uri, page.file.name, page.file.size)
                else listOf(page.tag)
            },
            restore = {
                when (it[0]) {
                    Settings.tag -> Settings
                    Logs.tag -> Logs
                    "log-detail-screen" -> LogDetail(DiagnosticLog.LogFile(it[1] as Uri, it[2] as String, it[3] as Long))
                    else -> SettingsSection.entries.firstOrNull { section -> section.tag == it[0] }
                        ?.let { section -> Section(section) } ?: Library
                }
            },
        )
    }
}

private class PageTransition(val child: AppPage, initialProgress: Float) {
    val parent = child.parent()
    val progress = Animatable(initialProgress)
}

@Composable
private fun Modifier.pageBackSwipe(
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
): Modifier {
    val start by rememberUpdatedState(onStart)
    val dragBy by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    val cancel by rememberUpdatedState(onCancel)
    return pointerInput(Unit) {
        val touchSlop = viewConfiguration.touchSlop
        val startThreshold = maxOf(touchSlop, 16.dp.toPx())
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var displacement = Offset.Zero
            while (true) {
                val change = awaitPointerEvent().changes.singleOrNull() ?: return@awaitEachGesture
                if (change.id != down.id || !change.pressed || change.isConsumed) return@awaitEachGesture

                displacement += change.positionChange()
                val verticalDistance = abs(displacement.y)
                val mostlyRightward = displacement.x >= verticalDistance * 1.5f
                // 锁定已经开始的纵向或向左手势，直到松手。
                if (displacement.x <= -touchSlop || (!mostlyRightward && verticalDistance >= touchSlop)) {
                    return@awaitEachGesture
                }
                if (!mostlyRightward || displacement.x < startThreshold) continue

                change.consume()
                start()
                dragBy(displacement.x - startThreshold)
                val completed = drag(down.id) { movement ->
                    dragBy(movement.positionChange().x)
                    movement.consume()
                }
                if (completed) end() else cancel()
                break
            }
        }
    }
}

@Composable
fun ZViewerApp(model: LibraryViewModel, state: LibraryState) {
    val activity = LocalActivity.current
    val speed = state.settings.animationSpeed
    val session = state.session
    val waitingForVideo = session != null && state.waitingForVideoEntry
    LaunchedEffect(session, waitingForVideo) {
        if (session != null && waitingForVideo) {
            delay(3_000)
            model.finishVideoEntry(session)
        }
    }
    val openingRequest = state.openingRequest
    var showOpening by remember(openingRequest) { mutableStateOf(false) }
    LaunchedEffect(openingRequest) {
        if (openingRequest != null) {
            delay(250)
            showOpening = true
        }
    }
    val lightBars = MaterialTheme.colorScheme.surface.luminance() > .5f
    SideEffect {
        if ((session == null || waitingForVideo) && activity != null) {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }
    var page by rememberSaveable(stateSaver = AppPage.saver) { mutableStateOf<AppPage>(AppPage.Library) }
    var transition by remember { mutableStateOf<PageTransition?>(null) }
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    var viewportWidth by remember { mutableFloatStateOf(1f) }
    val navigationScope = rememberCoroutineScope()
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    val pageStates = rememberSaveableStateHolder()
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 96.dp.toPx() }
    fun finishTransition(child: AppPage, showChild: Boolean, startProgress: Float) {
        navigationJob?.cancel()
        val motion = PageTransition(child, startProgress)
        val target = if (showChild) 1f else 0f
        val duration = (abs(target - startProgress) * speed.duration(360)).roundToInt()
        page = if (showChild) motion.child else motion.parent
        transition = motion
        dragProgress = null
        navigationJob = navigationScope.launch {
            motion.progress.animateTo(
                target,
                animationSpec = tween(duration, easing = FastOutSlowInEasing),
            )
            transition = null
            if (!showChild && motion.child != AppPage.Logs) pageStates.removeState(motion.child.tag)
        }
    }
    fun goBack() {
        if (page == AppPage.Library) return
        val start = dragProgress ?: transition?.takeIf { it.child == page }?.progress?.value ?: 1f
        finishTransition(page, showChild = false, startProgress = start)
    }
    fun beginBackGesture(): Float {
        navigationJob?.cancel()
        val motion = transition?.takeIf { it.child == page } ?: PageTransition(page, 1f)
        val start = dragProgress ?: motion.progress.value
        transition = motion
        dragProgress = start
        return start
    }
    fun finishBackGesture(showChild: Boolean) {
        val start = dragProgress ?: return
        val motion = transition ?: return
        finishTransition(motion.child, showChild, start)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.selectLibrary(uri)
    }
    val chooseLibrary = { picker.launch(state.treeUri?.let(Uri::parse)) }
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            if (session != null) {
                key(session) {
                    ReaderScreen(session, state.initialPage, state.settings,
                        model.repository, model::settings, { index -> model.saveProgress(session, index) },
                        { index -> model.videoPosition(session, index) },
                        { index, position -> model.saveVideoProgress(session, index, position) },
                        onBack = model::closeBook,
                        visible = !waitingForVideo,
                        onVideoEntryReady = { model.finishVideoEntry(session) })
                }
            }
            if (session == null || waitingForVideo) {
                val motion = transition
                val layers = if (motion == null) listOf(page) else listOf(motion.parent, motion.child)
                Box(Modifier.fillMaxSize().zIndex(1f).clipToBounds().background(MaterialTheme.colorScheme.background)
                    .onSizeChanged { viewportWidth = it.width.toFloat().coerceAtLeast(1f) }) {
                    layers.forEach { layer ->
                        key(layer) {
                            val active = layer == page
                            val layerModifier = Modifier.fillMaxSize()
                                .graphicsLayer {
                                    val progress = dragProgress ?: motion?.progress?.value ?: 0f
                                    if (layer == motion?.parent) {
                                        translationX = -size.width * progress
                                        scaleX = 1f - .2f * progress
                                        scaleY = scaleX
                                        transformOrigin = TransformOrigin.Center
                                    } else if (layer == motion?.child) {
                                        translationX = size.width * (1f - progress)
                                    }
                                }
                                .testTag(layer.tag)
                                .then(if (!active) Modifier.clearAndSetSemantics {} else Modifier)
                                .then(if (active && layer != AppPage.Library) Modifier.pageBackSwipe(
                                    onStart = { beginBackGesture() },
                                    onDrag = { dragAmount ->
                                        dragProgress = ((dragProgress ?: 1f) - dragAmount / viewportWidth).coerceIn(0f, 1f)
                                    },
                                    onEnd = {
                                        val start = dragProgress ?: 1f
                                        val distance = (1f - start) * viewportWidth
                                        finishBackGesture(showChild = distance < swipeThreshold)
                                    },
                                    onCancel = { finishBackGesture(showChild = true) },
                                ) else Modifier)
                            Box(layerModifier) {
                                pageStates.SaveableStateProvider(layer.tag) {
                                    when (layer) {
                                        AppPage.Library -> LibraryScreen(state, model, active,
                                            onSettings = { finishTransition(AppPage.Settings, showChild = true, startProgress = 0f) },
                                            onChooseLibrary = chooseLibrary)
                                        AppPage.Settings -> SettingsScreen(::goBack) { section ->
                                            finishTransition(AppPage.Section(section), showChild = true, startProgress = 0f)
                                        }
                                        is AppPage.Section -> SettingsSectionScreen(
                                            layer.section, state, model, active, ::goBack, chooseLibrary,
                                            onViewLogs = { finishTransition(AppPage.Logs, showChild = true, startProgress = 0f) })
                                        AppPage.Logs -> LogListScreen(active, ::goBack) { file ->
                                            finishTransition(AppPage.LogDetail(file), showChild = true, startProgress = 0f)
                                        }
                                        is AppPage.LogDetail -> LogDetailScreen(layer.file, ::goBack)
                                    }
                                }
                            }
                        }
                    }
                    if (waitingForVideo || motion != null && dragProgress == null) {
                        Box(Modifier.matchParentSize().pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        })
                    }
                }
                PredictiveBackHandler(enabled = page != AppPage.Library && !waitingForVideo) { events ->
                    var startProgress: Float? = null
                    try {
                        events.collect { event ->
                            val start = startProgress ?: beginBackGesture().also { startProgress = it }
                            dragProgress = start * (1f - event.progress)
                        }
                        goBack()
                    } catch (cancelled: CancellationException) {
                        if (startProgress != null) finishBackGesture(showChild = true)
                        throw cancelled
                    }
                }
            }
            BackHandler(enabled = waitingForVideo, onBack = model::closeBook)
        }
        UpdateFeedback(state, model)
    }
    if (showOpening && state.opening != null) {
        AppAlertDialog(onDismissRequest = model::cancelOpen, icon = { CircularProgressIndicator(Modifier.size(36.dp)) },
            title = { Text(stringResource(R.string.opening_reader)) }, text = { Text(state.opening) },
            confirmButton = { TextButton(onClick = model::cancelOpen) { Text(stringResource(R.string.cancel)) } })
    }
    if (state.error != null) {
        AppAlertDialog(onDismissRequest = model::dismissError, icon = { Icon(Icons.Outlined.Info, null) },
            title = { Text(stringResource(R.string.read_error_title)) }, text = { Text(state.error) },
            confirmButton = { TextButton(onClick = model::dismissError) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { model.dismissError(); chooseLibrary() }) { Text(stringResource(R.string.choose_library)) } })
    }
}

@Composable
private fun LibraryScreen(state: LibraryState, model: LibraryViewModel, active: Boolean,
    onSettings: () -> Unit, onChooseLibrary: () -> Unit) {
    val speed = state.settings.animationSpeed
    val haptic = hapticFeedback()
    val titles = listOf(stringResource(R.string.library), stringResource(R.string.favorites), stringResource(R.string.recent_reads))
    val pagerState = rememberPagerState { titles.size }
    val gridStates = titles.map { rememberLazyGridState() }
    val currentGrid = gridStates[pagerState.currentPage]
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var managingFavorites by rememberSaveable { mutableStateOf(false) }
    fun changeFavoriteManagement(enabled: Boolean) {
        if (managingFavorites == enabled) return
        managingFavorites = enabled
        haptic(if (enabled) HapticFeedbackConstantsCompat.LONG_PRESS else HapticFeedbackConstantsCompat.TOGGLE_OFF)
    }
    fun toggleFavorite(book: Book) {
        val isFavorite = book.id in model.state.value.favorites
        model.toggleFavorite(book)
        haptic(if (isFavorite) HapticFeedbackConstantsCompat.TOGGLE_OFF else HapticFeedbackConstantsCompat.TOGGLE_ON)
    }
    val contentInset = 24.dp
    val actionInset = contentInset - 12.dp
    val columns = state.settings.gridColumns
    val gridPadding = 20.dp
    val gridSpacing = 12.dp
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val coverWidth = with(LocalDensity.current) {
        ((windowWidth - 2 * gridPadding.roundToPx() - (columns - 1) * gridSpacing.roundToPx()) / columns)
            .coerceAtLeast(1)
    }
    val coverSize = remember(coverWidth) { PixelSize(coverWidth, (coverWidth / BookCoverAspectRatio).roundToInt()) }
    BackHandler(enabled = active && (managingFavorites || searching)) {
        if (managingFavorites) changeFavoriteManagement(false)
        else { searching = false; query = "" }
    }
    Scaffold(
        topBar = {
            // 顶栏默认标题 / 操作区边距为 16 / 4 dp；图标在 48 dp 点击区内各留 12 dp。
            TopAppBar(modifier = Modifier.padding(start = contentInset - 16.dp, end = actionInset - 4.dp), title = {
                Text("ZViewer", fontWeight = FontWeight.Bold, fontSize = 21.sp)
            }, actions = {
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Outlined.Search, stringResource(R.string.search_comics)) }
                IconButton(onClick = { changeFavoriteManagement(false); onSettings() }) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings)) }
            })
        },
        bottomBar = {
            NavigationBar {
                val icons = listOf(Icons.Outlined.CollectionsBookmark, Icons.Outlined.BookmarkBorder, Icons.Outlined.History)
                titles.forEachIndexed { index, title ->
                    NavigationBarItem(selected = pagerState.currentPage == index,
                        onClick = { pagerState.requestScrollToPage(index) },
                        icon = { Icon(icons[index], null) }, label = { Text(title) })
                }
            }
        },
        floatingActionButton = {
            LibraryActions(currentGrid, managingFavorites,
                onExitManagement = { changeFavoriteManagement(false) })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            AnimatedVisibility(
                visible = searching,
                enter = expandVertically(tween(speed.duration(280), easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
                    slideInVertically(tween(speed.duration(280), easing = FastOutSlowInEasing)) { -it / 4 } +
                    fadeIn(tween(speed.duration(180))),
                exit = shrinkVertically(tween(speed.duration(220), easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) +
                    slideOutVertically(tween(speed.duration(220), easing = FastOutSlowInEasing)) { -it / 4 } +
                    fadeOut(tween(speed.duration(150)))
            ) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = contentInset, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.search_title)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { IconButton(onClick = { query = ""; searching = false }) { Icon(Icons.Outlined.Close, stringResource(R.string.close_search)) } },
                    shape = RoundedCornerShape(28.dp), singleLine = true)
            }
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().testTag("library-pager"),
                flingBehavior = pageFlingBehavior(pagerState, speed),
                key = { it }) { tab ->
                var sortMenu by remember { mutableStateOf(false) }
                val filterOptions = if (tab == 2) {
                    listOf(0 to stringResource(R.string.filter_all), 1 to stringResource(R.string.filter_in_progress), 3 to stringResource(R.string.filter_read))
                } else {
                    listOf(0 to stringResource(R.string.filter_all), 1 to stringResource(R.string.filter_in_progress), 2 to stringResource(R.string.filter_unread), 3 to stringResource(R.string.filter_read))
                }
                val selectedFilter = if (tab == 2 && filter == 2) 0 else filter
                val books = remember(state.books, state.favorites, state.progress, tab, query, selectedFilter, sort) {
                    state.books.filter { book ->
                        val progress = state.progress[book.id] ?: ReadingProgress()
                        val matchesTab = when (tab) { 1 -> book.id in state.favorites; 2 -> progress.updated > 0; else -> true }
                        val matchesFilter = when (selectedFilter) {
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
                            else -> filtered
                        }
                    }
                }
                val hasFavorites = state.books.any { it.id in state.favorites }
                val hasRecent = state.books.any { (state.progress[it.id]?.updated ?: 0L) > 0L }
                val pageModifier = Modifier.fillMaxSize()
                    .then(if (pagerState.currentPage != tab) Modifier.clearAndSetSemantics {} else Modifier)
                if (state.treeUri == null) {
                    Box(pageModifier) { WelcomeScreen(onChooseLibrary) }
                } else {
                    Layout(modifier = pageModifier, content = {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(start = contentInset, end = actionInset, top = 14.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(titles[tab], style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                                    Text(pluralStringResource(R.plurals.comic_count, books.size, books.size), Modifier.padding(top = 5.dp),
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { changeFavoriteManagement(false); model.refresh() }, enabled = !state.scanning) { Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh_library)) }
                                EndAlignedDropdownMenu(
                                    expanded = sortMenu,
                                    onDismissRequest = { sortMenu = false },
                                    anchor = {
                                        IconButton(onClick = { sortMenu = true }) {
                                            Icon(Icons.AutoMirrored.Outlined.Sort, stringResource(R.string.sort))
                                        }
                                    },
                                ) {
                                    listOf(stringResource(R.string.sort_by_name), stringResource(R.string.sort_modified), stringResource(R.string.recent_reads)).forEachIndexed { index, label ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = { sort = index; sortMenu = false },
                                            trailingIcon = { if (sort == index) Icon(Icons.Outlined.Check, null) })
                                    }
                                }
                            }
                            LibraryFilters(filterOptions, selectedFilter, contentInset) { filter = it }
                        }
                        LazyVerticalGrid(columns = GridCells.Fixed(columns),
                            state = gridStates[tab], modifier = Modifier.fillMaxSize().testTag("library-grid-$tab"),
                            contentPadding = PaddingValues(gridPadding, 12.dp, gridPadding, 88.dp),
                            horizontalArrangement = Arrangement.spacedBy(gridSpacing), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            val recent = if (tab == 0 && query.isEmpty() && selectedFilter == 0) books
                                .filter { (state.progress[it.id]?.updated ?: 0) > 0 && (state.progress[it.id]?.fraction ?: 0f) < 1f }
                                .maxByOrNull { state.progress[it.id]?.updated ?: 0 } else null
                            if (recent != null) item(span = { GridItemSpan(maxLineSpan) }) {
                                ContinueCard(recent, state.progress.getValue(recent.id), onClick = { model.open(recent) })
                            }
                            items(books, key = { it.id }) { book ->
                                BookCard(book, state.progress[book.id] ?: ReadingProgress(), book.id in state.favorites,
                                    model.repository, coverSize, state.coverRevision, state.settings.showFileType,
                                    showFavoriteIcon = state.settings.showFavoriteIcon || managingFavorites,
                                    managingFavorites = managingFavorites,
                                    onClick = {
                                        if (managingFavorites) toggleFavorite(book) else model.open(book)
                                    },
                                    onLongClick = { changeFavoriteManagement(!managingFavorites) },
                                    onFavorite = { toggleFavorite(book) })
                            }
                        }
                        if (books.isEmpty()) {
                            EmptyLibrary(
                                scanning = state.scanning,
                                directoryEmpty = state.books.isEmpty(),
                                onChooseLibrary = onChooseLibrary,
                                emptyTitle = when {
                                    tab == 1 && !hasFavorites -> stringResource(R.string.no_favorites)
                                    tab == 2 && !hasRecent -> stringResource(R.string.no_recent_reads)
                                    else -> null
                                },
                                emptyDescription = when {
                                    tab == 1 && !hasFavorites -> stringResource(R.string.favorite_management_hint)
                                    tab == 2 && !hasRecent -> stringResource(R.string.start_reading_hint)
                                    else -> null
                                }
                            )
                        }
                    }) { measurables, constraints ->
                        val looseHeight = constraints.copy(minHeight = 0)
                        val header = measurables[0].measure(looseHeight)
                        val grid = measurables[1].measure(looseHeight.copy(maxHeight = constraints.maxHeight - header.height))
                        // 书本图标以分页视口为基准，说明文字高度不影响位置。
                        val iconTop = (constraints.maxHeight - EmptyLibraryIconSize.roundToPx()) / 2
                        val emptyTop = maxOf(header.height, iconTop)
                        val empty = measurables.getOrNull(2)?.measure(looseHeight.copy(maxHeight = constraints.maxHeight - emptyTop))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            header.placeRelative(0, 0)
                            grid.placeRelative(0, header.height)
                            empty?.placeRelative(0, emptyTop)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryActions(gridState: LazyGridState, managingFavorites: Boolean, onExitManagement: () -> Unit) {
    val speed = LocalAnimationSpeed.current
    var returnAnimationDistance by remember(gridState) { mutableStateOf<Float?>(null) }
    val returningToTop = returnAnimationDistance != null
    val showScrollToTop = !returningToTop && gridState.canScrollBackward
    val visibility = remember { Animatable(0f) }
    val animationDistancePx = with(LocalDensity.current) { 112.dp.toPx() }
    // 用剩余滚动距离同步两个按钮，列表到顶时动画也结束。
    val buttonProgress by remember(gridState) {
        derivedStateOf {
            val distance = returnAnimationDistance
            if (distance == null || gridState.firstVisibleItemIndex != 0) {
                visibility.value
            } else {
                val fraction = (gridState.firstVisibleItemScrollOffset / distance).coerceIn(0f, 1f)
                visibility.value * FastOutSlowInEasing.transform(fraction)
            }
        }
    }
    LaunchedEffect(gridState, showScrollToTop, returningToTop) {
        if (!returningToTop) {
            visibility.animateTo(if (showScrollToTop) 1f else 0f, tween(speed.duration(240), easing = FastOutSlowInEasing))
            return@LaunchedEffect
        }
        coroutineScope {
            // 手势中断滚动时，保留当前按钮位置继续过渡。
            launch { gridState.animateScrollToItem(0) }.join()
        }
        visibility.snapTo(buttonProgress)
        returnAnimationDistance = null
    }

    val buttonSize = 48.dp
    val spacing = 12.dp
    Box(Modifier.size(width = buttonSize * 2 + spacing, height = buttonSize),
        contentAlignment = Alignment.BottomEnd) {
        if (managingFavorites) {
            SmallFloatingActionButton(onClick = onExitManagement,
                modifier = Modifier.offset {
                    val distance = (buttonSize + spacing).toPx() * buttonProgress
                    IntOffset(-distance.roundToInt(), 0)
                },
                shape = CircleShape, containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(Icons.Outlined.Close, stringResource(R.string.exit_favorite_management))
            }
        }
        val arrowVisible by remember(gridState) { derivedStateOf { buttonProgress > 0f } }
        if (arrowVisible) {
            SmallFloatingActionButton(onClick = {
                if (!returningToTop && gridState.canScrollBackward) {
                    returnAnimationDistance = if (gridState.firstVisibleItemIndex == 0) {
                        gridState.firstVisibleItemScrollOffset.toFloat().coerceIn(1f, animationDistancePx)
                    } else animationDistancePx
                }
            },
                modifier = Modifier.graphicsLayer {
                    alpha = buttonProgress
                    translationY = size.height * (1f - alpha)
                },
                shape = CircleShape, containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.scroll_to_top))
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
            Icon(Icons.Outlined.CreateNewFolder, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.choose_library_directory), fontSize = 16.sp)
        }
    }
}

private val EmptyLibraryIconSize = 52.dp
private val EmptyLibraryLineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)

@Composable
private fun EmptyLibrary(
    scanning: Boolean,
    directoryEmpty: Boolean,
    onChooseLibrary: () -> Unit,
    emptyTitle: String? = null,
    emptyDescription: String? = null,
) {
    val languageTag = LocalConfiguration.current.locales[0].toLanguageTag()
    val locales = remember(languageTag) { LocaleList(languageTag) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 32.dp, end = 32.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.AutoMirrored.Outlined.MenuBook, null,
            Modifier.size(EmptyLibraryIconSize).testTag("empty-library-icon"), tint = MaterialTheme.colorScheme.primary)
        Text(when {
            scanning -> stringResource(R.string.scanning_library)
            directoryEmpty -> stringResource(R.string.empty_directory)
            else -> emptyTitle ?: stringResource(R.string.no_matching_comics)
        }, Modifier.padding(top = 20.dp),
            style = MaterialTheme.typography.titleMedium.copy(localeList = locales, lineBreak = EmptyLibraryLineBreak),
            textAlign = TextAlign.Center)
        if (!directoryEmpty) Text(emptyDescription ?: stringResource(R.string.try_other_filters), Modifier.fillMaxWidth().padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium.copy(localeList = locales, lineBreak = EmptyLibraryLineBreak),
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (directoryEmpty && !scanning) {
            TextButton(onClick = onChooseLibrary, Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.choose_other_directory)) }
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
                Text(stringResource(R.string.continue_reading), style = MaterialTheme.typography.labelMedium)
                Text(book.title, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.page_progress, progress.page + 1, progress.total), Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Outlined.PlayArrow, stringResource(R.string.continue_reading))
        }
    }
}

private const val BookCoverAspectRatio = .69f

@Composable
private fun BookCard(book: Book, progress: ReadingProgress, favorite: Boolean, repository: BookRepository,
    coverSize: PixelSize, revision: Int, showFileType: Boolean, showFavoriteIcon: Boolean,
    managingFavorites: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onFavorite: () -> Unit) {
    val managementLabel = stringResource(if (managingFavorites) R.string.exit_favorite_management else R.string.manage_favorites)
    Column(Modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onClick,
        onLongClickLabel = managementLabel, onLongClick = onLongClick,
        hapticFeedbackEnabled = false)) {
        Box(Modifier.fillMaxWidth().aspectRatio(BookCoverAspectRatio).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            BookCover(book, repository, coverSize, revision)
            if (showFileType) {
                Surface(Modifier.align(Alignment.TopStart).padding(7.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .92f),
                    shape = RoundedCornerShape(6.dp)) {
                    val formatLabel = when (book.kind) {
                        "folder" -> stringResource(R.string.image_folder)
                        "video-folder" -> stringResource(R.string.video_folder)
                        "mixed-folder" -> stringResource(R.string.mixed_folder)
                        else -> book.kind.uppercase(Locale.ROOT)
                    }
                    Text(formatLabel, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (showFavoriteIcon) {
                Box(Modifier.align(Alignment.BottomEnd).size(48.dp).clip(CircleShape)
                    .combinedClickable(
                        role = Role.Button,
                        onClick = onFavorite,
                        onLongClickLabel = managementLabel,
                        onLongClick = onLongClick,
                        hapticFeedbackEnabled = false,
                    ), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .92f)) {
                        Icon(if (favorite) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                            stringResource(if (favorite) R.string.remove_favorite else R.string.add_favorite, book.title),
                            Modifier.padding(6.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (progress.total > 0) LinearProgressIndicator(progress = { progress.fraction },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp))
        }
        Text(book.title, Modifier.padding(top = 9.dp, start = 1.dp, end = 1.dp), style = MaterialTheme.typography.titleSmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
        Text(when { progress.total == 0 -> stringResource(R.string.not_started); progress.fraction >= 1f -> stringResource(R.string.finished_pages, progress.total); else -> stringResource(R.string.card_page_progress, progress.page + 1, progress.total) },
            Modifier.padding(top = 3.dp, start = 1.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BookCover(book: Book, repository: BookRepository, size: PixelSize, revision: Int) {
    var failed by remember(book.cacheKey, size, revision) { mutableStateOf(false) }
    var bitmap by remember(book.cacheKey, size, revision) {
        mutableStateOf<Bitmap?>(repository.cachedCoverBitmap(book, size, revision))
    }
    LaunchedEffect(book.cacheKey, size, revision) {
        if (bitmap != null) return@LaunchedEffect
        try {
            bitmap = repository.coverBitmap(book, size, revision)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            dev.zolive.zviewer.data.DiagnosticLog.error("Cover", "封面加载失败 book=${book.id.take(8)} kind=${book.kind}", error)
            failed = true
        }
    }
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(), book.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    else Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(if (failed) Icons.Outlined.ImageNotSupported else Icons.AutoMirrored.Outlined.MenuBook, null,
            Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (failed) Text(stringResource(R.string.tap_to_read), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall)
    }
}
