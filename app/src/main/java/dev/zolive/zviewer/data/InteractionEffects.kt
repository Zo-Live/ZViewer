package dev.zolive.zviewer.data

import androidx.annotation.StringRes
import dev.zolive.zviewer.R

enum class AnimationCategory(internal val key: String, @StringRes internal val title: Int) {
    PageTransition("page_transition", R.string.motion_page_transition),
    ExpandAndPopup("expand_and_popup", R.string.motion_expand_and_popup),
    IconAndControlState("icon_and_control_state", R.string.motion_icon_and_control_state),
    ScrollAndRebound("scroll_and_rebound", R.string.motion_scroll_and_rebound),
    ContentZoom("content_zoom", R.string.motion_content_zoom),
    LoadingIndicator("loading_indicator", R.string.motion_loading_indicator),
}

enum class AnimationEffect(
    internal val key: String,
    internal val category: AnimationCategory,
    @StringRes internal val title: Int,
) {
    PageTransition("page_transition", AnimationCategory.PageTransition, R.string.motion_app_navigation),
    ReaderEntry("reader_entry", AnimationCategory.PageTransition, R.string.motion_reader_entry),
    LibraryPaging("library_paging", AnimationCategory.PageTransition, R.string.motion_library_paging),
    ReaderPaging("reader_paging", AnimationCategory.PageTransition, R.string.motion_reader_paging),
    SettingsTransition("settings_transition", AnimationCategory.ExpandAndPopup, R.string.motion_settings_transition),
    Dialog("dialog", AnimationCategory.ExpandAndPopup, R.string.motion_dialog),
    DropdownMenu("dropdown_menu", AnimationCategory.ExpandAndPopup, R.string.motion_dropdown_menu),
    LibrarySearch("library_search", AnimationCategory.ExpandAndPopup, R.string.motion_library_search),
    ReaderToolbar("reader_toolbar", AnimationCategory.ExpandAndPopup, R.string.motion_reader_toolbar),
    ReaderQuickSettings("reader_quick_settings", AnimationCategory.ExpandAndPopup, R.string.motion_reader_quick_settings),
    VideoSpeedMenu("video_speed_menu", AnimationCategory.ExpandAndPopup, R.string.motion_video_speed_menu),
    Icons("icons", AnimationCategory.IconAndControlState, R.string.motion_icons),
    Switch("switch", AnimationCategory.IconAndControlState, R.string.motion_switch),
    LibraryTabIndicator("library_tab_indicator", AnimationCategory.IconAndControlState, R.string.motion_library_tab_indicator),
    LibraryFilterRebound("library_filter_rebound", AnimationCategory.ScrollAndRebound, R.string.motion_library_filter_rebound),
    LibraryScrollToTop("library_scroll_to_top", AnimationCategory.ScrollAndRebound, R.string.motion_library_scroll_to_top),
    ReaderImageZoom("reader_image_zoom", AnimationCategory.ContentZoom, R.string.motion_reader_image_zoom),
    VideoZoom("video_zoom", AnimationCategory.ContentZoom, R.string.motion_video_zoom),
    CircularProgress("circular_progress", AnimationCategory.LoadingIndicator, R.string.motion_circular_progress),
}

enum class HapticCategory(internal val key: String, @StringRes internal val title: Int) {
    ConfirmAndEnable("confirm_and_enable", R.string.feedback_confirm_and_enable),
    CancelAndDisable("cancel_and_disable", R.string.feedback_cancel_and_disable),
    ActionCues("action_cues", R.string.feedback_action_cues),
    TicksAndBoundaries("ticks_and_boundaries", R.string.feedback_ticks_and_boundaries),
}

