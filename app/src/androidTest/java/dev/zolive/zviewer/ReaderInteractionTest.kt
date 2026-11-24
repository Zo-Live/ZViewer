package dev.zolive.zviewer

import android.view.View
import android.view.ViewGroup
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.lifecycle.Lifecycle
import dev.zolive.zviewer.data.*
import dev.zolive.zviewer.reader.ZoomVideoView
import dev.zolive.zviewer.reader.ZoomImageView
import dev.zolive.zviewer.reader.ReaderMediaItem
import dev.zolive.zviewer.ui.ReaderScreen
import dev.zolive.zviewer.ui.VideoPlaybackPool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

class ReaderInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var currentPage = 0
    private val saved = mutableMapOf<Int, Long>()
    private lateinit var session: BookSession
    private lateinit var readerSettings: MutableState<ReaderSettings>

    private fun open(video: Boolean = true, vertical: Boolean = true, loop: Boolean = true,
        rightToLeft: Boolean = false, preview: Boolean = true, initialPage: Int = 0, archive: Boolean = false,
        mixed: Boolean = false, mixedVideos: Boolean = false, names: List<String>? = null,
        doubleTapZoom: Boolean = true, paged: Boolean = !vertical) {
        val context = rule.activity
        val assets = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        val names = names ?: if (mixed && mixedVideos) listOf("page1.png", "video-folder/video2.mp4", "video-folder/video10.mp4", "page2.png")
            else if (mixed) listOf("page1.png", "video-folder/video2.mp4", "page2.png")
            else if (video) listOf("video-folder/video2.mp4", "video-folder/video10.mp4", "rotated.mp4")
            else listOf("page1.png", "page2.png", "page10.png")
        val pages = names.map { name ->
            val file = File(context.cacheDir, "reader-test-${name.substringAfterLast('/')}")
            assets.open("formats/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            PageSource(name, filePath = file.absolutePath, mediaType = if (name.extensionLower() in videoExtensions) "video" else "image")
        }
        val book = Book("reader-test", "test", "阅读手势测试", "folder", 0, 0)
        val repository = BookRepository(context)
        session = if (archive) {
            val tree = DocumentsContract.buildTreeDocumentUri("dev.zolive.zviewer.test.documents", "root/formats")
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "root/formats/videos.zip")
            runBlocking { repository.open(Book("archive-test", uri.toString(), "视频压缩包测试", "zip", 0, 0)) }
        } else BookSession(book, pages)
        currentPage = initialPage
        readerSettings = mutableStateOf(ReaderSettings(vertical = vertical, paged = paged, loopMode = loop,
            rightToLeft = rightToLeft, videoPreview = preview, doubleTapZoom = doubleTapZoom))
        rule.setContent {
            ReaderScreen(session, initialPage, readerSettings.value, repository,
                onSettings = { readerSettings.value = it }, onProgress = { currentPage = it },
                videoPosition = { saved[it] ?: 0L }, onVideoProgress = { page, position -> saved[page] = position }, onBack = {})
        }
        if (video && !mixed || mixed && session.pages[initialPage].isVideo) waitForVideo() else rule.waitUntil(20_000) {
            var ready = false
            rule.runOnUiThread {
                ready = descendants(rule.activity.window.decorView).filterIsInstance<ZoomImageView>()
                    .any { it.drawable != null && it.contentDescription == context.getString(R.string.image_accessibility, initialPage + 1) }
            }
            ready
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun videoViews(view: View = rule.activity.window.decorView): List<ZoomVideoView> = when (view) {
        is ZoomVideoView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { videoViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    /** 当前页对应的视频视图。视频书会同时预载相邻页（循环模式下同一真实页还可能出现在多个虚拟项），
     * 因此只取当前可见且播放器指向当前页的那一个。 */
    private fun currentVideoView(): ZoomVideoView {
        val path = session.pages[currentPage].filePath
        val rect = Rect()
        return videoViews().single {
            it.playerView.player?.currentMediaItem?.localConfiguration?.uri?.path == path && it.getGlobalVisibleRect(rect)
        }
    }

    private fun waitForVideo() {
        // 模拟器软解准备可能较慢，尤其是连续切换后；放宽到 30 秒。
        rule.waitUntil(30_000) {
            var ready = false
            rule.runOnUiThread {
                val path = session.pages[currentPage].filePath
                ready = videoViews().any { view ->
                    val player = view.playerView.player
                    player?.playbackState == Player.STATE_READY && player.videoSize.height > 0 &&
                        player.currentMediaItem?.localConfiguration?.uri?.path == path
                }
            }
            ready
        }
    }

    private fun swipe(forward: Boolean = true, vertical: Boolean = true) {
        rule.onRoot().performTouchInput {
            val start = if (vertical) Offset(centerX, height * .65f) else Offset(width * .75f, height * .4f)
            val end = if (vertical) Offset(centerX, height * .25f) else Offset(width * .25f, height * .4f)
            swipe(if (forward) start else end, if (forward) end else start, 200)
        }
    }

    private fun toggleControls(visible: Boolean = true) {
        rule.onRoot().performTouchInput { click(Offset(centerX, height * .35f)) }
        rule.waitUntil(15_000) {
            rule.onAllNodesWithContentDescription(rule.activity.getString(R.string.video_playback_progress)).fetchSemanticsNodes().isNotEmpty() == visible
        }
        rule.waitForIdle()
    }

    private fun sliderDrag(slider: SemanticsNodeInteraction, fractions: List<Float>, inspect: (Float, Float) -> Unit) {
        rule.waitUntil(5_000) { isEnabled().matches(slider.fetchSemanticsNode()) }
        val node = slider.assertIsDisplayed().fetchSemanticsNode()
        val bounds = node.boundsInWindow
        val range = node.config[SemanticsProperties.ProgressBarRangeInfo].range
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        var fraction = .15f
        fun send(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                bounds.left + bounds.width * fraction, bounds.center.y, 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN)
        try {
            for (target in fractions) {
                val start = fraction
                for (step in 1..6) {
                    fraction = start + (target - start) * step / 6
                    send(MotionEvent.ACTION_MOVE)
                    SystemClock.sleep(25)
                }
                var position = 0f
                rule.waitUntil(5_000) {
                    position = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
                    val expected = range.start + target * (range.endInclusive - range.start)
                    kotlin.math.abs(position - expected) <= (range.endInclusive - range.start) * .06f
                }
                inspect(target, position)
            }
        } finally {
            send(MotionEvent.ACTION_UP)
        }
    }

    private fun screenshot(name: String): Bitmap {
        rule.waitForIdle()
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(rule.activity.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun waitForPlayback() {
        rule.waitUntil(30_000) {
            var playing = false
            rule.runOnUiThread {
                val path = session.pages[currentPage].filePath
                playing = videoViews().any { view ->
                    val player = view.playerView.player
                    player?.isPlaying == true && player.currentPosition > 0L &&
                        player.currentMediaItem?.localConfiguration?.uri?.path == path
                }
            }
            playing
        }
    }

    @Test fun openingVideoArchiveAutoplaysWithoutInteraction() {
        open(archive = true, preview = false)
        waitForPlayback()
        rule.runOnIdle { assertEquals(0, currentPage) }
    }

    @Test fun reopeningVideoArchiveAutoplaysAtSavedPosition() {
        saved[1] = 4000L
        open(archive = true, preview = false, initialPage = 1)
        waitForPlayback()
        rule.runOnIdle {
            assertEquals(1, currentPage)
            assertTrue(currentVideoView().playerView.player!!.currentPosition >= 4000L)
        }
    }

    @Test fun draggingToNextVideoKeepsCurrentPlayingAndShowsIncomingFrame() {
        open(preview = false)
        waitForPlayback()
        lateinit var outgoing: ZoomVideoView
        lateinit var incoming: ZoomVideoView
        val incomingRect = Rect()
        rule.runOnIdle {
            outgoing = currentVideoView()
            val nextPath = session.pages[(currentPage + 1) % session.pages.size].filePath
            incoming = videoViews().first { it.playerView.player?.currentMediaItem?.localConfiguration?.uri?.path == nextPath }
        }
        // 拖动到过半之前并保持按住：此时还没有正式切换到下一段。
        var position = 0L
        rule.onRoot().performTouchInput {
            down(Offset(centerX, height * .62f))
            moveTo(Offset(centerX, height * .34f), 450)
        }
        rule.waitForIdle()
        rule.runOnIdle {
            val views = videoViews()
            position = outgoing.playerView.player!!.currentPosition
            assertTrue("拖动时应保留当前视频视图", views.contains(outgoing))
            assertTrue("拖动时当前视频应继续播放", outgoing.playerView.player!!.playWhenReady)
            assertSame("下一段视频应沿用预载好的播放器", incoming, views.first {
                it.playerView.player?.currentMediaItem?.localConfiguration?.uri?.path ==
                    session.pages[(currentPage + 1) % session.pages.size].filePath
            })
            assertFalse("拖动时下一段视频应保持暂停", incoming.playerView.player!!.playWhenReady)
            // 停靠页与左右相邻页各持有一个预载播放器，其余离屏页只显示封面。
            assertTrue("同时存在的预载播放器不应超过三个", views.count { it.playerView.player != null } <= 3)
            assertTrue("目标页应滑入可见区域", incoming.getGlobalVisibleRect(incomingRect))
        }
        rule.waitUntil(5_000) {
            var advancing = false
            rule.runOnUiThread { advancing = outgoing.playerView.player!!.currentPosition > position + 200 }
            advancing
        }
        // 目标页在拖动期间已预建播放器 / 显示封面，下一段区域不应该是黑的。
        var colored = 0
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val bitmap = screenshot("transition-cover")
            colored = 0
            for (pixelY in incomingRect.top.coerceAtLeast(0) until incomingRect.bottom.coerceAtLeast(0) step 12) {
                for (pixelX in incomingRect.left.coerceAtLeast(0) until incomingRect.right.coerceAtLeast(0) step 12) {
                    val color = bitmap.getPixel(pixelX, pixelY)
                    if (Color.green(color) < 80 && (Color.red(color) > 150 || Color.blue(color) > 150)) colored++
                }
            }
            bitmap.recycle()
            if (colored > 10) break
            Thread.sleep(300)
        }
        assertTrue("滑入中的下一段视频应显示首帧而不是黑屏，colored=$colored", colored > 10)
        // 越过中线后松开，完成平滑切换，之后由目标视频接管播放。
        rule.onRoot().performTouchInput {
            moveTo(Offset(centerX, height * .05f), 200)
            up()
        }
        rule.waitUntil(5_000) { currentPage == 1 }
        waitForVideo()
        waitForPlayback()
    }

    /**
     * 拖动到下一段视频再原路拉回：目标页在仍可见期间不能被回收，否则会看到画面闪断
     * （Surface 被释放后再重建，画面跳回封面 / 首帧）。
     */
    @Test fun reversingDuringVideoDragKeepsIncomingPlayerAttached() {
        open(preview = false)
        waitForPlayback()
        lateinit var incoming: ZoomVideoView
        lateinit var incomingPlayer: Player
        rule.runOnIdle {
            val nextPath = session.pages[(currentPage + 1) % session.pages.size].filePath
            incoming = videoViews().first { it.playerView.player?.currentMediaItem?.localConfiguration?.uri?.path == nextPath }
            incomingPlayer = incoming.playerView.player!!
        }
        // 先拖过中线让目标页进入可见区域，再在同一次手势里拉回（不松手），
        // 但保留一小部分目标页可见，验证目标切换回上一页时播放器不会被回收。
        rule.onRoot().performTouchInput {
            down(Offset(centerX, height * .62f))
            moveTo(Offset(centerX, height * .30f), 400)
            moveTo(Offset(centerX, height * .44f), 200)
            moveTo(Offset(centerX, height * .50f), 200)
        }
        rule.waitForIdle()
        var visible = false
        rule.runOnIdle {
            assertSame("拉回过程中目标页应继续持有预载播放器", incomingPlayer, incoming.playerView.player)
            assertFalse("目标页播放器应保持暂停", incomingPlayer.playWhenReady)
            visible = incoming.getGlobalVisibleRect(Rect())
        }
        assertTrue("拉回过程中目标页仍应可见，才能验证回收时机", visible)
        rule.onRoot().performTouchInput { up() }
        rule.waitUntil(5_000) { currentPage == 0 }
        rule.runOnIdle { assertSame("手势结束后相邻页的播放器仍可复用", incomingPlayer, incoming.playerView.player) }
        swipe()
        rule.waitUntil(15_000) { currentPage == 1 }
        waitForVideo()
        waitForPlayback()
    }

    /**
     * 临近片尾时滑动切换不能重置播放器的视频输出。ExoPlayer 在快播完时切换 repeatMode
     * 会重新初始化解码器（视频尺寸归零、画面重新渲染首帧），在真机与模拟器上都会闪屏；
     * 切换期间播完由 onEnded 重新播放处理，因此不应根据滚动状态切换 repeatMode。
     */
    @Test fun switchingVideoNearEndDoesNotResetVideoOutput() {
        open(preview = false)
        waitForPlayback()
        val player = rule.runOnIdle { currentVideoView().playerView.player!! }
        val zeroSizes = java.util.concurrent.atomic.AtomicInteger(0)
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.height == 0) zeroSizes.incrementAndGet()
            }
        }
        rule.runOnIdle {
            player.addListener(listener)
            player.seekTo(player.duration - 900)
        }
        waitForPlayback()
        // 拖过中线再拉回，覆盖切换的“开始 / 结束”两个阶段。
        rule.onRoot().performTouchInput {
            down(Offset(centerX, height * .62f))
            moveTo(Offset(centerX, height * .32f), 250)
            moveTo(Offset(centerX, height * .66f), 250)
            up()
        }
        rule.waitForIdle()
        Thread.sleep(800)
        rule.runOnIdle { player.removeListener(listener) }
        assertEquals("临近片尾切换视频不应重置解码器输出", 0, zeroSizes.get())
    }

    @Test fun currentVideoLoopsWhenEndedDuringTransition() {
        open(preview = false)
        waitForPlayback()
        lateinit var outgoing: ZoomVideoView
        rule.runOnIdle { outgoing = currentVideoView() }
        rule.onRoot().performTouchInput {
            down(Offset(centerX, height * .62f))
            moveTo(Offset(centerX, height * .34f), 350)
        }
        rule.waitForIdle()
        rule.runOnIdle { outgoing.playerView.player!!.seekTo(11_900L) }
        rule.waitUntil(8_000) {
            var looped = false
            rule.runOnUiThread {
                val player = outgoing.playerView.player!!
                looped = player.isPlaying && player.playbackState != Player.STATE_ENDED && player.currentPosition < 6_000L
            }
            looped
        }
        // 松开后未过半的拖动回到原页，当前视频仍在循环位置继续播放。
        rule.onRoot().performTouchInput { up() }
        rule.waitUntil(5_000) { currentPage == 0 }
        rule.runOnIdle { assertTrue(outgoing.playerView.player!!.isPlaying) }
    }

    @Test fun verticalVideoSwipesAndButtonsShowOnePlayerAfterTransition() {
        open()
        var previous: Player? = null
        repeat(9) { step ->
            swipe()
            rule.waitUntil(15_000) { currentPage == (step + 1) % 3 }
            waitForVideo()
            rule.runOnIdle {
                val view = currentVideoView()
                if (previous != null) assertNotSame(previous, view.playerView.player)
                previous = view.playerView.player
                assertEquals(rule.activity.window.decorView.height, view.height)
                assertEquals(1f, view.playerView.scaleX, .001f)
                val surface = view.playerView.videoSurfaceView!!
                val videoSize = view.playerView.player!!.videoSize
                val expected = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                assertEquals(expected, surface.width.toFloat() / surface.height, .03f)
            }
        }
        toggleControls()
        repeat(6) {
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.next_page)).performClick()
            rule.waitUntil(15_000) { currentPage == (it + 1) % 3 }
        }
        repeat(6) {
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.previous_page)).performClick()
            rule.waitUntil(15_000) { currentPage == Math.floorMod(-it - 1, 3) }
        }
        waitForVideo()
        rule.runOnIdle { assertNotNull(currentVideoView()) }
    }

    @Test fun horizontalPagingAndDoubleTapDoNotSeekOrPlay() {
        open(vertical = false, rightToLeft = true)
        swipe(forward = false, vertical = false)
        rule.waitUntil(15_000) { currentPage == 1 }
        waitForVideo()
        rule.onRoot().performTouchInput { doubleClick(Offset(width * .2f, height * .4f)) }
        waitForVideoZoom(2.5f)
        rule.runOnIdle {
            val view = currentVideoView()
            assertFalse(view.playerView.player!!.playWhenReady)
            assertEquals(0L, view.playerView.player!!.currentPosition)
        }
        rule.onRoot().performTouchInput { doubleClick(Offset(width * .8f, height * .4f)) }
        waitForVideoZoom(1f)
        swipe(forward = true, vertical = false)
        rule.waitUntil(15_000) { currentPage == 0 }
    }

    private fun waitForVideoZoom(scale: Float) {
        rule.waitUntil(5_000) {
            var reached = false
            rule.runOnUiThread { reached = kotlin.math.abs(currentVideoView().playerView.scaleX - scale) < .01f }
            reached
        }
    }

    @Test fun disablingDoubleTapZoomStillAllowsVideoToolbarZoom() {
        open(doubleTapZoom = false)
        rule.onRoot().performTouchInput { doubleClick(Offset(centerX, height * .35f)) }
        Thread.sleep(300)
        rule.runOnIdle { assertEquals(1f, currentVideoView().playerView.scaleX, .01f) }
        toggleControls()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.zoom_in_video)).performClick()
        waitForVideoZoom(1.5f)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.zoom_out_video)).performClick()
        waitForVideoZoom(1f)
    }

    @Test fun progressControlsSeekAndHideTogether() {
        open(names = listOf("moving-video/moving.mp4", "video-folder/video2.mp4"))
        toggleControls()
        val player = rule.runOnIdle { currentVideoView().playerView.player!! }
        fun frame() = rule.runOnIdle { (currentVideoView().playerView.videoSurfaceView as android.view.TextureView).bitmap!! }
        val slider = rule.onNodeWithContentDescription(rule.activity.getString(R.string.video_playback_progress))
        var before = frame()
        var lastTarget = 0L
        sliderDrag(slider, listOf(.35f, .8f, .4f, .6f)) { fraction, position ->
            assertEquals(fraction * 60_000, position, 3_500f)
            val target = position.toLong()
            try {
                rule.waitUntil(5_000) {
                    val dragging = frame()
                    val changed = !before.sameAs(dragging)
                    dragging.recycle()
                    changed && rule.runOnIdle { player.currentPosition == target && !player.playWhenReady }
                }
            } catch (timeout: ComposeTimeoutException) {
                val position = rule.runOnIdle { player.currentPosition }
                throw AssertionError("拖动至 ${target}ms，播放器位置为 ${position}ms", timeout)
            }
            before.recycle()
            before = frame()
            lastTarget = target
            assertEquals(0L, rule.runOnIdle { saved[0] ?: 0L })
        }
        before.recycle()
        val timeText = rule.onNode(hasText(" / 01:00", substring = true)).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().text
        assertTrue(timeText, Regex("00:3[0-9] / 01:00").matches(timeText))
        rule.waitUntil(15_000) { rule.runOnIdle { saved[0] } == lastTarget }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.play_video)).performClick()
        rule.waitUntil(15_000) {
            var playing = false
            rule.runOnUiThread { playing = currentVideoView().playerView.player!!.isPlaying }
            playing
        }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.pause_video)).performClick()
        toggleControls(visible = false)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.video_playback_progress)).assertDoesNotExist()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.next_page)).assertDoesNotExist()
    }

    @Test fun videoSpeedHoldLocksOnlyWithVerticalMovementAndRespectsToolbars() {
        open(names = listOf("moving-video/moving.mp4"))
        val player = rule.runOnIdle { currentVideoView().playerView.player!! }
        val root = rule.onRoot()
        fun hold(yFraction: Float = .45f) {
            root.performTouchInput { down(Offset(width * .82f, height * yFraction)); advanceEventTime(650) }
        }
        fun speed() = rule.runOnIdle { player.playbackParameters.speed }

        hold(.82f)
        rule.waitUntil(4_000) { speed() == 2f }
        waitForPlayback()
        root.performTouchInput { moveBy(Offset(-width * .25f, -height * .10f)); up() }
        assertEquals(1f, speed(), 0f)

        hold()
        rule.waitUntil(4_000) { speed() == 2f }
        root.performTouchInput { moveBy(Offset(0f, -height * .15f)); up() }
        assertEquals(2f, speed(), 0f)
        hold()
        root.performTouchInput { moveBy(Offset(0f, 6f)); up() }
        assertEquals(2f, speed(), 0f)
        hold()
        root.performTouchInput { moveBy(Offset(0f, height * .15f)); up() }
        assertEquals(1f, speed(), 0f)

        toggleControls()
        val toolbarY = rule.onNodeWithContentDescription(rule.activity.getString(R.string.reader_settings))
            .fetchSemanticsNode().boundsInRoot.center.y
        root.performTouchInput { longClick(Offset(width * .30f, toolbarY), 650) }
        assertEquals(1f, speed(), 0f)

        val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        val menuItem = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        for ((label, rate) in listOf("0.5x" to .5f, "0.75x" to .75f, "1.5x" to 1.5f)) {
            rule.onNode(button and hasText("1.0x")).performClick()
            rule.onNode(menuItem and hasText(label)).performClick()
            assertEquals(rate, speed(), 0f)

            hold()
            rule.waitUntil(4_000) { speed() == 2f }
            root.performTouchInput { up() }
            assertEquals(rate, speed(), 0f)

            hold()
            rule.waitUntil(4_000) { speed() == 2f }
            root.performTouchInput { moveBy(Offset(0f, height * .15f)); up() }
            assertEquals(1f, speed(), 0f)
        }

        hold()
        rule.waitUntil(4_000) { speed() == 2f }
        root.performTouchInput { moveBy(Offset(0f, height * .15f)); up() }
        assertEquals(1f, speed(), 0f)
        hold()
        rule.waitUntil(4_000) { speed() == 2f }
        root.performTouchInput { cancel() }
        assertEquals(1f, speed(), 0f)
        assertEquals(0, currentPage)
    }

    @Test fun videoSpeedMenuOpensAboveAnchorAndAppliesAllRatesWithoutPitchShift() {
        open(names = listOf("moving-video/moving.mp4"))
        toggleControls()
        val player = rule.runOnIdle { currentVideoView().playerView.player!! }
        val rates = listOf("2.0x" to 2f, "1.5x" to 1.5f, "1.0x" to 1f, "0.75x" to .75f, "0.5x" to .5f)
        val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        val menuItem = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        fun screenBounds(node: SemanticsNode): ComposeRect = rule.runOnIdle {
            val coordinates = node.layoutInfo.coordinates
            node.boundsInWindow.translate(coordinates.positionOnScreen() - coordinates.positionInWindow())
        }
        var displayed = "1.0x"
        for ((text, rate) in rates) {
            val anchor = rule.onNode(button and hasText(displayed)).assertIsDisplayed()
            val anchorBounds = screenBounds(anchor.fetchSemanticsNode())
            anchor.performClick()
            rule.onNode(menuItem and hasText(displayed)).assertIsSelected()
            val bounds = rates.map { (option, _) -> screenBounds(rule.onNode(menuItem and hasText(option)).fetchSemanticsNode()) }
            bounds.zipWithNext().forEach { (above, below) -> assertTrue(above.bottom <= below.top) }
            assertEquals(anchorBounds.top, bounds.last().bottom, 12f)
            assertEquals(anchorBounds.right, bounds.last().right, 2f)
            rule.onNode(menuItem and hasText(text)).performClick()
            rule.runOnIdle {
                assertEquals(rate, player.playbackParameters.speed, 0f)
                assertEquals(1f, player.playbackParameters.pitch, 0f)
                assertFalse(player.playWhenReady)
            }
            displayed = text
        }
        rule.onNode(button and hasText(displayed)).assertIsDisplayed()
    }

    @Test fun toolbarSpeedLockInheritsLatestRateAcrossMixedContentAndUnlockKeepsCurrentRate() {
        open(mixed = true, vertical = false, loop = false,
            names = listOf("moving-video/moving.mp4", "video-folder/video10.mp4", "page1.png", "video-folder/video2.mp4"))
        toggleControls()
        val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        val menuItem = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        val locked = SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, rule.activity.getString(R.string.video_speed_locked),
        )
        val speed = rule.onNode(button and hasText("0.75x"))
        fun assertPlayerSpeed(expected: Float) = rule.runOnIdle {
            assertEquals(expected, currentVideoView().playerView.player!!.playbackParameters.speed, 0f)
        }
        rule.onNode(button and hasText("1.0x")).performClick()
        rule.onNode(menuItem and hasText("1.5x")).performClick()
        val initialSpeed = rule.onNode(button and hasText("1.5x"))
        initialSpeed.performTouchInput { longClick() }.assert(locked)
        initialSpeed.performTouchInput { click() }
        rule.onNode(menuItem and hasText("1.5x")).assertIsSelected()
        rule.onNode(menuItem and hasText("0.75x")).performTouchInput { click() }
        speed.assert(locked)
        assertPlayerSpeed(.75f)

        val root = rule.onRoot()
        root.performTouchInput { down(Offset(width * .82f, height * .45f)); advanceEventTime(650) }
        rule.waitUntil(4_000) { rule.runOnIdle { currentVideoView().playerView.player!!.playbackParameters.speed == 2f } }
        root.performTouchInput { up() }
        speed.assert(locked)
        assertPlayerSpeed(.75f)

        swipe(vertical = false)
        rule.waitUntil(15_000) { currentPage == 1 }
        waitForVideo()
        speed.assert(locked)
        assertPlayerSpeed(.75f)
        swipe(vertical = false)
        rule.waitUntil(15_000) { currentPage == 2 }
        swipe(vertical = false)
        rule.waitUntil(15_000) { currentPage == 3 }
        waitForVideo()
        speed.assert(locked)
        assertPlayerSpeed(.75f)
        speed.performTouchInput { longClick() }.assert(!locked)
        assertPlayerSpeed(.75f)

        rule.onRoot().performTouchInput {
            down(Offset(width * .4f, height * .4f))
            moveBy(Offset(width * .15f, 0f))
            cancel()
        }
        rule.waitForIdle()
        assertEquals(3, currentPage)
        speed.assert(!locked)

        swipe(forward = false, vertical = false)
        rule.waitUntil(15_000) { currentPage == 2 }
        swipe(vertical = false)
        rule.waitUntil(15_000) { currentPage == 3 }
        waitForVideo()
        rule.onNode(button and hasText("1.0x")).assertIsDisplayed()
        assertPlayerSpeed(1f)

        swipe(forward = false, vertical = false)
        rule.waitUntil(15_000) { currentPage == 2 }
        swipe(forward = false, vertical = false)
        rule.waitUntil(15_000) { currentPage == 1 }
        waitForVideo()
        val normalSpeed = rule.onNode(button and hasText("1.0x"))
        normalSpeed.assertIsDisplayed()
        assertPlayerSpeed(1f)

        normalSpeed.performTouchInput { longClick() }.assert(locked)
        normalSpeed.performClick()
        rule.onNode(menuItem and hasText("0.75x")).performClick()
        speed.assert(locked)
        root.performTouchInput { down(Offset(width * .82f, height * .45f)); advanceEventTime(650) }
        rule.waitUntil(4_000) { rule.runOnIdle { currentVideoView().playerView.player!!.playbackParameters.speed == 2f } }
        root.performTouchInput { moveBy(Offset(0f, height * .15f)); up() }
        normalSpeed.assert(locked)
        assertPlayerSpeed(1f)

        swipe(forward = false, vertical = false)
        rule.waitUntil(15_000) { currentPage == 0 }
        waitForVideo()
        normalSpeed.assert(locked)
        assertPlayerSpeed(1f)
    }

    @Test fun horizontalVideoSwipeSeeksOnlyWhenEnabledAndDirectionMatches() {
        open(names = listOf("moving-video/moving.mp4", "video-folder/video2.mp4"))
        val player = rule.runOnIdle { currentVideoView().playerView.player!! }
        fun position() = rule.runOnIdle { player.currentPosition }
        swipe(forward = false, vertical = false)
        rule.waitUntil(5_000) { position() in 25_000L..35_000L }
        assertEquals(0, currentPage)
        assertFalse(rule.runOnIdle { player.playWhenReady })
        swipe(vertical = false)
        rule.waitUntil(5_000) { position() <= 2_000L }
        val before = position()

        rule.onRoot().performTouchInput {
            swipe(center, center + Offset(6f, 1f), 180)
            swipe(center, center + Offset(width * .14f, width * .12f), 180)
        }
        assertEquals(before, position())

        rule.onRoot().performTouchInput {
            down(Offset(width * .2f, height * .25f))
            repeat(40) { moveBy(Offset(width * .0125f, width * .0075f)) }
        }
        rule.waitUntil(5_000) { position() in 25_000L..35_000L }
        rule.onRoot().performTouchInput { cancel() }
        rule.waitUntil(5_000) { position() == before }
        assertEquals(0, currentPage)

        rule.onRoot().performTouchInput {
            down(Offset(width * .2f, height * .25f))
            repeat(40) { moveBy(Offset(width * .0125f, width * .0075f)) }
            up()
        }
        rule.waitUntil(5_000) { position() in 25_000L..35_000L }
        assertEquals(0, currentPage)

        val after = position()
        rule.runOnIdle { readerSettings.value = readerSettings.value.copy(horizontalVideoSeek = false) }
        swipe(forward = false, vertical = false)
        assertEquals(after, position())
        swipe(vertical = true)
        rule.waitUntil(10_000) { currentPage == 1 }
    }

    @Test fun draggingPageProgressShowsTheTargetBeforeSavingIt() {
        open(video = false, vertical = false, loop = false, names = List(360) {
            listOf("page1.png", "page2.png", "page10.png")[it % 3]
        })
        rule.onRoot().performTouchInput { click(Offset(centerX, height * .35f)) }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithContentDescription(rule.activity.getString(R.string.reader_settings))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        val slider = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        var targetPage = 0
        sliderDrag(slider, listOf(.5f, .99f, .5f, .99f)) { fraction, position ->
            assertEquals(fraction * session.pages.lastIndex, position, 12f)
            targetPage = position.roundToInt()
            val page = targetPage + 1
            val description = rule.activity.getString(R.string.image_accessibility, page)
            val thumbnail = rule.onNodeWithContentDescription(
                rule.activity.getString(R.string.image_accessibility_without_zoom, page)
            )
            rule.waitUntil(5_000) {
                !thumbnail.isDisplayed() && rule.runOnIdle {
                    descendants(rule.activity.window.decorView).filterIsInstance<ZoomImageView>().any {
                        it.drawable != null && it.contentDescription == description && it.getGlobalVisibleRect(Rect())
                    }
                }
            }
            assertEquals(0, rule.runOnIdle { currentPage })
        }
        rule.waitUntil(5_000) { rule.runOnIdle { currentPage } == targetPage }
        rule.waitUntil(5_000) {
            rule.runOnIdle {
                val description = rule.activity.getString(R.string.image_accessibility, targetPage + 1)
                descendants(rule.activity.window.decorView).filterIsInstance<ZoomImageView>().any {
                    it.drawable != null && it.contentDescription == description && it.getGlobalVisibleRect(Rect())
                }
            }
        }
    }

    @Test fun releasingPageProgressStartsTheDistantVideoAfterPreviewingIt() {
        open(preview = false, loop = false, names = listOf(
            "moving-video/moving.mp4", "video-folder/video2.mp4", "page1.png", "page2.png", "rotated.mp4",
        ), mixed = true)
        toggleControls()
        val slider = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo) and
            !hasContentDescription(rule.activity.getString(R.string.video_playback_progress)))
        sliderDrag(slider, listOf(.99f)) { _, position ->
            assertEquals(4, position.roundToInt())
            val description = rule.activity.getString(R.string.video_accessibility, 5)
            rule.waitUntil(10_000) {
                rule.runOnIdle {
                    videoViews().any {
                        it.contentDescription == description && it.getGlobalVisibleRect(Rect()) &&
                            it.playerView.player?.videoSize?.width ?: 0 > 0
                    }
                }
            }
            rule.runOnIdle {
                val path = session.pages[4].filePath
                val player = videoViews().first { it.playerView.player?.currentMediaItem?.localConfiguration?.uri?.path == path }
                    .playerView.player!!
                assertFalse(player.playWhenReady)
            }
            assertEquals(0, rule.runOnIdle { currentPage })
        }
        rule.waitUntil(10_000) { rule.runOnIdle { currentPage } == 4 }
        waitForVideo()
        rule.waitUntil(10_000) {
            rule.runOnIdle { currentVideoView().playerView.player?.isPlaying == true }
        }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.video_playback_progress)).assertIsEnabled()
    }

    @Test fun draggingContinuousPageProgressShowsTheFollowingPage() {
        open(video = false, names = List(8) { index ->
            listOf("page1.png", "page2.png", "page10.png")[index % 3]
        })
        rule.onRoot().performTouchInput { click(Offset(centerX, height * .35f)) }
        val slider = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        rule.waitUntil(5_000) { slider.isDisplayed() }
        var targetPage = 0
        sliderDrag(slider, listOf(.5f, .99f, .4f)) { fraction, position ->
            targetPage = position.roundToInt()
            if (fraction == .99f) assertEquals(session.pages.lastIndex, targetPage)
            val followingPage = (targetPage + 1) % session.pages.size
            val firstDescription = rule.activity.getString(R.string.image_accessibility, targetPage + 1)
            val nextDescription = rule.activity.getString(R.string.image_accessibility, followingPage + 1)
            rule.waitUntil(5_000) {
                rule.runOnIdle {
                    val images = descendants(rule.activity.window.decorView).filterIsInstance<ZoomImageView>()
                    val first = Rect()
                    val next = Rect()
                    images.any { it.contentDescription == firstDescription && it.drawable != null && it.getGlobalVisibleRect(first) } &&
                        images.any { it.contentDescription == nextDescription && it.drawable != null && it.getGlobalVisibleRect(next) } &&
                        next.top >= first.bottom
                }
            }
            assertEquals(0, rule.runOnIdle { currentPage })
        }
        rule.waitUntil(5_000) { rule.runOnIdle { currentPage } == targetPage }
        rule.waitUntil(5_000) {
            rule.runOnIdle {
                val nextDescription = rule.activity.getString(R.string.image_accessibility, targetPage + 2)
                descendants(rule.activity.window.decorView).filterIsInstance<ZoomImageView>().any {
                    it.contentDescription == nextDescription && it.drawable != null && it.getGlobalVisibleRect(Rect())
                }
            }
        }
    }

    @Test fun continuousToolbarZoomUsesThePageNearestTheViewportCenter() {
        open(video = false, loop = false)
        rule.onRoot().performTouchInput {
            swipe(Offset(centerX, height * .72f), Offset(centerX, height * .38f), 850)
        }
        rule.onRoot().performTouchInput { click(Offset(centerX, height * .35f)) }
        val zoomIn = rule.onNodeWithContentDescription(rule.activity.getString(R.string.zoom_in_image))
        rule.waitUntil(5_000) { zoomIn.isDisplayed() }
        rule.runOnIdle { assertEquals(0, currentPage) }
        val first = rule.activity.getString(R.string.image_accessibility, 1)
        val second = rule.activity.getString(R.string.image_accessibility, 2)
        fun imageViews() = descendants(rule.activity.window.decorView).filterIsInstance<ZoomImageView>()
        rule.waitUntil(5_000) { rule.runOnIdle { imageViews().any { it.contentDescription == second } } }
        fun scale(description: String): Float = rule.runOnIdle {
            val values = FloatArray(9)
            imageViews().single { it.contentDescription == description }.imageMatrix.getValues(values)
            values[android.graphics.Matrix.MSCALE_X]
        }
        val firstScale = scale(first)
        val secondScale = scale(second)
        zoomIn.performClick()
        rule.waitUntil(5_000) { scale(second) > secondScale * 1.45f }
        assertEquals(firstScale, scale(first), .001f)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.zoom_out_image)).performClick()
        rule.waitUntil(5_000) { kotlin.math.abs(scale(second) - secondScale) < .001f }
    }

    @Test fun verticalPagedImagesSnapOnePageAtATime() {
        open(video = false, loop = false, paged = true)
        assertEquals(setOf(1), visibleImageBounds().keys)
        swipe()
        rule.waitUntil(10_000) { currentPage == 1 && visibleImageBounds().keys == setOf(2) }
    }

    @Test fun horizontalContinuousFitShowsTheWholePageAtBaseScale() {
        open(video = false, loop = false, vertical = false, paged = false)
        val viewport = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val first = rule.activity.getString(R.string.image_accessibility, 1)
        fun pageSize(): Pair<Int, Int> = rule.runOnIdle {
            val view = descendants(rule.activity.window.decorView).filterIsInstance<ZoomImageView>()
                .single { it.contentDescription == first }
            view.width to view.height
        }
        assertTrue(pageSize().first > viewport.width)
        rule.runOnIdle { readerSettings.value = readerSettings.value.copy(continuousImageFitScreen = true) }
        rule.waitUntil(5_000) { pageSize().first == viewport.width.roundToInt() }
        assertTrue(pageSize().second < viewport.height)
        val visible = visibleImageBounds().getValue(1)
        assertEquals(pageSize().first, visible.width())
        assertEquals(pageSize().second, visible.height())
        assertEquals(0, rule.runOnIdle { currentPage })
    }

    @Test fun pagedFitHeightLetsTheReaderPanThenTurnThePage() {
        open(video = false, loop = false, vertical = false, paged = true)
        rule.runOnIdle { readerSettings.value = readerSettings.value.copy(pagedImageFit = PagedImageFit.Height) }
        val first = rule.activity.getString(R.string.image_accessibility, 1)
        fun pageFrame(): Pair<RectF, Int> = rule.runOnIdle {
            val view = descendants(rule.activity.window.decorView).filterIsInstance<ZoomImageView>()
                .single { it.contentDescription == first }
            val image = view.drawable
            val frame = RectF(0f, 0f, image.intrinsicWidth.toFloat(), image.intrinsicHeight.toFloat())
            view.imageMatrix.mapRect(frame)
            frame to view.width
        }
        val (initial, width) = pageFrame()
        assertEquals(0f, initial.left, 1f)
        assertTrue(initial.right > width)
        rule.onRoot().performTouchInput {
            swipe(Offset(width * .65f, centerY), Offset(width * .45f, centerY), 350)
        }
        assertTrue(pageFrame().first.left < -1f)
        assertEquals(0, rule.runOnIdle { currentPage })
        dragAcrossHorizontalImage()
        if (rule.runOnIdle { currentPage } == 0) swipe(vertical = false)
        rule.waitUntil(5_000) { rule.runOnIdle { currentPage } == 1 }
    }

    @Test fun videoFillUsesTheSameCropForTheCoverAndThePlayer() {
        open(loop = false, preview = false)
        rule.waitUntil(10_000) { rule.runOnIdle { currentVideoView().isVideoLayoutReady } }
        rule.runOnIdle {
            val view = currentVideoView()
            assertEquals(ImageView.ScaleType.FIT_CENTER, (view.getChildAt(1) as ImageView).scaleType)
            assertTrue(view.playerView.videoSurfaceView!!.height < view.height)
            readerSettings.value = readerSettings.value.copy(videoFillScreen = true)
        }
        rule.waitUntil(10_000) {
            rule.runOnIdle {
                val view = currentVideoView()
                val surface = requireNotNull(view.playerView.videoSurfaceView)
                surface.height == view.height && surface.width > view.width && view.isVideoLayoutReady &&
                    (view.getChildAt(1) as ImageView).scaleType == ImageView.ScaleType.CENTER_CROP
            }
        }
    }

    @Test fun filledVideoPansWithoutSeekingAndKeepsTheViewportCovered() {
        open(loop = false, names = listOf("video-folder/video2.mp4"))
        rule.runOnIdle { readerSettings.value = readerSettings.value.copy(videoFillScreen = true) }
        rule.waitUntil(15_000) { rule.runOnIdle { !currentVideoView().acceptsSwipeSeek } }
        val player = rule.runOnIdle { currentVideoView().playerView.player!! }
        dragAcrossHorizontalImage(reverse = true)
        val offset = rule.runOnIdle { currentVideoView().playerView.translationX }
        assertTrue(offset > 0f)
        assertEquals(0L, rule.runOnIdle { player.currentPosition })
        val frame = screenshot("video-fill-panned")
        for (fraction in listOf(.1f, .5f, .9f)) {
            val pixel = frame.getPixel((frame.width * fraction).roundToInt(), frame.height / 2)
            assertTrue(Color.red(pixel) > 200 && Color.green(pixel) < 40)
        }
        rule.runOnIdle { readerSettings.value = readerSettings.value.copy(videoFillScreen = false) }
        rule.runOnIdle {
            assertEquals(0f, currentVideoView().playerView.translationX, .1f)
            assertTrue(currentVideoView().acceptsSwipeSeek)
        }
    }

    @Test fun switchingPageModeReusesTheCurrentImageBitmap() {
        open(video = false, vertical = false, loop = true, paged = true)
        rule.runOnIdle { readerSettings.value = readerSettings.value.copy(continuousImageFitScreen = true) }
        val first = rule.activity.getString(R.string.image_accessibility, 1)
        fun imageView(): ZoomImageView = descendants(rule.activity.window.decorView).filterIsInstance<ZoomImageView>()
            .single { it.contentDescription == first && it.getGlobalVisibleRect(Rect()) }
        val bitmap = rule.runOnIdle { (imageView().drawable as BitmapDrawable).bitmap }
        for (paged in listOf(false, true, false)) {
            rule.runOnIdle { readerSettings.value = readerSettings.value.copy(paged = paged) }
            rule.runOnIdle {
                assertEquals(0, currentPage)
                assertSame(bitmap, (imageView().drawable as BitmapDrawable).bitmap)
            }
        }
    }

    @Test fun switchingPageModeKeepsTheMixedBookPlayerEvenWhenTheVideoMovesBetweenPagerPositions() {
        open(mixed = true, initialPage = 3, preview = false,
            names = listOf("page1.png", "page2.png", "page10.png", "moving-video/moving.mp4", "static.png", "video-folder/video10.mp4", "static.jpg"))
        waitForPlayback()
        val video = rule.runOnIdle { currentVideoView() }
        val player = rule.runOnIdle { video.playerView.player!! }
        val textureView = video.playerView.videoSurfaceView as android.view.TextureView
        val texture = rule.runOnIdle { textureView.surfaceTexture }
        for (paged in listOf(true, false, true)) {
            rule.runOnIdle { readerSettings.value = readerSettings.value.copy(paged = paged) }
            waitForVideo()
            rule.runOnIdle {
                assertEquals(3, currentPage)
                val current = currentVideoView()
                assertSame(player, current.playerView.player)
                if (current === video) assertSame(texture, textureView.surfaceTexture)
            }
        }
        waitForPlayback()
    }

    @Test fun horizontalContinuousImagesMeetWithoutSpacing() {
        open(video = false, loop = false, vertical = false, paged = false)
        dragAcrossHorizontalImage()
        assertTouchingImages(vertical = false, reverse = false, first = 1, second = 2)
        assertEquals(0, currentPage)
    }

    @Test fun continuousVerticalMixedContentScrollsImagesAndSnapsAroundVideo() {
        checkContinuousMixedContent(vertical = true, reverse = false)
    }

    @Test fun continuousHorizontalMixedContentScrollsImagesAndSnapsAroundVideoFromRightToLeft() {
        checkContinuousMixedContent(vertical = false, reverse = true)
    }

    private fun checkContinuousMixedContent(vertical: Boolean, reverse: Boolean) {
        open(mixed = true, vertical = vertical, rightToLeft = reverse, paged = false, loop = false,
            names = listOf("page1.png", "page2.png", "moving-video/moving.mp4", "page10.png", "page1.png"))
        if (!vertical) dragAcrossHorizontalImage(reverse)
        assertTouchingImages(vertical, reverse, first = 1, second = 2)

        val pagerAction = if (vertical) SemanticsActions.PageDown else SemanticsActions.PageRight
        val run = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex) and
            !SemanticsMatcher.keyIsDefined(pagerAction))
        run.performScrollToIndex(1)
        if (!vertical) dragAcrossHorizontalImage(reverse)
        rule.waitUntil(10_000) { currentPage == 1 }

        rule.onRoot().performTouchInput {
            val start = if (vertical) Offset(centerX, height * .55f) else Offset(width * .45f, centerY)
            val drag = if (vertical) Offset(0f, -height * .2f) else Offset(width * .2f * if (reverse) 1f else -1f, 0f)
            down(start)
            moveBy(drag, 500)
        }
        rule.waitUntil(10_000) { rule.runOnIdle { videoViews().any { it.getGlobalVisibleRect(Rect()) } } }
        assertEquals(1, currentPage)
        rule.onRoot().performTouchInput { cancel() }
        rule.waitUntil(10_000) { rule.runOnIdle { videoViews().none { it.getGlobalVisibleRect(Rect()) } } }

        swipe(forward = !reverse, vertical = vertical)
        rule.waitUntil(10_000) { currentPage == 2 && visibleImageBounds().isEmpty() }
        waitForVideo()
        swipe(forward = reverse, vertical = vertical)
        rule.waitUntil(10_000) { currentPage == 1 }
        swipe(forward = !reverse, vertical = vertical)
        rule.waitUntil(10_000) { currentPage == 2 && visibleImageBounds().isEmpty() }
        swipe(forward = !reverse, vertical = vertical)
        rule.waitUntil(10_000) { currentPage == 3 }
        if (!vertical) dragAcrossHorizontalImage(reverse)
        assertTouchingImages(vertical, reverse, first = 4, second = 5)
    }

    private fun dragAcrossHorizontalImage(reverse: Boolean = false) {
        rule.onRoot().performTouchInput {
            val start = Offset(width * .92f, centerY)
            val end = Offset(width * .08f, centerY)
            swipe(if (reverse) end else start, if (reverse) start else end, 1150)
        }
    }

    private fun visibleImageBounds(): Map<Int, Rect> = rule.runOnIdle {
        val pages = session.pages.indices.associateBy { rule.activity.getString(R.string.image_accessibility, it + 1) }
        descendants(rule.activity.window.decorView).filterIsInstance<ZoomImageView>().mapNotNull { view ->
            val page = pages[view.contentDescription] ?: return@mapNotNull null
            val rect = Rect()
            if (view.drawable != null && view.getGlobalVisibleRect(rect)) (page + 1) to rect else null
        }.toMap()
    }

    private fun assertTouchingImages(vertical: Boolean, reverse: Boolean, first: Int, second: Int) {
        rule.waitUntil(10_000) {
            val bounds = visibleImageBounds()
            val before = bounds[first] ?: return@waitUntil false
            val after = bounds[second] ?: return@waitUntil false
            val gap = when {
                vertical -> after.top - before.bottom
                reverse -> before.left - after.right
                else -> after.left - before.right
            }
            kotlin.math.abs(gap) <= 1
        }
    }

    @Test fun horizontalImageLoopHasAdjacentPagesInBothDirections() {
        open(video = false, vertical = false, initialPage = 2)
        rule.onRoot().performTouchInput {
            down(Offset(width * .8f, height * .4f))
            moveTo(Offset(width * .3f, height * .4f), 500)
        }
        rule.waitUntil(15_000) {
            var adjacent = false
            rule.runOnUiThread {
                val visible = descendants(rule.activity.window.decorView).filterIsInstance<ZoomImageView>()
                    .filter { it.getGlobalVisibleRect(Rect()) }.map { it.contentDescription.toString() }
                adjacent = rule.activity.getString(R.string.image_accessibility, 3) in visible &&
                    rule.activity.getString(R.string.image_accessibility, 1) in visible
            }
            adjacent
        }
        screenshot("image-loop-boundary").recycle()
        rule.onRoot().performTouchInput {
            moveTo(Offset(width * .15f, height * .4f), 100)
            up()
        }
        rule.waitUntil(15_000) { currentPage == 0 }
        swipe(forward = false, vertical = false)
        rule.waitUntil(15_000) { currentPage == 2 }
    }

    @Test fun verticalImageLoopMovesPastBothBoundaries() {
        open(video = false, initialPage = 2)
        repeat(4) { swipe() }
        rule.waitUntil(15_000) { currentPage != 2 }
        repeat(4) { swipe(forward = false) }
        rule.waitUntil(15_000) { currentPage == 2 }
    }

    @Test fun videoBoundariesStopWithoutLoopAndResumeKeepsPausePosition() {
        open(loop = false)
        swipe(forward = false)
        rule.runOnIdle { assertEquals(0, currentPage) }
        toggleControls()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.previous_page)).assertIsNotEnabled()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.video_playback_progress)).performTouchInput {
            swipe(Offset(width * .1f, centerY), Offset(width * .5f, centerY), 300)
        }
        rule.waitUntil(15_000) { (saved[0] ?: 0L) > 4000L }
        val position = saved[0]!!
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForVideo()
        rule.runOnIdle {
            val player = currentVideoView().playerView.player!!
            assertFalse(player.playWhenReady)
            assertEquals(position, player.currentPosition)
        }
        repeat(2) { step ->
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.next_page)).performClick()
            rule.waitUntil(15_000) { currentPage == step + 1 }
        }
        waitForVideo()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.next_page)).assertIsNotEnabled()
        toggleControls(visible = false)
        swipe()
        rule.runOnIdle { assertEquals(2, currentPage) }
    }

    @Test fun autoplayAdvancesToNextVideoAndLoopReturnsToFirst() {
        open(preview = false)
        repeat(3) { step ->
            waitForVideo()
            rule.runOnIdle { currentVideoView().playerView.player!!.seekTo(11_800L) }
            rule.waitUntil(15_000) { currentPage == (step + 1) % 3 }
        }
        waitForVideo()
        rule.runOnIdle {
            assertNotNull(currentVideoView())
            assertTrue(currentVideoView().playerView.player!!.playWhenReady)
            assertEquals(0L, saved[2])
        }
    }

    @Test fun mixedContentFollowsVerticalReadingAndShowsPageSpecificVideoControls() {
        open(video = true, mixed = true, vertical = true, preview = false, initialPage = 0)
        swipe(vertical = true)
        // 模拟器帧率低时 Pager 的减速动画会明显变慢，放宽等待时间。
        rule.waitUntil(15_000) { currentPage == 1 }
        waitForVideo()
        waitForPlayback()
        toggleControls()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.video_playback_progress)).assertIsDisplayed()
        toggleControls(visible = false)
        swipe(vertical = true)
        rule.waitUntil(15_000) { currentPage == 2 }
        toggleControls(visible = false)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.video_playback_progress)).assertDoesNotExist()
    }

    @Test fun changingReadingDirectionKeepsPlayingVideoAndItsTexture() {
        open(mixed = true, initialPage = 1, preview = false)
        waitForPlayback()
        val video = rule.runOnIdle { currentVideoView() }
        val player = rule.runOnIdle { video.playerView.player!! }
        val texture = rule.runOnIdle { (video.playerView.videoSurfaceView as android.view.TextureView).surfaceTexture }
        assertNotNull(texture)
        for (vertical in listOf(false, true)) {
            rule.runOnIdle { readerSettings.value = readerSettings.value.copy(vertical = vertical) }
            rule.runOnIdle {
                assertEquals(1, currentPage)
                assertSame(video, currentVideoView())
                assertSame(player, video.playerView.player)
                assertSame(texture, (video.playerView.videoSurfaceView as android.view.TextureView).surfaceTexture)
            }
            waitForPlayback()
        }
    }

    @Test fun changingReadingDirectionOnImageKeepsThePreloadedVideoTexture() {
        open(mixed = true, preview = false, loop = false)
        val preloaded = rule.runOnIdle { videoViews().single { it.playerView.player != null } }
        val player = rule.runOnIdle { preloaded.playerView.player!! }
        rule.waitUntil(10_000) { rule.runOnIdle { preloaded.isSurfaceAvailable } }
        val texture = rule.runOnIdle { (preloaded.playerView.videoSurfaceView as android.view.TextureView).surfaceTexture }
        rule.runOnIdle { readerSettings.value = readerSettings.value.copy(vertical = false) }
        rule.runOnIdle { assertEquals(0, currentPage) }
        dragAcrossHorizontalImage()
        swipe(vertical = false)
        rule.waitUntil(15_000) { currentPage == 1 }
        waitForPlayback()
        rule.runOnIdle {
            assertSame(preloaded, currentVideoView())
            assertSame(player, preloaded.playerView.player)
            assertSame(texture, (preloaded.playerView.videoSurfaceView as android.view.TextureView).surfaceTexture)
        }
    }

    @Test fun mixedContentStartsVideoAfterImageAndKeepsNextVideoPlayable() {
        open(video = true, mixed = true, mixedVideos = true, vertical = false, preview = false, initialPage = 0)
        swipe(vertical = false)
        rule.waitUntil(15_000) { currentPage == 1 }
        waitForVideo()
        waitForPlayback()
        var firstPosition = 0L
        val firstPath = session.pages[currentPage].filePath
        rule.runOnIdle {
            firstPosition = videoViews().first {
                it.playerView.player?.currentMediaItem?.localConfiguration?.uri?.path == firstPath
            }.playerView.player!!.currentPosition
        }
        rule.waitUntil(15_000) {
            var advanced = false
            rule.runOnUiThread {
                advanced = videoViews().any {
                    it.playerView.player?.currentMediaItem?.localConfiguration?.uri?.path == firstPath &&
                        (it.playerView.player?.currentPosition ?: 0L) > firstPosition
                }
            }
            advanced
        }
        swipe(vertical = false)
        rule.waitUntil(15_000) { currentPage == 2 }
        waitForVideo()
        waitForPlayback()
        rule.runOnIdle {
            val views = videoViews()
            assertEquals("混合视频切换后同一时间只能有一个播放中的视频", 1, views.count { it.playerView.player?.isPlaying == true })
            val path = session.pages[currentPage].filePath
            assertTrue("图片切视频后下一个视频应自动播放", views.any {
                it.playerView.player?.isPlaying == true &&
                    it.playerView.player?.currentMediaItem?.localConfiguration?.uri?.path == path
            })
        }
    }

    @Test fun mixedContentPausesVideoAfterLeavingIt() {
        open(video = true, mixed = true, vertical = false, preview = false, initialPage = 0,
            names = listOf("page1.png", "video-folder/video2.mp4", "page2.png", "video-folder/video10.mp4", "page10.png"))
        swipe(vertical = false)
        rule.waitUntil(15_000) { currentPage == 1 }
        waitForVideo()
        waitForPlayback()
        val path = session.pages[1].filePath
        rule.waitUntil(15_000) { (saved[1] ?: 0L) > 0L }
        swipe(vertical = false)
        rule.waitUntil(15_000) { currentPage == 2 }
        // 相邻页会保留预载播放器，但必须保持暂停，位置不再前进；保存的进度停在离开时的位置。
        rule.waitUntil(15_000) {
            var paused = false
            rule.runOnUiThread {
                val view = videoViews().firstOrNull {
                    it.playerView.player?.currentMediaItem?.localConfiguration?.uri?.path == path
                }
                paused = view == null || view.playerView.player?.isPlaying == false
            }
            paused
        }
        val savedPosition = saved[1] ?: 0L
        Thread.sleep(2000)
        rule.runOnIdle { assertEquals("离屏后播放位置不应继续前进", savedPosition, saved[1] ?: 0L) }
    }

    @Test fun mixedContentReplaysEndedVideoWhenReturning() {
        open(video = true, mixed = true, vertical = false, preview = false, initialPage = 0,
            names = listOf("page1.png", "video-folder/video2.mp4", "page2.png"))
        swipe(vertical = false)
        rule.waitUntil(15_000) { currentPage == 1 }
        waitForVideo()
        waitForPlayback()
        rule.waitUntil(15_000) { currentPage == 2 }
        swipe(forward = false, vertical = false)
        rule.waitUntil(15_000) { currentPage == 1 }
        waitForVideo()
        waitForPlayback()
        rule.runOnIdle {
            val player = currentVideoView().playerView.player!!
            assertTrue("返回已播完的视频应从头重新播放", player.currentPosition > 0L)
        }
    }

    // 画面在持续变化说明视频有可用的输出 Surface。播放器失去 Surface 时仍会推进进度，
    // 但画面会停在最后一帧，因此用两帧截图的像素差检测“进度走、画面不动”。
    private fun assertVideoKeepsRendering(tag: String) {
        val first = screenshot("$tag-a")
        Thread.sleep(500)
        val second = screenshot("$tag-b")
        var changed = 0
        for (pixelY in 0 until first.height step 8) {
            for (pixelX in 0 until first.width step 8) {
                val a = first.getPixel(pixelX, pixelY)
                val b = second.getPixel(pixelX, pixelY)
                if (kotlin.math.abs(Color.red(a) - Color.red(b)) > 24 ||
                    kotlin.math.abs(Color.green(a) - Color.green(b)) > 24 ||
                    kotlin.math.abs(Color.blue(a) - Color.blue(b)) > 24) changed++
            }
        }
        first.recycle()
        second.recycle()
        assertTrue("$tag：视频画面应在持续变化，changed=$changed", changed > 20)
    }

    @Test fun mixedContentKeepsRenderingAfterPagerViewRecreate() {
        // 视频位于列表末端，切到最前面的图片会超出 Pager 的预载窗口，迫使视频页的
        // AndroidView 被销毁重建；旧视图的 onRelease 可能晚于新视图绑定播放器。
        open(video = true, mixed = true, vertical = false, preview = false, initialPage = 2,
            names = listOf("page1.png", "page2.png", "moving-video/moving.mp4"))
        waitForVideo()
        waitForPlayback()
        repeat(3) {
            swipe(forward = false, vertical = false)
            rule.waitUntil(15_000) { currentPage == 1 }
            swipe(forward = false, vertical = false)
            rule.waitUntil(15_000) { currentPage == 0 }
            swipe(forward = true, vertical = false)
            rule.waitUntil(15_000) { currentPage == 1 }
            swipe(forward = true, vertical = false)
            rule.waitUntil(15_000) { currentPage == 2 }
            waitForVideo()
            waitForPlayback()
        }
        assertVideoKeepsRendering("mixed-pager-recreate")
    }

    @Test fun staleVideoViewReleaseKeepsNewOwnerSurface() {
        rule.runOnUiThread {
            val context = rule.activity
            val pool = VideoPlaybackPool(context, mutableStateOf(null))
            val state = pool.acquire(ReaderMediaItem(0, 0))
            try {
                val active = ZoomVideoView(context)
                val transient = ZoomVideoView(context)
                state.bindOwner(active, active = true)
                assertSame("停靠页视图应成为所有者", active, state.ownerView)
                assertSame(state.player, active.playerView.player)
                // Pager 预载/循环产生的重复项可以先绑定播放器，旧视图的 onRelease 随后才执行。
                state.bindOwner(transient)
                assertSame(transient, state.ownerView)
                assertNull("交接后旧视图不应再持有播放器", active.playerView.player)
                assertSame(state.player, transient.playerView.player)
                // 重复项被回收后，仍停靠的活动视图应拿回 Surface，而不是让播放器无画面空跑。
                state.releaseOwner(transient)
                assertSame("重复项释放后应回退到停靠视图", active, state.ownerView)
                assertSame(state.player, active.playerView.player)
                state.releaseOwner(active)
                assertNull(active.playerView.player)
                assertNull(state.ownerView)
            } finally {
                pool.releaseAll()
            }
        }
    }

    @Test fun mixedContentCancelledSwipeKeepsVideoRendering() {
        open(video = true, mixed = true, vertical = false, preview = false, initialPage = 2,
            names = listOf("page1.png", "page2.png", "moving-video/moving.mp4"))
        waitForVideo()
        waitForPlayback()
        repeat(3) {
            // 拖过中线后拉回并松手，回到原视频；真机手势更容易触发旧视图的延迟释放。
            rule.onRoot().performTouchInput {
                down(Offset(centerX - width * .25f, centerY))
                moveTo(Offset(centerX + width * .25f, centerY), 400)
                moveTo(Offset(centerX - width * .2f, centerY), 400)
                up()
            }
            rule.waitForIdle()
            rule.waitUntil(15_000) { currentPage == 2 }
            waitForVideo()
            waitForPlayback()
        }
        assertVideoKeepsRendering("mixed-cancel-swipe")
    }
}
