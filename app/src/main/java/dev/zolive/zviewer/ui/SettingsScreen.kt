@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.zolive.zviewer.ui

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.zolive.zviewer.BuildConfig
import dev.zolive.zviewer.LibraryState
import dev.zolive.zviewer.LibraryViewModel
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.AnimationEffect
import dev.zolive.zviewer.data.AnimationSpeed
import dev.zolive.zviewer.data.AppLanguage
import dev.zolive.zviewer.data.AutoLockInterval
import dev.zolive.zviewer.data.ReaderSettings
import dev.zolive.zviewer.data.CacheLimits
import dev.zolive.zviewer.data.DefaultThemeAccent
import dev.zolive.zviewer.data.DiagnosticLog
import dev.zolive.zviewer.data.HapticEvent
import dev.zolive.zviewer.data.LibraryTab
import dev.zolive.zviewer.data.PagedImageFit
import dev.zolive.zviewer.data.UpdateDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val SettingsContentInset = 24.dp
private val SettingsSectionIconSize = 20.dp
private val SettingsBackButtonSize = 48.dp
private val SettingsActionWidth = 48.dp
private val SettingsTrailingIconSize = 24.dp
private val SettingsTrailingMinWidth = 52.dp
private val SettingsTrailingGap = 16.dp
private val SettingsSupportingMaxWidth = 480.dp
private const val DisabledSettingsContentOpacity = 0.38f

@Composable
private fun settingsTransitionDuration(millis: Int): Int {
    val speed = animationSpeed(AnimationEffect.SettingsTransition)
    val inheritedScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    val inheritedSpeed = (inheritedScale as? PreferredMotionDurationScale)?.speed ?: AnimationSpeed.Moderate
    // 阅读快捷设置的面板有独立速度，页内选项按自己的速度计算时长。
    return (millis * speed.durationScale / inheritedSpeed.durationScale).roundToInt()
}

@Composable
private fun SettingsVisibility(visible: Boolean, content: @Composable () -> Unit) {
    val duration = settingsTransitionDuration(220)
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(duration), expandFrom = Alignment.Top) + fadeIn(tween(duration)),
        exit = shrinkVertically(tween(duration), shrinkTowards = Alignment.Top) + fadeOut(tween(duration)),
    ) { content() }
}

internal enum class SettingsSection(@StringRes val title: Int, @DrawableRes val icon: Int, val tag: String) {
    Appearance(R.string.appearance, R.drawable.ic_symbol_style, "settings-appearance-screen"),
    Preferences(R.string.preferences, R.drawable.ic_symbol_tune, "settings-preferences-screen"),
    Reading(R.string.reading, R.drawable.ic_symbol_chrome_reader_mode, "settings-reading-screen"),
    LibraryAndStorage(R.string.library_and_storage, R.drawable.ic_symbol_folder, "settings-storage-screen"),
    Diagnostics(R.string.diagnostics, R.drawable.ic_symbol_bug_report, "settings-diagnostics-screen"),
    Security(R.string.security_and_privacy, R.drawable.ic_symbol_security, "settings-security-screen"),
    Application(R.string.app, R.drawable.ic_symbol_info, "settings-application-screen"),
}

@Composable
internal fun SettingsScreen(onBack: () -> Unit, onOpen: (SettingsSection) -> Unit) {
    SettingsPage(R.string.settings, R.string.back_to_library, onBack) {
        SettingsSection.entries.forEach { section ->
            SettingsListItem(
                headlineContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(section.icon), null, Modifier.size(SettingsSectionIconSize),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(stringResource(section.title))
                    }
                },
                trailingContent = {
                    Icon(
                        painterResource(R.drawable.ic_symbol_keyboard_arrow_right), null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.clickable(role = Role.Button) { onOpen(section) },
            )
        }
    }
}

@Composable
internal fun SettingsSectionScreen(
    section: SettingsSection,
    state: LibraryState,
    model: LibraryViewModel,
    active: Boolean,
    onBack: () -> Unit,
    onChooseLibrary: () -> Unit,
    onViewLogs: () -> Unit,
    onOpenInteractionSettings: (InteractionSettingsPage) -> Unit,
) {
    SettingsPage(section.title, R.string.back_to_settings, onBack) {
        when (section) {
            SettingsSection.Appearance -> AppearanceSettings(state.settings, model::settings)
            SettingsSection.Preferences -> AppPreferences(state.settings, model::settings, onOpenInteractionSettings)
            SettingsSection.Reading -> ReaderPreferences(
                state.settings, state.readerFitExpanded, model::setReaderFitExpanded, model::settings,
            )
            SettingsSection.LibraryAndStorage -> LibraryStorageSettings(state, model, onChooseLibrary)
            SettingsSection.Diagnostics -> DiagnosticsSettings(state.settings, model, active, onViewLogs)
            SettingsSection.Security -> SecuritySettings(state, model)
            SettingsSection.Application -> ApplicationSettings(state, model)
        }
    }
}

