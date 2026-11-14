package dev.zolive.zviewer

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zolive.zviewer.data.BookRepository
import dev.zolive.zviewer.data.CacheLimits
import dev.zolive.zviewer.data.PreferenceStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class CacheSettingsTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = PreferenceStore(context)
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun prepare() = runBlocking {
        context.getSharedPreferences("zviewer", Context.MODE_PRIVATE).edit().clear().commit()
        BookRepository(context).use { it.clearCache(includeCovers = true) }
    }

    @After fun close() = runBlocking {
        if (::scenario.isInitialized) scenario.close()
        BookRepository(context).use { it.clearCache(includeCovers = true) }
    }

    private fun launchSettings() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithText("清理封面").performScrollTo()
    }

    private fun sparse(root: File, path: String, bytes: Long): File = File(root, path).apply {
        parentFile!!.mkdirs()
        RandomAccessFile(this, "rw").use { it.setLength(bytes) }
        setLastModified(1000)
    }

    private fun setThreshold(mib: Int) {
        rule.onNodeWithContentDescription("清理阈值").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(mib.toFloat()) }
        rule.waitUntil(5_000) { store.settings().cacheLimitMiB == mib }
    }

    @Test fun defaultManualClearKeepsCoversAndEnabledClearRemovesThem() {
        launchSettings()
        rule.onNodeWithText("清理封面").assertIsOff()
        assertFalse(store.settings().clearCovers)
        rule.onNode(hasText("双击清除临时解压文件和封面", substring = true)).assertDoesNotExist()
        val cover = sparse(context.filesDir, "covers/manual.jpg", 100)
        val frame = sparse(context.filesDir, "covers/frames/manual-0.jpg", 100)
        val page = sparse(context.cacheDir, "books/manual/page.bin", 100)
        rule.onNodeWithText("清理阅读缓存").performScrollTo().performTouchInput { click() }
        rule.waitForIdle()
        assertTrue(page.isFile)
        rule.onNodeWithText("清理阅读缓存").performTouchInput { doubleClick() }
        rule.waitUntil(5_000) { !page.exists() }
        assertTrue(cover.isFile)
        assertTrue(frame.isFile)
        assertEquals(1000L, cover.lastModified())
        rule.onNodeWithText("清理封面").performScrollTo().performClick().assertIsOn()
        rule.onNode(hasText("双击清除临时解压文件和封面", substring = true)).assertExists()
        rule.onNodeWithText("清理阅读缓存").performScrollTo().performTouchInput { doubleClick() }
        rule.waitUntil(5_000) { !cover.exists() && !frame.exists() }
        rule.onNodeWithText("清理封面").performClick().assertIsOff()
        rule.onNode(hasText("双击清除临时解压文件和封面", substring = true)).assertDoesNotExist()
    }

    @Test fun bothSettingsPersistAndThresholdEndpointsAreUsable() {
        launchSettings()
        assertEquals(1536, store.settings().cacheLimitMiB)
        rule.onNodeWithText("清理封面").performClick()
        setThreshold(CacheLimits.MAX_MIB)
        rule.onNodeWithText("8 GiB").assertExists()
        scenario.close()
        launchSettings()
        rule.onNodeWithText("清理封面").assertIsOn()
        rule.onNodeWithText("8 GiB").assertExists()
        rule.onNodeWithText("清理封面").performClick()
        setThreshold(CacheLimits.MIN_MIB)
        rule.onNodeWithText("256 MiB").assertExists()
        scenario.recreate()
        rule.onNodeWithText("清理封面").performScrollTo().assertIsOff()
        rule.onNodeWithText("256 MiB").assertExists()
        assertFalse(PreferenceStore(context).settings().clearCovers)
        assertEquals(256, PreferenceStore(context).settings().cacheLimitMiB)
    }

    @Test fun loweringThresholdImmediatelyTrimsAndRaisingItPreservesReadingFiles() = runBlocking {
        launchSettings()
        rule.onNodeWithText("清理封面").performClick()
        val cover = sparse(context.filesDir, "covers/threshold.jpg", 100)
        val old = sparse(context.cacheDir, "books/threshold/old.bin", 300L * 1024 * 1024)
        BookRepository(context).use { it.trimCache() }
        assertTrue(old.isFile)
        setThreshold(256)
        rule.waitUntil(5_000) { !old.exists() }
        assertTrue(cover.isFile)
        setThreshold(512)
        val reusable = sparse(context.cacheDir, "books/threshold/reusable.bin", 400L * 1024 * 1024)
        BookRepository(context).use { it.trimCache() }
        assertTrue(reusable.isFile)
        assertTrue(cover.isFile)
    }

    @Test fun draggingThresholdSliderDoesNotTriggerSettingsBackGesture() {
        launchSettings()
        rule.onNodeWithContentDescription("清理阈值").performScrollTo().performTouchInput {
            swipe(Offset(width * .2f, centerY), Offset(width * .85f, centerY), durationMillis = 600)
        }
        rule.waitForIdle()
        rule.onNodeWithText("清理封面").assertExists()
        rule.onNodeWithContentDescription("返回书库").assertIsDisplayed()
        val limit = store.settings().cacheLimitMiB
        assertTrue(limit > 1536)
        assertEquals(0, limit % CacheLimits.STEP_MIB)
        assertTrue(limit in CacheLimits.MIN_MIB..CacheLimits.MAX_MIB)
    }
}
