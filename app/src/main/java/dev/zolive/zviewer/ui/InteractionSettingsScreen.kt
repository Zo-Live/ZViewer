package dev.zolive.zviewer.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.AnimationEffect
import dev.zolive.zviewer.data.HapticEvent
import dev.zolive.zviewer.data.ReaderSettings

internal enum class InteractionSettingsPage(@StringRes val title: Int, val tag: String) {
    Animation(R.string.animation_settings, "animation-settings-screen"),
    Haptics(R.string.haptic_settings, "haptic-settings-screen"),
}

private data class InteractionGroup<T>(@StringRes val title: Int, val items: Set<T>)

private data class HapticSection(@StringRes val title: Int, val options: List<Pair<HapticEvent, Int>>)

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

private val hapticSections = listOf(
    HapticSection(R.string.interaction_general, listOf(
        HapticEvent.Switch to R.string.feedback_switch,
        HapticEvent.MenuSelection to R.string.feedback_menu_selection,
        HapticEvent.EnableHapticFeedback to R.string.feedback_enable_haptics,
        HapticEvent.TextSelection to R.string.feedback_text_selection,
    )),
    HapticSection(R.string.appearance, listOf(
        HapticEvent.ThemeColorPreset to R.string.feedback_theme_color_preset,
        HapticEvent.ThemeColorWheel to R.string.feedback_theme_color_wheel,
        HapticEvent.ThemeColorSliders to R.string.feedback_theme_color_sliders,
        HapticEvent.ApplyThemeColor to R.string.feedback_apply_theme_color,
    )),
    HapticSection(R.string.library, listOf(
        HapticEvent.Favorite to R.string.feedback_favorite,
        HapticEvent.FavoriteManagement to R.string.feedback_favorite_management,
    )),
    HapticSection(R.string.library_and_storage, listOf(
        HapticEvent.CacheLimit to R.string.feedback_cache_limit,
        HapticEvent.ClearCache to R.string.clear_reading_cache,
        HapticEvent.ClearReadingHistory to R.string.clear_reading_history,
    )),
    HapticSection(R.string.diagnostics, listOf(
        HapticEvent.ClearLogs to R.string.clear_logs,
        HapticEvent.LogTextSelection to R.string.feedback_log_text_selection,
    )),
    HapticSection(R.string.reading, listOf(
        HapticEvent.ReaderPageSlider to R.string.feedback_reader_page_slider,
    )),
    HapticSection(R.string.interaction_video, listOf(
        HapticEvent.VideoPlayPause to R.string.feedback_video_play_pause,
        HapticEvent.VideoProgress to R.string.video_playback_progress,
        HapticEvent.VideoSpeedSelection to R.string.feedback_video_speed_selection,
        HapticEvent.VideoSpeedLock to R.string.feedback_video_speed_lock,
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
            InteractionSettingsPageContent(
                page.title, onBack, emptyList(), settings.disabledHaptics, sections = hapticSections.map { section ->
                    section.title to section.options.map { (event, label) -> InteractionGroup(label, setOf(event)) }
                },
            ) {
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
    sections: List<Pair<Int, List<InteractionGroup<T>>>> = emptyList(),
    onChange: (Set<T>) -> Unit,
) {
    val allItems = remember(groups, sections) { (groups + sections.flatMap { it.second }).flatMap { it.items }.toSet() }
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
        @Composable
        fun GroupRow(group: InteractionGroup<T>) {
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
        groups.forEach { GroupRow(it) }
        sections.forEachIndexed { index, (sectionTitle, options) ->
            Text(
                stringResource(sectionTitle),
                Modifier.padding(top = if (index == 0) 8.dp else 24.dp, bottom = 4.dp).semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            options.forEach { GroupRow(it) }
        }
    }
}
