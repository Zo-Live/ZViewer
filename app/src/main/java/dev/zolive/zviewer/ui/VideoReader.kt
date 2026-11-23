@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.zolive.zviewer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.BookRepository
import dev.zolive.zviewer.data.BookSession
import dev.zolive.zviewer.data.DiagnosticLog
import dev.zolive.zviewer.data.ReaderSettings
import dev.zolive.zviewer.reader.SpeedHoldEnd
import dev.zolive.zviewer.reader.ZoomVideoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToLong

private const val NormalSpeed = 1f
private const val FastSpeed = 2f
private const val MaxSwipeSeekMs = 60_000L
private val videoSpeedOptions = listOf(2f to "2.0x", 1.5f to "1.5x", 1f to "1.0x", .75f to "0.75x", .5f to "0.5x")

internal class VideoPlaybackState(val player: ExoPlayer) {
    var index by mutableIntStateOf(-1)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    var playRequested by mutableStateOf(false)
    var playbackState by mutableIntStateOf(Player.STATE_IDLE)
    var ready by mutableStateOf(false)
    private var surfaceReady by mutableStateOf(false)

    /**
     * The view that currently owns this player's video surface. Pager pages can be disposed and
     * recreated, and the old AndroidView's onRelease may run after the replacement has already
     * bound the player. Keeping the owner explicit lets binding transfer in a deterministic order
     * and stops stale views from clearing the player's surface.
     */
    var ownerView: ZoomVideoView? = null

    /** Whether the owner view's TextureView currently has a usable Surface for rendering. */
    var surfaceAttached by mutableStateOf(false)
        private set

    var firstFrameReady by mutableStateOf(false)
    val frameReady: Boolean get() = firstFrameReady && surfaceAttached && surfaceReady
    var failed by mutableStateOf(false)
    var seekable by mutableStateOf(false)
    var scrubPosition by mutableStateOf<Long?>(null)
        private set
    private var lastPreviewSeek = -1L
    private var swipeSeekOrigin: Long? = null
    var speed by mutableFloatStateOf(NormalSpeed)
        private set
    private var speedLocked = false
    private var speedBeforeHold: Float? = null
    private var playingBeforeHold = false
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
        if (index >= 0 && scrubPosition == null) {
            saveProgress(index, if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition.coerceAtLeast(0L))
        }
    }

    fun scrubTo(positionMs: Long) {
        if (released || !seekable || duration <= 0L) return
        val target = positionMs.coerceIn(0L, duration)
        val previous = scrubPosition
        if (target == previous) return
        scrubPosition = target
        if (previous == null) {
            pause()
            player.setScrubbingModeEnabled(true)
        }
    }

    fun submitScrubPreview() {
        val target = scrubPosition?.coerceAtMost(duration - 1) ?: return
        if (released || !seekable || target < 0 || target == lastPreviewSeek) return
        lastPreviewSeek = target
        player.seekTo(target)
    }

    fun finishScrub() {
        val target = scrubPosition ?: return
        scrubPosition = null
        if (released) return
        player.setScrubbingModeEnabled(false)
        if (seekable && duration > 0L) {
            val finalPosition = target.coerceIn(0L, duration)
            if (finalPosition != lastPreviewSeek) player.seekTo(finalPosition)
        }
        lastPreviewSeek = -1L
        refresh()
        save()
    }

    fun startSwipeSeek(): Boolean {
        if (released || failed || !seekable || duration <= 0 || scrubPosition != null || swipeSeekOrigin != null) return false
        swipeSeekOrigin = player.currentPosition.coerceIn(0L, duration)
        return true
    }

    fun swipeSeek(screenFraction: Float) {
        val origin = swipeSeekOrigin ?: return
        val offset = (screenFraction * minOf(duration, MaxSwipeSeekMs)).roundToLong()
        scrubTo(origin + offset)
    }

    fun finishSwipeSeek(cancelled: Boolean) {
        val origin = swipeSeekOrigin ?: return
        swipeSeekOrigin = null
        if (cancelled && scrubPosition != null) scrubTo(origin)
        finishScrub()
    }

    fun selectSpeed(value: Float): Boolean {
        if (released || speedBeforeHold != null || value == speed) return false
        speedLocked = value == FastSpeed
        applySpeed(value)
        return true
    }

    fun startSpeedHold(): Boolean {
        if (released || failed || index < 0 || !ready || scrubPosition != null || speedBeforeHold != null) return false
        speedBeforeHold = speed
        playingBeforeHold = playRequested
        playRequested = true
        applySpeed(FastSpeed)
        return true
    }

    fun finishSpeedHold(end: SpeedHoldEnd): Int {
        val original = speedBeforeHold ?: return HapticFeedbackConstantsCompat.NO_HAPTICS
        speedBeforeHold = null
        if (released) return HapticFeedbackConstantsCompat.NO_HAPTICS
        if (end == SpeedHoldEnd.Cancel) {
            playRequested = playingBeforeHold
            applySpeed(original)
        } else if (end == SpeedHoldEnd.Up && !speedLocked) {
            speedLocked = true
            return HapticFeedbackConstantsCompat.CONFIRM
        } else if (end == SpeedHoldEnd.Down && speedLocked) {
            speedLocked = false
            applySpeed(NormalSpeed)
            return HapticFeedbackConstantsCompat.GESTURE_THRESHOLD_DEACTIVATE
        } else if (!speedLocked) {
            applySpeed(NormalSpeed)
        }
        return HapticFeedbackConstantsCompat.NO_HAPTICS
    }

    fun resetSpeed() {
        speedBeforeHold = null
        speedLocked = false
        applySpeed(NormalSpeed)
    }

    private fun applySpeed(value: Float) {
        if (speed == value) return
        player.playbackParameters = PlaybackParameters(value)
        speed = player.playbackParameters.speed
    }

    fun togglePlayback() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
        playRequested = !playRequested
        if (!playRequested) player.pause()
    }

    fun pause() {
        player.pause()
        refresh()
    }

    /** 重新从头播放。用于过渡期间当前视频播完时保持循环。 */
    fun replay() {
        player.seekTo(0L)
        playRequested = true
        player.play()
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
        if (released) {
            return
        }
        if (active) activeView = view
        if (ownerView === view) {
            surfaceStateChanged(view, view.isVideoLayoutReady)
            return
        }
        val previous = ownerView
        if (previous != null && previous.playerView.player === player) {
            previous.showVideoFrame(false)
            previous.bindPlayer(null)
        }
        ownerView = view
        firstFrameReady = false
        view.showVideoFrame(false)
        view.bindPlayer(player)
        surfaceStateChanged(view, view.isVideoLayoutReady)
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
        view.showVideoFrame(false)
        view.onLayoutReady = {}
        view.bindPlayer(null)
        activeView?.let { bindOwner(it) }
    }

    /** Updates layout/surface readiness for the owner view only; stale views are ignored. */
    fun surfaceStateChanged(view: ZoomVideoView, layoutReady: Boolean) {
        if (ownerView !== view) return
        val available = view.isSurfaceAvailable
        if (surfaceAttached && !available) firstFrameReady = false
        surfaceReady = layoutReady && available
        surfaceAttached = available
    }

    fun release() {
        if (released) return
        finishScrub()
        released = true
        ownerView?.let(::releaseOwner)
        DiagnosticLog.log("Video", "释放播放器 index=$index position=${player.currentPosition} state=${player.playbackState}")
        save()
        player.release()
    }

    fun isReleased(): Boolean = released
}

