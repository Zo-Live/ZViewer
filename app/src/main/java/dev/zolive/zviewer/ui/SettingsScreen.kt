@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.zolive.zviewer.ui

import android.graphics.Color as AndroidColor
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.zolive.zviewer.LibraryState
import dev.zolive.zviewer.LibraryViewModel
import dev.zolive.zviewer.data.ReaderSettings

@Composable
fun SettingsScreen(state: LibraryState, model: LibraryViewModel, onBack: () -> Unit, onChooseLibrary: () -> Unit) {
    val settings = state.settings
    var customColor by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val cacheSize by produceState(0L, state.cacheRevision) { value = model.repository.cacheSize() }
    Scaffold(topBar = { TopAppBar(title = { Text("设置") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回书库") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsHeading("让阅读，合你心意")
            Text("外观", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            SettingsCard {
                Text("应用主题", style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEachIndexed { index, (value, label) ->
                        SegmentedButton(selected = settings.theme == value, onClick = { model.settings(settings.copy(theme = value)) },
                            shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(label) }
                    }
                }
                Text("主题颜色", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
                Text("为按钮、导航和界面选择一种颜色", Modifier.padding(top = 5.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text("每行封面数", Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    (2..5).forEachIndexed { index, count ->
                        SegmentedButton(selected = settings.gridColumns == count,
                            onClick = { model.settings(settings.copy(gridColumns = count)) },
                            shape = SegmentedButtonDefaults.itemShape(index, 4)) { Text("$count") }
                    }
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
                    Text("${Formatter.formatShortFileSize(context, cacheSize)} · 保留漫画原件和阅读记录")
                }, leadingContent = { Icon(Icons.Outlined.CleaningServices, null) },
                    modifier = Modifier.clickable { confirmClear = true }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
            TextButton(onClick = { about = true }, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)) { Text("关于 ZViewer 1.0.0") }
            Text("留一点时间，给喜欢的故事。", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 32.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (customColor) {
        var value by remember { mutableStateOf("%06X".format(settings.accent and 0xFFFFFF)) }
        val valid = value.matches(Regex("[0-9a-fA-F]{6}"))
        AlertDialog(onDismissRequest = { customColor = false }, title = { Text("自定义主题颜色") }, text = {
            Column {
                Text("输入六位十六进制色值，界面将自动生成对应的明暗配色。")
                OutlinedTextField(value, { value = it.removePrefix("#").take(6) }, Modifier.padding(top = 16.dp),
                    label = { Text("颜色值") }, prefix = { Text("#") }, singleLine = true, isError = !valid)
            }
        }, confirmButton = { TextButton(enabled = valid, onClick = {
            model.settings(settings.copy(accent = AndroidColor.parseColor("#$value").toLong() and 0xFFFFFFFF)); customColor = false
        }) { Text("应用") } }, dismissButton = { TextButton(onClick = { customColor = false }) { Text("取消") } })
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清理缓存？") },
        text = { Text("将删除临时解压文件和封面，下次阅读时重新生成。漫画原件、收藏和阅读进度会保留。") },
        confirmButton = { TextButton(onClick = { model.clearCache(); confirmClear = false }) { Text("清理") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("ZViewer · 本地漫画阅读器") },
        text = { Text("版本 1.0.0\n\n支持 ZIP / CBZ、RAR / CBR、7Z、PDF 与图片文件夹。支持 APNG、动态 WebP、GIF、AVIF 及常用静态图片。\n\n全部阅读数据仅保存在本机，不联网、不上传文件。密码保护、分卷压缩包及 DRM 文件请先自行转换。\n\n开源组件：AndroidX / Compose（Apache 2.0）、APNG4Android（Apache 2.0）、libarchive（BSD）、libavif（BSD）。\n\nHEIF / HEIC 的可解码范围取决于设备系统解码器。") },
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
private fun SettingsHeading(text: String) {
    Text(text, Modifier.padding(top = 12.dp, bottom = 12.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
            shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("垂直连续") }
        SegmentedButton(selected = !settings.vertical, onClick = { onSettings(settings.copy(vertical = false)) },
            shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("水平翻页") }
    }
    PreferenceSwitch("深色阅读背景", "关闭后使用浅色画布，与应用主题独立", settings.readerDark) { onSettings(settings.copy(readerDark = it)) }
    PreferenceSwitch("从右向左翻页", "适用于日漫的水平阅读模式", settings.rightToLeft) { onSettings(settings.copy(rightToLeft = it)) }
    PreferenceSwitch("阅读时屏幕常亮", "离开阅读器后恢复系统设置", settings.keepScreenOn) { onSettings(settings.copy(keepScreenOn = it)) }
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
