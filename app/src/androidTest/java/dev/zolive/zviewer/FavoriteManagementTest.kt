package dev.zolive.zviewer

import android.content.Context
import android.provider.DocumentsContract
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zolive.zviewer.data.Book
import dev.zolive.zviewer.data.BookRepository
import dev.zolive.zviewer.data.PreferenceStore
import dev.zolive.zviewer.data.ReaderSettings
import dev.zolive.zviewer.reader.ZoomImageView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class FavoriteManagementTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = PreferenceStore(context)
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var book: Book

    @Before fun prepareLibrary() = runBlocking {
        context.getSharedPreferences("zviewer", Context.MODE_PRIVATE).edit().clear().commit()
        val repository = BookRepository(context)
        try {
            val tree = DocumentsContract.buildTreeDocumentUri("dev.zolive.zviewer.test.documents", "root/书库")
            val books = repository.scan(tree)
            book = books.first { it.kind == "cbz" }
            store.treeUri = tree.toString()
            store.saveLibrary(books)
            store.saveFavorites(setOf(book.id))
            store.saveProgress(book.id, 0, 3)
        } finally {
            repository.close()
        }
    }

    @After fun close() {
        if (::scenario.isInitialized) scenario.close()
    }

    private fun launch(showFavoriteIcon: Boolean? = null) {
        if (showFavoriteIcon != null) store.saveSettings(ReaderSettings(showFavoriteIcon = showFavoriteIcon))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(20_000) {
            rule.onAllNodesWithContentDescription("刷新书库").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("刷新书库").assertIsEnabled()
    }

    private fun selectTab(title: String) {
        rule.onNode(hasText(title) and hasClickAction()).performClick()
    }

    private fun cover() = rule.onNode(hasText(book.title) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))

    private fun longPressCover() {
        cover().performScrollTo().performTouchInput { longClick(Offset(centerX, height * .3f)) }
        rule.onNodeWithContentDescription("退出收藏管理").assertIsDisplayed()
    }

    private fun tapCover() {
        cover().performTouchInput { click(Offset(centerX, height * .3f)) }
    }

    private fun assertIconVisible(visible: Boolean) {
        val description = if (book.id in store.favorites()) "取消收藏 ${book.title}" else "收藏 ${book.title}"
        if (visible) rule.onNodeWithContentDescription(description).assertIsDisplayed()
        else rule.onNodeWithContentDescription(description).assertDoesNotExist()
    }

    private fun verifyTab(title: String, showFavoriteIcon: Boolean) {
        launch(showFavoriteIcon)
        selectTab(title)
        assertIconVisible(showFavoriteIcon)
        longPressCover()
        assertTrue(book.id in store.favorites())
        assertIconVisible(true)
        tapCover()
        rule.waitForIdle()
        assertFalse(book.id in store.favorites())
        rule.onNodeWithContentDescription("退出收藏管理").assertIsDisplayed()
        if (title == "收藏") {
            cover().assertDoesNotExist()
            rule.onNodeWithText("没有符合条件的漫画").assertIsDisplayed()
            selectTab("书库")
            cover().performScrollTo()
        }
        tapCover()
        rule.waitForIdle()
        assertTrue(book.id in store.favorites())
        rule.onNodeWithContentDescription("取消收藏 ${book.title}").performTouchInput { click() }
        rule.waitForIdle()
        assertFalse(book.id in store.favorites())
        rule.onNodeWithContentDescription("收藏 ${book.title}").performTouchInput { click() }
        rule.waitForIdle()
        assertTrue(book.id in store.favorites())
        rule.onNodeWithContentDescription("退出收藏管理").performClick()
        rule.onNodeWithContentDescription("退出收藏管理").assertDoesNotExist()
        assertIconVisible(showFavoriteIcon)
        assertEquals(showFavoriteIcon, PreferenceStore(context).settings().showFavoriteIcon)
    }

    @Test fun libraryWithIcons() = verifyTab("书库", true)
    @Test fun libraryWithoutIcons() = verifyTab("书库", false)
    @Test fun favoritesWithIcons() = verifyTab("收藏", true)
    @Test fun favoritesWithoutIcons() = verifyTab("收藏", false)
    @Test fun recentWithIcons() = verifyTab("最近阅读", true)
    @Test fun recentWithoutIcons() = verifyTab("最近阅读", false)

    @Test fun searchSortFiltersAndTabsKeepManagementUntilBack() {
        launch(false)
        longPressCover()
        rule.onNodeWithContentDescription("搜索漫画").performClick()
        rule.onNodeWithContentDescription("退出收藏管理").assertIsDisplayed()
        rule.onNodeWithContentDescription("排序").performClick()
        rule.onNodeWithText("最近修改").performClick()
        rule.onNodeWithContentDescription("退出收藏管理").assertIsDisplayed()
        rule.onNodeWithText("未读").performClick()
        rule.onNodeWithContentDescription("退出收藏管理").assertIsDisplayed()
        rule.onNodeWithText("全部").performClick()
        selectTab("最近阅读")
        rule.onNodeWithContentDescription("退出收藏管理").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextReplacement("没有匹配的漫画")
        rule.onNodeWithText("没有符合条件的漫画").assertIsDisplayed()
        rule.onNodeWithContentDescription("退出收藏管理").assertIsDisplayed()
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithContentDescription("退出收藏管理").assertDoesNotExist()
        rule.onNodeWithContentDescription("关闭搜索").assertIsDisplayed()
        rule.onNodeWithText("没有匹配的漫画").assertIsDisplayed()
        rule.onNodeWithContentDescription("关闭搜索").performClick()
        assertIconVisible(false)
    }

    @Test fun settingsAndRefreshExitManagement() {
        launch(false)
        longPressCover()
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithContentDescription("返回书库").performClick()
        rule.onNodeWithContentDescription("退出收藏管理").assertDoesNotExist()
        assertIconVisible(false)
        longPressCover()
        rule.onNodeWithContentDescription("刷新书库").performClick()
        rule.onNodeWithContentDescription("退出收藏管理").assertDoesNotExist()
        assertIconVisible(false)
    }

    @Test fun settingDefaultsOnAndPersistsBothChoicesAcrossRelaunch() {
        launch()
        assertTrue(ReaderSettings().showFavoriteIcon)
        assertTrue(store.settings().showFavoriteIcon)
        assertIconVisible(true)
        listOf(false, true).forEach { enabled ->
            rule.onNodeWithContentDescription("设置").performClick()
            rule.onNodeWithText("显示收藏图标").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("在封面右下角显示收藏按钮").assertIsDisplayed()
            val titleBounds = rule.onNodeWithText("显示收藏图标").fetchSemanticsNode().boundsInRoot
            val switches = rule.onAllNodes(isToggleable()).fetchSemanticsNodes()
            val switchIndex = switches.indices.minBy { abs(switches[it].boundsInRoot.center.y - titleBounds.center.y) }
            rule.onAllNodes(isToggleable())[switchIndex].performClick()
            rule.waitForIdle()
            assertEquals(enabled, PreferenceStore(context).settings().showFavoriteIcon)
            scenario.close()
            launch()
            assertIconVisible(enabled)
        }
    }

    @Test fun recreationPreservesManagementAndNormalTapOpensReaderAfterExit() {
        launch(false)
        longPressCover()
        scenario.recreate()
        rule.onNodeWithContentDescription("退出收藏管理").assertIsDisplayed()
        assertIconVisible(true)
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithContentDescription("退出收藏管理").assertDoesNotExist()
        assertIconVisible(false)
        tapCover()
        rule.waitUntil(20_000) {
            var ready = false
            scenario.onActivity { activity ->
                ready = descendants(activity.window.decorView).filterIsInstance<ZoomImageView>().any { it.drawable != null }
            }
            ready
        }
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithContentDescription("退出收藏管理").assertDoesNotExist()
        cover().assertIsDisplayed()
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