internal fun createVideoPlaybackState(context: Context): VideoPlaybackState =
    VideoPlaybackState(ExoPlayer.Builder(context).build())

/**
 * 按 Pager 虚拟项管理播放器。循环模式下同一真实页面可能同时出现在相邻的两个虚拟项中，
 * 每个虚拟项各自持有播放器，避免两个视图争抢同一个输出 Surface 而出现黑屏；离开可见范围后及时回收。
 */
internal class VideoPlaybackPool(private val context: Context) {
    private class Entry(val state: VideoPlaybackState) { var refs = 0 }

    private val entries = mutableStateMapOf<Int, Entry>()

    val size: Int get() = entries.size

    fun acquire(item: Int): VideoPlaybackState {
        val entry = entries.getOrPut(item) { Entry(createVideoPlaybackState(context)) }
        entry.refs++
        return entry.state
    }

    fun release(item: Int) {
        val entry = entries[item] ?: return
        entry.refs--
        if (entry.refs > 0) return
        entries.remove(item)
        entry.state.release()
    }

    fun get(item: Int): VideoPlaybackState? = entries[item]?.state

    fun snapshot(): List<Pair<Int, VideoPlaybackState>> = entries.map { it.key to it.value.state }

    fun releaseAll() {
        entries.values.forEach { it.state.release() }
        entries.clear()
    }
}