enum class HapticEvent(
    internal val key: String,
    internal val category: HapticCategory,
    @StringRes internal val title: Int,
    internal val legacyKey: String = key,
) {
    SwitchOn("switch_on", HapticCategory.ConfirmAndEnable, R.string.feedback_switch_on, "switch"),
    EnableHapticFeedback("enable_haptic_feedback", HapticCategory.ConfirmAndEnable, R.string.feedback_enable_haptics),
    AddFavorite("add_favorite", HapticCategory.ConfirmAndEnable, R.string.feedback_add_favorite, "favorite"),
    EnterFavoriteManagement("enter_favorite_management", HapticCategory.ConfirmAndEnable, R.string.feedback_enter_favorite_management, "favorite_management"),
    VideoPlay("video_play", HapticCategory.ConfirmAndEnable, R.string.feedback_video_play, "video_play_pause"),
    VideoSpeedLock("video_speed_lock_on", HapticCategory.ConfirmAndEnable, R.string.feedback_video_speed_lock, "video_speed_lock"),
    VideoSpeedHoldFast("video_speed_hold_fast", HapticCategory.ConfirmAndEnable, R.string.feedback_video_speed_hold_fast, "video_speed_lock"),
    ThemeColorPreset("theme_color_preset", HapticCategory.ConfirmAndEnable, R.string.feedback_theme_color_preset),
    ApplyThemeColor("apply_theme_color", HapticCategory.ConfirmAndEnable, R.string.feedback_apply_theme_color),
    ThemeColorWheelConfirm("theme_color_wheel_confirm", HapticCategory.ConfirmAndEnable, R.string.feedback_theme_color_wheel_confirm, "theme_color_wheel"),
    ThemeColorSlidersConfirm("theme_color_sliders_confirm", HapticCategory.ConfirmAndEnable, R.string.feedback_theme_color_sliders_confirm, "theme_color_sliders"),
    SwitchOff("switch_off", HapticCategory.CancelAndDisable, R.string.feedback_switch_off, "switch"),
    RemoveFavorite("remove_favorite", HapticCategory.CancelAndDisable, R.string.feedback_remove_favorite, "favorite"),
    ExitFavoriteManagement("exit_favorite_management", HapticCategory.CancelAndDisable, R.string.feedback_exit_favorite_management, "favorite_management"),
    VideoPause("video_pause", HapticCategory.CancelAndDisable, R.string.feedback_video_pause, "video_play_pause"),
    VideoSpeedUnlock("video_speed_unlock", HapticCategory.CancelAndDisable, R.string.feedback_video_speed_unlock, "video_speed_lock"),
    VideoSpeedHoldNormal("video_speed_hold_normal", HapticCategory.CancelAndDisable, R.string.feedback_video_speed_hold_normal, "video_speed_lock"),
    MenuSelection("menu_selection", HapticCategory.ActionCues, R.string.feedback_menu_selection),
    VideoSpeedSelection("video_speed_selection", HapticCategory.ActionCues, R.string.feedback_video_speed_selection),
    TextSelection("text_selection", HapticCategory.ActionCues, R.string.feedback_text_selection),
    LogTextSelection("log_text_selection", HapticCategory.ActionCues, R.string.feedback_log_text_selection),
    ClearCache("clear_cache", HapticCategory.ActionCues, R.string.clear_reading_cache),
    ClearReadingHistory("clear_reading_history", HapticCategory.ActionCues, R.string.clear_reading_history),
    ClearLogs("clear_logs", HapticCategory.ActionCues, R.string.clear_logs),
    CacheLimit("cache_limit", HapticCategory.TicksAndBoundaries, R.string.feedback_cache_limit),
    ReaderPageSlider("reader_page_slider", HapticCategory.TicksAndBoundaries, R.string.feedback_reader_page_slider),
    VideoProgress("video_progress", HapticCategory.TicksAndBoundaries, R.string.video_playback_progress),
    ThemeColorWheel("theme_color_wheel_drag", HapticCategory.TicksAndBoundaries, R.string.feedback_theme_color_wheel, "theme_color_wheel"),
    ThemeColorSliders("theme_color_sliders_drag", HapticCategory.TicksAndBoundaries, R.string.feedback_theme_color_sliders, "theme_color_sliders"),
}
