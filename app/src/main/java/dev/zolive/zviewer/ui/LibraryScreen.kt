@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.zolive.zviewer.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ZViewerApp(model: LibraryViewModel, state: LibraryState) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.selectLibrary(uri)
    }
    val chooseLibrary = { picker.launch(state.treeUri?.let(Uri::parse)) }
    Surface(Modifier.fillMaxSize()) {
        when {
            state.session != null -> ReaderScreen(state.session, state.initialPage, state.settings,
                model.repository, model::settings, model::saveProgress, model::closeBook)
            settingsOpen -> {
                BackHandler { settingsOpen = false }
                SettingsScreen(state, model, onBack = { settingsOpen = false }, onChooseLibrary = chooseLibrary)
            }
            else -> LibraryScreen(state, model, onSettings = { settingsOpen = true }, onChooseLibrary = chooseLibrary)
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
private fun LibraryScreen(state: LibraryState, model: LibraryViewModel, onSettings: () -> Unit, onChooseLibrary: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var sortMenu by remember { mutableStateOf(false) }
    val titles = listOf("书库", "收藏", "最近阅读")
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
    if (searching) BackHandler { searching = false; query = "" }
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                    Text("ZViewer", fontWeight = FontWeight.SemiBold, fontSize = 21.sp)
                }
            }, actions = {
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Outlined.Search, "搜索漫画") }
                IconButton(onClick = onSettings) { Icon(Icons.Outlined.Tune, "设置") }
            })
        },
        bottomBar = {
            NavigationBar {
                val icons = listOf(Icons.Outlined.CollectionsBookmark, Icons.Outlined.BookmarkBorder, Icons.Outlined.History)
                titles.forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Icon(icons[index], null) }, label = { Text(title) })
                }
            }
        },
        floatingActionButton = {
            if (state.treeUri != null) SmallFloatingActionButton(onClick = onChooseLibrary,
                containerColor = MaterialTheme.colorScheme.secondaryContainer) { Icon(Icons.Outlined.FolderOpen, "切换书库目录") }
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
            if (state.treeUri == null) WelcomeScreen(onChooseLibrary)
            else {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(titles[tab], style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Text("${books.size} 本漫画 · 随时翻开，接着读", Modifier.padding(top = 5.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = model::refresh, enabled = !state.scanning) { Icon(Icons.Outlined.Refresh, "刷新书库") }
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
                    EmptyLibrary(state.scanning, query.isNotEmpty() || filter != 0 || tab != 0, onChooseLibrary)
                } else {
                    LazyVerticalGrid(columns = GridCells.Fixed(state.settings.gridColumns),
                        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 88.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        val recent = if (tab == 0 && query.isEmpty() && filter == 0) books
                            .filter { (state.progress[it.id]?.updated ?: 0) > 0 && (state.progress[it.id]?.fraction ?: 0f) < 1f }
                            .maxByOrNull { state.progress[it.id]?.updated ?: 0 } else null
                        if (recent != null) item(span = { GridItemSpan(maxLineSpan) }) {
                            ContinueCard(recent, state.progress.getValue(recent.id), onClick = { model.open(recent) })
                        }
                        items(books, key = { it.id }) { book ->
                            BookCard(book, state.progress[book.id] ?: ReadingProgress(), book.id in state.favorites,
                                model.repository, state.cacheRevision, onClick = { model.open(book) },
                                onFavorite = { model.toggleFavorite(book) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeScreen(onChooseLibrary: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 30.dp), horizontalAlignment = Alignment.CenterHorizontally,
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
        Text("好故事，就在手边。", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("选择一个漫画文件夹，\n把喜欢的世界收进你的书库。", Modifier.padding(top = 14.dp, bottom = 30.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 26.sp)
        Button(onClick = onChooseLibrary, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Outlined.CreateNewFolder, null); Spacer(Modifier.width(10.dp)); Text("选择书库文件夹", fontSize = 16.sp)
        }
        Text("ZIP · RAR · 7Z · PDF · 图片文件夹", Modifier.padding(top = 20.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("本地阅读 · 无需账号 · 不上传文件", Modifier.padding(top = 8.dp, bottom = 36.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyLibrary(scanning: Boolean, filtered: Boolean, onChooseLibrary: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
        Text(if (scanning) "正在整理你的书库…" else if (filtered) "还没有符合条件的漫画" else "这个文件夹还没有漫画", Modifier.padding(top = 20.dp),
            style = MaterialTheme.typography.titleMedium)
        Text(if (filtered) "试试其他筛选条件，或收藏一本喜欢的漫画。" else "可添加压缩包、PDF，或装有图片的子文件夹。", Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!filtered && !scanning) TextButton(onClick = onChooseLibrary, Modifier.padding(top = 12.dp)) { Text("选择其他文件夹") }
    }
}

@Composable
private fun ContinueCard(book: Book, progress: ReadingProgress, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(30.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text("继续上次的故事", style = MaterialTheme.typography.labelMedium)
                Text(book.title, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("第 ${progress.page + 1} / ${progress.total} 页", Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Outlined.PlayArrow, "继续阅读")
        }
    }
}

@Composable
private fun BookCard(book: Book, progress: ReadingProgress, favorite: Boolean, repository: BookRepository,
    revision: Int, onClick: () -> Unit, onFavorite: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.69f).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            BookCover(book, repository, revision)
            Surface(Modifier.align(Alignment.TopStart).padding(7.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .92f),
                shape = RoundedCornerShape(6.dp)) {
                Text(book.formatLabel, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onFavorite, modifier = Modifier.align(Alignment.BottomEnd).size(48.dp)) {
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
            val file = repository.cover(book)
            value = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }
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
