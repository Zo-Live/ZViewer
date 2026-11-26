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
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
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
import dev.zolive.zviewer.data.AnimationEffect
import dev.zolive.zviewer.data.Book
import dev.zolive.zviewer.data.BookRepository
import dev.zolive.zviewer.data.DiagnosticLog
import dev.zolive.zviewer.data.HapticEvent
import dev.zolive.zviewer.data.LibraryTab
import dev.zolive.zviewer.data.ReadingProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
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
    data class Interaction(val settingsPage: InteractionSettingsPage) : AppPage(settingsPage.tag)
    data object Logs : AppPage("log-list-screen")
    data class LogDetail(val file: DiagnosticLog.LogFile) : AppPage("log-detail-screen")

    fun parent(): AppPage = when (this) {
        Library, Settings -> Library
        is Section -> Settings
        is Interaction -> Section(SettingsSection.Preferences)
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
                    InteractionSettingsPage.Animation.tag -> Interaction(InteractionSettingsPage.Animation)
                    InteractionSettingsPage.Haptics.tag -> Interaction(InteractionSettingsPage.Haptics)
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

private class ContinueReadingMotion(val bookId: String) {
    val icon = Animatable(0f)
    val entry = Animatable(0f)
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
    val speed = animationSpeed(AnimationEffect.PageTransition)
    val session = state.session
    val waitingForVideo = session != null && state.waitingForVideoEntry
    val iconSpec = iconAnimationSpec()
    val entrySpec = tween<Float>(animationSpeed(AnimationEffect.ReaderEntry).duration(300), easing = LinearEasing)
    var continueMotion by remember { mutableStateOf<ContinueReadingMotion?>(null) }
    LaunchedEffect(continueMotion) {
        val motion = continueMotion ?: return@LaunchedEffect
        coroutineScope {
            launch { motion.icon.animateTo(1f, iconSpec) }
            motion.entry.animateTo(1f, entrySpec)
        }
        continueMotion = null
    }
    val readerEntry = remember(continueMotion, waitingForVideo, session) {
        derivedStateOf {
            continueMotion?.takeIf { !waitingForVideo && it.bookId == session?.book?.id && it.entry.value < 1f }
        }
    }.value
    val readerEntryStart = remember(readerEntry, session) { readerEntry?.entry?.value ?: 0f }
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
    val settingsIcon = remember { mutableFloatStateOf(if (page == AppPage.Library) 0f else 1f) }
    val settingsIconSpec = iconAnimationSpec(FastOutSlowInEasing)
    var settingsIconJob by remember { mutableStateOf<Job?>(null) }
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
        if (child == AppPage.Settings) {
            settingsIconJob?.cancel()
            settingsIconJob = navigationScope.launch {
                animate(settingsIcon.floatValue, target, animationSpec = settingsIconSpec) { value, _ ->
                    settingsIcon.floatValue = value
                }
            }
        }
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
        if (page == AppPage.Settings) settingsIconJob?.cancel()
        val motion = transition?.takeIf { it.child == page } ?: PageTransition(page, 1f)
        val start = dragProgress ?: motion.progress.value
        transition = motion
        dragProgress = start
        return start
    }
    fun updateBackGesture(progress: Float) {
        if (transition?.child == AppPage.Settings) {
            val delta = progress - (dragProgress ?: progress)
            settingsIcon.floatValue = (settingsIcon.floatValue + delta).coerceIn(0f, 1f)
        }
        dragProgress = progress
    }
    fun finishBackGesture(showChild: Boolean) {
        val start = dragProgress ?: return
        val motion = transition ?: return
        finishTransition(motion.child, showChild, start)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.selectLibrary(uri)
    }
    val chooseLibrary = {
        model.cancelStartupReading()
        picker.launch(state.treeUri?.let(Uri::parse))
    }
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            if (session != null) {
                key(session) {
                    Box(Modifier.zIndex(if (readerEntry != null) 2f else 0f).graphicsLayer {
                        if (readerEntry != null && readerEntryStart < 1f) {
                            val fade = (readerEntry.entry.value - readerEntryStart) / (1f - readerEntryStart)
                            alpha = fade.coerceIn(0f, 1f)
                        }
                    }) {
                        ReaderScreen(session, state.initialPage, state.settings,
                            model.repository, model::settings, { index -> model.saveProgress(session, index) },
                            { index -> model.videoPosition(session, index) },
                            { index, position -> model.saveVideoProgress(session, index, position) },
                            onBack = {
                                continueMotion = null
                                model.closeBook()
                            },
                            visible = !waitingForVideo,
                            onVideoEntryReady = { model.finishVideoEntry(session) })
                    }
                }
            }
            if (session == null || waitingForVideo || readerEntry != null) {
                val motion = transition
                val layers = if (motion == null) listOf(page) else listOf(motion.parent, motion.child)
                Box(Modifier.fillMaxSize().zIndex(1f).clipToBounds().background(MaterialTheme.colorScheme.background)
                    .onSizeChanged { viewportWidth = it.width.toFloat().coerceAtLeast(1f) }) {
                    layers.forEach { layer ->
                        key(layer) {
                            val active = layer == page && readerEntry == null
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
                                        updateBackGesture(((dragProgress ?: 1f) - dragAmount / viewportWidth).coerceIn(0f, 1f))
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
                                        AppPage.Library -> LibraryScreen(state, model, active, continueMotion, settingsIcon,
                                            onContinue = { book ->
                                                continueMotion = ContinueReadingMotion(book.id)
                                                model.open(book)
                                            },
                                            onSettings = {
                                                model.cancelStartupReading()
                                                finishTransition(AppPage.Settings, showChild = true, startProgress = 0f)
                                            },
                                            onChooseLibrary = chooseLibrary)
                                        AppPage.Settings -> SettingsScreen(::goBack) { section ->
                                            finishTransition(AppPage.Section(section), showChild = true, startProgress = 0f)
                                        }
                                        is AppPage.Section -> SettingsSectionScreen(
                                            layer.section, state, model, active, ::goBack, chooseLibrary,
                                            onViewLogs = { finishTransition(AppPage.Logs, showChild = true, startProgress = 0f) },
                                            onOpenInteractionSettings = {
                                                finishTransition(AppPage.Interaction(it), showChild = true, startProgress = 0f)
                                            })
                                        is AppPage.Interaction -> InteractionSettingsScreen(
                                            layer.settingsPage, state.settings, ::goBack, model::settings)
                                        AppPage.Logs -> LogListScreen(active, ::goBack) { file ->
                                            finishTransition(AppPage.LogDetail(file), showChild = true, startProgress = 0f)
                                        }
                                        is AppPage.LogDetail -> LogDetailScreen(layer.file, ::goBack)
                                    }
                                }
                            }
                        }
                    }
                    if (waitingForVideo || readerEntry != null || motion != null && dragProgress == null) {
                        Box(Modifier.matchParentSize().pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        })
                    }
                }
                PredictiveBackHandler(enabled = page != AppPage.Library && !waitingForVideo && readerEntry == null) { events ->
                    var startProgress: Float? = null
                    try {
                        events.collect { event ->
                            val start = startProgress ?: beginBackGesture().also { startProgress = it }
                            updateBackGesture(start * (1f - event.progress))
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
        AppAlertDialog(onDismissRequest = model::cancelOpen, icon = { AppCircularProgressIndicator(Modifier.size(36.dp)) },
            title = { Text(stringResource(R.string.opening_reader)) }, text = { Text(state.opening) },
            confirmButton = { TextButton(onClick = model::cancelOpen) { Text(stringResource(R.string.cancel)) } })
    }
    SettingsImportDialog(model)
    if (state.error != null) {
        AppAlertDialog(onDismissRequest = model::dismissError, icon = { Icon(painterResource(R.drawable.ic_symbol_info), null) },
            title = { Text(stringResource(R.string.read_error_title)) }, text = { Text(state.error) },
            confirmButton = { TextButton(onClick = model::dismissError) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { model.dismissError(); chooseLibrary() }) { Text(stringResource(R.string.choose_library)) } })
    }
}

