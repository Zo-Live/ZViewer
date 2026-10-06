package dev.zolive.zviewer.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.zolive.zviewer.data.BookSession
import dev.zolive.zviewer.data.ReaderSettings
import dev.zolive.zviewer.reader.ZoomVideoView
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

internal class VideoPlaybackState(val player: ExoPlayer) {
    var index by mutableIntStateOf(-1)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    var playRequested by mutableStateOf(false)
    var playbackState by mutableIntStateOf(Player.STATE_IDLE)
    var ready by mutableStateOf(false)
    var surfaceReady by mutableStateOf(false)

    /**
     * The view that currently owns this player's video surface. Pager pages can be disposed and
     * recreated, and the old AndroidView's onRelease may run after the replacement has already
     * bound the player. Keeping the owner explicit lets binding transfer in a deterministic order
     * and stops stale views from clearing the player's surface.
     */
    var ownerView: ZoomVideoView? = null

    /** Whether the owner view's TextureView currently has a usable Surface for rendering. */
    var surfaceAttached by mutableStateOf(false)

    var firstFrameReady by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var seekable by mutableStateOf(false)
    var saveProgress: (Int, Long) -> Unit = { _, _ -> }
    var preparedIndex by mutableIntStateOf(-1)
    private var released = false

    fun refresh() {
        position = player.currentPosition.coerceAtLeast(0L)
        duration = player.duration.coerceAtLeast(0L)
        playing = player.isPlaying
        playbackState = player.playbackState
        ready = playbackState == Player.STATE_READY
        seekable = player.isCurrentMediaItemSeekable
    }

    fun save() {
        if (index >= 0) saveProgress(index, if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition.coerceAtLeast(0L))
    }

    fun seek(positionMs: Long) {
        if (!seekable || duration <= 0L) return
        player.seekTo(positionMs.coerceIn(0L, duration))
        refresh()
        save()
    }

    fun togglePlayback() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
        playRequested = !playRequested
        if (!playRequested) player.pause()
    }

    fun pause() {
        player.playWhenReady = false
        player.pause()
        refresh()
    }

    /** The settled/active view among the composed pages, used to fall back after a transient view. */
    private var activeView: ZoomVideoView? = null

    /**
     * Makes [view] the sole owner of this player's video output surface. When a replacement view
     * appears before the previous owner is released (Compose can release the old AndroidView after
     * the new one bound), the previous view is detached first, so binding order stays deterministic.
     */
    fun bindOwner(view: ZoomVideoView, active: Boolean = false) {
        if (active) activeView = view
        if (ownerView === view) {
            surfaceAttached = view.isSurfaceAvailable
            return
        }
        val previous = ownerView
        if (previous != null && previous.playerView.player === player) previous.playerView.player = null
        ownerView = view
        view.playerView.player = player
        surfaceAttached = view.isSurfaceAvailable
    }

    /**
     * Only the current owner may detach. A stale view's release must not clear the live surface. When
     * the released view was a transient duplicate, the still-composed active view takes the surface
     * back so a cancelled swipe cannot leave the player without a picture.
     */
    fun releaseOwner(view: ZoomVideoView) {
        if (activeView === view) activeView = null
        if (ownerView !== view) return
        ownerView = null
        surfaceAttached = false
        surfaceReady = false
        firstFrameReady = false
        view.onLayoutReady = {}
        view.playerView.player = null
        activeView?.let { bindOwner(it) }
    }

    /** Updates layout/surface readiness for the owner view only; stale views are ignored. */
    fun surfaceStateChanged(view: ZoomVideoView, layoutReady: Boolean) {
        if (ownerView !== view) return
        surfaceReady = layoutReady
        surfaceAttached = view.isSurfaceAvailable
    }

    fun release() {
        if (released) return
        released = true
        save()
        player.release()
    }

    fun isReleased(): Boolean = released
}

internal fun createVideoPlaybackState(context: Context): VideoPlaybackState =
    VideoPlaybackState(ExoPlayer.Builder(context).build())

