@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.zolive.zviewer.ui

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.SpannableString
import android.text.Spanned
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.textclassifier.TextClassifier
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_INLINE_LOG_CHARS = 400_000
// Android 分享面板会在 Intent 和 ClipData 中重复写入所选文字。
private const val MAX_INLINE_SHARE_CHARS = 100_000

@Composable
fun LogListScreen(active: Boolean, onBack: () -> Unit, onOpen: (DiagnosticLog.LogFile) -> Unit) {
    val context = LocalContext.current
    val listing by produceState<DiagnosticLog.LogListing?>(null, active) {
        if (active) value = withContext(Dispatchers.IO) { DiagnosticLog.listFiles(context) }
    }
    Scaffold(topBar = {
        CenterAlignedTopAppBar(
            title = { Text(stringResource(R.string.log_list)) },
            navigationIcon = { BackButton(R.string.back_to_diagnostics, onBack) },
        )
    }) { padding ->
        val result = listing
        if (result == null || result.files.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                when {
                    result == null -> CircularProgressIndicator()
                    result.error != null -> Text(stringResource(R.string.log_list_failed))
                    else -> Text(stringResource(R.string.no_logs))
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                if (result.error != null) {
                    item {
                        Text(stringResource(R.string.log_list_failed), Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                    }
                }
                items(result.files, key = { it.uri.toString() }) { file ->
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .clickable(role = Role.Button) { onOpen(file) }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(file.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
fun LogDetailScreen(file: DiagnosticLog.LogFile, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val result by produceState<Result<String>?>(null, file.uri) {
        value = withContext(Dispatchers.IO) { runCatching { DiagnosticLog.read(context, file.uri) } }
    }
    val content = result?.getOrNull()
    var actionRunning by remember { mutableStateOf(false) }
    fun runAction(errorMessage: Int, action: suspend () -> Unit) {
        if (actionRunning) return
        actionRunning = true
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
            } finally {
                actionRunning = false
            }
        }
    }
    fun shareLog(selection: String? = null) {
        runAction(R.string.log_share_failed) {
            val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
            if (selection != null && selection.length <= MAX_INLINE_SHARE_CHARS) {
                intent.putExtra(Intent.EXTRA_TEXT, selection)
            } else {
                val uri = withContext(Dispatchers.IO) {
                    DiagnosticLog.shareUri(context, file.uri, file.name, selection)
                }
                intent.putExtra(Intent.EXTRA_STREAM, uri)
                intent.clipData = ClipData.newRawUri(file.name, uri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, null))
        }
    }
    fun copyLog(text: String) {
        runAction(R.string.log_copy_failed) {
            val clip = if (text.length <= MAX_INLINE_LOG_CHARS) {
                ClipData.newPlainText(file.name, text)
            } else {
                val uri = withContext(Dispatchers.IO) {
                    DiagnosticLog.shareUri(context, file.uri, file.name, text)
                }
                ClipData.newUri(context.contentResolver, file.name, uri)
            }
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        }
    }
    fun openSelection(selection: String, intent: Intent, errorMessage: Int) {
        runAction(errorMessage) {
            if (selection.length > MAX_INLINE_LOG_CHARS) {
                Toast.makeText(context, R.string.log_selection_too_long, Toast.LENGTH_SHORT).show()
            } else {
                context.startActivity(intent)
            }
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Text(file.name, Modifier.padding(start = 8.dp, end = 12.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = { BackButton(R.string.back_to_logs, onBack) },
            actions = {
                IconButton(enabled = !actionRunning && content != null, onClick = {
                    shareLog()
                }) { Icon(Icons.Outlined.Share, stringResource(R.string.share_log)) }
                IconButton(enabled = !actionRunning && content != null, onClick = {
                    content?.let { copyLog(it) }
                }) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy_log)) }
            },
        )
    }) { padding ->
        if (content == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (result == null) CircularProgressIndicator()
                else Text(stringResource(R.string.log_read_failed))
            }
        } else {
            SelectableLog(content, Modifier.fillMaxSize().padding(padding)) { action, selection ->
                when (action) {
                    R.string.copy_log -> copyLog(selection)
                    R.string.share_log -> shareLog(selection)
                    R.string.search_log_selection -> openSelection(selection,
                        Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, selection),
                        R.string.log_selection_search_failed)
                    R.string.translate_log_selection -> openSelection(selection,
                        Intent(Intent.ACTION_TRANSLATE).putExtra(Intent.EXTRA_TEXT, selection),
                        R.string.log_selection_translate_failed)
                }
            }
        }
    }
}

@Composable
private fun SelectableLog(content: String, modifier: Modifier, onAction: (Int, String) -> Unit) {
    val handleAction by rememberUpdatedState(onAction)
    val hapticsEnabled = LocalHapticsEnabled.current
    val textColor = MaterialTheme.colorScheme.onBackground.toArgb()
    val fontSize = MaterialTheme.typography.bodySmall.fontSize.value
    key(content) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                val text = TextView(context).apply {
                    val padding = (16 * resources.displayMetrics.density).toInt()
                    setPadding(padding, padding, padding, padding)
                    includeFontPadding = false
                    typeface = Typeface.MONOSPACE
                    breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
                    setTextIsSelectable(true)
                    setText(content, TextView.BufferType.SPANNABLE)
                    setTextClassifier(TextClassifier.NO_OP)
                    customSelectionActionModeCallback = LogSelectionMenu(this) { action, selection ->
                        handleAction(action, selection)
                    }
                }
                ScrollView(context).apply {
                    addView(text, MATCH_PARENT, WRAP_CONTENT)
                }
            },
            update = { scroll ->
                (scroll.getChildAt(0) as TextView).apply {
                    isHapticFeedbackEnabled = hapticsEnabled
                    setTextColor(textColor)
                    textSize = fontSize
                }
            },
        )
    }
}