@Composable
private fun LibraryScreen(state: LibraryState, model: LibraryViewModel, active: Boolean,
    continueMotion: ContinueReadingMotion?, settingsIcon: State<Float>, onContinue: (Book) -> Unit,
    onSettings: () -> Unit, onChooseLibrary: () -> Unit) {
    val searchSpeed = animationSpeed(AnimationEffect.LibrarySearch)
    val enterManagementHaptic = hapticFeedback(HapticEvent.EnterFavoriteManagement)
    val exitManagementHaptic = hapticFeedback(HapticEvent.ExitFavoriteManagement)
    val addFavoriteHaptic = hapticFeedback(HapticEvent.AddFavorite)
    val removeFavoriteHaptic = hapticFeedback(HapticEvent.RemoveFavorite)
    val tabs = LibraryTab.entries
    val pagerState = rememberPagerState(initialPage = state.settings.startupPage.ordinal) { tabs.size }
    val gridStates = tabs.map { rememberLazyGridState() }
    val currentGrid = gridStates[pagerState.currentPage]
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var requestSearchFocus by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun closeSearch() {
        requestSearchFocus = false
        focusManager.clearFocus()
        keyboard?.hide()
        searching = false
        query = ""
    }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var managingFavorites by rememberSaveable { mutableStateOf(false) }
    fun changeFavoriteManagement(enabled: Boolean) {
        if (managingFavorites == enabled) return
        managingFavorites = enabled
        if (enabled) enterManagementHaptic(HapticFeedbackConstantsCompat.LONG_PRESS)
        else exitManagementHaptic(HapticFeedbackConstantsCompat.TOGGLE_OFF)
    }
    fun toggleFavorite(book: Book) {
        val isFavorite = book.id in model.state.value.favorites
        model.toggleFavorite(book)
        if (isFavorite) removeFavoriteHaptic(HapticFeedbackConstantsCompat.TOGGLE_OFF)
        else addFavoriteHaptic(HapticFeedbackConstantsCompat.TOGGLE_ON)
    }
    val contentInset = 24.dp
    val actionInset = contentInset - 12.dp
    val columns = state.settings.gridColumns
    val gridPadding = 20.dp
    val gridSpacing = 12.dp
    val window = LocalWindowInfo.current.containerSize
    val mediumWidth = with(LocalDensity.current) { window.width.toDp() >= 600.dp }
    val useRail = with(LocalDensity.current) {
        window.width.toDp() >= 840.dp && window.height.toDp() >= 480.dp
    }
    BackHandler(enabled = active && (managingFavorites || searching)) {
        if (managingFavorites) changeFavoriteManagement(false)
        else closeSearch()
    }
    LibraryScaffold(
        useRail = useRail,
        topBar = {
            // 顶栏默认标题 / 操作区边距为 16 / 4 dp；图标在 48 dp 点击区内各留 12 dp。
            TopAppBar(modifier = Modifier.padding(start = contentInset - 16.dp, end = actionInset - 4.dp), title = {
                Text("ZViewer", style = MaterialTheme.typography.appTitle)
            }, actions = {
                LibraryActionIconButton(LibraryActionIcon.Search, active = active) {
                    if (searching) closeSearch()
                    else {
                        requestSearchFocus = true
                        searching = true
                    }
                }
                SettingsIconButton(stringResource(R.string.settings), settingsIcon, active = active) {
                    if (searching) {
                        focusManager.clearFocus()
                        keyboard?.hide()
                    }
                    changeFavoriteManagement(false)
                    onSettings()
                }
            })
        },
        navigation = {
            LibraryNavigation(useRail, mediumWidth, pagerState.currentPage) { index ->
                pagerState.requestScrollToPage(index)
            }
        },
        floatingActionButton = {
            LibraryActions(currentGrid, managingFavorites,
                onExitManagement = { changeFavoriteManagement(false) })
        },
    ) {
        val coverWidth = with(LocalDensity.current) {
            ((constraints.maxWidth - 2 * gridPadding.roundToPx() - (columns - 1) * gridSpacing.roundToPx()) / columns)
                .coerceAtLeast(1)
        }
        val coverSize = remember(coverWidth) { PixelSize(coverWidth, (coverWidth / BookCoverAspectRatio).roundToInt()) }
        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = searching,
                enter = expandVertically(tween(searchSpeed.duration(280), easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
                    slideInVertically(tween(searchSpeed.duration(280), easing = FastOutSlowInEasing)) { -it / 4 } +
                    fadeIn(tween(searchSpeed.duration(180))),
                exit = shrinkVertically(tween(searchSpeed.duration(220), easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) +
                    slideOutVertically(tween(searchSpeed.duration(220), easing = FastOutSlowInEasing)) { -it / 4 } +
                    fadeOut(tween(searchSpeed.duration(150)))
            ) {
                TextSelectionHaptics {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth()
                        .padding(horizontal = contentInset, vertical = 8.dp).focusRequester(searchFocus),
                        placeholder = { Text(stringResource(R.string.search_title)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_symbol_search), null) },
                        trailingIcon = { IconButton(onClick = ::closeSearch) { Icon(painterResource(R.drawable.ic_symbol_close), stringResource(R.string.close_search)) } },
                        shape = RoundedCornerShape(28.dp), singleLine = true)
                    LaunchedEffect(searching, requestSearchFocus, active) {
                        if (searching && requestSearchFocus && active) {
                            searchFocus.requestFocus()
                            keyboard?.show()
                            requestSearchFocus = false
                        }
                    }
                }
            }
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().testTag("library-pager"),
                flingBehavior = pageFlingBehavior(pagerState, animationSpeed(AnimationEffect.LibraryPaging)),
                key = { it }) { pageIndex ->
                val tab = tabs[pageIndex]
                var sortMenu by remember { mutableStateOf(false) }
                val filterOptions = if (tab == LibraryTab.Recent) {
                    listOf(0 to stringResource(R.string.filter_all), 1 to stringResource(R.string.filter_in_progress), 3 to stringResource(R.string.filter_read))
                } else {
                    listOf(0 to stringResource(R.string.filter_all), 1 to stringResource(R.string.filter_in_progress), 2 to stringResource(R.string.filter_unread), 3 to stringResource(R.string.filter_read))
                }
                val selectedFilter = if (tab == LibraryTab.Recent && filter == 2) 0 else filter
                val books = remember(state.books, state.favorites, state.progress, tab, query, selectedFilter, sort) {
                    state.books.filter { book ->
                        val progress = state.progress[book.id] ?: ReadingProgress()
                        val matchesTab = when (tab) {
                            LibraryTab.Library -> true
                            LibraryTab.Favorites -> book.id in state.favorites
                            LibraryTab.Recent -> progress.updated > 0
                        }
                        val matchesFilter = when (selectedFilter) {
                            1 -> progress.updated > 0 && progress.fraction < 1f
                            2 -> progress.updated == 0L
                            3 -> progress.total > 0 && progress.fraction >= 1f
                            else -> true
                        }
                        matchesTab && matchesFilter && book.title.contains(query, ignoreCase = true)
                    }.let { filtered ->
                        when {
                            tab == LibraryTab.Recent || sort == 2 -> filtered.sortedByDescending { state.progress[it.id]?.updated ?: 0 }
                            sort == 1 -> filtered.sortedByDescending { it.modified }
                            else -> filtered
                        }
                    }
                }
                val hasFavorites = state.books.any { it.id in state.favorites }
                val hasRecent = state.books.any { (state.progress[it.id]?.updated ?: 0L) > 0L }
                val pageModifier = Modifier.fillMaxSize()
                    .then(if (pagerState.currentPage != pageIndex) Modifier.clearAndSetSemantics {} else Modifier)
                if (state.treeUri == null) {
                    Box(pageModifier) { WelcomeScreen(onChooseLibrary) }
                } else {
                    Layout(modifier = pageModifier, content = {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(start = contentInset, end = actionInset, top = 14.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(tab.title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                                    Text(pluralStringResource(R.plurals.comic_count, books.size, books.size), Modifier.padding(top = 5.dp),
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                LibraryActionIconButton(LibraryActionIcon.Refresh, enabled = !state.scanning, active = active) {
                                    changeFavoriteManagement(false)
                                    model.refresh()
                                }
                                EndAlignedDropdownMenu(
                                    expanded = sortMenu,
                                    onDismissRequest = { sortMenu = false },
                                    anchor = {
                                        LibraryActionIconButton(LibraryActionIcon.Sort, active = active) {
                                            sortMenu = true
                                        }
                                    },
                                ) {
                                    listOf(stringResource(R.string.sort_by_name), stringResource(R.string.sort_modified), stringResource(R.string.recent_reads)).forEachIndexed { index, label ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = { sort = index; sortMenu = false },
                                            trailingIcon = { if (sort == index) Icon(painterResource(R.drawable.ic_symbol_check), null) })
                                    }
                                }
                            }
                            LibraryFilters(filterOptions, selectedFilter, contentInset) { filter = it }
                        }
                        LazyVerticalGrid(columns = GridCells.Fixed(columns),
                            state = gridStates[pageIndex], modifier = Modifier.fillMaxSize().testTag("library-grid-$pageIndex"),
                            contentPadding = PaddingValues(gridPadding, 12.dp, gridPadding, 88.dp),
                            horizontalArrangement = Arrangement.spacedBy(gridSpacing), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            val recent = if (tab == LibraryTab.Library && query.isEmpty() && selectedFilter == 0) books
                                .filter { (state.progress[it.id]?.updated ?: 0) > 0 && (state.progress[it.id]?.fraction ?: 0f) < 1f }
                                .maxByOrNull { state.progress[it.id]?.updated ?: 0 } else null
                            if (recent != null) item(span = { GridItemSpan(maxLineSpan) }) {
                                val fill = continueMotion?.takeIf { it.bookId == recent.id }?.icon?.asState()
                                ContinueCard(recent, state.progress.getValue(recent.id), fill, onClick = { onContinue(recent) })
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
                                icon = when (tab) {
                                    LibraryTab.Library -> LibraryFillIcon.Book.outline
                                    LibraryTab.Favorites -> LibraryFillIcon.Bookmark.outline
                                    LibraryTab.Recent -> R.drawable.ic_symbol_history
                                },
                                scanning = state.scanning,
                                directoryEmpty = state.books.isEmpty(),
                                onChooseLibrary = onChooseLibrary,
                                emptyTitle = when {
                                    tab == LibraryTab.Favorites && !hasFavorites -> stringResource(R.string.no_favorites)
                                    tab == LibraryTab.Recent && !hasRecent -> stringResource(R.string.no_recent_reads)
                                    else -> null
                                },
                                emptyDescription = when {
                                    tab == LibraryTab.Favorites && !hasFavorites -> stringResource(R.string.favorite_management_hint)
                                    tab == LibraryTab.Recent && !hasRecent -> stringResource(R.string.start_reading_hint)
                                    else -> null
                                }
                            )
                        }
                    }) { measurables, constraints ->
                        val looseHeight = constraints.copy(minHeight = 0)
                        val header = measurables[0].measure(looseHeight)
                        val grid = measurables[1].measure(looseHeight.copy(maxHeight = constraints.maxHeight - header.height))
                        // 空状态图标以分页视口为基准，说明文字高度不影响位置。
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
private fun LibraryScaffold(
    useRail: Boolean,
    topBar: @Composable () -> Unit,
    navigation: @Composable () -> Unit,
    floatingActionButton: @Composable () -> Unit,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        if (useRail) navigation()
        val modifier = Modifier.weight(1f)
        Scaffold(
            modifier = if (useRail) modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)) else modifier,
            topBar = topBar,
            bottomBar = { if (!useRail) navigation() },
            floatingActionButton = floatingActionButton,
        ) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), content = content)
        }
    }
}

