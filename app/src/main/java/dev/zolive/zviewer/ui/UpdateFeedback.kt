package dev.zolive.zviewer.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.zolive.zviewer.LibraryState
import dev.zolive.zviewer.LibraryViewModel
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.UpdateDownload
import dev.zolive.zviewer.data.UpdateDownloader
import kotlinx.coroutines.delay

/** 在应用层显示反馈；阅读、准备阅读和后台期间保留结果，恢复可交互页面后才计时。 */
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
    val notice = state.updateNotice?.takeIf { prompt == null }?.let { stringResource(it) }
    var lastNotice by remember { mutableStateOf("") }
    if (notice != null) SideEffect { lastNotice = notice }
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = notice != null
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(accessibility?.calculateRecommendedTimeoutMillis(3_500, containsText = true) ?: 3_500)
            model.dismissUpdateNotice()
        }
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(start = 24.dp, end = 24.dp, bottom = 144.dp),
        contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visibleState = visible,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 2 }) {
            val background = if (MaterialTheme.colorScheme.surface.luminance() > .5f) DarkReaderBackground else LightReaderBackground
            Surface(shape = RoundedCornerShape(16.dp), color = background.copy(alpha = OverlayBackgroundAlpha),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.widthIn(max = 360.dp).semantics {
                    liveRegion = LiveRegionMode.Polite
                    dismiss { model.dismissUpdateNotice(); true }
                }) {
                Text(notice ?: lastNotice, Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
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
