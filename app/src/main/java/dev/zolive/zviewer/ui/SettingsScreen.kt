@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.zolive.zviewer.ui

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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.zolive.zviewer.BuildConfig
import dev.zolive.zviewer.LibraryState
import dev.zolive.zviewer.LibraryViewModel
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.AppLanguage
import dev.zolive.zviewer.data.ReaderSettings
import dev.zolive.zviewer.data.CacheLimits
import dev.zolive.zviewer.data.DiagnosticLog
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
private val SettingsTrailingMinWidth = 52.dp
private val SettingsTrailingGap = 16.dp
private val SettingsSupportingMaxWidth = 480.dp

internal enum class SettingsSection(@StringRes val title: Int, val icon: ImageVector, val tag: String) {
    Appearance(R.string.appearance, Icons.Outlined.Style, "settings-appearance-screen"),
    Preferences(R.string.preferences, Icons.Outlined.Tune, "settings-preferences-screen"),
    Reading(R.string.reading, Icons.Outlined.AutoStories, "settings-reading-screen"),
    LibraryAndStorage(R.string.library_and_storage, Icons.Outlined.FolderOpen, "settings-storage-screen"),
    Diagnostics(R.string.diagnostics, Icons.Outlined.BugReport, "settings-diagnostics-screen"),
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
) {
    SettingsPage(section.title, R.string.back_to_settings, onBack) {
        when (section) {
            SettingsSection.Appearance -> AppearanceSettings(state.settings, model::settings)
            SettingsSection.Preferences -> AppPreferences(state.settings, model::settings)
            SettingsSection.Reading -> ReaderPreferences(state.settings, model::settings)
            SettingsSection.LibraryAndStorage -> LibraryStorageSettings(state, model, onChooseLibrary)
            SettingsSection.Diagnostics -> DiagnosticsSettings(state.settings, model, active, onViewLogs)
            SettingsSection.Application -> ApplicationSettings(state, model)
        }
    }
}