@Composable
private fun LibraryNavigation(useRail: Boolean, mediumWidth: Boolean, page: Int, onSelect: (Int) -> Unit) {
    val spec = iconAnimationSpec()
    val bookFill = animateFloatAsState(if (page == LibraryTab.Library.ordinal) 1f else 0f, spec)
    val bookmarkFill = animateFloatAsState(if (page == LibraryTab.Favorites.ordinal) 1f else 0f, spec)
    val historyTurn = remember { Animatable(0f) }
    val currentPage by rememberUpdatedState(page)
    val currentSpec by rememberUpdatedState(spec)
    LaunchedEffect(Unit) {
        snapshotFlow { currentPage }
            .drop(1)
            .filter { it == LibraryTab.Recent.ordinal }
            .collectLatest {
                historyTurn.snapTo(0f)
                historyTurn.animateTo(1f, currentSpec)
            }
    }
    val tabIcon: @Composable (LibraryTab) -> Unit = { tab ->
        when (tab) {
            LibraryTab.Library -> LibraryFillingIcon(LibraryFillIcon.Book, bookFill)
            LibraryTab.Favorites -> LibraryFillingIcon(LibraryFillIcon.Bookmark, bookmarkFill)
            LibraryTab.Recent -> LibraryHistoryIcon(historyTurn.asState())
        }
    }
    WithAnimationSpeed(animationSpeed(AnimationEffect.LibraryTabIndicator)) {
        LibraryNavigationBar(useRail, mediumWidth, page, onSelect, tabIcon)
    }
}

