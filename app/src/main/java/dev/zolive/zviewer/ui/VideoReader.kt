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
        ready = playbackState == Player.STATE_READY && player.videoSize.width > 0 && player.videoSize.height > 0
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
    LaunchedEffect(state, active, allowPlayback, state.playRequested, state.ready, state.surfaceReady, state.firstFrameReady, state.failed) {
        val shouldPlay = active && allowPlayback && state.playRequested && state.ready && state.surfaceReady && state.firstFrameReady && !state.failed
        state.player.playWhenReady = shouldPlay
        if (!shouldPlay) state.player.pause()
        state.refresh()
        if (!active) state.save()
    }
    LaunchedEffect(state, active) {
        var ticks = 0
        while (active) {
            state.refresh()
            if (ticks++ % 5 == 0) state.save()
            delay(200)
        }
    }
}

@Composable
internal fun VideoPage(
    state: VideoPlaybackState, index: Int, settings: ReaderSettings, foreground: Color,
    zoomSequence: Int, zoomAction: Int, onTap: () -> Unit, onPage: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(factory = { ZoomVideoView(it) }, modifier = Modifier.fillMaxSize(),
            onRelease = { view ->
                state.surfaceReady = false
                state.firstFrameReady = false
                view.onLayoutReady = {}
                view.playerView.player = null
            }, update = { view ->
                view.playerView.player = state.player
                view.bindPage(index)
                view.vertical = settings.vertical
                view.rightToLeft = settings.rightToLeft
                view.onTap = onTap
                view.onPage = onPage
                view.onLayoutReady = { ready -> state.surfaceReady = ready }
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
