@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.zolive.zviewer.ui

import android.graphics.Color as AndroidColor
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.zolive.zviewer.BuildConfig
import dev.zolive.zviewer.LibraryState
import dev.zolive.zviewer.LibraryViewModel
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.AppLanguage
import dev.zolive.zviewer.data.ReaderSettings
import dev.zolive.zviewer.data.CacheLimits
import dev.zolive.zviewer.data.UpdateDownload
import kotlin.math.roundToInt

private val SettingsContentInset = 24.dp
private val SettingsSectionIconSize = 20.dp

@Composable
fun SettingsScreen(
    state: LibraryState,
    model: LibraryViewModel,
    onBack: () -> Unit,
    onChooseLibrary: () -> Unit,
    onSwipeStart: () -> Unit = {},
    onSwipe: (Float) -> Unit = {},
    onSwipeEnd: () -> Unit = {},
    onSwipeCancel: () -> Unit = {}
) {
    val settings = state.settings
    var customColor by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    var cacheLimitMiB by remember(settings.cacheLimitMiB) { mutableIntStateOf(settings.cacheLimitMiB) }
    val context = LocalContext.current
    val activity = LocalActivity.current
    val latestSwipeStart by rememberUpdatedState(onSwipeStart)
    val latestSwipe by rememberUpdatedState(onSwipe)
    val latestSwipeEnd by rememberUpdatedState(onSwipeEnd)
    val latestSwipeCancel by rememberUpdatedState(onSwipeCancel)
    val cacheSize by produceState(0L, state.cacheRevision) { value = model.repository.cacheSize() }
    val logPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.setLogDirectory(uri)
    }
    Scaffold(modifier = Modifier.pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { latestSwipeStart() },
            onHorizontalDrag = { change, dragAmount ->
                latestSwipe(dragAmount)
                change.consume()
            },
            onDragEnd = { latestSwipeEnd() },
            onDragCancel = { latestSwipeCancel() }
        )
    }, topBar = {
        // 顶栏默认边距 4 dp，加上 48 dp 点击区的一半，使返回箭头与分区图标中心对齐。
        val topBarInset = SettingsContentInset + SettingsSectionIconSize / 2 - 28.dp
        CenterAlignedTopAppBar(modifier = Modifier.padding(horizontal = topBarInset),
            title = { Text(stringResource(R.string.settings)) }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back_to_library)) }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = SettingsContentInset),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsSectionTitle(stringResource(R.string.appearance), Icons.Outlined.Style, topPadding = 8.dp)
            SettingsCard {
                PreferenceMenu(
                    title = stringResource(R.string.app_theme),
                    selectedValue = settings.theme,
                    options = listOf(
                        "system" to stringResource(R.string.theme_system),
                        "light" to stringResource(R.string.theme_light),
                        "dark" to stringResource(R.string.theme_dark),
                    ),
                ) { model.settings(settings.copy(theme = it)) }
                Text(stringResource(R.string.theme_color), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    val colors = listOf(0xFF49684D to stringResource(R.string.color_moss), 0xFF476A8A to stringResource(R.string.color_mist), 0xFF79618C to stringResource(R.string.color_wisteria), 0xFF9A5357 to stringResource(R.string.color_rose), 0xFF8B683E to stringResource(R.string.color_amber))
                    colors.forEach { (color, name) ->
                        val description = stringResource(R.string.theme_color_description, name)
                        Box(Modifier.size(44.dp).semantics { contentDescription = description }
                            .selectable(selected = settings.accent == color, role = Role.RadioButton) {
                                model.settings(settings.copy(accent = color))
                            }
                            .padding(4.dp).background(Color(color), CircleShape)
                            .then(if (settings.accent == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
                            contentAlignment = Alignment.Center) {
                            if (settings.accent == color) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), Color.White)
                        }
                    }
                    IconButton(onClick = { customColor = true }, Modifier.size(44.dp)) { Icon(Icons.Outlined.Palette, stringResource(R.string.custom_theme_color)) }
                }
            }
            SettingsSectionTitle(stringResource(R.string.preferences), Icons.Outlined.Tune)
            SettingsCard {
                PreferenceMenu(
                    title = stringResource(R.string.language),
                    selectedValue = settings.language,
                    options = AppLanguage.entries.map { it to it.endonym },
                    titleIcon = Icons.Outlined.Language,
                ) { model.settings(settings.copy(language = it)) }
                PreferenceMenu(
                    title = stringResource(R.string.grid_columns),
                    selectedValue = settings.gridColumns,
                    options = (2..5).map { it to it.toString() },
                ) { model.settings(settings.copy(gridColumns = it)) }
                PreferenceSwitch(stringResource(R.string.show_file_type), stringResource(R.string.show_file_type_hint), settings.showFileType) {
                    model.settings(settings.copy(showFileType = it))
                }
                PreferenceSwitch(stringResource(R.string.show_favorite_icon), stringResource(R.string.show_favorite_icon_hint), settings.showFavoriteIcon) {
                    model.settings(settings.copy(showFavoriteIcon = it))
                }
            }
            SettingsSectionTitle(stringResource(R.string.reading), Icons.Outlined.AutoStories)
            SettingsCard { ReaderPreferences(settings, model::settings) }
            SettingsSectionTitle(stringResource(R.string.library_and_storage), Icons.Outlined.FolderOpen)
            SettingsCard {
                SettingsListItem(headlineContent = { Text(stringResource(R.string.library_directory)) }, supportingContent = {
                    Text(state.treeUri?.let { android.net.Uri.decode(it.substringAfterLast('/')) } ?: stringResource(R.string.no_directory_selected))
                },
                    modifier = Modifier.clickable(onClick = onChooseLibrary))
                SettingsListItem(headlineContent = { Text(stringResource(R.string.current_library_cache_only)) },
                    supportingContent = { Text(stringResource(R.string.current_library_cache_only_hint)) },
                    trailingContent = { Switch(checked = settings.currentLibraryCacheOnly, onCheckedChange = null) },
                    modifier = Modifier.toggleable(value = settings.currentLibraryCacheOnly, role = Role.Switch) {
                        model.settings(settings.copy(currentLibraryCacheOnly = it))
                    })
                SettingsListItem(headlineContent = { Text(stringResource(R.string.clear_reading_cache)) }, supportingContent = {
                    Text(stringResource(
                        if (settings.clearCovers) R.string.clear_reading_cache_with_covers_hint else R.string.clear_reading_cache_hint,
                        Formatter.formatShortFileSize(context, cacheSize),
                    ))
                },
                    modifier = Modifier.combinedClickable(onClick = {}, onDoubleClick = model::clearCache))
                SettingsListItem(headlineContent = { Text(stringResource(R.string.clear_covers)) },
                    trailingContent = { Switch(checked = settings.clearCovers, onCheckedChange = null) },
                    modifier = Modifier.toggleable(value = settings.clearCovers, role = Role.Switch) {
                        model.settings(settings.copy(clearCovers = it))
                    })
                SettingsListItem(headlineContent = { Text(stringResource(R.string.rebuild_library_covers)) },
                    supportingContent = { Text(stringResource(R.string.rebuild_library_covers_hint)) },
                    trailingContent = { Switch(checked = settings.rebuildLibraryCovers, onCheckedChange = null) },
                    modifier = Modifier.toggleable(value = settings.rebuildLibraryCovers, role = Role.Switch) {
                        model.settings(settings.copy(rebuildLibraryCovers = it))
                    })
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
            SettingsSectionTitle(stringResource(R.string.diagnostics), Icons.Outlined.BugReport)
            SettingsCard {
                SettingsListItem(headlineContent = { Text(stringResource(R.string.write_diagnostic_logs)) }, supportingContent = {
                    Text(stringResource(R.string.write_diagnostic_logs_hint))
                },
                    trailingContent = {
                        Switch(checked = settings.diagnosticLog, onCheckedChange = null)
                    },
                    modifier = Modifier.toggleable(value = settings.diagnosticLog, role = Role.Switch) {
                        model.settings(settings.copy(diagnosticLog = it))
                    })
                if (settings.diagnosticLog) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    SettingsListItem(headlineContent = { Text(stringResource(R.string.log_directory)) }, supportingContent = {
                        Text(settings.logDirectory?.let(::logDirectoryLabel) ?: stringResource(R.string.log_default_directory))
                    },
                        modifier = Modifier.clickable { logPicker.launch(null) })
                    if (settings.logDirectory != null) {
                        TextButton(onClick = { model.setLogDirectory(null) }, Modifier.align(Alignment.End).padding(bottom = 4.dp)) {
                            Text(stringResource(R.string.restore_default_directory))
                        }
                    }
                    Text(stringResource(R.string.log_filename_hint),
                        Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SettingsSectionTitle(stringResource(R.string.app), Icons.Outlined.Info)
            SettingsCard {
                val download = state.updateDownload
                val updateLabel = when (download) {
                    is UpdateDownload.Pending -> if (download.waiting) stringResource(R.string.update_download_waiting) else stringResource(R.string.update_downloading)
                    is UpdateDownload.Ready -> stringResource(R.string.update_install)
                    is UpdateDownload.Failed -> stringResource(R.string.update_download_retry)
                    null -> stringResource(R.string.update_check)
                }
                SettingsListItem(headlineContent = { Text(updateLabel) },
                    supportingContent = { Text(download?.let { "ZViewer ${it.version}" } ?: stringResource(R.string.current_version, BuildConfig.VERSION_NAME)) },
                    trailingContent = {
                        if (state.checkingUpdate || state.downloadingUpdate) {
                            val description = stringResource(
                                if (state.downloadingUpdate) R.string.update_download_preparing else R.string.update_checking,
                            )
                            CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = description }, strokeWidth = 2.dp)
                        }
                    },
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
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    if (customColor) {
        var value by remember { mutableStateOf("%06X".format(settings.accent and 0xFFFFFF)) }
        val valid = value.matches(Regex("[0-9a-fA-F]{6}"))
        AlertDialog(onDismissRequest = { customColor = false }, title = { Text(stringResource(R.string.custom_theme_color)) }, text = {
            Column {
                Text(stringResource(R.string.color_hex_hint))
                OutlinedTextField(value, { value = it.removePrefix("#").take(6) }, Modifier.padding(top = 16.dp),
                    label = { Text(stringResource(R.string.color_value)) }, prefix = { Text("#") }, singleLine = true, isError = !valid)
            }
        }, confirmButton = { TextButton(enabled = valid, onClick = {
            model.settings(settings.copy(accent = AndroidColor.parseColor("#$value").toLong() and 0xFFFFFFFF)); customColor = false
        }) { Text(stringResource(R.string.apply)) } }, dismissButton = { TextButton(onClick = { customColor = false }) { Text(stringResource(R.string.cancel)) } })
    }
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text(stringResource(R.string.about_title)) },
        text = { Text(stringResource(R.string.about_description, BuildConfig.VERSION_NAME), Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { about = false }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { about = false; licenses = true }) { Text(stringResource(R.string.open_source_licenses)) } })
    if (licenses) {
        val notices by produceState(stringResource(R.string.loading_licenses)) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                context.assets.open("open_source_notices.txt").bufferedReader().use { it.readText() }
            }
        }
        AlertDialog(onDismissRequest = { licenses = false }, title = { Text(stringResource(R.string.open_source_licenses)) },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
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
private fun SettingsSectionTitle(title: String, icon: ImageVector, topPadding: Dp = 12.dp) {
    Row(Modifier.padding(top = topPadding), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(SettingsSectionIconSize), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SettingsListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    // 水平留白由 SettingsCard 统一提供，避免列表项再叠加默认的 16 dp。
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}
        .heightIn(min = if (supportingContent == null) 56.dp else 72.dp).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ProvideTextStyle(MaterialTheme.typography.bodyLarge, headlineContent)
            if (supportingContent != null) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium, supportingContent)
                }
            }
        }
        if (trailingContent != null) {
            ProvideTextStyle(MaterialTheme.typography.labelSmall, trailingContent)
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), content = content)
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
                val spacing = 12.dp
                val valueMaxWidth = ((maxWidth - spacing) / 2).coerceAtLeast(0.dp)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClickLabel = action) { expanded = !expanded }
                        .semantics { stateDescription = menuState }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
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
                            style = MaterialTheme.typography.bodyMedium, color = tint)
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
private fun PreferenceSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth()
        .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
        .padding(top = 18.dp)
        .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null)
    }
}

private fun logDirectoryLabel(uri: String): String {
    val raw = Uri.decode(uri.substringAfterLast('/'))
    return raw.substringAfterLast(':').ifBlank { raw }
}
