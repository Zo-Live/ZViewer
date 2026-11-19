package dev.zolive.zviewer

import android.content.Context
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import dev.zolive.zviewer.data.*
import dev.zolive.zviewer.ui.ZViewerApp
import dev.zolive.zviewer.ui.ZViewerTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UpdateInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = PreferenceStore(context)
    private lateinit var model: LibraryViewModel
    private val calls = AtomicInteger()
    private val downloads = AtomicInteger()

    @Before fun prepare() {
        context.getSharedPreferences("zviewer", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("zviewer_updates", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun launch(startup: Boolean = false, version: String = "9.0.0", gate: CountDownLatch? = null,
        failure: Boolean = false, downloadFailure: Boolean = false) {
        store.saveSettings(store.settings().copy(checkUpdatesOnStartup = startup))
        val checker = UpdateChecker(context, {
            calls.incrementAndGet()
            if (gate != null) check(gate.await(15, TimeUnit.SECONDS))
            if (failure) throw SocketTimeoutException("模拟网络超时")
            ReleaseResponse(200, releaseJson(version))
        })
        rule.runOnUiThread {
            model = LibraryViewModel(rule.activity.application, checker) {
                downloads.incrementAndGet()
                if (downloadFailure) throw IllegalStateException("下载服务不可用")
                42L
            }
            rule.activity.viewModelStore.put("update-test", model)
        }
        rule.setContent {
            val state by model.state.collectAsStateWithLifecycle()
            ZViewerTheme(state.settings) { ZViewerApp(model, state) }
        }
    }

    private fun settings() {
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.settings)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.about)).performScrollTo()
    }

    private fun checkUpdate() {
        rule.onNodeWithText(rule.activity.getString(R.string.update_check)).performScrollTo().performClick()
    }

    private fun waitForCheck() {
        rule.waitUntil(10_000) { !model.state.value.checkingUpdate }
        rule.waitForIdle()
    }

    private fun openBook() {
        val tree = DocumentsContract.buildTreeDocumentUri("dev.zolive.zviewer.test.documents", "root/书库")
        // 测试目录中的图片文件夹可直接提供真实阅读会话。
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, "root/书库/07 图片文件夹")
        val book = Book("update-reader-test", folder.toString(), "更新提示测试", "folder", 0, 0)
        rule.runOnIdle { model.open(book) }
        rule.waitUntil(15_000) { model.state.value.session != null || model.state.value.error != null }
        assertNull(model.state.value.error)
        assertNotNull(model.state.value.session)
    }

    @Test fun applicationSectionDefaultsAndAboutAreAccessible() {
        launch()
        settings()
        rule.onNodeWithText(rule.activity.getString(R.string.app)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.update_check_on_start)).assertIsOff().performClick().assertIsOn()
        assertTrue(PreferenceStore(context).settings().checkUpdatesOnStartup)
        rule.onNodeWithText(rule.activity.getString(R.string.update_check_on_start)).performClick().assertIsOff()
        rule.onNodeWithText(rule.activity.getString(R.string.current_version, BuildConfig.VERSION_NAME)).assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.about)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.about_title)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.ok)).performClick()
        assertEquals(0, calls.get())
    }

    @Test fun latestNoticeDismissesOnAnyTapAndExpires() {
        launch(version = BuildConfig.VERSION_NAME)
        settings()
        checkUpdate()
        waitForCheck()
        rule.onNodeWithText(rule.activity.getString(R.string.update_latest)).assertIsDisplayed()
        rule.onRoot().performTouchInput { click(Offset(width * .8f, height * .3f)) }
        rule.onNodeWithText(rule.activity.getString(R.string.update_latest)).assertDoesNotExist()
        checkUpdate()
        waitForCheck()
        rule.onNodeWithText(rule.activity.getString(R.string.update_latest)).assertExists()
        assertEquals(2, calls.get())
        rule.mainClock.advanceTimeBy(4_000)
        rule.onNodeWithText(rule.activity.getString(R.string.update_latest)).assertDoesNotExist()
    }

    @Test fun latestNoticeStaysAtSameHeightWhenSystemBackClosesSettings() {
        launch(version = BuildConfig.VERSION_NAME)
        settings()
        checkUpdate()
        waitForCheck()
        val notice = rule.onNodeWithText(rule.activity.getString(R.string.update_latest))
        val bounds = notice.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        rule.mainClock.autoAdvance = false
        try {
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            rule.mainClock.advanceTimeBy(160)
            assertEquals(bounds, notice.fetchSemanticsNode().boundsInRoot)
            rule.mainClock.advanceTimeBy(400)
            rule.onNodeWithTag("settings-screen").assertDoesNotExist()
            assertEquals(bounds, notice.fetchSemanticsNode().boundsInRoot)
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    @Test fun checkingIsNonModalAndRepeatedClicksDoNotDuplicateRequests() {
        val gate = CountDownLatch(1)
        launch(gate = gate)
        settings()
        checkUpdate()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.update_checking)).assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.update_now)).assertDoesNotExist()
        rule.runOnIdle { repeat(5) { model.checkForUpdates() } }
        gate.countDown()
        waitForCheck()
        assertEquals(1, calls.get())
        rule.onNodeWithText(rule.activity.getString(R.string.update_prompt, "9.0.0")).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.update_later)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.update_now)).assertDoesNotExist()
        assertEquals(0, downloads.get())
    }

    @Test fun confirmationUsesSelectedAssetAndStartsExactlyOneDownload() {
        launch()
        settings()
        checkUpdate()
        waitForCheck()
        rule.onNodeWithText(rule.activity.getString(R.string.update_now)).performClick()
        rule.waitUntil(10_000) { !model.state.value.downloadingUpdate }
        rule.onNodeWithText(rule.activity.getString(R.string.update_background)).assertIsDisplayed()
        assertEquals(1, calls.get())
        assertEquals(1, downloads.get())
    }

    @Test fun manualFailuresAreActionableAndStartupFailuresAreSilent() {
        launch(startup = true, failure = true)
        waitForCheck()
        assertNull(model.state.value.updateNotice)
        settings()
        checkUpdate()
        waitForCheck()
        rule.onNodeWithText(rule.activity.getString(R.string.update_check_failed)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.update_latest)).assertDoesNotExist()
        assertEquals(2, calls.get())
    }

    @Test fun failedDownloadCanBeDismissedWithoutLeavingSettings() {
        launch(downloadFailure = true)
        settings()
        checkUpdate()
        waitForCheck()
        rule.onNodeWithText(rule.activity.getString(R.string.update_now)).performClick()
        rule.waitUntil(10_000) { !model.state.value.downloadingUpdate }
        rule.onNodeWithText(rule.activity.getString(R.string.update_cannot_download)).assertIsDisplayed()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.back_to_library)).assertIsDisplayed()
    }

    @Test fun startupDialogWaitsUntilTheBookIsClosed() {
        val gate = CountDownLatch(1)
        launch(startup = true, gate = gate)
        openBook()
        gate.countDown()
        waitForCheck()
        assertNotNull(model.state.value.updatePrompt)
        rule.onNodeWithText(rule.activity.getString(R.string.update_now)).assertDoesNotExist()
        rule.mainClock.advanceTimeBy(5_000)
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(5_000) { model.state.value.session == null }
        rule.onNodeWithText(rule.activity.getString(R.string.update_prompt, "9.0.0")).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.update_later)).performClick()
    }

    @Test fun latestNoticeDoesNotExpireWhileReading() {
        val gate = CountDownLatch(1)
        launch(version = BuildConfig.VERSION_NAME, gate = gate)
        rule.runOnIdle { model.checkForUpdates() }
        openBook()
        gate.countDown()
        waitForCheck()
        rule.onNodeWithText(rule.activity.getString(R.string.update_latest)).assertDoesNotExist()
        rule.mainClock.advanceTimeBy(8_000)
        assertEquals(R.string.update_latest, model.state.value.updateNotice)
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(5_000) { model.state.value.session == null }
        rule.onNodeWithText(rule.activity.getString(R.string.update_latest)).assertIsDisplayed()
        rule.mainClock.advanceTimeBy(4_000)
        rule.onNodeWithText(rule.activity.getString(R.string.update_latest)).assertDoesNotExist()
    }

    @Test fun startupUpToDateIsSilent() {
        launch(startup = true, version = BuildConfig.VERSION_NAME)
        waitForCheck()
        assertEquals(1, calls.get())
        assertNull(model.state.value.updatePrompt)
        assertNull(model.state.value.updateNotice)
    }
}