@Composable
internal fun rememberVideoPlayback(
    state: VideoPlaybackState, session: BookSession, index: Int, active: Boolean, allowPlayback: Boolean, settings: ReaderSettings,
    videoPosition: (Int) -> Long, onProgress: (Int, Long) -> Unit, onEnded: () -> Unit,
) {
    val latestProgress by rememberUpdatedState(onProgress)
    val latestEnded by rememberUpdatedState(onEnded)
    val latestActive by rememberUpdatedState(active)
    val latestAllowPlayback by rememberUpdatedState(allowPlayback)
    state.saveProgress = { page, position -> latestProgress(page, position) }

    DisposableEffect(state) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { state.refresh() }
            override fun onPlaybackStateChanged(playbackState: Int) {
                state.refresh()
                DiagnosticLog.log("Video", "index=${state.index} playbackState=${stateName(playbackState)} " +
                    "position=${state.position}/${state.duration}")
                if (playbackState == Player.STATE_ENDED && state.scrubPosition == null && latestAllowPlayback) {
                    state.playRequested = false
                    state.save()
                    if (latestActive) latestEnded()
                }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                DiagnosticLog.log("Video", "index=${state.index} playWhenReady=$playWhenReady reason=$reason")
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                DiagnosticLog.log("Video", "index=${state.index} isPlaying=$isPlaying position=${state.player.currentPosition}")
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                state.refresh()
                DiagnosticLog.log("Video", "index=${state.index} 视频尺寸 ${videoSize.width}x${videoSize.height} " +
                    "旋转=${videoSize.unappliedRotationDegrees} 比例=${videoSize.pixelWidthHeightRatio}")
            }
            override fun onRenderedFirstFrame() {
                state.firstFrameReady = true
            }
            override fun onPlayerError(error: PlaybackException) {
                state.failed = true
                DiagnosticLog.error("Video", "index=${state.index} 播放失败 code=${error.errorCodeName}", error)
            }
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
        state.resetSpeed()
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
        DiagnosticLog.log("Video", "准备 index=$index format=${page.name.substringAfterLast('.')} position=${videoPosition(index)}")
        state.player.setMediaItem(MediaItem.fromUri(uri), videoPosition(index).coerceAtLeast(0L))
        state.player.playWhenReady = false
        state.player.prepare()
    }
    LaunchedEffect(state) {
        snapshotFlow { state.scrubPosition }.filterNotNull().conflate().collect {
            withFrameNanos { state.submitScrubPreview() }
        }
    }
    LaunchedEffect(state, settings.videoLoopSingle, settings.loopMode) {
        // 注意不要根据滚动 / 切换状态切换 repeatMode：ExoPlayer 在接近片尾时切换循环模式
        // 会重新初始化解码器输出（视频尺寸归零、画面重新渲染首帧），在真机与模拟器上都会闪屏。
        // 切换期间播完的情况由 onEnded 回调重新从头播放处理。
        state.player.repeatMode = if (settings.videoLoopSingle || settings.loopMode && session.pages.size == 1) {
            Player.REPEAT_MODE_ONE
        } else Player.REPEAT_MODE_OFF
    }
    LaunchedEffect(state, settings.videoPreview) {
        state.playRequested = !settings.videoPreview
    }
    val scrubbing = state.scrubPosition != null
    val shouldPlay = active && allowPlayback && !scrubbing && state.playRequested && state.ready && !state.failed && state.surfaceAttached
    LaunchedEffect(state, shouldPlay) {
        state.player.playWhenReady = shouldPlay
        state.refresh()
    }
    LaunchedEffect(state, active) {
        if (!active) state.save()
    }
    LaunchedEffect(state, active, allowPlayback) {
        // 混合内容会保留已创建的播放器；视频播完后自动进入下一项，再次回到该项时播放器停在结尾帧。
        // 重新激活已播完的播放器时回到开头并按设置恢复播放；用户手动暂停（非 ENDED）不受影响。
        if (active && allowPlayback && state.player.playbackState == Player.STATE_ENDED) {
            state.player.seekTo(0L)
            state.playRequested = !settings.videoPreview
            state.refresh()
        }
        var ticks = 0
        while (active) {
            state.ownerView?.let { state.surfaceStateChanged(it, it.isVideoLayoutReady) }
            if (state.scrubPosition == null) {
                state.refresh()
                if (ticks++ % 5 == 0) state.save()
            }
            delay(200)
        }
    }
}

private fun stateName(state: Int): String = when (state) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> "STATE_$state"
}

/**
 * 会话级视频封面缓存。循环模式下同一真实页会出现在多个虚拟项里，缓存可以让重建的视图
 * 立即拿到已解码的封面；只保留最近使用的几页，避免长视频书占用过多内存。
 */
internal class VideoCoverCache(private val maxEntries: Int = 4) {
    private val values = mutableStateMapOf<Int, Bitmap>()
    private val order = ArrayDeque<Int>()