@Composable
private fun SettingsPage(
    @StringRes title: Int,
    @StringRes backLabel: Int,
    onBack: () -> Unit,
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
    PreferenceSwitch(
        title = stringResource(R.string.dynamic_color),
        checked = useDynamicColor,
        enabled = supportsDynamicColor,
    ) { onSettings(settings.copy(dynamicColor = it)) }
    Text(stringResource(R.string.theme_color), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge)
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val colors = listOf(0xFF49684D to stringResource(R.string.color_moss), 0xFF476A8A to stringResource(R.string.color_mist), 0xFF79618C to stringResource(R.string.color_wisteria), 0xFF9A5357 to stringResource(R.string.color_rose), 0xFF8B683E to stringResource(R.string.color_amber))
        colors.forEach { (color, name) ->
            val description = stringResource(R.string.theme_color_description, name)
            val selected = activeAccent == color
            Box(Modifier.size(44.dp).semantics { contentDescription = description }
                .selectable(selected = selected, role = Role.RadioButton) {
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
private fun AppPreferences(settings: ReaderSettings, onSettings: (ReaderSettings) -> Unit) {
    PreferenceMenu(
        title = stringResource(R.string.language),
        selectedValue = settings.language,
        options = AppLanguage.entries.map { it to it.endonym },
        titleIcon = Icons.Outlined.Language,
    ) { onSettings(settings.copy(language = it)) }
    PreferenceSwitch(stringResource(R.string.show_file_type), stringResource(R.string.show_file_type_hint), settings.showFileType) {
        onSettings(settings.copy(showFileType = it))
    }
    PreferenceSwitch(stringResource(R.string.show_favorite_icon), stringResource(R.string.show_favorite_icon_hint), settings.showFavoriteIcon) {
        onSettings(settings.copy(showFavoriteIcon = it))
    }
}

@Composable
private fun LibraryStorageSettings(state: LibraryState, model: LibraryViewModel, onChooseLibrary: () -> Unit) {
    val settings = state.settings
    val context = LocalContext.current
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
        modifier = Modifier.combinedClickable(onClick = {}, onDoubleClick = model::clearCache))
    SettingsListItem(headlineContent = { Text(stringResource(R.string.clear_covers)) },
        trailingContent = { Switch(checked = settings.clearCovers, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = settings.clearCovers, role = Role.Switch) {
            model.settings(settings.copy(clearCovers = it))
        })
    PreferenceSwitch(stringResource(R.string.rebuild_library_covers),
        stringResource(R.string.rebuild_library_covers_hint), settings.rebuildLibraryCovers) {
        model.settings(settings.copy(rebuildLibraryCovers = it))
    }
    SettingsListItem(headlineContent = { Text(stringResource(R.string.cache_limit)) },
        trailingContent = { Text(if (cacheLimitMiB < 1024) "$cacheLimitMiB MiB"
            else "${(cacheLimitMiB / 1024f).toString().removeSuffix(".0")} GiB") })
    val cacheLimitDescription = stringResource(R.string.cache_limit)
    Slider(value = cacheLimitMiB.toFloat(),
        onValueChange = { cacheLimitMiB = (it / CacheLimits.STEP_MIB).roundToInt() * CacheLimits.STEP_MIB },
        onValueChangeFinished = { model.settings(settings.copy(cacheLimitMiB = cacheLimitMiB)) },
        valueRange = CacheLimits.MIN_MIB.toFloat()..CacheLimits.MAX_MIB.toFloat(),
        steps = (CacheLimits.MAX_MIB - CacheLimits.MIN_MIB) / CacheLimits.STEP_MIB - 1,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = cacheLimitDescription })
    SettingsListItem(headlineContent = { Text(stringResource(R.string.clear_cache_on_removal)) },
        trailingContent = { Switch(checked = settings.clearCacheOnTaskRemoval, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = settings.clearCacheOnTaskRemoval, role = Role.Switch) {
            model.settings(settings.copy(clearCacheOnTaskRemoval = it))
        })
    SettingsListItem(headlineContent = { Text(stringResource(R.string.clear_reading_history)) }, supportingContent = {
        Text(stringResource(R.string.clear_reading_history_hint))
    },
        modifier = Modifier.combinedClickable(onClick = {}, onDoubleClick = model::clearReadingProgress))
    SettingsListItem(headlineContent = { Text(stringResource(R.string.clear_history_on_removal)) },
        trailingContent = { Switch(checked = settings.clearProgressOnTaskRemoval, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = settings.clearProgressOnTaskRemoval, role = Role.Switch) {
            model.settings(settings.copy(clearProgressOnTaskRemoval = it))
        })
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
    fun runLogOperation(errorMessage: Int, successMessage: Int? = null, operation: () -> Unit) {
        if (logOperationRunning) return
        logOperationRunning = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { operation() }
                if (successMessage != null) Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show()
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
        if (uri != null) runLogOperation(R.string.log_export_failed, R.string.log_exported) {
            DiagnosticLog.export(context, uri)
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
                        Icon(Icons.Outlined.Refresh, stringResource(R.string.restore_default_directory))
                    }
                }
            } else null,
            modifier = Modifier.clickable { logPicker.launch(null) })
    }
    SettingsListItem(headlineContent = { Text(stringResource(R.string.view_logs)) },
        trailingContent = {
            Box(Modifier.width(SettingsActionWidth), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
            }
        },
        modifier = Modifier.clickable(enabled = !logOperationRunning, role = Role.Button, onClick = onViewLogs))
    SettingsListItem(headlineContent = { Text(stringResource(R.string.export_logs)) },
        modifier = Modifier.clickable(enabled = !logOperationRunning, role = Role.Button) { exportPicker.launch(null) })
    SettingsListItem(headlineContent = { Text(stringResource(R.string.clear_logs)) },
        supportingContent = { Text(stringResource(R.string.clear_logs_hint)) },
        trailingContent = { Text(Formatter.formatShortFileSize(context, logSize)) },
        modifier = Modifier.combinedClickable(
            enabled = !logOperationRunning,
            role = Role.Button,
            onClick = {},
            onDoubleClick = {
                runLogOperation(R.string.log_clear_failed) { check(DiagnosticLog.clear(context)) }
            },
        ))
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
                CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = description }, strokeWidth = 2.dp)
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
    SettingsListItem(headlineContent = { Text(stringResource(R.string.update_check_on_start)) },
        trailingContent = { Switch(checked = settings.checkUpdatesOnStartup, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = settings.checkUpdatesOnStartup, role = Role.Switch) {
            model.settings(settings.copy(checkUpdatesOnStartup = it))
        })
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
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
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
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClickLabel = action) { expanded = !expanded }
                        .semantics { stateDescription = menuState }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(SettingsTrailingGap),
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge)
                        if (titleIcon != null) Icon(titleIcon, null, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
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
                    if (!isSelected) onSelect(option.first)
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
    PreferenceSwitch(stringResource(R.string.reader_dark), stringResource(R.string.reader_dark_hint), settings.readerDark) { onSettings(settings.copy(readerDark = it)) }
    PreferenceSwitch(stringResource(R.string.reader_rtl), stringResource(R.string.reader_rtl_hint), settings.rightToLeft) { onSettings(settings.copy(rightToLeft = it)) }
    PreferenceSwitch(stringResource(R.string.keep_screen_on), stringResource(R.string.keep_screen_on_hint), settings.keepScreenOn) { onSettings(settings.copy(keepScreenOn = it)) }
    PreferenceSwitch(stringResource(R.string.loop_single_video), stringResource(R.string.loop_single_video_hint), settings.videoLoopSingle) { onSettings(settings.copy(videoLoopSingle = it)) }
    PreferenceSwitch(stringResource(R.string.video_preview), stringResource(R.string.video_preview_hint), settings.videoPreview) { onSettings(settings.copy(videoPreview = it)) }
    PreferenceSwitch(stringResource(R.string.loop_mode), stringResource(R.string.loop_mode_hint), settings.loopMode) { onSettings(settings.copy(loopMode = it)) }
}

@Composable
private fun PreferenceSwitch(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    SettingsListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

private fun logDirectoryLabel(uri: String): String {
    val raw = Uri.decode(uri.substringAfterLast('/'))
    return raw.substringAfterLast(':').ifBlank { raw }
}
