package dev.zolive.zviewer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import dev.zolive.zviewer.data.AppLanguage
import dev.zolive.zviewer.data.Book
import dev.zolive.zviewer.data.BookRepository
import dev.zolive.zviewer.data.PreferenceStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

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

    @Test fun cancelledOpenCannotClearTheReplacementRequest() {
        val first = book.copy(modified = book.modified + 1)
        val pdf = store.readLibrary().first { it.kind == "pdf" }
        val second = pdf.copy(modified = pdf.modified + 1)
        val firstId = DocumentsContract.getDocumentId(Uri.parse(first.uri))
        val secondId = DocumentsContract.getDocumentId(Uri.parse(second.uri))
        val resolver = context.contentResolver
        val authority = "dev.zolive.zviewer.test.documents"
        val firstCache = File(context.cacheDir, "books/${first.cacheKey}")
        resolver.call(authority, "blockOpen", firstId, null)
        resolver.call(authority, "blockOpen", secondId, null)
        try {
            launch()
            lateinit var model: LibraryViewModel
            scenario.onActivity {
                model = ViewModelProvider(it)[LibraryViewModel::class.java]
                model.open(first)
            }
            rule.waitUntil(10_000) { resolver.call(authority, "enteredOpen", firstId, null)?.getBoolean("entered") == true }
            scenario.onActivity {
                model.cancelOpen()
                model.open(second)
            }
            resolver.call(authority, "releaseOpen", firstId, null)
            rule.waitUntil(10_000) {
                !firstCache.exists() && resolver.call(authority, "enteredOpen", secondId, null)?.getBoolean("entered") == true
            }
            rule.onNodeWithText("正在准备 PDF", substring = true).assertExists()
            resolver.call(authority, "releaseOpen", secondId, null)
            rule.waitUntil(10_000) { model.state.value.session?.book == second }
        } finally {
            resolver.call(authority, "releaseOpen", firstId, null)
            resolver.call(authority, "releaseOpen", secondId, null)
        }
    }

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
        rule.onNodeWithText("暂无收藏的漫画").assertIsDisplayed()
        swipe(true)
        tab("最近阅读").assertIsSelected()
        scenario.close()
        context.getSharedPreferences("zviewer", Context.MODE_PRIVATE).edit().clear().commit()
        launch()
        swipe(true)
        tab("收藏").assertIsSelected()
        swipe(true)
        tab("最近阅读").assertIsSelected()
        rule.onNodeWithText("选择书库目录").assertIsDisplayed()
    }

    @Test fun emptyIconsAlignAcrossTabsWithEnglishFiltersAndMessages() {
        store.saveFavorites(emptySet())
        store.saveSettings(store.settings().copy(language = AppLanguage.ENGLISH))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNodeWithText("Finished").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Finished").performClick().assertIsSelected()
        rule.onNodeWithText("No matching comics").assertIsDisplayed()
        val icon = rule.onNodeWithTag("empty-library-icon")
        val libraryCenter = icon.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center.y

        tab("Favorites").performClick()
        rule.onNodeWithText("No favorites yet").assertIsDisplayed()
        assertEquals(libraryCenter, icon.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center.y, 1f)

        tab("Recently read").performClick()
        rule.onNodeWithText("No matching comics").assertIsDisplayed()
        rule.onNodeWithText("Unread").assertDoesNotExist()
        assertEquals(libraryCenter, icon.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center.y, 1f)
    }

    @Test fun swipingFiltersKeepsTheCurrentTabWhenTheRowFits() {
        launch()
        tab("收藏").performClick()
        val filters = rule.onNodeWithTag("library-filters")
        val scrollRange = filters.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertEquals(0f, scrollRange.maxValue(), 0f)
        filters.performTouchInput { swipeLeft() }
        tab("收藏").assertIsSelected()
        filters.performTouchInput { swipeRight() }
        tab("收藏").assertIsSelected()
        assertEquals(0f, scrollRange.value(), 0f)
        rule.onNodeWithText("已读").performClick().assertIsSelected()
        swipe(true)
        tab("最近阅读").assertIsSelected()
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

    @Test fun searchMovesContentSmoothlyOnEveryTab() {
        launch()
        rule.onNodeWithTag("library-grid-0").performScrollToIndex(4)
        listOf("书库", "收藏", "最近阅读").forEachIndexed { index, title ->
            tab(title).performClick()
            val pager = rule.onNodeWithTag("library-pager")
            val grid = rule.onNodeWithTag("library-grid-$index")
            val original = pager.fetchSemanticsNode().boundsInRoot
            val scroll = grid.fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            rule.mainClock.autoAdvance = false
            rule.onNodeWithContentDescription("搜索漫画").performClick()
            rule.mainClock.advanceTimeBy(96)
            val entering = pager.fetchSemanticsNode().boundsInRoot
            rule.mainClock.advanceTimeBy(80)
            val expanding = pager.fetchSemanticsNode().boundsInRoot
            rule.mainClock.advanceTimeBy(300)
            val expanded = pager.fetchSemanticsNode().boundsInRoot
            assertTrue(entering.top > original.top)
            assertTrue(expanding.top > entering.top)
            assertTrue(expanded.top > expanding.top)
            assertEquals(original.bottom, expanded.bottom, 1f)
            rule.onNodeWithContentDescription("关闭搜索").assertIsDisplayed().performClick()
            rule.mainClock.advanceTimeBy(64)
            val leaving = pager.fetchSemanticsNode().boundsInRoot
            rule.mainClock.advanceTimeBy(64)
            val collapsing = pager.fetchSemanticsNode().boundsInRoot
            assertTrue(leaving.top < expanded.top)
            assertTrue(collapsing.top < leaving.top && collapsing.top > original.top)
            rule.mainClock.advanceTimeBy(300)
            rule.mainClock.autoAdvance = true
            assertEquals(original, pager.fetchSemanticsNode().boundsInRoot)
            assertEquals(scroll, grid.fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value(), .01f)
            rule.onNodeWithContentDescription("关闭搜索").assertDoesNotExist()
            tab(title).assertIsSelected()
        }
    }

    @Test fun searchTransitionCanReverseAndBackClearsQuery() {
        launch()
        val pager = rule.onNodeWithTag("library-pager")
        val original = pager.fetchSemanticsNode().boundsInRoot
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("搜索漫画").performClick()
        rule.mainClock.advanceTimeBy(96)
        assertTrue(pager.fetchSemanticsNode().boundsInRoot.top > original.top)
        rule.onNodeWithContentDescription("搜索漫画").performClick()
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithContentDescription("搜索漫画").performClick()
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithContentDescription("关闭搜索").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextReplacement("搜索动效验收_无匹配")
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("没有符合条件的漫画").assertIsDisplayed()
        systemBack()
        rule.mainClock.advanceTimeBy(64)
        assertTrue(pager.fetchSemanticsNode().boundsInRoot.top > original.top)
        rule.mainClock.advanceTimeBy(300)
        rule.mainClock.autoAdvance = true
        assertEquals(original, pager.fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithContentDescription("关闭搜索").assertDoesNotExist()
        rule.onNodeWithContentDescription("搜索漫画").performClick()
        rule.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText,
            androidx.compose.ui.text.AnnotatedString("")))
    }

    @Test fun swipingRightOnSettingsReturnsToLibrary() {
        launch()
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithTag("settings-screen").assertIsDisplayed().performTouchInput { swipeRight() }
        rule.waitForIdle()
        rule.onNodeWithTag("library-screen").assertIsDisplayed()
        rule.onNodeWithTag("settings-screen").assertDoesNotExist()
    }

    @Test fun diagnosticsUsesTheSameTransitionAndReturnsOnePageAtATime() {
        launch()
        rule.onNodeWithContentDescription("设置").performClick()
        val settings = rule.onNodeWithTag("settings-screen")
        val diagnostics = rule.onNodeWithTag("settings-diagnostics-screen")
        val original = settings.fetchSemanticsNode().boundsInRoot
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithText("诊断").performClick()
            rule.mainClock.advanceTimeBy(160)
            val outgoing = settings.fetchSemanticsNode().boundsInRoot
            val incoming = diagnostics.fetchSemanticsNode().boundsInRoot
            assertTrue(outgoing.height < original.height)
            assertTrue(outgoing.right < original.right)
            assertTrue(incoming.left > original.left && incoming.left < original.right)
            rule.mainClock.advanceTimeBy(400)
        } finally {
            rule.mainClock.autoAdvance = true
        }
        diagnostics.performTouchInput {
            swipe(Offset(width * .4f, centerY), Offset(width * .5f, centerY))
        }.assertIsDisplayed()
        rule.onNodeWithText("查看日志").performClick()
        rule.onNodeWithTag("log-list-screen").assertIsDisplayed()
        scenario.recreate()
        rule.onNodeWithContentDescription("返回诊断").performClick()
        diagnostics.assertIsDisplayed().performTouchInput { swipeRight() }
        settings.assertIsDisplayed()
        diagnostics.assertDoesNotExist()
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

    @Test fun returnToTopFollowsEachTabAndMovesTheManagementButton() {
        store.readLibrary().forEach { store.saveProgress(it.id, 0, 3) }
        store.saveFavorites(store.readLibrary().mapTo(mutableSetOf()) { it.id })
        launch()
        val arrow = rule.onNodeWithContentDescription("回到顶部")
        val close = rule.onNodeWithContentDescription("退出收藏管理")
        rule.onNodeWithContentDescription("切换书库目录").assertDoesNotExist()
        arrow.assertDoesNotExist()
        rule.onNode(hasText(book.title) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .performScrollTo().performTouchInput {
                longClick(Offset(centerX, height * .3f))
            }
        val trailingPosition = close.fetchSemanticsNode().boundsInRoot.center.x
        val grid = rule.onNodeWithTag("library-grid-0")
        grid.performTouchInput { swipeUp(durationMillis = 700) }
        arrow.assertIsDisplayed()
        assertEquals(trailingPosition, arrow.fetchSemanticsNode().boundsInRoot.center.x, 1f)
        assertTrue(close.fetchSemanticsNode().boundsInRoot.center.x < trailingPosition)
        tab("收藏").performClick()
        arrow.assertDoesNotExist()
        assertEquals(trailingPosition, close.fetchSemanticsNode().boundsInRoot.center.x, 1f)
        rule.onNodeWithTag("library-grid-1").performTouchInput { swipeUp() }
        arrow.assertIsDisplayed().performClick().assertDoesNotExist()
        tab("最近阅读").performClick()
        arrow.assertDoesNotExist()
        rule.onNodeWithTag("library-grid-2").performTouchInput { swipeUp() }
        arrow.assertIsDisplayed().performClick().assertDoesNotExist()
        tab("书库").performClick()
        arrow.assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        try {
            val leadingPosition = close.fetchSemanticsNode().boundsInRoot.center.x
            arrow.performClick()
            val scroll = grid.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            var movedBeforeTop = false
            for (frame in 1..120) {
                rule.mainClock.advanceTimeByFrame()
                if (scroll.value() == 0f) break
                val position = close.fetchSemanticsNode().boundsInRoot.center.x
                movedBeforeTop = movedBeforeTop || position > leadingPosition + 1f
            }
            assertTrue(movedBeforeTop)
            assertEquals(0f, scroll.value(), .001f)
            arrow.assertDoesNotExist()
            assertEquals(trailingPosition, close.fetchSemanticsNode().boundsInRoot.center.x, 1f)
        } finally {
            rule.mainClock.autoAdvance = true
        }
        close.performClick()
        close.assertDoesNotExist()
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