@Composable
internal fun SettingsPage(
    @StringRes title: Int,
    @StringRes backLabel: Int,
    onBack: () -> Unit,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(topBar = {
        // 返回箭头与分区图标的中心对齐，标题区两侧等宽并随文字换行增高。
        val backInset = SettingsContentInset + SettingsSectionIconSize / 2 - SettingsBackButtonSize / 2
        Box(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(TopAppBarDefaults.windowInsets).heightIn(min = 64.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(title),
                Modifier.padding(horizontal = backInset + SettingsBackButtonSize + 6.dp, vertical = 8.dp)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = backInset)
                .size(SettingsBackButtonSize)) {
                Icon(painterResource(R.drawable.ic_symbol_arrow_back), stringResource(backLabel))
            }
            if (action != null) {
                Box(Modifier.align(Alignment.CenterEnd).padding(end = backInset)) { action() }
            }
        }
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsContentInset).padding(top = 8.dp, bottom = 20.dp),
            content = content,
        )
    }
}

@Composable
private fun AppearanceSettings(settings: ReaderSettings, onSettings: (ReaderSettings) -> Unit) {
    var customColor by remember { mutableStateOf(false) }
    val haptic = hapticFeedback(HapticEvent.ThemeColorPreset)
    PreferenceMenu(
        title = stringResource(R.string.app_theme),
        selectedValue = settings.theme,
        options = listOf(
            "system" to stringResource(R.string.theme_system),
            "light" to stringResource(R.string.theme_light),
            "dark" to stringResource(R.string.theme_dark),
        ),
    ) { onSettings(settings.copy(theme = it)) }
    PreferenceMenu(
        title = stringResource(R.string.grid_columns),
        selectedValue = settings.gridColumns,
        options = (2..5).map { it to it.toString() },
    ) { onSettings(settings.copy(gridColumns = it)) }
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val useDynamicColor = supportsDynamicColor && settings.dynamicColor
    val activeAccent = if (useDynamicColor) {
        MaterialTheme.colorScheme.primary.toArgb().toLong() and 0xFFFFFFFFL
    } else {
        settings.accent
    }
    if (supportsDynamicColor) {
        PreferenceSwitch(stringResource(R.string.dynamic_color), checked = useDynamicColor) {
            onSettings(settings.copy(dynamicColor = it))
        }
    }
    Text(stringResource(R.string.theme_color), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge)
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val colors = listOf(DefaultThemeAccent to stringResource(R.string.color_moss), 0xFF476A8A to stringResource(R.string.color_mist), 0xFF79618C to stringResource(R.string.color_wisteria), 0xFF9A5357 to stringResource(R.string.color_rose), 0xFF8B683E to stringResource(R.string.color_amber))
        colors.forEach { (color, name) ->
            val description = stringResource(R.string.theme_color_description, name)
            val selected = activeAccent == color
            Box(Modifier.size(44.dp).semantics { contentDescription = description }
                .selectable(selected = selected, role = Role.RadioButton) {
                    if (!selected) haptic(HapticFeedbackConstantsCompat.CONFIRM)
                    onSettings(settings.copy(accent = color, dynamicColor = useDynamicColor && selected))
                }
                .padding(4.dp).background(Color(color), CircleShape)
                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
                contentAlignment = Alignment.Center) {
                if (selected) Icon(painterResource(R.drawable.ic_symbol_check), null, Modifier.size(20.dp), Color.White)
            }
        }
        IconButton(onClick = { customColor = true }, Modifier.size(44.dp)) { Icon(painterResource(R.drawable.ic_symbol_palette), stringResource(R.string.custom_theme_color)) }
    }
    Spacer(Modifier.height(11.5.dp))
    PreferenceSwitch(
        title = stringResource(R.string.verification_follows_theme),
        subtitle = if (settings.verifyOnStartup) null else stringResource(R.string.verification_follows_theme_hint),
        checked = settings.verificationFollowsTheme,
        enabled = settings.verifyOnStartup,
    ) { onSettings(settings.copy(verificationFollowsTheme = it)) }
    if (customColor) {
        ThemeColorDialog(
            accent = settings.accent,
            dark = isDarkTheme(settings.theme),
            dynamicColor = useDynamicColor,
            onDismiss = { customColor = false },
            onApply = {
                onSettings(settings.copy(accent = it, dynamicColor = false))
                customColor = false
            },
        )
    }
}

