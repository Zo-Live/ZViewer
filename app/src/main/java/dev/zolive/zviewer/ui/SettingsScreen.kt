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
import androidx.annotation.StringRes
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
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
import dev.zolive.zviewer.data.UpdateDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val SettingsContentInset = 24.dp
private val SettingsSectionIconSize = 20.dp
private val SettingsBackButtonSize = 48.dp
private val SettingsActionWidth = 48.dp
private val SettingsTrailingIconSize = 24.dp
private val SettingsTrailingMinWidth = 52.dp
private val SettingsTrailingGap = 16.dp
private val SettingsSupportingMaxWidth = 480.dp

internal enum class SettingsSection(@StringRes val title: Int, val icon: ImageVector, val tag: String) {
    Appearance(R.string.appearance, Icons.Outlined.Style, "settings-appearance-screen"),
    Preferences(R.string.preferences, Icons.Outlined.Tune, "settings-preferences-screen"),
    Reading(R.string.reading, Icons.Outlined.AutoStories, "settings-reading-screen"),
    LibraryAndStorage(R.string.library_and_storage, Icons.Outlined.FolderOpen, "settings-storage-screen"),
    Diagnostics(R.string.diagnostics, Icons.Outlined.BugReport, "settings-diagnostics-screen"),
    Security(R.string.security_and_privacy, Icons.Outlined.Security, "settings-security-screen"),
    Application(R.string.app, Icons.Outlined.Info, "settings-application-screen"),
}

@Composable
internal fun SettingsScreen(onBack: () -> Unit, onOpen: (SettingsSection) -> Unit) {
    SettingsPage(R.string.settings, R.string.back_to_library, onBack) {
        SettingsSection.entries.forEach { section ->
            SettingsListItem(
                headlineContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            section.icon, null, Modifier.size(SettingsSectionIconSize),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(stringResource(section.title))
                    }
                },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
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
            SettingsSection.Reading -> ReaderPreferences(state.settings, model::settings)
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
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(backLabel))
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
                if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), Color.White)
            }
        }
        IconButton(onClick = { customColor = true }, Modifier.size(44.dp)) { Icon(Icons.Outlined.Palette, stringResource(R.string.custom_theme_color)) }
    }
    if (settings.verifyOnStartup) {
        Spacer(Modifier.height(11.5.dp))
        PreferenceSwitch(stringResource(R.string.verification_follows_theme), checked = settings.verificationFollowsTheme) {
            onSettings(settings.copy(verificationFollowsTheme = it))
        }
    }
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
        titleIcon = Icons.Outlined.Language,
    ) { onSettings(settings.copy(language = it)) }
    PreferenceMenu(
        title = stringResource(R.string.animation_speed),
        selectedValue = settings.animationSpeed,
        options = listOf(
            AnimationSpeed.Moderate to stringResource(R.string.animation_speed_moderate),
            AnimationSpeed.Enhanced to stringResource(R.string.animation_speed_enhanced),
        ),
    ) { onSettings(settings.copy(animationSpeed = it)) }
    if (settings.animationSpeed == AnimationSpeed.Enhanced) {
        InteractionSettingsLink(InteractionSettingsPage.Animation, onOpen)
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
    if (settings.hapticFeedback) {
        InteractionSettingsLink(InteractionSettingsPage.Haptics, onOpen)
    }
    PreferenceSwitch(stringResource(R.string.show_file_type), stringResource(R.string.show_file_type_hint), settings.showFileType) {
        onSettings(settings.copy(showFileType = it))
    }
    PreferenceSwitch(stringResource(R.string.show_favorite_icon), stringResource(R.string.show_favorite_icon_hint), settings.showFavoriteIcon) {
        onSettings(settings.copy(showFavoriteIcon = it))
    }
}

