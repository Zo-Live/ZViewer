package dev.zolive.zviewer.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.AnimationCategory
import dev.zolive.zviewer.data.AnimationEffect
import dev.zolive.zviewer.data.HapticCategory
import dev.zolive.zviewer.data.HapticEvent
import dev.zolive.zviewer.data.ReaderSettings

internal sealed class InteractionSettingsPage(
    @StringRes val title: Int,
    val tag: String,
    val parent: InteractionSettingsPage? = null,
) {
    data object Animation : InteractionSettingsPage(R.string.animation_settings, "animation-settings-screen")
    data object Haptics : InteractionSettingsPage(R.string.haptic_settings, "haptic-settings-screen")
    data class Motion(val category: AnimationCategory) :
        InteractionSettingsPage(category.title, "animation-settings-${category.key}", Animation)
    data class Feedback(val category: HapticCategory) :
        InteractionSettingsPage(category.title, "haptic-settings-${category.key}", Haptics)

    companion object {
        private val motionPages by lazy { AnimationCategory.entries.map(::Motion) }
        private val feedbackPages by lazy { HapticCategory.entries.map(::Feedback) }
        private val pagesByTag by lazy {
            (listOf(Animation, Haptics) + motionPages + feedbackPages).associateBy { it.tag }
        }

        fun fromTag(tag: String): InteractionSettingsPage? = pagesByTag[tag]
    }

    fun categories(): List<InteractionSettingsPage> = when (this) {
        Animation -> motionPages
        Haptics -> feedbackPages
        is Motion, is Feedback -> emptyList()
    }
}

@Composable
internal fun InteractionSettingsScreen(
    page: InteractionSettingsPage,
    settings: ReaderSettings,
    onBack: () -> Unit,
    onOpen: (InteractionSettingsPage) -> Unit,
    onSettings: (ReaderSettings) -> Unit,
) {
    val backLabel = when (page) {
        InteractionSettingsPage.Animation, InteractionSettingsPage.Haptics -> R.string.back_to_preferences
        is InteractionSettingsPage.Motion -> R.string.back_to_animation_settings
        is InteractionSettingsPage.Feedback -> R.string.back_to_haptic_settings
    }
    SettingsPage(page.title, backLabel, onBack) {
        when (page) {
            InteractionSettingsPage.Animation, InteractionSettingsPage.Haptics -> {
                page.categories().forEach { category ->
                    SettingsPageLink(category.title) { onOpen(category) }
                }
            }
            is InteractionSettingsPage.Motion -> {
                val category = page.category
                val enabled = category !in settings.disabledAnimationCategories
                CategorySwitch(enabled, R.string.motion_category_enabled_hint) {
                    onSettings(settings.copy(disabledAnimationCategories = settings.disabledAnimationCategories.withEnabled(category, it)))
                }
                AnimationEffect.entries.filter { it.category == category }.forEach { effect ->
                    PreferenceSwitch(
                        title = stringResource(effect.title),
                        checked = effect !in settings.disabledAnimations,
                        enabled = enabled,
                        preserveAnimationComposition = effect == AnimationEffect.Switch,
                    ) {
                        onSettings(settings.copy(disabledAnimations = settings.disabledAnimations.withEnabled(effect, it)))
                    }
                }
            }
            is InteractionSettingsPage.Feedback -> {
                val category = page.category
                val enabled = category !in settings.disabledHapticCategories
                CategorySwitch(enabled, R.string.feedback_category_enabled_hint) {
                    onSettings(settings.copy(disabledHapticCategories = settings.disabledHapticCategories.withEnabled(category, it)))
                }
                HapticEvent.entries.filter { it.category == category }.forEach { event ->
                    PreferenceSwitch(
                        title = stringResource(event.title),
                        checked = event !in settings.disabledHaptics,
                        enabled = enabled,
                    ) {
                        onSettings(settings.copy(disabledHaptics = settings.disabledHaptics.withEnabled(event, it)))
                    }
                }
            }
        }
    }
}

@Composable
private fun CategorySwitch(checked: Boolean, @StringRes hint: Int, onChange: (Boolean) -> Unit) {
    PreferenceSwitch(
        title = stringResource(R.string.interaction_enabled),
        subtitle = stringResource(hint),
        checked = checked,
        preserveAnimationComposition = true,
        onChange = onChange,
    )
}

private fun <T> Set<T>.withEnabled(item: T, enabled: Boolean): Set<T> = if (enabled) this - item else this + item