@Composable
private fun AppPreferences(
    settings: ReaderSettings,
    onSettings: (ReaderSettings) -> Unit,
    onOpen: (InteractionSettingsPage) -> Unit,
) {
    val view = LocalView.current
    PreferenceMenu(
        title = stringResource(R.string.language),
        selectedValue = settings.language,
        options = AppLanguage.entries.map { it to it.endonym },
        titleIcon = R.drawable.ic_symbol_language,
    ) { onSettings(settings.copy(language = it)) }
    PreferenceMenu(
        title = stringResource(R.string.animation_speed),
        selectedValue = settings.animationSpeed,
        options = listOf(
            AnimationSpeed.Moderate to stringResource(R.string.animation_speed_moderate),
            AnimationSpeed.Enhanced to stringResource(R.string.animation_speed_enhanced),
        ),
    ) { onSettings(settings.copy(animationSpeed = it)) }
    val showAnimationSettings = settings.animationSpeed == AnimationSpeed.Enhanced
    SettingsVisibility(showAnimationSettings) {
        InteractionSettingsLink(InteractionSettingsPage.Animation, showAnimationSettings, onOpen)
    }
    PreferenceSwitch(
        title = stringResource(R.string.haptic_feedback),
        checked = settings.hapticFeedback,
        feedbackOnToggle = false,
    ) { enabled ->
        onSettings(settings.copy(hapticFeedback = enabled))
        view.isHapticFeedbackEnabled = enabled
        if (enabled && HapticEvent.EnableHapticFeedback !in settings.disabledHaptics) {
            ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.TOGGLE_ON)
        }
    }
    SettingsVisibility(settings.hapticFeedback) {
        InteractionSettingsLink(InteractionSettingsPage.Haptics, settings.hapticFeedback, onOpen)
    }
    PreferenceMenu(
        title = stringResource(R.string.preferred_startup_page),
        selectedValue = settings.startupPage,
        options = LibraryTab.entries.map { it to stringResource(it.title) },
    ) { onSettings(settings.copy(startupPage = it)) }
    PreferenceSwitch(stringResource(R.string.resume_reading_on_startup), checked = settings.resumeReadingOnStartup) {
        onSettings(settings.copy(resumeReadingOnStartup = it))
    }
    PreferenceSwitch(stringResource(R.string.show_file_type), stringResource(R.string.show_file_type_hint), settings.showFileType) {
        onSettings(settings.copy(showFileType = it))
    }
    PreferenceSwitch(stringResource(R.string.show_favorite_icon), stringResource(R.string.show_favorite_icon_hint), settings.showFavoriteIcon) {
        onSettings(settings.copy(showFavoriteIcon = it))
    }
}

@Composable
private fun InteractionSettingsLink(page: InteractionSettingsPage, enabled: Boolean, onOpen: (InteractionSettingsPage) -> Unit) {
    SettingsListItem(
        headlineContent = { Text(stringResource(page.title)) },
        trailingContent = { Icon(painterResource(R.drawable.ic_symbol_keyboard_arrow_right), null) },
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button) { onOpen(page) },
    )
}

@Composable
private fun SecuritySettings(state: LibraryState, model: LibraryViewModel) {
    val context = LocalContext.current
    val settings = state.settings
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.authorizeGalleryAccess(uri)
    }
    fun canEnableVerification(enabled: Boolean): Boolean {
        if (!enabled || context.getSystemService(KeyguardManager::class.java).isDeviceSecure) return true
        Toast.makeText(context, R.string.verification_needs_screen_lock, Toast.LENGTH_SHORT).show()
        return false
    }
    PreferenceSwitch(
        stringResource(R.string.verify_on_startup), stringResource(R.string.verify_on_startup_hint),
        settings.verifyOnStartup,
    ) { if (canEnableVerification(it)) model.settings(settings.copy(verifyOnStartup = it)) }
    PreferenceSwitch(
        stringResource(R.string.hide_recent_preview),
        stringResource(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) R.string.hide_recent_preview_hint
            else R.string.hide_recent_preview_legacy_hint,
        ),
        settings.hideRecentPreview,
    ) { model.settings(settings.copy(hideRecentPreview = it)) }
    PreferenceSwitch(
        stringResource(R.string.verify_after_background), checked = settings.verifyAfterBackground,
    ) { if (canEnableVerification(it)) model.settings(settings.copy(verifyAfterBackground = it)) }
    PreferenceMenu(
        title = stringResource(R.string.auto_lock_interval),
        subtitle = stringResource(R.string.auto_lock_interval_hint),
        selectedValue = settings.autoLockInterval,
        enabled = settings.verifyAfterBackground,
        options = listOf(
            AutoLockInterval.Immediately to stringResource(R.string.auto_lock_immediately),
            AutoLockInterval.FifteenSeconds to stringResource(R.string.auto_lock_fifteen_seconds),
            AutoLockInterval.OneMinute to stringResource(R.string.auto_lock_one_minute),
            AutoLockInterval.FiveMinutes to stringResource(R.string.auto_lock_five_minutes),
        ),
    ) { model.settings(settings.copy(autoLockInterval = it)) }
    PreferenceSwitch(
        stringResource(R.string.hide_recent_card), stringResource(R.string.hide_recent_card_hint),
        settings.hideRecentCard,
    ) { model.settings(settings.copy(hideRecentCard = it)) }
    PreferenceSwitch(
        stringResource(R.string.hide_gallery_images), stringResource(R.string.hide_gallery_images_hint),
        settings.hideGalleryImages,
    ) { enabled ->
        val tree = state.treeUri?.let(Uri::parse)
        if (enabled && tree != null && model.galleryNeedsWriteAccess(tree)) {
            Toast.makeText(context, R.string.gallery_select_current_library, Toast.LENGTH_LONG).show()
            galleryPicker.launch(tree)
        } else {
            model.settings(settings.copy(hideGalleryImages = enabled))
        }
    }
}

