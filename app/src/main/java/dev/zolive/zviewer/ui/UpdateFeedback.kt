package dev.zolive.zviewer.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.zolive.zviewer.LibraryState
import dev.zolive.zviewer.LibraryViewModel
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.UpdateDownload
import dev.zolive.zviewer.data.UpdateDownloader

/** 阅读、准备阅读和后台期间保留更新结果，恢复可交互页面后再显示。 */
@Composable
fun UpdateFeedback(state: LibraryState, model: LibraryViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { model.monitorUpdateDownload() }
    }
    if (state.session != null || state.opening != null || state.error != null || lifecycleState != Lifecycle.State.RESUMED) return
    val prompt = state.updatePrompt
    val activity = LocalActivity.current
    val ready = state.updateDownload as? UpdateDownload.Ready
    LaunchedEffect(activity, ready, prompt) {
        if (lifecycle.currentState == Lifecycle.State.RESUMED &&
            activity != null && ready?.launchAutomatically == true && prompt == null) {
            openUpdateInstaller(activity, model, ready)
        }
    }
    if (prompt != null) {
        AlertDialog(onDismissRequest = model::dismissUpdate,
            text = { Text(stringResource(R.string.update_prompt, prompt.version)) },
            dismissButton = { TextButton(onClick = model::dismissUpdate) { Text(stringResource(R.string.update_later)) } },
            confirmButton = { TextButton(onClick = model::startUpdateDownload) { Text(stringResource(R.string.update_now)) } })
    }
    val notice = state.updateNotice?.takeIf { prompt == null }
    val context = LocalContext.current
    LaunchedEffect(notice) {
        if (notice != null) {
            Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
            model.dismissUpdateNotice()
        }
    }
}

internal fun openUpdateInstaller(activity: Activity, model: LibraryViewModel, download: UpdateDownload.Ready) {
    if (!model.prepareUpdateInstallation(download)) return
    try {
        activity.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(download.uri, UpdateDownloader.APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (error: ActivityNotFoundException) {
        model.showUpdateInstallerError(error)
    } catch (error: SecurityException) {
        model.showUpdateInstallerError(error)
    }
}
