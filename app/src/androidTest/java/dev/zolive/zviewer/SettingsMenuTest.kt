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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
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
            "阅读" to "阅读模式",
            "书库与存储" to "清理阅读缓存",
            "诊断" to "查看日志",
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
        val reading = setting("阅读模式").performScrollTo()
        reading.assert(hasText("垂直连续")).performClick()
        option("垂直连续").assertIsSelected()
        option("水平翻页").performClick()
        reading.assert(hasText("水平翻页"))
        assertFalse(store.settings().vertical)
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
        val toggle = setting("动态取色").assertIsOff()
        val presets = listOf(
            0xFF49684D to "苔绿", 0xFF476A8A to "雾蓝", 0xFF79618C to "藤紫",
            0xFF9A5357 to "蔷薇", 0xFF8B683E to "琥珀",
        ).associate { (color, name) -> color to rule.onNodeWithContentDescription("主题颜色：$name") }
        presets.getValue(0xFF49684D).assertIsSelected()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            toggle.assertIsNotEnabled()
            presets.getValue(0xFF476A8A).performClick().assertIsSelected()
            return
        }

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
        val toggle = setting("动态取色").assertIsOff()
        val custom = rule.onNodeWithContentDescription("自定义主题颜色")
        val mist = rule.onNodeWithContentDescription("主题颜色：雾蓝")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            toggle.assertIsNotEnabled()
            custom.assertIsDisplayed()
            return
        }

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