@Composable
private fun LibraryStorageSettings(state: LibraryState, model: LibraryViewModel, onChooseLibrary: () -> Unit) {
    val settings = state.settings
    val context = LocalContext.current
    val cacheSize by produceState(0L, state.cacheRevision) { value = model.repository.cacheSize() }
    SettingsListItem(headlineContent = { Text(stringResource(R.string.library_directory)) }, supportingContent = {
        Text(state.treeUri?.let { Uri.decode(it.substringAfterLast('/')) } ?: stringResource(R.string.no_directory_selected))
    },
        modifier = Modifier.clickable(onClick = onChooseLibrary))
    PreferenceSwitch(stringResource(R.string.current_library_cache_only),
        stringResource(R.string.current_library_cache_only_hint), settings.currentLibraryCacheOnly) {
        model.settings(settings.copy(currentLibraryCacheOnly = it))
    }
    SettingsListItem(headlineContent = { Text(stringResource(R.string.clear_reading_cache)) }, supportingContent = {
        Text(stringResource(
            if (settings.clearCovers) R.string.clear_reading_cache_with_covers_hint else R.string.clear_reading_cache_hint,
        ))
    },
        trailingContent = { Text(Formatter.formatShortFileSize(context, cacheSize)) },
        modifier = Modifier.doubleClickToClear(HapticEvent.ClearCache, onClear = model::clearCache))
    PreferenceSwitch(stringResource(R.string.clear_covers), checked = settings.clearCovers) {
        model.settings(settings.copy(clearCovers = it))
    }
    PreferenceSwitch(stringResource(R.string.rebuild_library_covers),
        stringResource(R.string.rebuild_library_covers_hint), settings.rebuildLibraryCovers) {
        model.settings(settings.copy(rebuildLibraryCovers = it))
    }
    CacheLimitSlider(settings.cacheLimitMiB) { model.settings(settings.copy(cacheLimitMiB = it)) }
    PreferenceSwitch(stringResource(R.string.clear_cache_on_removal), checked = settings.clearCacheOnTaskRemoval) {
        model.settings(settings.copy(clearCacheOnTaskRemoval = it))
    }
    SettingsListItem(headlineContent = { Text(stringResource(R.string.clear_reading_history)) }, supportingContent = {
        Text(stringResource(R.string.clear_reading_history_hint))
    },
        modifier = Modifier.doubleClickToClear(HapticEvent.ClearReadingHistory, onClear = model::clearReadingProgress))
    PreferenceSwitch(stringResource(R.string.clear_history_on_removal), checked = settings.clearProgressOnTaskRemoval) {
        model.settings(settings.copy(clearProgressOnTaskRemoval = it))
    }
}

@Composable
private fun CacheLimitSlider(limitMiB: Int, onLimitChanged: (Int) -> Unit) {
    val haptic = hapticFeedback(HapticEvent.CacheLimit)
    val options = CacheLimits.optionsMiB
    var index by remember(limitMiB) { mutableIntStateOf(options.indexOf(limitMiB)) }
    val mib = options[index]
    val label = stringResource(R.string.cache_limit)
    val displayedLimit = when {
        mib == CacheLimits.UNLIMITED_MIB -> stringResource(R.string.cache_limit_unlimited)
        mib < 1024 -> "$mib MiB"
        else -> "${(mib / 1024f).toString().removeSuffix(".0")} GiB"
    }
    SettingsListItem(headlineContent = { Text(label) }, trailingContent = { Text(displayedLimit) })
    val interactionSource = remember { MutableInteractionSource() }
    val thumbSize = DpSize(4.dp, 44.dp)
    val trackHeight = 16.dp
    Slider(
        value = index.toFloat(),
        onValueChange = {
            val next = it.roundToInt()
            if (next != index) {
                index = next
                haptic(
                    if (next == 0 || next == options.lastIndex) {
                        HapticFeedbackConstantsCompat.GESTURE_THRESHOLD_ACTIVATE
                    } else {
                        HapticFeedbackConstantsCompat.SEGMENT_FREQUENT_TICK
                    },
                )
            }
        },
        onValueChangeFinished = { onLimitChanged(options[index]) },
        valueRange = 0f..options.lastIndex.toFloat(),
        steps = options.lastIndex - 1,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .systemGestureExclusion()
            .semantics {
                contentDescription = label
                stateDescription = displayedLimit
            }
            .padding(horizontal = (trackHeight - thumbSize.width) / 2),
        thumb = {
            Box(Modifier.width(thumbSize.width), contentAlignment = Alignment.Center) {
                SliderDefaults.Thumb(interactionSource, thumbSize = thumbSize)
            }
        },
        track = {
            val colors = SliderDefaults.colors()
            Canvas(Modifier.fillMaxWidth().height(trackHeight)) {
                val thumbWidth = thumbSize.width.roundToPx()
                val thumbCenterOffset = thumbWidth / 2f - thumbWidth / 2
                fun tickCenter(tick: Int): Float =
                    (size.width * (tick.toFloat() / options.lastIndex)).roundToInt() + thumbCenterOffset

                val selectedCenter = tickCenter(index)
                val gap = thumbWidth / 2f + 6.dp.toPx()
                val outerCorner = CornerRadius(center.y)
                val innerCorner = CornerRadius(2.dp.toPx())

                fun drawSegment(left: Float, right: Float, color: Color, start: CornerRadius, end: CornerRadius) {
                    if (right > left) {
                        val path = Path().apply {
                            addRoundRect(RoundRect(left, 0f, right, size.height, start, end, end, start))
                        }
                        drawPath(path, color)
                    }
                }

                val direction = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                scale(scaleX = direction, scaleY = 1f) {
                    drawSegment(-outerCorner.x, selectedCenter - gap, colors.activeTrackColor, outerCorner, innerCorner)
                    drawSegment(selectedCenter + gap, size.width + outerCorner.x, colors.inactiveTrackColor, innerCorner, outerCorner)
                    for (tick in options.indices) {
                        val capacity = options[tick]
                        val radius = when {
                            tick == 0 -> 2.5.dp
                            tick == options.lastIndex -> 2.dp
                            capacity in 1024 until CacheLimits.MAX_MIB && capacity.countOneBits() == 1 -> 1.5.dp
                            else -> 0.75.dp
                        }.toPx()
                        val centerX = tickCenter(tick)
                        if (abs(centerX - selectedCenter) <= gap + radius) continue
                        val color = if (tick < index) colors.activeTickColor else colors.inactiveTickColor
                        drawCircle(color, radius, Offset(centerX, center.y))
                    }
                }
            }
        },
    )
}

