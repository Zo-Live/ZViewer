@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.zolive.zviewer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LogListScreen(active: Boolean, onBack: () -> Unit, onOpen: (DiagnosticLog.LogFile) -> Unit) {
    val context = LocalContext.current
    val files by produceState<List<DiagnosticLog.LogFile>?>(null, active) {
        if (active) value = withContext(Dispatchers.IO) { DiagnosticLog.files(context) }
    }
    Scaffold(topBar = {
        CenterAlignedTopAppBar(
            title = { Text(stringResource(R.string.log_list)) },
            navigationIcon = { BackButton(R.string.back_to_settings, onBack) },
        )
    }) { padding ->
        val logs = files
        if (logs.isNullOrEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (logs == null) CircularProgressIndicator()
                else Text(stringResource(R.string.no_logs))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(logs, key = { it.uri.toString() }) { file ->
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
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Text(file.name, Modifier.padding(start = 8.dp, end = 12.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = { BackButton(R.string.back_to_logs, onBack) },
            actions = {
                IconButton(enabled = !actionRunning && content != null, onClick = {
                    runAction(R.string.log_share_failed) {
                        val uri = withContext(Dispatchers.IO) { DiagnosticLog.shareUri(context, file.uri, file.name) }
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri(file.name, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                    }
                }) { Icon(Icons.Outlined.Share, stringResource(R.string.share_log)) }
                IconButton(enabled = !actionRunning && content != null, onClick = {
                    val text = content ?: return@IconButton
                    runAction(R.string.log_copy_failed) {
                        val clip = if (text.length <= 400_000) {
                            ClipData.newPlainText(file.name, text)
                        } else {
                            val uri = withContext(Dispatchers.IO) {
                                DiagnosticLog.shareUri(context, file.uri, file.name, text)
                            }
                            ClipData.newUri(context.contentResolver, file.name, uri)
                        }
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                    }
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
            val lines = remember(content) { content.lineSequence().toList() }
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
                items(lines) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun BackButton(description: Int, onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(description))
    }
}
