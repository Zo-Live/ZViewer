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
        rightToLeft: Boolean = false, preview: Boolean = true, initialPage: Int = 0, archive: Boolean = false) {
        val context = rule.activity
        val assets = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        val names = if (video) listOf("video-folder/video2.mp4", "video-folder/video10.mp4", "rotated.mp4")
            else listOf("page1.png", "page2.png", "page10.png")
        val pages = names.map { name ->
            val file = File(context.cacheDir, "reader-test-${name.substringAfterLast('/')}")
            assets.open("formats/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            PageSource(name, filePath = file.absolutePath, mediaType = if (video) "video" else "image")
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
        if (video) waitForVideo() else rule.waitUntil(10_000) {
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

    private fun waitForVideo() {
        rule.waitUntil(15_000) {
            var ready = false
            rule.runOnUiThread {
                val player = videoViews().singleOrNull()?.playerView?.player
                val path = session.pages[currentPage].filePath
                ready = player?.playbackState == Player.STATE_READY && player.videoSize.height > 0 &&
                    player.currentMediaItem?.localConfiguration?.uri?.path == path
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
        rule.waitUntil(5000) {
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
        rule.waitUntil(5000) {
            var playing = false
            rule.runOnUiThread {
                val player = videoViews().singleOrNull()?.playerView?.player
                playing = player?.isPlaying == true && player.currentPosition > 0L
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
            assertTrue(videoViews().single().playerView.player!!.currentPosition >= 4000L)
        }
    }

    @Test fun videoTransitionKeepsBothViewsUntilIncomingFrameIsReady() {
        open(preview = false)
        waitForPlayback()
        lateinit var outgoing: ZoomVideoView
        rule.runOnIdle { outgoing = videoViews().single() }
        rule.mainClock.autoAdvance = false
        try {
            rule.runOnUiThread { outgoing.onPage(1) }
            rule.mainClock.advanceTimeByFrame()
            rule.waitUntil(5000) {
                var ready = false
                rule.runOnUiThread {
                    val views = videoViews()
                    assertTrue("翻页开始时应保留原视频视图和画面", views.contains(outgoing))
                    assertTrue("过渡期间两个播放器均应暂停", views.all { it.playerView.player?.playWhenReady == false })
                    val incoming = views.singleOrNull { it !== outgoing }
                    ready = incoming?.playerView?.player?.playbackState == Player.STATE_READY
                }
                ready
            }
            lateinit var incoming: ZoomVideoView
            rule.runOnUiThread { incoming = videoViews().single { it !== outgoing } }
            repeat(18) { frame ->
                rule.mainClock.advanceTimeByFrame()
                rule.runOnUiThread {
                    val views = videoViews()
                    if (views.size == 2) {
                        assertTrue(views.all { it.playerView.player?.playWhenReady == false })
                        assertTrue(views.contains(outgoing))
                        assertTrue(views.contains(incoming))
                    }
                }
                val bitmap = screenshot("video-transition-$frame")
                var coloredPixels = 0
                for (pixelY in 0 until bitmap.height step 40) {
                    for (pixelX in 0 until bitmap.width step 40) {
                        val color = bitmap.getPixel(pixelX, pixelY)
                        if (Color.green(color) < 50 && (Color.red(color) > 200 || Color.blue(color) > 200)) coloredPixels++
                    }
                }
                bitmap.recycle()
                assertTrue("第 $frame 帧应保留视频画面，不能整屏闪黑", coloredPixels > 20)
            }
            rule.mainClock.autoAdvance = true
            rule.waitUntil(5000) { currentPage == 1 }
            waitForVideo()
            rule.runOnIdle { assertSame("过渡结束时应保留已显示首帧的新视频视图", incoming, videoViews().single()) }
            waitForPlayback()
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    @Test fun verticalVideoSwipesAndButtonsShowOnePlayerAfterTransition() {
        open()
        var previous: Player? = null
        repeat(9) { step ->
            swipe()
            rule.waitUntil(5000) { currentPage == (step + 1) % 3 }
            waitForVideo()
            rule.runOnIdle {
                val view = videoViews().single()
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
            rule.waitUntil(5000) { currentPage == (it + 1) % 3 }
        }
        repeat(6) {
            rule.onNodeWithContentDescription("上一页").performClick()
            rule.waitUntil(5000) { currentPage == Math.floorMod(-it - 1, 3) }
        }
        waitForVideo()
        rule.runOnIdle { assertEquals(1, videoViews().size) }
    }

    @Test fun horizontalPagingAndDoubleTapDoNotSeekOrPlay() {
        open(vertical = false, rightToLeft = true)
        swipe(forward = false, vertical = false)
        rule.waitUntil(5000) { currentPage == 1 }
        waitForVideo()
        rule.onRoot().performTouchInput { doubleClick(Offset(width * .2f, height * .4f)) }
        rule.runOnIdle {
            val view = videoViews().single()
            assertEquals(2.5f, view.playerView.scaleX, .01f)
            assertFalse(view.playerView.player!!.playWhenReady)
            assertEquals(0L, view.playerView.player!!.currentPosition)
        }
        rule.onRoot().performTouchInput { doubleClick(Offset(width * .8f, height * .4f)) }
        rule.runOnIdle { assertEquals(1f, videoViews().single().playerView.scaleX, .01f) }
        swipe(forward = true, vertical = false)
        rule.waitUntil(5000) { currentPage == 0 }
    }

    @Test fun progressControlsSeekAndHideTogether() {
        open()
        swipe(vertical = false)
        rule.runOnIdle {
            assertEquals(0, currentPage)
            assertEquals(0L, videoViews().single().playerView.player!!.currentPosition)
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
        rule.waitUntil(5000) { (saved[0] ?: 0L) in 5_000L..9_000L }
        rule.onNodeWithContentDescription("播放视频").performClick()
        rule.waitUntil(5000) {
            var playing = false
            rule.runOnUiThread { playing = videoViews().single().playerView.player!!.isPlaying }
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
        rule.waitUntil(5000) {
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
        rule.waitUntil(5000) { currentPage == 0 }
        swipe(forward = false, vertical = false)
        rule.waitUntil(5000) { currentPage == 2 }
    }

    @Test fun verticalImageLoopMovesPastBothBoundaries() {
        open(video = false, initialPage = 2)
        repeat(4) { swipe() }
        rule.waitUntil(5000) { currentPage != 2 }
        repeat(4) { swipe(forward = false) }
        rule.waitUntil(5000) { currentPage == 2 }
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
        rule.waitUntil(5000) { (saved[0] ?: 0L) > 4000L }
        val position = saved[0]!!
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForVideo()
        rule.runOnIdle {
            val player = videoViews().single().playerView.player!!
            assertFalse(player.playWhenReady)
            assertEquals(position, player.currentPosition)
        }
        repeat(2) { step ->
            rule.onNodeWithContentDescription("下一页").performClick()
            rule.waitUntil(5000) { currentPage == step + 1 }
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
            rule.runOnIdle { videoViews().single().playerView.player!!.seekTo(11_800L) }
            rule.waitUntil(5000) { currentPage == (step + 1) % 3 }
        }
        waitForVideo()
        rule.runOnIdle {
            assertEquals(1, videoViews().size)
            assertTrue(videoViews().single().playerView.player!!.playWhenReady)
            assertEquals(0L, saved[2])
        }
    }
}