@Composable
private fun DiagnosticsSettings(
    settings: ReaderSettings,
    model: LibraryViewModel,
    active: Boolean,
    onViewLogs: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var logOperationRunning by remember { mutableStateOf(false) }
    var logRevision by remember { mutableIntStateOf(0) }
    val logSize by produceState(0L, lifecycle, active, settings.logDirectory, settings.diagnosticLog, logRevision) {
        if (active) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            do {
                value = withContext(Dispatchers.IO) { DiagnosticLog.listFiles(context).files.sumOf { it.size } }
                if (settings.diagnosticLog) delay(2_000)
            } while (settings.diagnosticLog)
        }
    }
    fun runLogOperation(errorMessage: Int, operation: () -> Int?) {
        if (logOperationRunning) return
        logOperationRunning = true
        scope.launch {
            try {
                val message = withContext(Dispatchers.IO) { operation() }
                if (message != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
            } finally {
                logOperationRunning = false
                logRevision++
            }
        }
    }
    val logPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.setLogDirectory(uri)
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runLogOperation(R.string.log_export_failed) {
            if (DiagnosticLog.export(context, uri)) R.string.log_exported else R.string.log_export_empty
        }
    }
    PreferenceSwitch(stringResource(R.string.write_diagnostic_logs),
        stringResource(R.string.write_diagnostic_logs_hint), settings.diagnosticLog) {
        model.settings(settings.copy(diagnosticLog = it))
    }
    SettingsListItem(headlineContent = { Text(stringResource(R.string.log_directory)) }, supportingContent = {
        Text(settings.logDirectory?.let(::logDirectoryLabel) ?: stringResource(R.string.log_default_directory))
    },
        trailingContent = {
            val showReset = settings.logDirectory != null
            val duration = settingsTransitionDuration(180)
            AnimatedVisibility(visible = showReset, enter = fadeIn(tween(duration)), exit = fadeOut(tween(duration))) {
                IconButton(onClick = { model.setLogDirectory(null) }, enabled = showReset,
                    modifier = Modifier.width(SettingsActionWidth)) {
                    Icon(
                        painterResource(R.drawable.ic_symbol_refresh), stringResource(R.string.restore_default_directory),
                        Modifier.offset(x = (SettingsActionWidth - SettingsTrailingIconSize) / 2)
                            .size(SettingsTrailingIconSize),
                    )
                }
            }
        },
        modifier = Modifier.clickable { logPicker.launch(null) })
    SettingsListItem(headlineContent = { Text(stringResource(R.string.view_logs)) },
        trailingContent = { Icon(painterResource(R.drawable.ic_symbol_keyboard_arrow_right), null) },
        modifier = Modifier.clickable(enabled = !logOperationRunning, role = Role.Button, onClick = onViewLogs))
    SettingsListItem(headlineContent = { Text(stringResource(R.string.export_logs)) },
        modifier = Modifier.clickable(enabled = !logOperationRunning, role = Role.Button) { exportPicker.launch(null) })
    SettingsListItem(headlineContent = { Text(stringResource(R.string.clear_logs)) },
        supportingContent = { Text(stringResource(R.string.clear_logs_hint)) },
        trailingContent = { Text(Formatter.formatShortFileSize(context, logSize)) },
        modifier = Modifier.doubleClickToClear(HapticEvent.ClearLogs, enabled = !logOperationRunning, role = Role.Button) {
            runLogOperation(R.string.log_clear_failed) {
                check(DiagnosticLog.clear(context))
                null
            }
        })
}