@Composable
private fun InteractionSettingsLink(page: InteractionSettingsPage, onOpen: (InteractionSettingsPage) -> Unit) {
    SettingsListItem(
        headlineContent = { Text(stringResource(page.title)) },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
        modifier = Modifier.clickable(role = Role.Button) { onOpen(page) },
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
        stringResource(R.string.hide_recent_preview), stringResource(R.string.hide_recent_preview_hint),
        settings.hideRecentPreview,
    ) { model.settings(settings.copy(hideRecentPreview = it)) }
    PreferenceSwitch(
        stringResource(R.string.verify_after_background), checked = settings.verifyAfterBackground,
    ) { if (canEnableVerification(it)) model.settings(settings.copy(verifyAfterBackground = it)) }
    if (settings.verifyAfterBackground) {
        PreferenceMenu(
            title = stringResource(R.string.auto_lock_interval),
            subtitle = stringResource(R.string.auto_lock_interval_hint),
            selectedValue = settings.autoLockInterval,
            options = listOf(
                AutoLockInterval.Immediately to stringResource(R.string.auto_lock_immediately),
                AutoLockInterval.FifteenSeconds to stringResource(R.string.auto_lock_fifteen_seconds),
                AutoLockInterval.OneMinute to stringResource(R.string.auto_lock_one_minute),
                AutoLockInterval.FiveMinutes to stringResource(R.string.auto_lock_five_minutes),
            ),
        ) { model.settings(settings.copy(autoLockInterval = it)) }
    }
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
    val haptic = hapticFeedback(HapticEvent.CacheLimit)
    var cacheLimitMiB by remember(settings.cacheLimitMiB) { mutableIntStateOf(settings.cacheLimitMiB) }
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
    SettingsListItem(headlineContent = { Text(stringResource(R.string.cache_limit)) },
        trailingContent = { Text(if (cacheLimitMiB < 1024) "$cacheLimitMiB MiB"
            else "${(cacheLimitMiB / 1024f).toString().removeSuffix(".0")} GiB") })
    val cacheLimitDescription = stringResource(R.string.cache_limit)
    Slider(value = cacheLimitMiB.toFloat(),
        onValueChange = {
            val step = (it / CacheLimits.STEP_MIB).roundToInt() * CacheLimits.STEP_MIB
            if (step != cacheLimitMiB) {
                cacheLimitMiB = step
                haptic(
                    if (step == CacheLimits.MIN_MIB || step == CacheLimits.MAX_MIB) {
                        HapticFeedbackConstantsCompat.GESTURE_THRESHOLD_ACTIVATE
                    } else {
                        HapticFeedbackConstantsCompat.SEGMENT_FREQUENT_TICK
                    },
                )
            }
        },
        onValueChangeFinished = { model.settings(settings.copy(cacheLimitMiB = cacheLimitMiB)) },
        valueRange = CacheLimits.MIN_MIB.toFloat()..CacheLimits.MAX_MIB.toFloat(),
        steps = (CacheLimits.MAX_MIB - CacheLimits.MIN_MIB) / CacheLimits.STEP_MIB - 1,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = cacheLimitDescription })
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
    if (settings.diagnosticLog) {
        SettingsListItem(headlineContent = { Text(stringResource(R.string.log_directory)) }, supportingContent = {
            Text(settings.logDirectory?.let(::logDirectoryLabel) ?: stringResource(R.string.log_default_directory))
        },
            trailingContent = if (settings.logDirectory != null) {
                {
                    IconButton(onClick = { model.setLogDirectory(null) }, modifier = Modifier.width(SettingsActionWidth)) {
                        Icon(
                            Icons.Outlined.Refresh, stringResource(R.string.restore_default_directory),
                            Modifier.offset(x = (SettingsActionWidth - SettingsTrailingIconSize) / 2)
                                .size(SettingsTrailingIconSize),
                        )
                    }
                }
            } else null,
            modifier = Modifier.clickable { logPicker.launch(null) })
    }
    SettingsListItem(headlineContent = { Text(stringResource(R.string.view_logs)) },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
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
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}
        .heightIn(min = if (supportingContent == null) 56.dp else 72.dp).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(SettingsTrailingGap), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ProvideTextStyle(MaterialTheme.typography.bodyLarge, headlineContent)
            if (supportingContent != null) {
                Box(Modifier.widthIn(max = SettingsSupportingMaxWidth)) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                        ProvideTextStyle(MaterialTheme.typography.bodySmall, supportingContent)
                    }
                }
            }
        }
        if (trailingContent != null) {
            // 短数值也预留与开关同宽的位置，让相邻行的说明文字在同一列内换行。
            Box(Modifier.widthIn(min = SettingsTrailingMinWidth), contentAlignment = Alignment.CenterEnd) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
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
private fun <T> PreferenceMenu(
    title: String,
    selectedValue: T,
    options: List<Pair<T, String>>,
    titleIcon: ImageVector? = null,
    subtitle: String? = null,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val haptic = hapticFeedback(HapticEvent.MenuSelection)
    val current = options.firstOrNull { it.first == selectedValue } ?: options.first()
    val action = stringResource(if (expanded) R.string.menu_collapse else R.string.menu_expand)
    val menuState = stringResource(if (expanded) R.string.menu_expanded else R.string.menu_collapsed)
    EndAlignedDropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = Modifier.selectableGroup(),
        anchor = {
            BoxWithConstraints {
                val valueMaxWidth = ((maxWidth - SettingsTrailingGap) / 2).coerceAtLeast(0.dp)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = if (subtitle == null) 56.dp else 72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClickLabel = action) { expanded = !expanded }
                        .semantics { stateDescription = menuState }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(SettingsTrailingGap),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge)
                            if (titleIcon != null) Icon(titleIcon, null, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (subtitle != null) {
                            Text(subtitle, Modifier.widthIn(max = SettingsSupportingMaxWidth),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(
                        Modifier.widthIn(max = valueMaxWidth),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val tint = MaterialTheme.colorScheme.onSurfaceVariant
                        Text(current.second, Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyMedium, color = tint, textAlign = TextAlign.End)
                        Icon(if (expanded) Icons.Outlined.ArrowDropUp else Icons.Outlined.ArrowDropDown,
                            null, Modifier.size(20.dp), tint)
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
                    expanded = false
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
                    if (isSelected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
                },
            )
        }
    }
}

@Composable
fun ReaderPreferences(settings: ReaderSettings, onSettings: (ReaderSettings) -> Unit) {
    PreferenceMenu(
        title = stringResource(R.string.reading_mode),
        selectedValue = settings.vertical,
        options = listOf(true to stringResource(R.string.reading_vertical), false to stringResource(R.string.reading_horizontal)),
    ) { onSettings(settings.copy(vertical = it)) }
    if (settings.vertical) {
        PreferenceSwitch(stringResource(R.string.horizontal_video_seek), checked = settings.horizontalVideoSeek) {
            onSettings(settings.copy(horizontalVideoSeek = it))
        }
    } else {
        PreferenceSwitch(stringResource(R.string.reader_rtl), checked = settings.rightToLeft) { onSettings(settings.copy(rightToLeft = it)) }
    }
    PreferenceSwitch(stringResource(R.string.keep_screen_on), checked = settings.keepScreenOn) { onSettings(settings.copy(keepScreenOn = it)) }
    PreferenceSwitch(stringResource(R.string.volume_key_paging), checked = settings.volumeKeyPaging) { onSettings(settings.copy(volumeKeyPaging = it)) }
    PreferenceSwitch(stringResource(R.string.double_tap_zoom), checked = settings.doubleTapZoom) { onSettings(settings.copy(doubleTapZoom = it)) }
    PreferenceSwitch(stringResource(R.string.reader_dark), stringResource(R.string.reader_dark_hint), settings.readerDark) { onSettings(settings.copy(readerDark = it)) }
    PreferenceSwitch(stringResource(R.string.loop_single_video), stringResource(R.string.loop_single_video_hint), settings.videoLoopSingle) { onSettings(settings.copy(videoLoopSingle = it)) }
    PreferenceSwitch(stringResource(R.string.video_preview), stringResource(R.string.video_preview_hint), settings.videoPreview) { onSettings(settings.copy(videoPreview = it)) }
    PreferenceSwitch(stringResource(R.string.loop_mode), stringResource(R.string.loop_mode_hint), settings.loopMode) { onSettings(settings.copy(loopMode = it)) }
}

@Composable
internal fun PreferenceSwitch(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    feedbackOnToggle: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val haptic = hapticFeedback(HapticEvent.Switch)
    SettingsListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            WithAnimationSpeed(animationSpeed(AnimationEffect.Switch)) {
                Switch(checked, onCheckedChange = null)
            }
        },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch) {
            if (feedbackOnToggle) {
                haptic(if (it) HapticFeedbackConstantsCompat.TOGGLE_ON else HapticFeedbackConstantsCompat.TOGGLE_OFF)
            }
            onChange(it)
        },
    )
}

private fun logDirectoryLabel(uri: String): String {
    val raw = Uri.decode(uri.substringAfterLast('/'))
    return raw.substringAfterLast(':').ifBlank { raw }
}
