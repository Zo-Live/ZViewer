package dev.zolive.zviewer

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zolive.zviewer.data.AnimationEffect
import dev.zolive.zviewer.data.AnimationSpeed
import dev.zolive.zviewer.data.AutoLockInterval
import dev.zolive.zviewer.data.HapticEvent
import dev.zolive.zviewer.data.LibraryFilter
import dev.zolive.zviewer.data.PagedImageFit
import dev.zolive.zviewer.data.PreferenceStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SettingsMenuTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = PreferenceStore(context)
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun launchSettings() {
        context.getSharedPreferences("zviewer", Context.MODE_PRIVATE).edit().clear().commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNodeWithContentDescription("设置").performClick()
    }

    @After fun close() {
        if (::scenario.isInitialized) scenario.close()
        context.getSharedPreferences("zviewer", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun setting(title: String) = rule.onNode(hasText(title) and hasClickAction())

    private fun option(label: String) = rule.onNode(
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
    )

    @Test fun sectionsOpenInOrderAndReturnToSettings() {
        val sections = listOf(
            "外观" to "应用主题",
            "偏好" to "语言",
            "阅读" to "画面适配",
            "书库与存储" to "清理阅读缓存",
            "诊断" to "查看日志",
            "安全与隐私" to "启动时验证",
            "应用" to "关于 ZViewer",
        )
        val positions = sections.map { (section, _) -> setting(section).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(positions.zipWithNext().all { (above, below) -> above < below })
        sections.forEach { (section, firstControl) ->
            setting(section).performScrollTo().performClick()
            rule.onNodeWithText(section).assertIsDisplayed()
            setting(firstControl).performScrollTo().assertIsDisplayed()
            rule.onNodeWithContentDescription("返回设置").performClick()
            setting(section).assertIsDisplayed()
        }
    }

    @Test fun securityDefaultsAndAutoLockIntervalArePersisted() {
        setting("安全与隐私").performClick()
        setting("启动时验证").assertIsOff()
        setting("切换至后台后重新验证").assertIsOff()
        val recentCard = setting("隐藏后台卡片")
        recentCard.assert(hasText("从最近任务列表隐藏应用卡片。需要先将应用切换至后台。"))
            .assertIsOff().performClick().assertIsOn()
        setting("在系统相册中隐藏图片").assertIsOff()
        setting("自动锁定间隔").assertIsDisplayed().assertIsNotEnabled()
        assertTrue(store.settings().hideRecentCard)
        assertEquals(AutoLockInterval.FifteenSeconds, store.settings().autoLockInterval)

        store.saveSettings(store.settings().copy(verifyAfterBackground = true))
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNodeWithContentDescription("设置").performClick()
        setting("安全与隐私").performClick()

        val interval = setting("自动锁定间隔")
        recentCard.assertIsOn()
        val positions = listOf(interval, recentCard, setting("在系统相册中隐藏图片"))
            .map { it.fetchSemanticsNode().boundsInRoot.top }
        assertTrue(positions.zipWithNext().all { (above, below) -> above < below })
        recentCard.performClick().assertIsOff()
        interval.assert(hasText("设定切换至后台后间隔多久需重新验证")).assert(hasText("15 秒")).performClick()
        option("立即").assertIsNotSelected()
        option("15 秒").assertIsSelected()
        option("1 分钟").assertIsNotSelected()
        option("5 分钟").assertIsNotSelected().performClick()
        interval.assert(hasText("5 分钟"))
        setting("切换至后台后重新验证").performClick().assertIsOff()
        setting("自动锁定间隔").assertIsDisplayed().assertIsNotEnabled()
        assertEquals(AutoLockInterval.FiveMinutes, store.settings().autoLockInterval)
        assertFalse(store.settings().hideRecentCard)
    }

    @Test fun recentPreviewAppliesVersionSpecificCapturePolicy() {
        val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        val hint = if (legacy) "在最近任务列表中隐藏应用预览，开启后无法截图或录屏。"
        else "在最近任务列表中隐藏应用预览"
        setting("安全与隐私").performClick()
        val preview = setting("模糊预览")
        preview.assert(hasText(hint)).assertIsOff().performClick().assertIsOn()
        assertTrue(store.settings().hideRecentPreview)
        assertSecureWindow(legacy)

        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNodeWithContentDescription("设置").performClick()
        setting("安全与隐私").performClick()
        preview.assertIsOn()
        assertSecureWindow(legacy)

        preview.performClick().assertIsOff()
        assertFalse(store.settings().hideRecentPreview)
        assertSecureWindow(false)
    }

    private fun assertSecureWindow(expected: Boolean) {
        scenario.onActivity { activity ->
            val secure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            assertEquals(expected, secure)
        }
    }

    @Test fun settingsExposeCurrentValuesAndMenuSelection() {
        setting("外观").performClick()
        val theme = setting("应用主题").performScrollTo()
        theme.assert(hasText("跟随系统"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已收起"))
        assertEquals("展开选项", theme.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        theme.performClick()
        theme.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
        option("跟随系统").assertIsSelected()
        option("浅色").assertIsNotSelected()
        option("深色").performClick()
        theme.assert(hasText("深色"))
        assertEquals("dark", store.settings().theme)

        val columns = setting("每行封面数").performScrollTo()
        val columnBounds = columns.fetchSemanticsNode().boundsInRoot
        val colorLabel = rule.onNodeWithText("主题颜色").fetchSemanticsNode().boundsInRoot
        assertTrue(columnBounds.top >= theme.fetchSemanticsNode().boundsInRoot.bottom)
        assertTrue(columnBounds.bottom <= colorLabel.top)
        columns.assert(hasText("3")).performClick()
        option("3").assertIsSelected()
        option("5").performClick()
        columns.assert(hasText("5"))
        assertEquals(5, store.settings().gridColumns)

        rule.onNodeWithContentDescription("返回设置").performClick()
        setting("偏好").performClick()
        columns.assertDoesNotExist()

        rule.onNodeWithContentDescription("返回设置").performClick()
        setting("阅读").performClick()
        val mode = setting("翻页模式").performScrollTo()
        mode.assert(hasText("连续")).performClick()
        option("连续").assertIsSelected()
        option("分页").performClick()
        mode.assert(hasText("分页"))
        assertTrue(store.settings().paged)
        assertTrue(store.settings().vertical)
    }

    @Test fun preferredStartupPageAppliesToEachNewLaunch() {
        val startupPage = setting("首选启动页")
        val page = setting("页面")
        val filter = setting("筛选项")
        val resumeReading = setting("启动即继续阅读")
        val library = rule.onNode(hasText("书库") and isSelectable())
        val favorites = rule.onNode(hasText("收藏") and isSelectable())
        val recent = rule.onNode(hasText("最近阅读") and isSelectable())

        fun openPreferences() {
            rule.onNodeWithContentDescription("设置").performClick()
            setting("偏好").performClick()
            startupPage.performScrollTo().assertIsDisplayed()
        }
        fun relaunch() {
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
        }

        setting("偏好").performClick()
        startupPage.performScrollTo()
        page.assertDoesNotExist()
        filter.assertDoesNotExist()
        val fileType = setting("显示文件类型").fetchSemanticsNode().boundsInRoot
        val resumeRow = resumeReading.assertIsOff().fetchSemanticsNode().boundsInRoot
        assertEquals(startupPage.fetchSemanticsNode().boundsInRoot.bottom, resumeRow.top, 1f)
        assertEquals(resumeRow.bottom, fileType.top, 1f)
        resumeReading.performClick().assertIsOn()
        assertTrue(store.settings().resumeReadingOnStartup)
        startupPage.performClick()
        val rows = listOf(startupPage, page, filter, resumeReading).map { it.fetchSemanticsNode().boundsInRoot.top }
        assertTrue(rows.zipWithNext().all { (above, below) -> above < below })
        filter.assert(hasText("全部")).performClick()
        option("全部").assertIsSelected()
        option("在读").assertIsNotSelected()
        option("已读").assertIsNotSelected()
        option("未读").performClick()
        page.assert(hasText("书库")).performClick()
        option("书库").assertIsSelected()
        option("最近阅读").assertIsNotSelected()
        option("收藏").assertIsNotSelected().performClick()
        page.assert(hasText("收藏"))
        filter.assert(hasText("未读"))
        rule.onNodeWithContentDescription("返回设置").performClick()
        rule.onNodeWithContentDescription("返回书库").performClick()
        library.assertIsSelected()
        scenario.recreate()
        library.assertIsSelected()

        relaunch()
        favorites.assertIsSelected()
        openPreferences()
        resumeReading.performScrollTo().assertIsOn()
        assertEquals(LibraryFilter.Unread, store.settings().startupFilter)
        page.assert(hasText("收藏")).performClick()
        option("收藏").assertIsSelected()
        option("最近阅读").performClick()
        filter.assert(hasText("全部")).performClick()
        option("未读").assertDoesNotExist()
        option("全部").assertIsSelected()
        option("在读").assertIsNotSelected()
        option("已读").performClick()
        relaunch()
        recent.assertIsSelected()
        openPreferences()
        filter.assert(hasText("已读"))
        page.assert(hasText("最近阅读")).performClick()
        option("书库").performClick()
        startupPage.performClick()
        page.assertDoesNotExist()
        filter.assertDoesNotExist()
        relaunch()
        library.assertIsSelected()
        openPreferences()
        page.assertDoesNotExist()
        filter.assertDoesNotExist()
    }

    @Test fun readingFitExpandsInlineAndKeepsSeparateChoices() {
        setting("阅读").performClick()
        val fit = setting("画面适配")
        val image = setting("图片与 PDF")
        val video = setting("视频")
        val mode = setting("翻页模式")
        image.assertDoesNotExist()
        video.assertDoesNotExist()
        fit.performClick()
        val rows = listOf(fit, image, video, mode).map { it.fetchSemanticsNode().boundsInRoot.top }
        assertTrue(rows.zipWithNext().all { (above, below) -> above < below })
        val labelPositions = listOf("画面适配", "图片与 PDF", "视频").map {
            rule.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        }
        assertEquals(1, labelPositions.distinct().size)

        image.assert(hasText("填充屏幕")).performClick()
        option("填充屏幕").assertIsSelected()
        option("适应宽度").assertDoesNotExist()
        option("适应屏幕").performClick()
        video.assert(hasText("适应屏幕")).performClick()
        option("填充屏幕").performClick()

        mode.performClick()
        option("分页").performClick()
        image.assert(hasText("适应屏幕")).performClick()
        option("填充屏幕").assertDoesNotExist()
        option("适应宽度").assertIsNotSelected()
        option("适应高度").performClick()
        fit.performClick()
        image.assertDoesNotExist()
        video.assertDoesNotExist()
        fit.performClick()
        image.assert(hasText("适应高度"))
        video.assert(hasText("填充屏幕"))
        mode.performClick()
        option("连续").performClick()
        image.assert(hasText("适应屏幕"))
        assertEquals(PagedImageFit.Height, store.settings().pagedImageFit)
        assertTrue(store.settings().continuousImageFitScreen)
        assertTrue(store.settings().videoFillScreen)
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNodeWithContentDescription("设置").performClick()
        setting("阅读").performClick()
        image.assertIsDisplayed()
        video.assertIsDisplayed()
    }

    @Test fun interactionPreferencesFollowLanguageAndPersist() {
        setting("偏好").performClick()
        val language = setting("语言")
        val speed = setting("动画速度")
        val haptics = setting("触感反馈")
        val rows = listOf(language, speed, setting("动画设置"), haptics, setting("触感设置"), setting("首选启动页"),
            setting("启动即继续阅读"), setting("显示文件类型"))
            .map { it.fetchSemanticsNode().boundsInRoot }
        rows.zipWithNext().forEach { (upper, lower) -> assertEquals(upper.bottom, lower.top, 1f) }
        speed.assert(hasText("适中")).performClick()
        option("适中").assertIsSelected()
        option("增强").assertIsNotSelected().performClick()
        speed.assert(hasText("增强"))
        haptics.assertIsOn().performClick().assertIsOff()
        assertEquals(AnimationSpeed.Enhanced, store.settings().animationSpeed)
        assertFalse(store.settings().hapticFeedback)

        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNodeWithContentDescription("设置").performClick()
        setting("偏好").performClick()
        speed.assert(hasText("增强"))
        haptics.assertIsOff()
        speed.performClick()
        option("增强").assertIsSelected()
        option("适中").performClick()
        haptics.performClick().assertIsOn()
        assertEquals(AnimationSpeed.Moderate, store.settings().animationSpeed)
        assertTrue(store.settings().hapticFeedback)
    }

    @Test fun interactionCategoriesKeepIndividualChoicesWhenDisabled() {
        setting("偏好").performClick()
        val speed = setting("动画速度")
        val haptics = setting("触感反馈")
        val animationLink = setting("动画设置")
        val hapticsLink = setting("触感设置")
        val master = setting("启用")
        val back = rule.onNodeWithContentDescription("返回偏好")
        animationLink.assertIsDisplayed().assertIsNotEnabled()
        hapticsLink.assertIsDisplayed().assertIsEnabled()

        speed.performClick()
        option("增强").performClick()
        animationLink.assertIsEnabled().performClick()
        rule.onNodeWithContentDescription("全部选项").assertDoesNotExist()
        rule.onAllNodes(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertCountEquals(7)
        setting("展开与弹出").performClick()
        val menu = setting("下拉菜单")
        val search = setting("搜索栏展开和收起")
        master.assert(hasText("关闭后，以下动画速度保持为「适中」。")).assertIsOn()
        search.assertIsOn().performClick().assertIsOff()
        master.performClick().assertIsOff()
        menu.assertIsOn().assertIsNotEnabled()
        search.assertIsOff().assertIsNotEnabled()
        assertEquals(setOf(AnimationEffect.LibrarySearch), store.settings().disabledAnimations)
        assertEquals(AnimationSpeed.Moderate, store.settings().animationSpeedFor(AnimationEffect.DropdownMenu))
        assertEquals(AnimationSpeed.Enhanced, store.settings().animationSpeedFor(AnimationEffect.ReaderEntry))
        scenario.recreate()
        master.assertIsOff().performClick().assertIsOn()
        search.assertIsEnabled().assertIsOff()
        assertEquals(AnimationSpeed.Enhanced, store.settings().animationSpeedFor(AnimationEffect.DropdownMenu))
        rule.onNodeWithContentDescription("返回动画设置").performClick()
        back.performClick()
        speed.performClick()
        option("适中").performClick()
        animationLink.assertIsDisplayed().assertIsNotEnabled()

        hapticsLink.performClick()
        rule.onNodeWithContentDescription("全部选项").assertDoesNotExist()
        setting("确认与开启").performClick()
        val favorite = setting("添加收藏")
        master.assert(hasText("关闭后，以下触感效果将不可用。")).assertIsOn()
        favorite.assertIsOn().performClick().assertIsOff()
        master.performClick().assertIsOff()
        favorite.assertIsNotEnabled().assertIsOff()
        setting("打开开关").assertIsNotEnabled().assertIsOn()
        assertFalse(store.settings().hapticsEnabledFor(HapticEvent.EnableHapticFeedback))
        assertTrue(store.settings().hapticsEnabledFor(HapticEvent.RemoveFavorite))
        rule.onNodeWithContentDescription("返回触感设置").performClick()
        back.performClick()
        haptics.performClick().assertIsOff()
        hapticsLink.assertIsDisplayed().assertIsNotEnabled()
        haptics.performClick().assertIsOn()
        hapticsLink.assertIsEnabled().performClick()
        setting("确认与开启").performClick()
        master.assertIsOff().performClick().assertIsOn()
        favorite.assertIsEnabled().assertIsOff()
        assertEquals(setOf(HapticEvent.AddFavorite), store.settings().disabledHaptics)
    }

    @Test fun legacyInteractionChoicesAppearAsIndividualSwitches() {
        scenario.close()
        context.getSharedPreferences("zviewer", Context.MODE_PRIVATE).edit()
            .putBoolean("fast_animations", true)
            .putStringSet("disabled_animations", setOf("library_search", "reader_paging"))
            .putStringSet("disabled_haptics", setOf("favorite", "theme_color_wheel", "video_speed_lock"))
            .commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNodeWithContentDescription("设置").performClick()
        setting("偏好").performClick()

        setting("动画设置").performClick()
        setting("展开与弹出").performClick()
        setting("启用").assertIsOn()
        setting("搜索栏展开和收起").assertIsOff().performClick().assertIsOn()
        assertEquals(setOf(AnimationEffect.ReaderPaging), store.settings().disabledAnimations)
        rule.onNodeWithContentDescription("返回动画设置").performClick()
        rule.onNodeWithContentDescription("返回偏好").performClick()

        setting("触感设置").performClick()
        setting("确认与开启").performClick()
        setting("启用").assertIsOn()
        setting("添加收藏").assertIsOff().performClick().assertIsOn()
        setting("确认色轮选色").assertIsOff()
        setting("锁定播放倍速").assertIsOff()
        setting("长按临时加速").assertIsOff()
        val saved = store.settings()
        assertTrue(saved.hapticsEnabledFor(HapticEvent.AddFavorite))
        assertFalse(saved.hapticsEnabledFor(HapticEvent.RemoveFavorite))
        assertFalse(saved.hapticsEnabledFor(HapticEvent.ThemeColorWheel))
        assertFalse(saved.hapticsEnabledFor(HapticEvent.ThemeColorWheelConfirm))
        assertFalse(saved.hapticsEnabledFor(HapticEvent.VideoSpeedUnlock))
        assertFalse(saved.hapticsEnabledFor(HapticEvent.VideoSpeedHoldNormal))
    }

    @Test fun switchingLanguagesKeepsSettingsOpenAndPersists() {
        setting("偏好").performClick()
        lateinit var activity: MainActivity
        scenario.onActivity { activity = it }
        val backCenter = rule.onNodeWithContentDescription("返回设置").fetchSemanticsNode().boundsInRoot.center
        setting("语言").performScrollTo().assert(hasText("简体中文")).performClick()
        option("简体中文").assertIsSelected()
        option("繁體中文").assertIsNotSelected()
        option("日本語").assertIsNotSelected()
        option("English").performClick()
        awaitSetting("Language", "English")
        rule.onNodeWithText("Preferences").assertIsDisplayed()
        val englishBack = rule.onNodeWithContentDescription("Back to settings").fetchSemanticsNode().boundsInRoot.center
        assertEquals(backCenter, englishBack)
        scenario.onActivity { assertSame(activity, it) }

        setting("Language").performClick()
        option("繁體中文").performClick()
        awaitSetting("語言", "繁體中文")
        rule.onNodeWithText("偏好設定").assertIsDisplayed()
        scenario.onActivity { assertSame(activity, it) }

        setting("語言").performClick()
        option("日本語").performClick()
        awaitSetting("言語", "日本語")
        rule.onNodeWithText("基本設定").assertIsDisplayed()
        scenario.onActivity { assertSame(activity, it) }
        assertEquals("ja", store.language.tag)
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNodeWithContentDescription("設定").performClick()
        setting("基本設定").performClick()
        awaitSetting("言語", "日本語")
        setting("言語").performClick()
        option("简体中文").performClick()
        awaitSetting("语言", "简体中文")
    }

    @Test fun readingSettingsFollowDirectionIndependentlyOfMode() {
        setting("阅读").performClick()
        val mode = setting("翻页模式").assert(hasText("连续"))
        val direction = setting("翻页方向").assert(hasText("垂直"))
        val rightToLeft = setting("从右向左翻页")
        rule.onNodeWithText("阅读模式").assertDoesNotExist()
        val horizontalSeek = setting("左右滑动调整视频进度")
        val pageNumber = setting("右下角显示页码")
        val volume = setting("使用音量键翻页")
        val zoom = setting("双击以放缩")
        val resetProgress = setting("关闭漫画后重置阅读进度")
        rightToLeft.assertIsDisplayed().assertIsNotEnabled()
        horizontalSeek.assertIsOn()
        val topRows = listOf(mode, direction, horizontalSeek, rightToLeft).map { it.fetchSemanticsNode().boundsInRoot }
        topRows.zipWithNext().forEach { (upper, lower) -> assertTrue(upper.bottom <= lower.top) }
        horizontalSeek.performClick().assertIsOff()
        pageNumber.assertIsOn()
        volume.assertIsOff()
        zoom.assertIsOn()
        resetProgress.assertIsOff()
        mode.performClick()
        option("分页").performClick()
        horizontalSeek.assertIsOff()
        rightToLeft.assertIsDisplayed().assertIsNotEnabled()
        direction.performClick()
        option("水平").performClick()
        mode.assert(hasText("分页"))
        horizontalSeek.assertIsDisplayed().assertIsNotEnabled()
        rightToLeft.assertIsOff()
        val rows = listOf(mode, direction, horizontalSeek, rightToLeft, pageNumber).map { it.fetchSemanticsNode().boundsInRoot }
        rows.zipWithNext().forEach { (upper, lower) -> assertTrue(upper.bottom <= lower.top) }
        rule.onNodeWithText("适用于右开本的水平阅读模式").assertDoesNotExist()
        rule.onNodeWithText("离开阅读器后恢复系统设置").assertDoesNotExist()
        rightToLeft.performClick().assertIsOn()
        pageNumber.performClick().assertIsOff()
        volume.performScrollTo().performClick().assertIsOn()
        zoom.performScrollTo().performClick().assertIsOff()
        resetProgress.performScrollTo().performClick().assertIsOn()
        mode.performScrollTo().performClick()
        option("连续").performClick()
        rightToLeft.assertIsOn()
        horizontalSeek.assertIsDisplayed().assertIsNotEnabled()
        direction.performClick()
        option("垂直").performClick()
        rightToLeft.assertIsDisplayed().assertIsNotEnabled()
        horizontalSeek.assertIsOff()
        direction.performClick()
        option("水平").performClick()
        rightToLeft.assertIsOn()
        val saved = store.settings()
        assertFalse(saved.paged)
        assertFalse(saved.vertical)
        assertFalse(saved.horizontalVideoSeek)
        assertFalse(saved.showPageNumber)
        assertTrue(saved.volumeKeyPaging)
        assertFalse(saved.doubleTapZoom)
        assertTrue(saved.resetProgressOnClose)
    }

    @Test fun legacyHorizontalReadingKeepsPagingUntilModeIsChanged() {
        val preferences = context.getSharedPreferences("zviewer", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("vertical", false).remove("paged").commit()
        assertFalse(store.settings().vertical)
        assertTrue(store.settings().paged)
        store.saveSettings(store.settings().copy(paged = false))
        assertFalse(store.settings().vertical)
        assertFalse(store.settings().paged)
    }

    @Test fun verificationThemeRemainsVisibleAndRequiresStartupVerification() {
        setting("外观").performClick()
        val followTheme = setting("验证页跟随主题颜色")
        val hint = "开启「安全与隐私 - 启动时验证」后可用"
        followTheme.performScrollTo().assertIsNotEnabled().assert(hasText(hint))
        fun verifyOnStartup(enabled: Boolean) {
            scenario.onActivity {
                val model = ViewModelProvider(it)[LibraryViewModel::class.java]
                model.settings(model.state.value.settings.copy(verifyOnStartup = enabled))
            }
        }
        verifyOnStartup(true)
        followTheme.performScrollTo().assertIsEnabled().assert(!hasText(hint)).assertIsOn().performClick().assertIsOff()
        scenario.recreate()
        followTheme.performScrollTo().assertIsOff()
        verifyOnStartup(false)
        followTheme.performScrollTo().assertIsNotEnabled().assert(hasText(hint)).assertIsOff()
        verifyOnStartup(true)
        followTheme.performScrollTo().assertIsOff()
        assertFalse(store.settings().verificationFollowsTheme)
    }

    @Test fun customThemeColorControlsStayInSyncAndOnlyApplySaves() {
        setting("外观").performClick()
        setting("应用主题").performClick()
        option("浅色").performClick()
        val palette = rule.onNodeWithContentDescription("自定义主题颜色")
        val wheel = rule.onNodeWithContentDescription("色轮")
        val hex = rule.onNode(hasSetTextAction())
        val apply = rule.onNodeWithText("应用")
        palette.performClick()
        hex.assertTextContains("49684D")
        rule.onNodeWithContentDescription("浅色").assertIsDisplayed()
        rule.onNodeWithContentDescription("深色").assertDoesNotExist()

        hex.assertIsDisplayed().performTextReplacement("#FF0000")
        wheel.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "FF0000"))
        wheel.performScrollTo().performTouchInput {
            swipe(center + Offset(width * .43f, 0f), center + Offset(-width * .43f, 0f))
        }
        hex.assertTextContains("00FFFF")

        rule.onNodeWithContentDescription("G").performSemanticsAction(SemanticsActions.SetProgress) { it(128f) }
        hex.assertTextContains("0080FF")
        wheel.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0080FF"))
        hex.performTextReplacement("123")
        apply.assertIsNotEnabled()
        assertEquals(0xFF49684D, store.settings().accent)
        rule.onNodeWithText("取消").performClick()

        palette.performClick()
        hex.assertTextContains("49684D")
        hex.performTextReplacement("aBcDeF")
        apply.assertIsEnabled().performClick()
        assertEquals(0xFFABCDEF, store.settings().accent)
        setting("应用主题").performClick()
        option("深色").performClick()
        palette.performClick()
        hex.assertTextContains("ABCDEF")
        rule.onNodeWithContentDescription("浅色").assertDoesNotExist()
        rule.onNodeWithContentDescription("深色").assertIsDisplayed()
    }

    @Test fun presetsStaySelectableWithDynamicColor() {
        setting("外观").performClick()
        val toggle = setting("动态取色")
        val presets = listOf(
            0xFF49684D to "苔绿", 0xFF476A8A to "雾蓝", 0xFF79618C to "藤紫",
            0xFF9A5357 to "蔷薇", 0xFF8B683E to "琥珀",
        ).associate { (color, name) -> color to rule.onNodeWithContentDescription("主题颜色：$name") }
        presets.getValue(0xFF49684D).assertIsSelected()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            toggle.assertDoesNotExist()
            presets.getValue(0xFF476A8A).performClick().assertIsSelected()
            return
        }
        toggle.assertIsOff()

        setting("应用主题").performClick()
        option("浅色").performClick()
        val primary = dynamicLightColorScheme(context).primary.toArgb().toLong() and 0xFFFFFFFFL
        val (manualColor, manualPreset) = presets.entries.first { it.key != primary }
        manualPreset.performClick()
        toggle.performClick().assertIsOn()
        presets.forEach { (color, preset) ->
            preset.assertIsEnabled().assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, color == primary))
        }
        assertEquals(manualColor, store.settings().accent)
        toggle.performClick().assertIsOff()
        manualPreset.assertIsSelected()

        presets[primary]?.let { matchingPreset ->
            matchingPreset.performClick().assertIsSelected()
            toggle.assertIsOff().performClick().assertIsOn()
            matchingPreset.assertIsSelected()
            toggle.performClick().assertIsOff()
            matchingPreset.assertIsSelected()

            manualPreset.performClick()
            toggle.performClick().assertIsOn()
            matchingPreset.performClick().assertIsSelected()
            toggle.assertIsOn().performClick().assertIsOff()
            matchingPreset.assertIsSelected()
        }

        val (overrideColor, overridePreset) = presets.entries.first { it.key != primary && it.key != manualColor }
        toggle.performClick().assertIsOn()
        overridePreset.performClick().assertIsSelected()
        toggle.assertIsOff()
        assertEquals(overrideColor, store.settings().accent)
        assertFalse(store.settings().dynamicColor)
    }

    @Test fun dynamicColorPickerStartsFromTheSystemSchemeAndOnlyAppliesChangedColors() {
        setting("外观").performClick()
        val toggle = setting("动态取色")
        val custom = rule.onNodeWithContentDescription("自定义主题颜色")
        val mist = rule.onNodeWithContentDescription("主题颜色：雾蓝")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            toggle.assertDoesNotExist()
            custom.assertIsDisplayed()
            return
        }
        toggle.assertIsOff()

        mist.performClick().assertIsSelected()
        val manualAccent = store.settings().accent
        setting("应用主题").performClick()
        option("浅色").performClick()
        toggle.performClick().assertIsOn()
        assertTrue(store.settings().dynamicColor)
        custom.performClick()
        assertSystemPalette("浅色", dynamicLightColorScheme(context))
        val hex = rule.onNode(hasSetTextAction())
        val apply = rule.onNodeWithText("应用")
        val cancel = rule.onNodeWithText("取消")
        hex.performTextReplacement("123456")
        cancel.performClick()
        toggle.assertIsOn()
        assertEquals(manualAccent, store.settings().accent)

        custom.performClick()
        apply.performClick()
        toggle.assertIsOn()
        assertEquals(manualAccent, store.settings().accent)

        setting("应用主题").performClick()
        option("深色").performClick()
        val dark = dynamicDarkColorScheme(context)
        custom.performClick()
        assertSystemPalette("深色", dark)
        hex.performTextReplacement("123456")
        hex.performTextReplacement(colorHex(dark.primary.toArgb()).lowercase())
        apply.performClick()
        toggle.assertIsOn()
        assertEquals(manualAccent, store.settings().accent)

        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNodeWithContentDescription("设置").performClick()
        setting("外观").performClick()
        toggle.assertIsOn().performClick().assertIsOff()
        mist.assertIsSelected()
        assertEquals(manualAccent, store.settings().accent)

        toggle.performClick()
        custom.performClick()
        hex.performTextReplacement("ABCDEF")
        apply.performClick()
        toggle.assertIsOff()
        assertFalse(store.settings().dynamicColor)
        assertEquals(0xFFABCDEF, store.settings().accent)
        custom.performClick()
        hex.assertTextContains("ABCDEF")
    }

    private fun assertSystemPalette(label: String, scheme: ColorScheme) {
        val preview = rule.onNodeWithContentDescription(label).captureToImage().toPixelMap()
        listOf(scheme.primary, scheme.secondary, scheme.primaryContainer, scheme.surface).forEachIndexed { index, color ->
            assertEquals(color.toArgb(), preview[preview.width * (2 * index + 1) / 8, preview.height / 2].toArgb())
        }
        val primary = scheme.primary.toArgb()
        val hex = colorHex(primary)
        rule.onNode(hasSetTextAction()).assertTextContains(hex)
        rule.onNodeWithContentDescription("色轮")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, hex))
        listOf("R" to 16, "G" to 8, "B" to 0).forEach { (channel, shift) ->
            val value = ((primary shr shift) and 0xFF).toFloat()
            rule.onNodeWithContentDescription(channel).assert(SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(value, 0f..255f),
            ))
        }
    }

    private fun colorHex(rgb: Int) = (rgb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

    @Test fun licenseIntroductionUsesTheSelectedLanguage() {
        setting("偏好").performClick()
        setting("语言").performScrollTo().performClick()
        option("日本語").performClick()
        awaitSetting("言語", "日本語")

        rule.onNodeWithContentDescription("設定に戻る").performClick()
        setting("アプリ").performClick()
        rule.onNodeWithText("ZViewer について").performScrollTo().performClick()
        rule.onNode(hasText("オープンソースライセンス") and hasClickAction()).performClick()
        val notice = hasText("ライセンス本文は原文で掲載しています。", substring = true)
        rule.onNode(notice).assertIsDisplayed()
    }

    private fun awaitSetting(title: String, value: String) {
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText(title) and hasText(value) and hasClickAction())
                .fetchSemanticsNodes().isNotEmpty()
        }
        setting(title).performScrollTo().assertIsDisplayed()
    }
}
