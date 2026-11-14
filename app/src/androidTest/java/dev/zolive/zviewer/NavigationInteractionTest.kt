package dev.zolive.zviewer

import android.content.Context
import android.provider.DocumentsContract
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zolive.zviewer.data.Book
import dev.zolive.zviewer.data.BookRepository
import dev.zolive.zviewer.data.PreferenceStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NavigationInteractionTest {
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
        rule.mainClock.autoAdvance = true
        if (::scenario.isInitialized) scenario.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(20_000) {
            rule.onAllNodesWithContentDescription("设置").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        if (store.treeUri != null) rule.onNodeWithContentDescription("刷新书库").assertIsEnabled()
    }

    private fun tab(title: String) = rule.onNode(hasText(title) and isSelectable())

    private fun swipe(left: Boolean) {
        rule.onNodeWithTag("library-pager").performTouchInput {
            if (left) swipeLeft() else swipeRight()
        }
        rule.waitForIdle()
    }

    private fun systemBack() {
        rule.waitForIdle()
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test fun swipesAndNavigationButtonsStayInSyncAtBothEnds() {
        launch()
        swipe(false)
        tab("书库").assertIsSelected()
        swipe(true)
        tab("收藏").assertIsSelected()
        rule.onNodeWithText(book.title).assertIsDisplayed()
        swipe(true)
        tab("最近阅读").assertIsSelected()
        rule.onNodeWithText(book.title).assertIsDisplayed()
        swipe(true)
        tab("最近阅读").assertIsSelected()
        swipe(false)
        tab("收藏").assertIsSelected()
        swipe(false)
        tab("书库").assertIsSelected()
        tab("最近阅读").performClick().assertIsSelected()
        swipe(false)
        tab("收藏").assertIsSelected()
    }

    @Test fun emptyPagesAndWelcomeStillAcceptSwipes() {
        store.saveFavorites(emptySet())
        launch()
        swipe(true)
        tab("收藏").assertIsSelected()
        rule.onNodeWithText("没有符合条件的漫画").assertIsDisplayed()
        swipe(true)
        tab("最近阅读").assertIsSelected()
        scenario.close()
        context.getSharedPreferences("zviewer", Context.MODE_PRIVATE).edit().clear().commit()
        launch()
        swipe(true)
        tab("收藏").assertIsSelected()
        swipe(true)
        tab("最近阅读").assertIsSelected()
        rule.onNodeWithText("选择书库文件夹").assertIsDisplayed()
    }

    @Test fun partialDragCanBeCancelledAndVerticalScrollDoesNotChangeTab() {
        launch()
        rule.onNodeWithTag("library-pager").performTouchInput {
            down(Offset(width * .8f, centerY))
            moveTo(Offset(width * .55f, centerY), 250)
            moveTo(Offset(width * .8f, centerY), 250)
            up()
        }
        tab("书库").assertIsSelected()
        rule.onNodeWithTag("library-grid-0").performTouchInput { swipeUp() }
        tab("书库").assertIsSelected()
        assertTrue(rule.onNodeWithTag("library-grid-0").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
    }

    @Test fun settingsReturnsToSearchFilterAndSelectedPage() {
        launch()
        swipe(true)
        rule.onNodeWithContentDescription("搜索漫画").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement(book.title)
        rule.onNodeWithText("在读").performClick()
        rule.onNodeWithContentDescription("排序").performClick()
        rule.onNodeWithText("最近修改").performClick()
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithContentDescription("返回书库").performClick()
        tab("收藏").assertIsSelected()
        rule.onNode(hasSetTextAction()).assertTextEquals(book.title)
        rule.onNodeWithText("在读").assertIsSelected()
        rule.onNodeWithContentDescription("取消收藏 ${book.title}").assertIsDisplayed()
        rule.onNodeWithContentDescription("设置").performClick()
        systemBack()
        tab("收藏").assertIsSelected()
        rule.onNodeWithContentDescription("关闭搜索").assertIsDisplayed()
        systemBack()
        rule.onNodeWithContentDescription("关闭搜索").assertDoesNotExist()
    }

    @Test fun swipingRightOnSettingsReturnsToLibrary() {
        launch()
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithTag("settings-screen").assertIsDisplayed().performTouchInput { swipeRight() }
        rule.waitForIdle()
        rule.onNodeWithTag("library-screen").assertIsDisplayed()
        rule.onNodeWithTag("settings-screen").assertDoesNotExist()
    }

    @Test fun gridPositionSurvivesTabSwitchSettingsAndRecreation() {
        launch()
        rule.onNodeWithTag("library-grid-0").performTouchInput { swipeUp() }
        val scroll = rule.onNodeWithTag("library-grid-0").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(scroll > 0f)
        swipe(true)
        swipe(false)
        assertEquals(scroll, rule.onNodeWithTag("library-grid-0").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value(), .01f)
        rule.onNodeWithContentDescription("设置").performClick()
        scenario.recreate()
        rule.onNodeWithContentDescription("返回书库").performClick()
        tab("书库").assertIsSelected()
        assertEquals(scroll, rule.onNodeWithTag("library-grid-0").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value(), .01f)
    }

    @Test fun settingsAnimationMovesAndScalesBothDirections() {
        launch()
        val original = rule.onNodeWithTag("library-screen").fetchSemanticsNode().boundsInRoot
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("设置").performClick()
        rule.mainClock.advanceTimeBy(160)
        val outgoing = rule.onNodeWithTag("library-screen").fetchSemanticsNode().boundsInRoot
        val incoming = rule.onNodeWithTag("settings-screen").fetchSemanticsNode().boundsInRoot
        assertTrue(outgoing.height < original.height)
        assertTrue(outgoing.height > original.height * .79f)
        assertTrue(outgoing.top > original.top)
        assertTrue(outgoing.right < original.right)
        assertTrue(incoming.left > original.left && incoming.left < original.right)
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("library-screen").assertDoesNotExist()
        rule.onNodeWithContentDescription("返回书库").performClick()
        rule.mainClock.advanceTimeBy(160)
        val returning = rule.onNodeWithTag("library-screen").fetchSemanticsNode().boundsInRoot
        val leaving = rule.onNodeWithTag("settings-screen").fetchSemanticsNode().boundsInRoot
        assertTrue(returning.height < original.height)
        assertTrue(returning.top > original.top)
        assertTrue(leaving.left > original.left && leaving.left < original.right)
        rule.mainClock.advanceTimeBy(400)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("settings-screen").assertDoesNotExist()
        assertEquals(original, rule.onNodeWithTag("library-screen").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun backDuringSettingsEntranceRestoresLibrary() {
        launch()
        swipe(true)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("设置").performClick()
        rule.mainClock.advanceTimeBy(96)
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.mainClock.advanceTimeBy(600)
        rule.mainClock.autoAdvance = true
        tab("收藏").assertIsSelected()
        rule.onNodeWithTag("settings-screen").assertDoesNotExist()
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithContentDescription("返回书库").performClick()
        tab("收藏").assertIsSelected()
    }
}