@Composable
internal fun rememberVideoPlayback(
    state: VideoPlaybackState, session: BookSession, index: Int, active: Boolean, allowPlayback: Boolean, settings: ReaderSettings,
    videoPosition: (Int) -> Long, onProgress: (Int, Long) -> Unit, onEnded: () -> Unit,
) {
    val latestProgress by rememberUpdatedState(onProgress)
    val latestEnded by rememberUpdatedState(onEnded)
    val latestActive by rememberUpdatedState(active)
    state.saveProgress = { page, position -> latestProgress(page, position) }

    DisposableEffect(state) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { state.refresh() }
            override fun onPlaybackStateChanged(playbackState: Int) {
                state.refresh()
                if (playbackState == Player.STATE_ENDED) {
                    state.playRequested = false
                    state.save()
                    if (latestActive) latestEnded()
                }
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { state.refresh() }
            override fun onRenderedFirstFrame() { state.firstFrameReady = true }
            override fun onPlayerError(error: PlaybackException) { state.failed = true }
        }
        state.player.addListener(listener)
        onDispose {
            state.player.removeListener(listener)
        }
    }
    LaunchedEffect(state, index) {
        if (state.preparedIndex == index || state.isReleased()) return@LaunchedEffect
        state.preparedIndex = index
        state.save()
        state.player.stop()
        state.index = index
        state.failed = false
        state.position = 0L
        state.duration = 0L
        state.seekable = false
        state.ready = false
        state.firstFrameReady = false
        state.playRequested = !settings.videoPreview
        val page = session.pages[index]
        val uri = page.filePath?.let { Uri.fromFile(File(it)) } ?: Uri.parse(page.uri!!)
        state.player.setMediaItem(MediaItem.fromUri(uri), videoPosition(index).coerceAtLeast(0L))
        state.player.playWhenReady = false
        state.player.prepare()
    }
    LaunchedEffect(state, settings.videoLoopSingle, settings.loopMode) {
        state.player.repeatMode = if (settings.videoLoopSingle || settings.loopMode && session.pages.size == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }
    LaunchedEffect(state, settings.videoPreview) {
        state.playRequested = !settings.videoPreview
    }
    LaunchedEffect(state, active, allowPlayback, state.playRequested, state.ready, state.failed, state.surfaceAttached) {
        val shouldPlay = active && allowPlayback && state.playRequested && state.ready && !state.failed && state.surfaceAttached
        state.player.playWhenReady = shouldPlay
        if (!shouldPlay) state.player.pause()
        state.refresh()
        if (!active) state.save()
    }
    LaunchedEffect(state, active) {
        // 混合内容会保留已创建的播放器；视频播完后自动进入下一项，再次回到该项时播放器停在结尾帧。
        // 重新激活已播完的播放器时回到开头并按设置恢复播放；用户手动暂停（非 ENDED）不受影响。
        if (active && state.player.playbackState == Player.STATE_ENDED) {
            state.player.seekTo(0L)
            state.playRequested = !settings.videoPreview
            state.refresh()
        }
        var ticks = 0
        while (active) {
            state.refresh()
            state.surfaceAttached = state.ownerView?.isSurfaceAvailable == true
            if (ticks++ % 5 == 0) state.save()
            delay(200)
        }
    }
}

@Composable
internal fun VideoPage(
    state: VideoPlaybackState, index: Int, settings: ReaderSettings, foreground: Color, active: Boolean,
    zoomSequence: Int, zoomAction: Int, onTap: () -> Unit, onPage: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    val viewRef = remember { mutableStateOf<ZoomVideoView?>(null) }
    // Pager item compositions can be skipped or reused (especially while looping, when two virtual
    // items map to the same real page). The AndroidView update block is not guaranteed to re-run when
    // this page becomes active again, so re-assert surface ownership whenever activation changes.
    LaunchedEffect(state, active) {
        if (active) viewRef.value?.let { state.bindOwner(it, active = true) }
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(factory = { ZoomVideoView(it).also { view -> viewRef.value = view } }, modifier = Modifier.fillMaxSize(),
            onRelease = { view ->
                if (viewRef.value === view) viewRef.value = null
                state.releaseOwner(view)
            }, update = { view ->
                viewRef.value = view
                state.bindOwner(view, active = active)
                view.bindPage(index)
                view.vertical = settings.vertical
                view.rightToLeft = settings.rightToLeft
                view.onTap = onTap
                view.onPage = onPage
                view.onLayoutReady = { ready -> state.surfaceStateChanged(view, ready) }
                state.surfaceReady = view.isVideoLayoutReady
                if (zoomSequence > 0) view.command(zoomSequence, zoomAction)
            })
        if (state.failed) Text("视频无法播放，文件可能无法读取或设备不支持此编码。", Modifier.padding(28.dp), color = foreground)
        else if (state.playbackState == Player.STATE_BUFFERING || state.playbackState == Player.STATE_IDLE) {
            CircularProgressIndicator(color = foreground)
        }
    }
}

@Composable
internal fun VideoProgressControls(state: VideoPlaybackState) {
    var dragPosition by remember(state.index) { mutableStateOf<Long?>(null) }
    val displayedPosition = dragPosition ?: state.position
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text("${formatVideoTime(displayedPosition)} / ${formatVideoTime(state.duration)}",
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 48.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = state::togglePlayback, enabled = !state.failed && state.index >= 0) {
                Icon(if (state.playRequested) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    if (state.playRequested) "暂停视频" else "播放视频")
            }
            Slider(value = displayedPosition.toFloat().coerceIn(0f, state.duration.coerceAtLeast(1L).toFloat()),
                onValueChange = { dragPosition = it.toLong() },
                onValueChangeFinished = { dragPosition?.let(state::seek); dragPosition = null },
                valueRange = 0f..state.duration.coerceAtLeast(1L).toFloat(),
                enabled = state.seekable && state.duration > 0L && !state.failed,
                modifier = Modifier.weight(1f).semantics { contentDescription = "视频播放进度" })
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
}

internal fun formatVideoTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000L
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remaining = seconds % 60
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remaining)
    else String.format(Locale.ROOT, "%02d:%02d", minutes, remaining)
}
