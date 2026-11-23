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
import dev.zolive.zviewer.data.ReaderSettings

internal enum class InteractionSettingsPage(@StringRes val title: Int, val tag: String) {
    Animation(R.string.animation_settings, "animation-settings-screen"),
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