@Composable
private fun Modifier.doubleClickToClear(
    event: HapticEvent,
    enabled: Boolean = true,
    role: Role? = null,
    onClear: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val haptic by rememberUpdatedState(hapticFeedback(event))
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) haptic(HapticFeedbackConstantsCompat.CONTEXT_CLICK)
        }
    }
    return combinedClickable(
        interactionSource = interactionSource,
        indication = LocalIndication.current,
        enabled = enabled,
        role = role,
        onClick = {},
        onDoubleClick = onClear,
    )
}

@Composable
private fun ApplicationSettings(state: LibraryState, model: LibraryViewModel) {
    val settings = state.settings
    val context = LocalContext.current
    val activity = LocalActivity.current
    val transferring by model.transferringSettings.collectAsState()
    val pendingImport by model.settingsImport.collectAsState()
    val canTransfer = !transferring && pendingImport == null
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importSettings(uri)
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.exportSettings(uri)
    }
    var about by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    val download = state.updateDownload
    val updateLabel = when (download) {
        is UpdateDownload.Pending -> if (download.waiting) stringResource(R.string.update_download_waiting) else stringResource(R.string.update_downloading)
        is UpdateDownload.Ready -> stringResource(R.string.update_install)
        is UpdateDownload.Failed -> stringResource(R.string.update_download_retry)
        null -> stringResource(R.string.update_check)
    }
    SettingsListItem(headlineContent = { Text(updateLabel) },
        supportingContent = { Text(download?.let { "ZViewer ${it.version}" } ?: stringResource(R.string.current_version, BuildConfig.VERSION_NAME)) },
        trailingContent = if (state.checkingUpdate || state.downloadingUpdate) {
            {
                val description = stringResource(
                    if (state.downloadingUpdate) R.string.update_download_preparing else R.string.update_checking,
                )
                AppCircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = description }, strokeWidth = 2.dp)
            }
        } else null,
        modifier = Modifier.clickable(
            enabled = !state.checkingUpdate && !state.downloadingUpdate && download !is UpdateDownload.Pending,
            role = Role.Button
        ) {
            if (download is UpdateDownload.Ready) {
                if (activity != null) openUpdateInstaller(activity, model, download)
            } else model.checkForUpdates()
        })
    PreferenceSwitch(stringResource(R.string.update_check_on_start), checked = settings.checkUpdatesOnStartup) {
        model.settings(settings.copy(checkUpdatesOnStartup = it))
    }
    SettingsListItem(
        headlineContent = { Text(stringResource(R.string.settings_import)) },
        modifier = Modifier.clickable(enabled = canTransfer, role = Role.Button) {
            try {
                importPicker.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, R.string.settings_import_error, Toast.LENGTH_LONG).show()
            }
        },
    )
    SettingsListItem(
        headlineContent = { Text(stringResource(R.string.settings_export)) },
        modifier = Modifier.clickable(enabled = canTransfer, role = Role.Button) {
            try {
                exportPicker.launch("ZViewer-settings.json")
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, R.string.settings_export_error, Toast.LENGTH_LONG).show()
            }
        },
    )
    SettingsListItem(headlineContent = { Text(stringResource(R.string.about)) },
        modifier = Modifier.clickable(role = Role.Button) { about = true })
    SettingsListItem(headlineContent = { Text(stringResource(R.string.view_on_github)) },
        modifier = Modifier.clickable(role = Role.Button) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Zo-Live/ZViewer"))
            try {
                context.startActivity(Intent(intent).setPackage("com.github.android"))
            } catch (_: ActivityNotFoundException) {
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.github_open_failed, Toast.LENGTH_SHORT).show()
                }
            }
        })
    if (about) SettingsAlertDialog(onDismissRequest = { about = false }, title = { Text(stringResource(R.string.about_title)) },
        text = { Text(stringResource(R.string.about_description, BuildConfig.VERSION_NAME), Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { about = false }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { about = false; licenses = true }) { Text(stringResource(R.string.open_source_licenses)) } })
    if (licenses) {
        val notices by produceState(stringResource(R.string.loading_licenses)) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                context.assets.open("open_source_notices.txt").bufferedReader().use { it.readText() }
            }
        }
        SettingsAlertDialog(onDismissRequest = { licenses = false }, title = { Text(stringResource(R.string.open_source_licenses)) },
            text = {
                Column(
                    Modifier.height(420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.license_notices_intro), style = MaterialTheme.typography.bodySmall)
                    Text(notices, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { licenses = false }) { Text(stringResource(R.string.close)) } })
    }
}

