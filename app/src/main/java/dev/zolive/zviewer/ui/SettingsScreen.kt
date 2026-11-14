@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.zolive.zviewer.ui

import android.graphics.Color as AndroidColor
import android.net.Uri
import android.text.format.Formatter
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.zolive.zviewer.BuildConfig
import dev.zolive.zviewer.LibraryState
import dev.zolive.zviewer.LibraryViewModel
import dev.zolive.zviewer.data.ReaderSettings

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
    val context = LocalContext.current
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
    }, topBar = { TopAppBar(title = { Text("设置") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回书库") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("外观", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            SettingsCard {
                Text("应用主题", style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEachIndexed { index, (value, label) ->
                        SegmentedButton(selected = settings.theme == value, onClick = { model.settings(settings.copy(theme = value)) },
                            shape = SegmentedButtonDefaults.itemShape(index, 3), icon = {}) { Text(label) }
                    }
                }
                Text("主题颜色", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    val colors = listOf(0xFF49684D to "苔绿", 0xFF476A8A to "雾蓝", 0xFF79618C to "藤紫", 0xFF9A5357 to "蔷薇", 0xFF8B683E to "琥珀")
                    colors.forEach { (color, name) ->
                        Box(Modifier.size(44.dp).semantics { contentDescription = "主题颜色：$name" }
                            .clickable { model.settings(settings.copy(accent = color)) }
                            .padding(4.dp).background(Color(color), CircleShape)
                            .then(if (settings.accent == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
                            contentAlignment = Alignment.Center) {
                            if (settings.accent == color) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), Color.White)
                        }
                    }
                    IconButton(onClick = { customColor = true }, Modifier.size(44.dp)) { Icon(Icons.Outlined.Palette, "自定义主题颜色") }
                }
            }
            Text("偏好", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            SettingsCard {
                Text("每行封面数", style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    (2..5).forEachIndexed { index, count ->
                        SegmentedButton(selected = settings.gridColumns == count,
                            onClick = { model.settings(settings.copy(gridColumns = count)) },
                            shape = SegmentedButtonDefaults.itemShape(index, 4), icon = {}) { Text("$count") }
                    }
                }
                PreferenceSwitch("显示文件类型", "在封面左上角显示漫画格式", settings.showFileType) {
                    model.settings(settings.copy(showFileType = it))
                }
                PreferenceSwitch("显示收藏图标", "在封面右下角显示收藏按钮", settings.showFavoriteIcon) {
                    model.settings(settings.copy(showFavoriteIcon = it))
                }
            }
            Text("阅读", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            SettingsCard { ReaderPreferences(settings, model::settings) }
            Text("书库与存储", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            SettingsCard {
                ListItem(headlineContent = { Text("书库目录") }, supportingContent = {
                    Text(state.treeUri?.let { android.net.Uri.decode(it.substringAfterLast('/')) } ?: "尚未选择")
                }, leadingContent = { Icon(Icons.Outlined.FolderOpen, null) },
                    modifier = Modifier.clickable(onClick = onChooseLibrary), colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                ListItem(headlineContent = { Text("清理阅读缓存") }, supportingContent = {
                    Text("${Formatter.formatShortFileSize(context, cacheSize)} · 双击清除临时解压文件和封面")
                }, leadingContent = { Icon(Icons.Outlined.CleaningServices, null) },
                    modifier = Modifier.combinedClickable(onClick = {}, onDoubleClick = model::clearCache),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                ListItem(headlineContent = { Text("清除阅读记录") }, supportingContent = {
                    Text("双击清除所有漫画的页码和视频播放进度")
                }, leadingContent = { Icon(Icons.Outlined.History, null) },
                    modifier = Modifier.combinedClickable(onClick = {}, onDoubleClick = model::clearReadingProgress),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
            Text("诊断", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            SettingsCard {
                ListItem(headlineContent = { Text("输出诊断日志") }, supportingContent = {
                    Text("记录应用运行、视频切换、播放错误与崩溃信息")
                }, leadingContent = { Icon(Icons.Outlined.BugReport, null) },
                    trailingContent = {
                        Switch(checked = settings.diagnosticLog,
                            onCheckedChange = { model.settings(settings.copy(diagnosticLog = it)) })
                    },
                    modifier = Modifier.clickable { model.settings(settings.copy(diagnosticLog = !settings.diagnosticLog)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                if (settings.diagnosticLog) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    ListItem(headlineContent = { Text("日志目录") }, supportingContent = {
                        Text(settings.logDirectory?.let(::logDirectoryLabel) ?: "默认：应用专属目录 logs/")
                    }, leadingContent = { Icon(Icons.Outlined.FolderOpen, null) },
                        modifier = Modifier.clickable { logPicker.launch(null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                    if (settings.logDirectory != null) {
                        TextButton(onClick = { model.setLogDirectory(null) }, Modifier.align(Alignment.End).padding(bottom = 4.dp)) {
                            Text("恢复默认目录")
                        }
                    }
                    Text("日志写入所选目录的 zviewer-日期-时间.log（部分文件管理器会补 .txt 后缀）",
                        Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = { about = true }, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 32.dp)) { Text("关于 ZViewer ${BuildConfig.VERSION_NAME}") }
        }
    }
    if (customColor) {
        var value by remember { mutableStateOf("%06X".format(settings.accent and 0xFFFFFF)) }
        val valid = value.matches(Regex("[0-9a-fA-F]{6}"))
        AlertDialog(onDismissRequest = { customColor = false }, title = { Text("自定义主题颜色") }, text = {
            Column {
                Text("输入六位十六进制色值，界面将自动生成对应的明暗配色")
                OutlinedTextField(value, { value = it.removePrefix("#").take(6) }, Modifier.padding(top = 16.dp),
                    label = { Text("颜色值") }, prefix = { Text("#") }, singleLine = true, isError = !valid)
            }
        }, confirmButton = { TextButton(enabled = valid, onClick = {
            model.settings(settings.copy(accent = AndroidColor.parseColor("#$value").toLong() and 0xFFFFFFFF)); customColor = false
        }) { Text("应用") } }, dismissButton = { TextButton(onClick = { customColor = false }) { Text("取消") } })
    }
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("ZViewer · 本地漫画阅读器") },
        text = { Text("版本 ${BuildConfig.VERSION_NAME}\n\n支持 ZIP / CBZ、RAR / CBR、7Z、PDF、图片文件夹与常见视频格式。支持 APNG、动态 WebP、GIF、AVIF 及常用静态图片。\n\n全部阅读数据仅保存在本机，不联网、不上传文件。密码保护、分卷压缩包及 DRM 文件请先自行转换。\n\n开源组件：AndroidX / Compose（Apache 2.0）、Media3（Apache 2.0）、APNG4Android（Apache 2.0）、libarchive（BSD）、libavif（BSD）。\n\nHEIF / HEIC 的可解码范围取决于设备系统解码器。") },
        confirmButton = { TextButton(onClick = { about = false }) { Text("知道了") } },
        dismissButton = { TextButton(onClick = { about = false; licenses = true }) { Text("开源许可") } })
    if (licenses) {
        val notices by produceState("正在读取许可文本…") {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                context.assets.open("open_source_notices.txt").bufferedReader().use { it.readText() }
            }
        }
        AlertDialog(onDismissRequest = { licenses = false }, title = { Text("开源许可") },
            text = { Text(notices, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { licenses = false }) { Text("关闭") } })
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), content = content)
    }
}

@Composable
fun ReaderPreferences(settings: ReaderSettings, onSettings: (ReaderSettings) -> Unit) {
    Text("阅读模式", style = MaterialTheme.typography.titleMedium)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        SegmentedButton(selected = settings.vertical, onClick = { onSettings(settings.copy(vertical = true)) },
            shape = SegmentedButtonDefaults.itemShape(0, 2), icon = {}) { Text("垂直连续") }
        SegmentedButton(selected = !settings.vertical, onClick = { onSettings(settings.copy(vertical = false)) },
            shape = SegmentedButtonDefaults.itemShape(1, 2), icon = {}) { Text("水平翻页") }
    }
    PreferenceSwitch("深色阅读背景", "关闭后使用浅色画布，与应用主题独立", settings.readerDark) { onSettings(settings.copy(readerDark = it)) }
    PreferenceSwitch("从右向左翻页", "适用于日漫的水平阅读模式", settings.rightToLeft) { onSettings(settings.copy(rightToLeft = it)) }
    PreferenceSwitch("阅读时屏幕常亮", "离开阅读器后恢复系统设置", settings.keepScreenOn) { onSettings(settings.copy(keepScreenOn = it)) }
    PreferenceSwitch("单个视频循环播放", "视频播放完后留在当前视频并重新开始", settings.videoLoopSingle) { onSettings(settings.copy(videoLoopSingle = it)) }
    PreferenceSwitch("视频默认仅预览", "切换视频时暂停，轻触画面开始播放", settings.videoPreview) { onSettings(settings.copy(videoPreview = it)) }
    PreferenceSwitch("循环模式", "读到漫画结尾后继续翻页回到第一页", settings.loopMode) { onSettings(settings.copy(loopMode = it)) }
}

@Composable
private fun PreferenceSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

private fun logDirectoryLabel(uri: String): String {
    val raw = Uri.decode(uri.substringAfterLast('/'))
    return raw.substringAfterLast(':').ifBlank { raw }
}
