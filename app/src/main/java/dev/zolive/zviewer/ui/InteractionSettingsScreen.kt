package dev.zolive.zviewer.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.AnimationEffect
import dev.zolive.zviewer.data.HapticEvent
import dev.zolive.zviewer.data.ReaderSettings

internal enum class InteractionSettingsPage(@StringRes val title: Int, val tag: String) {
    Animation(R.string.animation_settings, "animation-settings-screen"),
    Haptics(R.string.haptic_settings, "haptic-settings-screen"),
}

private data class InteractionGroup<T>(@StringRes val title: Int, val options: List<Pair<T, Int>>)

private val animationGroups = listOf(
    InteractionGroup(R.string.interaction_general, listOf(
        AnimationEffect.PageTransition to R.string.motion_page_transition,
        AnimationEffect.Dialog to R.string.motion_dialog,
        AnimationEffect.DropdownMenu to R.string.motion_dropdown_menu,
        AnimationEffect.Switch to R.string.motion_switch,
        AnimationEffect.CircularProgress to R.string.motion_circular_progress,
    )),
    InteractionGroup(R.string.library, listOf(
        AnimationEffect.LibrarySearch to R.string.motion_library_search,
        AnimationEffect.LibraryPaging to R.string.motion_library_paging,
        AnimationEffect.LibraryTabIndicator to R.string.motion_library_tab_indicator,
        AnimationEffect.LibraryFilterRebound to R.string.motion_library_filter_rebound,
        AnimationEffect.LibraryScanProgress to R.string.motion_library_scan_progress,
        AnimationEffect.LibraryScrollToTop to R.string.motion_library_scroll_to_top,
    )),
    InteractionGroup(R.string.reading, listOf(
        AnimationEffect.ReaderPaging to R.string.motion_reader_paging,
        AnimationEffect.ReaderToolbar to R.string.motion_reader_toolbar,
        AnimationEffect.ReaderQuickSettings to R.string.motion_reader_quick_settings,
        AnimationEffect.ReaderImageZoom to R.string.motion_reader_image_zoom,
    )),
    InteractionGroup(R.string.interaction_video, listOf(
        AnimationEffect.VideoZoom to R.string.motion_video_zoom,
        AnimationEffect.VideoSpeedMenu to R.string.motion_video_speed_menu,
    )),
)

private val hapticGroups = listOf(
    InteractionGroup(R.string.interaction_general, listOf(
        HapticEvent.Switch to R.string.feedback_switch,
        HapticEvent.MenuSelection to R.string.feedback_menu_selection,
        HapticEvent.EnableHapticFeedback to R.string.feedback_enable_haptics,
        HapticEvent.TextSelection to R.string.feedback_text_selection,
    )),
    InteractionGroup(R.string.appearance, listOf(
        HapticEvent.ThemeColorPreset to R.string.feedback_theme_color_preset,
        HapticEvent.ThemeColorWheel to R.string.feedback_theme_color_wheel,
        HapticEvent.ThemeColorSliders to R.string.feedback_theme_color_sliders,
        HapticEvent.ApplyThemeColor to R.string.feedback_apply_theme_color,
    )),
    InteractionGroup(R.string.library, listOf(
        HapticEvent.Favorite to R.string.feedback_favorite,
        HapticEvent.FavoriteManagement to R.string.feedback_favorite_management,
    )),
    InteractionGroup(R.string.library_and_storage, listOf(
        HapticEvent.CacheLimit to R.string.feedback_cache_limit,
        HapticEvent.ClearCache to R.string.clear_reading_cache,
        HapticEvent.ClearReadingHistory to R.string.clear_reading_history,
    )),
    InteractionGroup(R.string.diagnostics, listOf(
        HapticEvent.ClearLogs to R.string.clear_logs,
        HapticEvent.LogTextSelection to R.string.feedback_log_text_selection,
    )),
    InteractionGroup(R.string.reading, listOf(
        HapticEvent.ReaderPageSlider to R.string.feedback_reader_page_slider,
    )),
    InteractionGroup(R.string.interaction_video, listOf(
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
    SettingsPage(page.title, R.string.back_to_preferences, onBack) {
        when (page) {
            InteractionSettingsPage.Animation -> InteractionSwitches(animationGroups, settings.disabledAnimations) {
                onSettings(settings.copy(disabledAnimations = it))
            }
            InteractionSettingsPage.Haptics -> InteractionSwitches(hapticGroups, settings.disabledHaptics) {
                onSettings(settings.copy(disabledHaptics = it))
            }
        }
    }
}

@Composable
private fun <T> InteractionSwitches(
    groups: List<InteractionGroup<T>>,
    disabledItems: Set<T>,
    onChange: (Set<T>) -> Unit,
) {
    groups.forEachIndexed { index, group ->
        Text(
            stringResource(group.title),
            Modifier.padding(top = if (index == 0) 8.dp else 24.dp, bottom = 4.dp).semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        group.options.forEach { (item, title) ->
            PreferenceSwitch(stringResource(title), checked = item !in disabledItems) { enabled ->
                onChange(if (enabled) disabledItems - item else disabledItems + item)
            }
        }
    }
}