private class LogSelectionMenu(
    private val view: TextView,
    private val onAction: (Int, String) -> Unit,
) : ActionMode.Callback {
    private val actions = listOf(
        R.string.copy_log to R.drawable.ic_log_content_copy,
        R.string.select_all_log to R.drawable.ic_log_select_all,
        R.string.search_log_selection to R.drawable.ic_log_search,
        R.string.translate_log_selection to R.drawable.ic_log_translate,
        R.string.share_log to R.drawable.ic_log_share,
    ).map { (action, icon) ->
        val title = SpannableString(view.context.getString(action))
        val drawable = checkNotNull(view.context.getDrawable(icon))
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        // 系统浮动工具栏会忽略普通菜单项的图标。将图标作为标题的一部分交给系统绘制。
        val span = object : ImageSpan(drawable, DynamicDrawableSpan.ALIGN_CENTER) {
            override fun draw(
                canvas: Canvas,
                text: CharSequence?,
                start: Int,
                end: Int,
                x: Float,
                top: Int,
                y: Int,
                bottom: Int,
                paint: Paint,
            ) {
                drawable.setTint(paint.color)
                super.draw(canvas, text, start, end, x, top, y, bottom, paint)
            }
        }
        title.setSpan(span, 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        action to title
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = onPrepareActionMode(mode, menu)

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.clear()
        val fullySelected = kotlin.math.abs(view.selectionEnd - view.selectionStart) == view.length()
        actions.forEachIndexed { order, (action, title) ->
            if (action != R.string.select_all_log || !fullySelected) {
                menu.add(Menu.NONE, action, order, title)
                    .setContentDescription(title.toString())
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            }
        }
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item.itemId == R.string.select_all_log) return view.onTextContextMenuItem(android.R.id.selectAll)
        val start = minOf(view.selectionStart, view.selectionEnd)
        val end = maxOf(view.selectionStart, view.selectionEnd)
        if (start < 0 || start >= end) return false
        val selection = view.text.subSequence(start, end).toString()
        mode.finish()
        onAction(item.itemId, selection)
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) = Unit
}

@Composable
private fun BackButton(description: Int, onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(description))
    }
}