    operator fun get(index: Int): Bitmap? = values[index]

    fun put(index: Int, bitmap: Bitmap) {
        if (values[index] === bitmap) return
        values[index] = bitmap
        order.remove(index)
        order.addLast(index)
        while (order.size > maxEntries) {
            val oldest = order.removeFirst()
            if (oldest != index) values.remove(oldest)
        }
    }
}

@Composable
internal fun rememberVideoCover(session: BookSession, index: Int, repository: BookRepository,
    cache: VideoCoverCache): Bitmap? {
    val cached = cache[index]
    LaunchedEffect(session.book.cacheKey, index, cached == null) {
        if (cached != null) return@LaunchedEffect
        // 首次抽帧可能因解码器暂时繁忙失败，稍后重试一次。
        repeat(2) { attempt ->
            val started = android.os.SystemClock.elapsedRealtime()
            val bitmap = withContext(Dispatchers.IO) {
                repository.videoFrame(session, index)?.takeIf(File::isFile)?.let { BitmapFactory.decodeFile(it.absolutePath) }
            }
            if (bitmap != null) {
                cache.put(index, bitmap)
                DiagnosticLog.log("Cover", "index=$index 封面 ${bitmap.width}x${bitmap.height} 耗时=${android.os.SystemClock.elapsedRealtime() - started}ms")
                return@LaunchedEffect
            }
            DiagnosticLog.log("Cover", "index=$index 封面第 ${attempt + 1} 次加载失败")
            if (attempt == 0) delay(300)
        }
    }
    return cached
}

/**
 * 视频页始终使用同一个 AndroidView：有播放器时挂上播放器，没有播放器（未停靠或仅预载）时
 * 只显示缓存的封面。避免在“封面页 / 播放器页”之间切换视图结构造成闪屏。
 */