@Composable
private fun LibraryNavigationBar(
    useRail: Boolean,
    mediumWidth: Boolean,
    page: Int,
    onSelect: (Int) -> Unit,
    tabIcon: @Composable (LibraryTab) -> Unit,
) {
    if (useRail) {
        NavigationRail(
            modifier = Modifier.testTag("library-navigation-rail"),
            windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
        ) {
            LibraryTab.entries.forEachIndexed { index, tab ->
                val selected = page == index
                NavigationRailItem(
                    selected = selected, onClick = { onSelect(index) },
                    icon = { tabIcon(tab) }, label = { Text(stringResource(tab.title)) },
                )
            }
        }
    } else {
        ShortNavigationBar(
            modifier = Modifier.testTag("library-navigation-bar"),
            arrangement = if (mediumWidth) ShortNavigationBarArrangement.Centered else ShortNavigationBarArrangement.EqualWeight,
        ) {
            LibraryTab.entries.forEachIndexed { index, tab ->
                val selected = page == index
                ShortNavigationBarItem(
                    selected = selected, onClick = { onSelect(index) },
                    icon = { tabIcon(tab) }, label = { Text(stringResource(tab.title)) },
                    iconPosition = if (mediumWidth) NavigationItemIconPosition.Start else NavigationItemIconPosition.Top,
                )
            }
        }
    }
}