@Composable
private fun SettingsListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    val headlineColor = settingsContentColor(enabled, LocalContentColor.current)
    val secondaryColor = settingsContentColor(enabled, MaterialTheme.colorScheme.onSurfaceVariant)
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}
        .heightIn(min = if (supportingContent == null) 56.dp else 72.dp).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(SettingsTrailingGap), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CompositionLocalProvider(LocalContentColor provides headlineColor) {
                ProvideTextStyle(MaterialTheme.typography.bodyLarge, headlineContent)
            }
            if (supportingContent != null) {
                Box(Modifier.widthIn(max = SettingsSupportingMaxWidth)) {
                    CompositionLocalProvider(LocalContentColor provides secondaryColor) {
                        ProvideTextStyle(MaterialTheme.typography.bodySmall, supportingContent)
                    }
                }
            }
        }
        if (trailingContent != null) {
            // 短数值也预留与开关同宽的位置，让相邻行的说明文字在同一列内换行。
            Box(Modifier.widthIn(min = SettingsTrailingMinWidth), contentAlignment = Alignment.CenterEnd) {
                CompositionLocalProvider(LocalContentColor provides secondaryColor) {
                    ProvideTextStyle(
                        MaterialTheme.typography.labelMedium.copy(textAlign = TextAlign.End),
                        trailingContent,
                    )
                }
            }
        }
    }
}

@Composable
private fun settingsContentColor(enabled: Boolean, color: Color): Color =
    if (enabled) color else MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledSettingsContentOpacity)

@Composable
private fun <T> PreferenceMenu(
    title: String,
    selectedValue: T,
    options: List<Pair<T, String>>,
    @DrawableRes titleIcon: Int? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    var menuRequested by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) menuRequested = false }
    val expanded = menuRequested && enabled
    val headlineColor = settingsContentColor(enabled, LocalContentColor.current)
    val secondaryColor = settingsContentColor(enabled, MaterialTheme.colorScheme.onSurfaceVariant)
    val haptic = hapticFeedback(HapticEvent.MenuSelection)
    val current = options.firstOrNull { it.first == selectedValue } ?: options.first()
    val action = stringResource(if (expanded) R.string.menu_collapse else R.string.menu_expand)
    val menuState = stringResource(if (expanded) R.string.menu_expanded else R.string.menu_collapsed)
    EndAlignedDropdownMenu(
        expanded = expanded,
        onDismissRequest = { menuRequested = false },
        modifier = Modifier.selectableGroup(),
        anchor = {
            BoxWithConstraints {
                val valueMaxWidth = ((maxWidth - SettingsTrailingGap) / 2).coerceAtLeast(0.dp)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = if (subtitle == null) 56.dp else 72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled, role = Role.Button, onClickLabel = action) { menuRequested = !expanded }
                        .semantics { stateDescription = menuState }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(SettingsTrailingGap),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, color = headlineColor)
                            if (titleIcon != null) Icon(painterResource(titleIcon), null, Modifier.size(18.dp), secondaryColor)
                        }
                        if (subtitle != null) {
                            Text(subtitle, Modifier.widthIn(max = SettingsSupportingMaxWidth),
                                style = MaterialTheme.typography.bodySmall, color = secondaryColor)
                        }
                    }
                    Row(
                        Modifier.widthIn(max = valueMaxWidth),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(current.second, Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyMedium, color = secondaryColor, textAlign = TextAlign.End)
                        Icon(painterResource(if (expanded) R.drawable.ic_symbol_arrow_drop_up else R.drawable.ic_symbol_arrow_drop_down),
                            null, Modifier.size(20.dp), secondaryColor)
                    }
                }
            }
        },
    ) {
        options.forEach { option ->
            val isSelected = option == current
            DropdownMenuItem(
                text = { Text(option.second) },
                onClick = {
                    menuRequested = false
                    if (!isSelected) {
                        haptic(HapticFeedbackConstantsCompat.SEGMENT_TICK)
                        onSelect(option.first)
                    }
                },
                modifier = Modifier.semantics {
                    role = Role.RadioButton
                    selected = isSelected
                },
                trailingIcon = {
                    if (isSelected) Icon(painterResource(R.drawable.ic_symbol_check), null, tint = MaterialTheme.colorScheme.primary)
                },
            )
        }
    }
}

