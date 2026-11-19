package dev.zolive.zviewer

import android.content.Context
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

    @Test fun settingsExposeCurrentValuesAndMenuSelection() {
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
        columns.assert(hasText("3")).performClick()
        option("3").assertIsSelected()
        option("5").performClick()
        columns.assert(hasText("5"))
        assertEquals(5, store.settings().gridColumns)

        val reading = setting("阅读模式").performScrollTo()
        reading.assert(hasText("垂直连续")).performClick()
        option("垂直连续").assertIsSelected()
        option("水平翻页").performClick()
        reading.assert(hasText("水平翻页"))
        assertFalse(store.settings().vertical)
    }

    @Test fun switchingLanguagesKeepsSettingsOpenAndPersists() {
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
        awaitSetting("言語", "日本語")
        setting("言語").performClick()
        option("简体中文").performClick()
        awaitSetting("语言", "简体中文")
    }

    @Test fun licenseIntroductionUsesTheSelectedLanguage() {
        setting("语言").performScrollTo().performClick()
        option("日本語").performClick()
        awaitSetting("言語", "日本語")

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