@Composable
private fun LibraryActions(gridState: LazyGridState, managingFavorites: Boolean, onExitManagement: () -> Unit) {
    val speed = animationSpeed(AnimationEffect.LibraryScrollToTop)
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
            val scale = PreferredMotionDurationScale(coroutineContext[MotionDurationScale], speed)
            launch(scale) { gridState.animateScrollToItem(0) }.join()
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
                Icon(painterResource(R.drawable.ic_symbol_close), stringResource(R.string.exit_favorite_management))
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
                Icon(painterResource(R.drawable.ic_symbol_keyboard_arrow_up), stringResource(R.string.scroll_to_top))
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
            Icon(painterResource(R.drawable.ic_symbol_create_new_folder), null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.choose_library_directory), fontSize = 16.sp)
        }
    }
}

private val EmptyLibraryIconSize = 52.dp
private val EmptyLibraryLineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)

@Composable
private fun EmptyLibrary(
    @DrawableRes icon: Int,
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
        Icon(painterResource(icon), null,
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
private fun ContinueCard(book: Book, progress: ReadingProgress, fill: State<Float>?, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            LibraryFillingIcon(LibraryFillIcon.MenuBook, fill, Modifier.size(30.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(stringResource(R.string.continue_reading), style = MaterialTheme.typography.labelMedium)
                Text(book.title, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.page_progress, progress.page + 1, progress.total), Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall)
            }
            LibraryFillingIcon(LibraryFillIcon.Play, fill, description = stringResource(R.string.continue_reading))
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
                        Icon(painterResource(if (favorite) R.drawable.ic_symbol_bookmark_filled else R.drawable.ic_symbol_bookmark),
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
        Icon(painterResource(if (failed) R.drawable.ic_symbol_image_not_supported else R.drawable.ic_symbol_menu_book), null,
            Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (failed) Text(stringResource(R.string.tap_to_read), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall)
    }
}