@Composable
internal fun ReaderPreferences(
    settings: ReaderSettings,
    fitExpanded: Boolean,
    onFitExpanded: (Boolean) -> Unit,
    onSettings: (ReaderSettings) -> Unit,
) {
    ReaderFitPreferences(settings, fitExpanded, onFitExpanded, onSettings)
    PreferenceMenu(
        title = stringResource(R.string.page_turn_mode),
        selectedValue = settings.paged,
        options = listOf(false to stringResource(R.string.reading_continuous), true to stringResource(R.string.reading_paged)),
    ) { onSettings(settings.copy(paged = it)) }
    PreferenceMenu(
        title = stringResource(R.string.page_turn_direction),
        selectedValue = settings.vertical,
        options = listOf(true to stringResource(R.string.reading_vertical), false to stringResource(R.string.reading_horizontal)),
    ) { onSettings(settings.copy(vertical = it)) }
    PreferenceSwitch(
        title = stringResource(R.string.horizontal_video_seek),
        checked = settings.horizontalVideoSeek,
        enabled = settings.vertical,
    ) { onSettings(settings.copy(horizontalVideoSeek = it)) }
    PreferenceSwitch(
        title = stringResource(R.string.reader_rtl),
        checked = settings.rightToLeft,
        enabled = !settings.vertical,
    ) { onSettings(settings.copy(rightToLeft = it)) }
    PreferenceSwitch(stringResource(R.string.show_page_number), checked = settings.showPageNumber) {
        onSettings(settings.copy(showPageNumber = it))
    }
    PreferenceSwitch(stringResource(R.string.keep_screen_on), checked = settings.keepScreenOn) { onSettings(settings.copy(keepScreenOn = it)) }
    PreferenceSwitch(stringResource(R.string.volume_key_paging), checked = settings.volumeKeyPaging) { onSettings(settings.copy(volumeKeyPaging = it)) }
    PreferenceSwitch(stringResource(R.string.double_tap_zoom), checked = settings.doubleTapZoom) { onSettings(settings.copy(doubleTapZoom = it)) }
    PreferenceSwitch(stringResource(R.string.reset_progress_on_close), checked = settings.resetProgressOnClose) {
        onSettings(settings.copy(resetProgressOnClose = it))
    }
    PreferenceSwitch(stringResource(R.string.reader_dark), stringResource(R.string.reader_dark_hint), settings.readerDark) { onSettings(settings.copy(readerDark = it)) }
    PreferenceSwitch(stringResource(R.string.loop_single_video), stringResource(R.string.loop_single_video_hint), settings.videoLoopSingle) { onSettings(settings.copy(videoLoopSingle = it)) }
    PreferenceSwitch(stringResource(R.string.video_preview), stringResource(R.string.video_preview_hint), settings.videoPreview) { onSettings(settings.copy(videoPreview = it)) }
    PreferenceSwitch(stringResource(R.string.loop_mode), stringResource(R.string.loop_mode_hint), settings.loopMode) { onSettings(settings.copy(loopMode = it)) }
}

@Composable
private fun ReaderFitPreferences(
    settings: ReaderSettings,
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    onSettings: (ReaderSettings) -> Unit,
) {
    val action = stringResource(if (expanded) R.string.menu_collapse else R.string.menu_expand)
    val state = stringResource(if (expanded) R.string.menu_expanded else R.string.menu_collapsed)
    SettingsListItem(
        headlineContent = { Text(stringResource(R.string.reader_frame_fit)) },
        trailingContent = {
            val arrow = if (expanded) R.drawable.ic_symbol_keyboard_arrow_down else R.drawable.ic_symbol_keyboard_arrow_right
            Icon(painterResource(arrow), null)
        },
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = action) { onExpanded(!expanded) }
            .semantics { stateDescription = state },
    )
    SettingsVisibility(expanded) {
        Column {
            val imageTitle = stringResource(R.string.reader_image_and_pdf)
            val fitScreen = stringResource(R.string.reader_fit_screen)
            val fillScreen = stringResource(R.string.reader_fill_screen)
            if (settings.paged) {
                PreferenceMenu(imageTitle, settings.pagedImageFit, listOf(
                    PagedImageFit.Screen to fitScreen,
                    PagedImageFit.Width to stringResource(R.string.reader_fit_width),
                    PagedImageFit.Height to stringResource(R.string.reader_fit_height),
                ), enabled = expanded) { onSettings(settings.copy(pagedImageFit = it)) }
            } else {
                PreferenceMenu(imageTitle, settings.continuousImageFitScreen, listOf(false to fillScreen, true to fitScreen),
                    enabled = expanded) { onSettings(settings.copy(continuousImageFitScreen = it)) }
            }
            PreferenceMenu(stringResource(R.string.reader_video_fit), settings.videoFillScreen,
                listOf(false to fitScreen, true to fillScreen), enabled = expanded) { onSettings(settings.copy(videoFillScreen = it)) }
        }
    }
}

@Composable
internal fun PreferenceSwitch(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    feedbackOnToggle: Boolean = true,
    preserveAnimationComposition: Boolean = false,
    enabled: Boolean = true,
    indeterminate: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    val switchOnHaptic = hapticFeedback(HapticEvent.SwitchOn)
    val switchOffHaptic = hapticFeedback(HapticEvent.SwitchOff)
    SettingsListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            WithAnimationSpeed(animationSpeed(AnimationEffect.Switch), preserveComposition = preserveAnimationComposition) {
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
                    thumbContent = if (indeterminate) {
                        { HorizontalDivider(Modifier.width(8.dp), thickness = 2.dp, color = MaterialTheme.colorScheme.surfaceContainerHighest) }
                    } else null,
                )
            }
        },
        enabled = enabled,
        modifier = Modifier.triStateToggleable(
            state = if (indeterminate) ToggleableState.Indeterminate else ToggleableState(checked),
            enabled = enabled,
            role = Role.Switch,
        ) {
            val next = !checked
            if (feedbackOnToggle) {
                if (next) switchOnHaptic(HapticFeedbackConstantsCompat.TOGGLE_ON)
                else switchOffHaptic(HapticFeedbackConstantsCompat.TOGGLE_OFF)
            }
            onChange(next)
        },
    )
}

private fun logDirectoryLabel(uri: String): String {
    val raw = Uri.decode(uri.substringAfterLast('/'))
    return raw.substringAfterLast(':').ifBlank { raw }
}
