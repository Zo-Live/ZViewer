package dev.zolive.zviewer

import android.content.Context
import android.os.Build
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
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zolive.zviewer.data.AnimationEffect
import dev.zolive.zviewer.data.AnimationSpeed
import dev.zolive.zviewer.data.AutoLockInterval
import dev.zolive.zviewer.data.HapticEvent
import dev.zolive.zviewer.data.PagedImageFit
import dev.zolive.zviewer.data.PreferenceStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        setting("模糊预览").assert(hasText("在最近任务列表中隐藏应用预览，开启后无法截屏或录屏。"))
            .assertIsOff().performClick().assertIsOn()
        setting("切换至后台后重新验证").assertIsOff()
        val recentCard = setting("隐藏后台卡片")
        recentCard.assert(hasText("从最近任务列表隐藏应用卡片。需要先将应用切换至后台。"))
            .assertIsOff().performClick().assertIsOn()
        setting("在系统相册中隐藏图片").assertIsOff()
        rule.onNodeWithText("自动锁定间隔").assertDoesNotExist()
        assertTrue(store.settings().hideRecentPreview)
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
        rule.onNodeWithText("自动锁定间隔").assertDoesNotExist()
        assertEquals(AutoLockInterval.FiveMinutes, store.settings().autoLockInterval)
        assertFalse(store.settings().hideRecentCard)
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
        val mode = setting("切页模式").performScrollTo()
        mode.assert(hasText("连续")).performClick()
        option("连续").assertIsSelected()
        option("分页").performClick()
        mode.assert(hasText("分页"))
        assertTrue(store.settings().paged)
        assertTrue(store.settings().vertical)
    }

    @Test fun preferredStartupPageAppliesToEachNewLaunch() {
        val startupPage = setting("首选启动页")
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
        val fileType = setting("显示文件类型").fetchSemanticsNode().boundsInRoot
        val resumeRow = resumeReading.assertIsOff().fetchSemanticsNode().boundsInRoot
        assertEquals(startupPage.fetchSemanticsNode().boundsInRoot.bottom, resumeRow.top, 1f)
        assertEquals(resumeRow.bottom, fileType.top, 1f)
        resumeReading.performClick().assertIsOn()
        assertTrue(store.settings().resumeReadingOnStartup)
        startupPage.assert(hasText("书库")).performClick()
        option("书库").assertIsSelected()
        option("最近阅读").assertIsNotSelected()
        option("收藏").assertIsNotSelected().performClick()
        startupPage.assert(hasText("收藏"))
        rule.onNodeWithContentDescription("返回设置").performClick()
        rule.onNodeWithContentDescription("返回书库").performClick()
        library.assertIsSelected()
        scenario.recreate()
        library.assertIsSelected()

        relaunch()
        favorites.assertIsSelected()
        openPreferences()
        resumeReading.assertIsOn()
        startupPage.assert(hasText("收藏")).performClick()
        option("收藏").assertIsSelected()
        option("最近阅读").performClick()
        relaunch()
        recent.assertIsSelected()
        openPreferences()
        startupPage.assert(hasText("最近阅读")).performClick()
        option("书库").performClick()
        relaunch()
        library.assertIsSelected()
    }

    @Test fun readingFitExpandsInlineAndKeepsSeparateChoices() {
        setting("阅读").performClick()
        val fit = setting("画面适配")
        val image = setting("图片与 PDF")
        val video = setting("视频")
        val mode = setting("切页模式")
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
    }

    @Test fun interactionPreferencesFollowLanguageAndPersist() {
        setting("偏好").performClick()
        val language = setting("语言")
        val speed = setting("动画速度")
        val haptics = setting("触感反馈")
        val rows = listOf(language, speed, haptics, setting("触感设置"), setting("首选启动页"),
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

    @Test fun interactionSubpagesShowOnlyWithTheirMasterSettingsAndRestoreSeparateChoices() {
        setting("偏好").performClick()
        val speed = setting("动画速度")
        val haptics = setting("触感反馈")
        val animationLink = setting("动画设置")
        val settingsTransition = setting("设置过渡")
        val hapticsLink = setting("触感设置")
        val back = rule.onNodeWithContentDescription("返回偏好")
        val isSwitch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        animationLink.assertDoesNotExist()
        hapticsLink.assertIsDisplayed()

        speed.performClick()
        option("增强").performClick()
        assertEquals(speed.fetchSemanticsNode().boundsInRoot.bottom, animationLink.fetchSemanticsNode().boundsInRoot.top, 1f)
        animationLink.performClick()
        rule.onAllNodes(isSwitch).assertCountEquals(AnimationEffect.entries.size)
        rule.onAllNodes(isSwitch and isOn()).assertCountEquals(AnimationEffect.entries.size)
        settingsTransition.assertIsOn().performClick().assertIsOff()
        setting("页面切换").assertIsOn()
        setting("开关切换").assertIsOn()
        assertEquals(AnimationSpeed.Moderate, store.settings().animationSpeedFor(AnimationEffect.SettingsTransition))
        assertEquals(AnimationSpeed.Enhanced, store.settings().animationSpeedFor(AnimationEffect.PageTransition))
        setting("播放倍速菜单").performScrollTo().performClick().assertIsOff()
        setting("视频缩放").assertIsOn()
        scenario.recreate()
        back.assertIsDisplayed().performClick()
        speed.performClick()
        option("适中").performClick()
        animationLink.assertDoesNotExist()
        speed.performClick()
        option("增强").performClick()
        animationLink.performClick()
        settingsTransition.assertIsOff()
        setting("播放倍速菜单").performScrollTo().assertIsOff()
        back.performClick()

        hapticsLink.performClick()
        rule.onAllNodes(isSwitch).assertCountEquals(HapticEvent.entries.size)
        rule.onAllNodes(isSwitch and isOn()).assertCountEquals(HapticEvent.entries.size)
        setting("添加和取消收藏").performScrollTo().performClick().assertIsOff()
        setting("进入和退出收藏管理").assertIsOn()
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        haptics.performClick().assertIsOff()
        hapticsLink.assertDoesNotExist()

        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNodeWithContentDescription("设置").performClick()
        setting("偏好").performClick()
        hapticsLink.assertDoesNotExist()
        haptics.performClick().assertIsOn()
        hapticsLink.performClick()
        setting("添加和取消收藏").performScrollTo().assertIsOff()
        setting("进入和退出收藏管理").assertIsOn()
        assertEquals(setOf(AnimationEffect.SettingsTransition, AnimationEffect.VideoSpeedMenu), store.settings().disabledAnimations)
        assertEquals(setOf(HapticEvent.Favorite), store.settings().disabledHaptics)
    }

    @Test fun interactionPageCheckboxTogglesAllOptionsAndPersists() {
        setting("偏好").performClick()
        setting("动画速度").performClick()
        option("增强").performClick()

        val allOptions = rule.onNodeWithContentDescription("全部选项")
        val isSwitch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        val partiallyEnabled = SemanticsMatcher.expectValue(
            SemanticsProperties.ToggleableState, ToggleableState.Indeterminate,
        )
        val back = rule.onNodeWithContentDescription("返回偏好")

        for ((page, item, count) in listOf(
            Triple("动画设置", "播放倍速菜单", AnimationEffect.entries.size),
            Triple("触感设置", "添加和取消收藏", HapticEvent.entries.size),
        )) {
            setting(page).performClick()
            allOptions.assertIsOn().performTouchInput { click() }.assertIsOff()
            rule.onAllNodes(isSwitch and isOff()).assertCountEquals(count)

            setting(item).performScrollTo().performClick().assertIsOn()
            allOptions.assert(partiallyEnabled).performTouchInput { click() }.assertIsOn()
            rule.onAllNodes(isSwitch and isOn()).assertCountEquals(count)

            allOptions.performTouchInput { click() }.assertIsOff()
            allOptions.performTouchInput { click() }.assertIsOn()
            rule.onAllNodes(isSwitch and isOn()).assertCountEquals(count)
            setting(item).performClick().assertIsOff()
            allOptions.assert(partiallyEnabled).performTouchInput { click() }.assertIsOn()
            allOptions.performTouchInput { click() }.assertIsOff()
            back.performClick()
        }

        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNodeWithContentDescription("设置").performClick()
        setting("偏好").performClick()
        setting("动画设置").performClick()
        allOptions.assertIsOff()
        rule.onAllNodes(isSwitch and isOff()).assertCountEquals(AnimationEffect.entries.size)
        back.performClick()
        setting("触感设置").performClick()
        allOptions.assertIsOff()
        rule.onAllNodes(isSwitch and isOff()).assertCountEquals(HapticEvent.entries.size)
        assertEquals(AnimationSpeed.Enhanced, store.settings().animationSpeed)
        assertTrue(store.settings().hapticFeedback)
    }

    @Test fun switchingLanguagesKeepsSettingsOpenAndPersists() {
        setting("偏好").performClick()
        setting("语言").performScrollTo().assert(hasText("简体中文")).performClick()
        option("简体中文").assertIsSelected()
        option("繁體中文").assertIsNotSelected()
        option("日本語").assertIsNotSelected()
        option("English").performClick()
        awaitSetting("Language", "English")

        setting("Language").performClick()
        option("繁體中文").performClick()
        awaitSetting("語言", "繁體中文")

        setting("語言").performClick()
        option("日本語").performClick()
        awaitSetting("言語", "日本語")
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
        val mode = setting("切页模式").assert(hasText("连续"))
        val direction = setting("切页方向").assert(hasText("垂直"))
        val rightToLeft = setting("从右向左翻页")
        rule.onNodeWithText("阅读模式").assertDoesNotExist()
        val horizontalSeek = setting("左右滑动调整视频进度")
        val pageNumber = setting("右下角显示页码")
        val volume = setting("使用音量键翻页")
        val zoom = setting("双击以放缩")
        val resetProgress = setting("关闭漫画后重置阅读进度")
        rightToLeft.assertDoesNotExist()
        horizontalSeek.assertIsOn()
        val topRows = listOf(mode, direction, horizontalSeek).map { it.fetchSemanticsNode().boundsInRoot }
        topRows.zipWithNext().forEach { (upper, lower) -> assertTrue(upper.bottom <= lower.top) }
        horizontalSeek.performClick().assertIsOff()
        pageNumber.assertIsOn()
        volume.assertIsOff()
        zoom.assertIsOn()
        resetProgress.assertIsOff()
        mode.performClick()
        option("分页").performClick()
        horizontalSeek.assertIsOff()
        rightToLeft.assertDoesNotExist()
        direction.performClick()
        option("水平").performClick()
        mode.assert(hasText("分页"))
        horizontalSeek.assertDoesNotExist()
        rightToLeft.assertIsOff()
        val rows = listOf(mode, direction, rightToLeft, pageNumber, setting("阅读时屏幕常亮"), volume, zoom, resetProgress,
            setting("深色阅读背景")).map { it.fetchSemanticsNode().boundsInRoot }
        rows.zipWithNext().forEach { (upper, lower) -> assertTrue(upper.bottom <= lower.top) }
        rule.onNodeWithText("适用于右开本的水平阅读模式").assertDoesNotExist()
        rule.onNodeWithText("离开阅读器后恢复系统设置").assertDoesNotExist()
        rightToLeft.performClick().assertIsOn()
        pageNumber.performClick().assertIsOff()
        volume.performClick().assertIsOn()
        zoom.performClick().assertIsOff()
        resetProgress.performClick().assertIsOn()
        mode.performClick()
        option("连续").performClick()
        rightToLeft.assertIsOn()
        horizontalSeek.assertDoesNotExist()
        direction.performClick()
        option("垂直").performClick()
        rightToLeft.assertDoesNotExist()
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

    @Test fun verificationThemeIsShownOnlyWithStartupVerificationAndPersists() {
        setting("外观").performClick()
        val followTheme = setting("验证页跟随主题颜色")
        followTheme.assertDoesNotExist()
        fun verifyOnStartup(enabled: Boolean) {
            scenario.onActivity {
                val model = ViewModelProvider(it)[LibraryViewModel::class.java]
                model.settings(model.state.value.settings.copy(verifyOnStartup = enabled))
            }
        }
        verifyOnStartup(true)
        followTheme.performScrollTo().assertIsOn().performClick().assertIsOff()
        scenario.recreate()
        followTheme.performScrollTo().assertIsOff()
        verifyOnStartup(false)
        followTheme.assertDoesNotExist()
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
