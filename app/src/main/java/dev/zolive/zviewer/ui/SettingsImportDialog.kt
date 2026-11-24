package dev.zolive.zviewer.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zolive.zviewer.LibraryViewModel
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.SettingsDirectory

@Composable
internal fun SettingsImportDialog(model: LibraryViewModel) {
    val pending by model.settingsImport.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (model.settingsImport.value?.awaitingDirectory == true) {
            model.resolveSettingsDirectory(accept = true, picked = uri)
        }
    }
    val request = pending?.takeUnless { it.awaitingDirectory }?.changes?.firstOrNull() ?: return
    val library = request.directory == SettingsDirectory.Library
    val defaultLabel = stringResource(if (library) R.string.no_directory_selected else R.string.log_default_directory)
    val current = request.current?.let { directoryLabel(Uri.parse(it)) } ?: defaultLabel
    val target = request.target?.let(::directoryLabel) ?: defaultLabel
    key(request.directory) {
        SettingsAlertDialog(
            onDismissRequest = { model.resolveSettingsDirectory(accept = false) },
            title = { Text(stringResource(if (library) R.string.settings_change_library else R.string.settings_change_logs)) },
            text = {
                val folders = stringResource(R.string.settings_change_directory, current, target)
                val message = if (request.needsGrant) {
                    folders + "\n\n" + stringResource(R.string.settings_change_directory_permission)
                } else folders
                Text(message)
            },
            confirmButton = {
                TextButton(onClick = {
                    val uri = model.confirmSettingsDirectory()
                    if (uri != null) {
                        try {
                            picker.launch(uri)
                        } catch (_: ActivityNotFoundException) {
                            model.resolveSettingsDirectory(accept = true, picked = uri)
                        }
                    }
                }) { Text(stringResource(R.string.settings_change_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { model.resolveSettingsDirectory(accept = false) }) {
                    Text(stringResource(R.string.settings_keep_directory))
                }
            },
        )
    }
}

private fun directoryLabel(uri: Uri): String =
    runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrElse { uri.toString() }