@Composable
internal fun VideoPage(
    state: VideoPlaybackState?, index: Int, session: BookSession, cover: Bitmap?,
    settings: ReaderSettings,
    videoPosition: (Int) -> Long, onProgress: (Int, Long) -> Unit,
    foreground: Color, background: Color, active: Boolean, allowPlayback: Boolean = active,
    zoomSequence: Int, zoomAction: Int, onTap: () -> Unit, onEnded: (VideoPlaybackState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewRef = remember { mutableStateOf<ZoomVideoView?>(null) }
    val haptic = hapticFeedback()
    val gesturesEnabled = active && allowPlayback
    val description = stringResource(
        if (settings.doubleTapZoom) R.string.video_accessibility else R.string.video_accessibility_without_zoom,
        index + 1,
    )
    if (state != null) {
        rememberVideoPlayback(state, session, index, active, allowPlayback, settings, videoPosition, onProgress) {
            onEnded(state)
        }
    }
    // The AndroidView update block is not guaranteed to re-run when this page becomes active again,
    // so re-assert surface ownership whenever activation changes.
    LaunchedEffect(state, active) {
        if (state != null && active) viewRef.value?.let { state.bindOwner(it, active = true) }
    }
    Box(modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        AndroidView(factory = { ZoomVideoView(it).also { view -> viewRef.value = view } }, modifier = Modifier.fillMaxSize(),
            onRelease = { view ->
                if (viewRef.value === view) viewRef.value = null
                state?.releaseOwner(view)
            }, update = { view ->
                viewRef.value = view
                view.bindPage(index)
                view.bindCover(cover)
                view.setCoverBackground(background.toArgb())
                view.onTap = onTap
                if (!gesturesEnabled) view.cancelPlaybackGestures()
                view.onSpeedHoldStart = { gesturesEnabled && state?.startSpeedHold() == true }
                view.onSpeedHoldEnd = { end ->
                    val feedback = state?.finishSpeedHold(end) ?: HapticFeedbackConstantsCompat.NO_HAPTICS
                    if (feedback != HapticFeedbackConstantsCompat.NO_HAPTICS) haptic(feedback)
                }
                view.onSwipeSeekStart = {
                    gesturesEnabled && settings.vertical && settings.horizontalVideoSeek && state?.startSwipeSeek() == true
                }
                view.onSwipeSeek = { fraction -> state?.swipeSeek(fraction) }
                view.onSwipeSeekEnd = { cancelled -> state?.finishSwipeSeek(cancelled) }
                view.doubleTapZoomEnabled = settings.doubleTapZoom
                view.zoomAnimationSpeed = settings.animationSpeed
                view.contentDescription = description
                if (state != null) {
                    state.bindOwner(view, active = active)
                    view.onLayoutReady = { ready -> state.surfaceStateChanged(view, ready) }
                    view.showVideoFrame(state.frameReady)
                } else {
                    view.showVideoFrame(false)
                    view.bindPlayer(null)
                    view.onLayoutReady = {}
                }
                if (zoomSequence > 0) view.command(zoomSequence, zoomAction)
            })
        if (state?.failed == true) Text(stringResource(R.string.video_error), Modifier.padding(28.dp), color = foreground)
        // 只在停靠页且首帧尚未渲染时显示进度圈；预载、拖动中的页面只显示封面，避免切换时闪现进度圈。
        else if (cover == null && state != null && active && !state.firstFrameReady &&
            (state.playbackState == Player.STATE_BUFFERING || state.playbackState == Player.STATE_IDLE)) {
            CircularProgressIndicator(color = foreground)
        }
    }
}

@Composable
internal fun VideoProgressControls(state: VideoPlaybackState, enabled: Boolean = true) {
    val haptic = hapticFeedback()
    val sliderHaptics = remember(state) { ProgressSliderHaptics() }
    val scrubbing = state.scrubPosition != null
    val displayedPosition = state.scrubPosition ?: state.position
    DisposableEffect(state) {
        onDispose { state.finishScrub() }
    }
    val positionSeconds = displayedPosition / 1000L
    val durationSeconds = state.duration / 1000L
    val time = remember(positionSeconds, durationSeconds) {
        "${formatVideoTime(displayedPosition)} / ${formatVideoTime(state.duration)}"
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(time, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            VideoSpeedMenu(state, enabled = enabled && !scrubbing && !state.failed && state.index >= 0)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                state.togglePlayback()
                haptic(if (state.playRequested) HapticFeedbackConstantsCompat.TOGGLE_ON else HapticFeedbackConstantsCompat.TOGGLE_OFF)
            }, enabled = enabled && !scrubbing && !state.failed && state.index >= 0) {
                Icon(if (state.playRequested) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    stringResource(if (state.playRequested) R.string.pause_video else R.string.play_video))
            }
            val progressDescription = stringResource(R.string.video_playback_progress)
            Slider(value = displayedPosition.toFloat().coerceIn(0f, state.duration.coerceAtLeast(1L).toFloat()),
                onValueChange = {
                    sliderHaptics.update((it / 1000f).toLong(), it <= 0f, it >= state.duration.toFloat(), haptic)
                    state.scrubTo(it.toLong())
                },
                onValueChangeFinished = state::finishScrub,
                valueRange = 0f..state.duration.coerceAtLeast(1L).toFloat(),
                enabled = enabled && state.seekable && state.duration > 0L && !state.failed,
                modifier = Modifier.weight(1f)
                    .trackProgressSliderDrag(sliderHaptics, positionSeconds, displayedPosition == 0L, displayedPosition >= state.duration)
                    .semantics { contentDescription = progressDescription })
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun VideoSpeedMenu(state: VideoPlaybackState, enabled: Boolean) {
    var expanded by remember(state, enabled) { mutableStateOf(false) }
    var anchorTop by remember { mutableFloatStateOf(0f) }
    val haptic = hapticFeedback()
    val label = stringResource(R.string.video_speed)
    Box(Modifier.onGloballyPositioned { anchorTop = it.boundsInWindow().top }) {
        Box(Modifier.height(40.dp).widthIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) { expanded = true }
            .padding(end = 4.dp), contentAlignment = Alignment.CenterEnd) {
            Text(videoSpeedOptions.first { it.first == state.speed }.second, style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) {
            Popup(AboveSpeedButton, onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
                val availableHeight = with(LocalDensity.current) { anchorTop.toDp() }
                Surface(shape = MaterialTheme.shapes.extraSmall, color = MenuDefaults.containerColor,
                    tonalElevation = MenuDefaults.TonalElevation, shadowElevation = 4.dp) {
                    Column(Modifier.width(112.dp).heightIn(max = availableHeight.coerceAtLeast(48.dp))
                        .verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                        videoSpeedOptions.forEach { (speed, text) ->
                            val isSelected = speed == state.speed
                            DropdownMenuItem(
                                text = { Text(text) },
                                onClick = {
                                    expanded = false
                                    if (state.selectSpeed(speed)) haptic(HapticFeedbackConstantsCompat.SEGMENT_TICK)
                                },
                                modifier = Modifier.semantics {
                                    role = Role.RadioButton
                                    selected = isSelected
                                },
                                trailingIcon = {
                                    if (isSelected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private object AboveSpeedButton : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val start = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
        return IntOffset(start.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (anchorBounds.top - popupContentSize.height).coerceAtLeast(0))
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
