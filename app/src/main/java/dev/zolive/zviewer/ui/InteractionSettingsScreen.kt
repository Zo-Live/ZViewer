package dev.zolive.zviewer.ui

import androidx.annotation.StringRes
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.AnimationEffect
import dev.zolive.zviewer.data.HapticEvent
import dev.zolive.zviewer.data.ReaderSettings

internal enum class InteractionSettingsPage(@StringRes val title: Int, val tag: String) {
    Animation(R.string.animation_settings, "animation-settings-screen"),
    Haptics(R.string.haptic_settings, "haptic-settings-screen"),
}

private data class InteractionGroup<T>(@StringRes val title: Int, val items: Set<T>)

private fun <T> Set<T>.enabledState(disabledItems: Set<T>): ToggleableState = when {
    none { it in disabledItems } -> ToggleableState.On
    disabledItems.containsAll(this) -> ToggleableState.Off
    else -> ToggleableState.Indeterminate
}

private val animationGroups = listOf(
    InteractionGroup(R.string.motion_page_transition, setOf(
        AnimationEffect.PageTransition,
        AnimationEffect.ReaderEntry,
        AnimationEffect.LibraryPaging,
        AnimationEffect.ReaderPaging,
    )),
    InteractionGroup(R.string.motion_expand_and_popup, setOf(
        AnimationEffect.SettingsTransition,
        AnimationEffect.Dialog,
        AnimationEffect.DropdownMenu,
        AnimationEffect.LibrarySearch,
        AnimationEffect.ReaderToolbar,
        AnimationEffect.ReaderQuickSettings,
        AnimationEffect.VideoSpeedMenu,
    )),
    InteractionGroup(R.string.motion_icon_and_control_state, setOf(
        AnimationEffect.Icons,
        AnimationEffect.Switch,
        AnimationEffect.LibraryTabIndicator,
    )),
    InteractionGroup(R.string.motion_scroll_and_rebound, setOf(
        AnimationEffect.LibraryFilterRebound,
        AnimationEffect.LibraryScrollToTop,
    )),
    InteractionGroup(R.string.motion_content_zoom, setOf(
        AnimationEffect.ReaderImageZoom,
        AnimationEffect.VideoZoom,
    )),
    InteractionGroup(R.string.motion_loading_indicator, setOf(
        AnimationEffect.CircularProgress,
    )),
)

private val hapticGroups = listOf(
    InteractionGroup(R.string.feedback_confirm_and_enable, setOf(
        HapticEvent.SwitchOn,
        HapticEvent.EnableHapticFeedback,
        HapticEvent.AddFavorite,
        HapticEvent.EnterFavoriteManagement,
        HapticEvent.VideoPlay,
        HapticEvent.VideoSpeedLock,
        HapticEvent.VideoSpeedHoldFast,
        HapticEvent.ThemeColorPreset,
        HapticEvent.ApplyThemeColor,
        HapticEvent.ThemeColorWheelConfirm,
        HapticEvent.ThemeColorSlidersConfirm,
    )),
    InteractionGroup(R.string.feedback_cancel_and_disable, setOf(
        HapticEvent.SwitchOff,
        HapticEvent.RemoveFavorite,
        HapticEvent.ExitFavoriteManagement,
        HapticEvent.VideoPause,
        HapticEvent.VideoSpeedUnlock,
        HapticEvent.VideoSpeedHoldNormal,
    )),
    InteractionGroup(R.string.feedback_action_cues, setOf(
        HapticEvent.MenuSelection,
        HapticEvent.VideoSpeedSelection,
        HapticEvent.TextSelection,
        HapticEvent.LogTextSelection,
        HapticEvent.ClearCache,
        HapticEvent.ClearReadingHistory,
        HapticEvent.ClearLogs,
    )),
    InteractionGroup(R.string.feedback_ticks_and_boundaries, setOf(
        HapticEvent.CacheLimit,
        HapticEvent.ReaderPageSlider,
        HapticEvent.VideoProgress,
        HapticEvent.ThemeColorWheel,
        HapticEvent.ThemeColorSliders,
    )),
)

@Composable
internal fun InteractionSettingsScreen(
    page: InteractionSettingsPage,
    settings: ReaderSettings,
    onBack: () -> Unit,
    onSettings: (ReaderSettings) -> Unit,
) {
    when (page) {
        InteractionSettingsPage.Animation ->
            InteractionSettingsPageContent(
                page.title, onBack, animationGroups, settings.disabledAnimations,
                preserveSwitchAnimationComposition = true,
            ) {
                onSettings(settings.copy(disabledAnimations = it))
            }
        InteractionSettingsPage.Haptics ->
            InteractionSettingsPageContent(page.title, onBack, hapticGroups, settings.disabledHaptics) {
                onSettings(settings.copy(disabledHaptics = it))
            }
    }
}

@Composable
private fun <T> InteractionSettingsPageContent(
    @StringRes title: Int,
    onBack: () -> Unit,
    groups: List<InteractionGroup<T>>,
    disabledItems: Set<T>,
    preserveSwitchAnimationComposition: Boolean = false,
    onChange: (Set<T>) -> Unit,
) {
    val allItems = remember(groups) { groups.flatMap { it.items }.toSet() }
    val state = allItems.enabledState(disabledItems)
    SettingsPage(title, R.string.back_to_preferences, onBack, action = {
        val label = stringResource(R.string.interaction_all_options)
        val description = stringResource(when (state) {
            ToggleableState.On -> R.string.interaction_all_enabled
            ToggleableState.Off -> R.string.interaction_all_disabled
            ToggleableState.Indeterminate -> R.string.interaction_some_enabled
        })
        TriStateCheckbox(
            state = state,
            onClick = { onChange(if (state == ToggleableState.On) disabledItems + allItems else disabledItems - allItems) },
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = description
            },
        )
    }) {
        groups.forEach { group ->
            val groupState = group.items.enabledState(disabledItems)
            val mixed = groupState == ToggleableState.Indeterminate
            PreferenceSwitch(
                title = stringResource(group.title),
                subtitle = if (mixed) stringResource(R.string.interaction_some_enabled) else null,
                checked = groupState == ToggleableState.On,
                preserveAnimationComposition = preserveSwitchAnimationComposition,
                indeterminate = mixed,
            ) { enabled ->
                onChange(if (enabled) disabledItems - group.items else disabledItems + group.items)
            }
        }
    }
}
