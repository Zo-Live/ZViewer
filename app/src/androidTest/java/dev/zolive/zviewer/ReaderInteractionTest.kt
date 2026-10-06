package dev.zolive.zviewer

import android.view.View
import android.view.ViewGroup
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.lifecycle.Lifecycle
import dev.zolive.zviewer.data.*
import dev.zolive.zviewer.reader.ZoomVideoView
import dev.zolive.zviewer.reader.ZoomImageView
import dev.zolive.zviewer.ui.ReaderScreen
import dev.zolive.zviewer.ui.createVideoPlaybackState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReaderInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var currentPage = 0
    private val saved = mutableMapOf<Int, Long>()
    private lateinit var session: BookSession

    private fun open(video: Boolean = true, vertical: Boolean = true, loop: Boolean = true,
        rightToLeft: Boolean = false, preview: Boolean = true, initialPage: Int = 0, archive: Boolean = false,
        mixed: Boolean = false, mixedVideos: Boolean = false, names: List<String>? = null) {
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
        rule.setContent {
            var settings by remember { mutableStateOf(ReaderSettings(vertical = vertical, loopMode = loop,
                rightToLeft = rightToLeft, videoPreview = preview)) }
            ReaderScreen(session, initialPage, settings, repository,
                onSettings = { settings = it }, onProgress = { currentPage = it },
                videoPosition = { saved[it] ?: 0L }, onVideoProgress = { page, position -> saved[page] = position }, onBack = {})
        }
        if (video && !mixed || mixed && session.pages[initialPage].isVideo) waitForVideo() else rule.waitUntil(20_000) {
            var ready = false
            rule.runOnUiThread {
                ready = descendants(rule.activity.window.decorView).filterIsInstance<ZoomImageView>()
                    .any { it.drawable != null && it.contentDescription.startsWith("第 ${initialPage + 1} 页") }
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
            rule.onAllNodesWithContentDescription("视频播放进度").fetchSemanticsNodes().isNotEmpty() == visible
        }
        rule.waitForIdle()
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
        var position = 0L
        rule.runOnIdle {
            outgoing = currentVideoView()
            position = outgoing.playerView.player!!.currentPosition
        }
        // 拖动到过半之前并保持按住：此时还没有正式切换到下一段。
        rule.onRoot().performTouchInput {
            down(Offset(centerX, height * .62f))
            moveTo(Offset(centerX, height * .34f), 450)
        }
        rule.waitForIdle()
        lateinit var incoming: ZoomVideoView
        val incomingRect = Rect()
        rule.runOnIdle {
            val views = videoViews()
            assertTrue("拖动时应保留当前视频视图", views.contains(outgoing))
            assertTrue("拖动时当前视频应继续播放", outgoing.playerView.player!!.playWhenReady)
            incoming = views.firstOrNull { it !== outgoing && it.playerView.player != null && it.getGlobalVisibleRect(incomingRect) }
                ?: throw AssertionError("拖动时应为目标页预建播放器")
            assertFalse("拖动时下一段视频应保持暂停", incoming.playerView.player!!.playWhenReady)
            // 同时最多保留当前页与目标页两个播放器，离屏预载页只显示封面。
            assertTrue("同时存在的播放器不应超过两个", views.count { it.playerView.player != null } <= 2)
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
            rule.onNodeWithContentDescription("下一页").performClick()
            rule.waitUntil(15_000) { currentPage == (it + 1) % 3 }
        }
        repeat(6) {
            rule.onNodeWithContentDescription("上一页").performClick()
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
        rule.runOnIdle {
            val view = currentVideoView()
            assertEquals(2.5f, view.playerView.scaleX, .01f)
            assertFalse(view.playerView.player!!.playWhenReady)
            assertEquals(0L, view.playerView.player!!.currentPosition)
        }
        rule.onRoot().performTouchInput { doubleClick(Offset(width * .8f, height * .4f)) }
        rule.runOnIdle { assertEquals(1f, currentVideoView().playerView.scaleX, .01f) }
        swipe(forward = true, vertical = false)
        rule.waitUntil(15_000) { currentPage == 0 }
    }

    @Test fun progressControlsSeekAndHideTogether() {
        open()
        swipe(vertical = false)
        rule.runOnIdle {
            assertEquals(0, currentPage)
            assertEquals(0L, currentVideoView().playerView.player!!.currentPosition)
        }
        toggleControls()
        val before = screenshot("video-controls")
        rule.onNodeWithContentDescription("视频播放进度").assertIsDisplayed().performTouchInput {
            down(Offset(width * .1f, centerY))
            moveTo(Offset(width * .6f, centerY), 500)
        }
        val timeText = rule.onNode(hasText(" / 00:12", substring = true)).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().text
        assertTrue(timeText, Regex("00:0[5-9] / 00:12").matches(timeText))
        val dragging = screenshot("video-seeking")
        assertEquals(before.getPixel(before.width / 2, before.height / 2), dragging.getPixel(dragging.width / 2, dragging.height / 2))
        before.recycle()
        dragging.recycle()
        rule.onNodeWithContentDescription("视频播放进度").performTouchInput { up() }
        rule.waitUntil(15_000) { (saved[0] ?: 0L) in 5_000L..9_000L }
        rule.onNodeWithContentDescription("播放视频").performClick()
        rule.waitUntil(15_000) {
            var playing = false
            rule.runOnUiThread { playing = currentVideoView().playerView.player!!.isPlaying }
            playing
        }
        rule.onNodeWithContentDescription("暂停视频").performClick()
        toggleControls(visible = false)
        rule.onNodeWithContentDescription("视频播放进度").assertDoesNotExist()
        rule.onNodeWithContentDescription("下一页").assertDoesNotExist()
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
                adjacent = visible.any { it.startsWith("第 3 页") } && visible.any { it.startsWith("第 1 页") }
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
        rule.onNodeWithContentDescription("上一页").assertIsNotEnabled()
        rule.onNodeWithContentDescription("视频播放进度").performTouchInput {
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
            rule.onNodeWithContentDescription("下一页").performClick()
            rule.waitUntil(15_000) { currentPage == step + 1 }
        }
        waitForVideo()
        rule.onNodeWithContentDescription("下一页").assertIsNotEnabled()
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
        rule.onNodeWithContentDescription("视频播放进度").assertIsDisplayed()
        toggleControls(visible = false)
        swipe(vertical = true)
        rule.waitUntil(15_000) { currentPage == 2 }
        toggleControls(visible = false)
        rule.onNodeWithContentDescription("视频播放进度").assertDoesNotExist()
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
        // 离开后播放器被释放，位置不再前进；保存的进度停在离开时的位置。
        rule.waitUntil(15_000) {
            var gone = false
            rule.runOnUiThread { gone = videoViews().none { it.playerView.player?.currentMediaItem?.localConfiguration?.uri?.path == path } }
            gone
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
            val state = createVideoPlaybackState(context)
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
                state.release()
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
