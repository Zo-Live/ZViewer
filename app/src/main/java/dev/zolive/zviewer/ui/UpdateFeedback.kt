package dev.zolive.zviewer.ui

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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.zolive.zviewer.LibraryState
import dev.zolive.zviewer.LibraryViewModel
import kotlinx.coroutines.delay

/** 在应用层显示反馈；阅读、准备阅读和后台期间保留结果，恢复可交互页面后才计时。 */
@Composable
fun UpdateFeedback(state: LibraryState, model: LibraryViewModel, settingsOpen: Boolean) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    if (state.session != null || state.opening != null || state.error != null || lifecycle != Lifecycle.State.RESUMED) return
    val prompt = state.updatePrompt
    if (prompt != null) {
        AlertDialog(onDismissRequest = model::dismissUpdate,
            text = { Text("检测到新版本：${prompt.version}\n是否立即更新？") },
            dismissButton = { TextButton(onClick = model::dismissUpdate) { Text("稍后更新") } },
            confirmButton = { TextButton(onClick = model::installUpdate) { Text("立即更新") } })
    }
    val notice = state.updateNotice.takeIf { prompt == null }
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
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp,
        vertical = if (settingsOpen) 24.dp else 88.dp), contentAlignment = Alignment.BottomCenter) {
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
