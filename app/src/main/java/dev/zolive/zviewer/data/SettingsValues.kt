package dev.zolive.zviewer.data

internal fun readSettings(value: (String, Any?) -> Any?): ReaderSettings {
    fun string(key: String, default: String? = null) = value(key, default) as String?
    fun boolean(key: String, default: Boolean) = value(key, default) as Boolean
    fun int(key: String, default: Int) = value(key, default) as Int
    fun long(key: String, default: Long) = value(key, default) as Long
    @Suppress("UNCHECKED_CAST")
    fun keys(key: String) = value(key, emptySet<String>()) as Set<String>
    val vertical = boolean("vertical", true)
    val startupPage = string("startup_page", LibraryTab.Library.key).let { key ->
        LibraryTab.entries.firstOrNull { it.key == key } ?: LibraryTab.Library
    }
    return ReaderSettings(
        theme = string("theme", "system") ?: "system",
        accent = long("accent", DefaultThemeAccent),
        dynamicColor = boolean("dynamic_color", false),
        verificationFollowsTheme = boolean("verification_follows_theme", true),
        readerDark = boolean("reader_dark", true),
        vertical = vertical,
        paged = boolean("paged", !vertical),
        pagedImageFit = string("paged_image_fit", PagedImageFit.Screen.key).let { key ->
            PagedImageFit.entries.firstOrNull { it.key == key } ?: PagedImageFit.Screen
        },
        continuousImageFitScreen = boolean("continuous_image_fit_screen", false),
        videoFillScreen = boolean("video_fill_screen", false),
        rightToLeft = boolean("rtl", false),
        horizontalVideoSeek = boolean("horizontal_video_seek", true),
        showPageNumber = boolean("show_page_number", true),
        keepScreenOn = boolean("keep_screen", true),
        volumeKeyPaging = boolean("volume_key_paging", false),
        doubleTapZoom = boolean("double_tap_zoom", true),
        resetProgressOnClose = boolean("reset_progress_on_close", false),
        gridColumns = int("columns", 3).coerceIn(2, 5),
        showFileType = boolean("show_file_type", false),
        showFavoriteIcon = boolean("show_favorite_icon", true),
        startupPage = startupPage,
        startupFilter = string("startup_filter", LibraryFilter.All.key).let { key ->
            (LibraryFilter.entries.firstOrNull { it.key == key } ?: LibraryFilter.All).forTab(startupPage)
        },
        resumeReadingOnStartup = boolean("resume_reading_on_startup", false),
        videoLoopSingle = boolean("video_loop_single", false),
        videoPreview = boolean("video_preview", false),
        loopMode = boolean("loop_mode", false),
        diagnosticLog = boolean("diagnostic_log", false),
        logDirectory = string("log_directory", null),
        checkUpdatesOnStartup = boolean("check_updates_on_startup", false),
        clearCovers = boolean("clear_covers", false),
        currentLibraryCacheOnly = boolean("current_library_cache_only", false),
        rebuildLibraryCovers = boolean("rebuild_library_covers", false),
        cacheLimitMiB = CacheLimits.normalize(int("cache_limit_mib", CacheLimits.DEFAULT_MIB)),
        clearCacheOnTaskRemoval = boolean("clear_cache_on_task_removal", false),
        clearProgressOnTaskRemoval = boolean("clear_progress_on_task_removal", false),
        language = AppLanguage.fromTag(string("language")),
        animationSpeed = if (boolean("fast_animations", false)) {
            AnimationSpeed.Enhanced
        } else {
            AnimationSpeed.Moderate
        },
        hapticFeedback = boolean("haptic_feedback", true),
        disabledAnimations = keys("disabled_animations").let { keys ->
            AnimationEffect.entries.filterTo(mutableSetOf()) { it.key in keys }
        },
        disabledHaptics = keys("disabled_haptics").let { keys ->
            HapticEvent.entries.filterTo(mutableSetOf()) { it.key in keys || it.legacyKey in keys }
        },
        disabledAnimationCategories = keys("disabled_animation_categories").let { keys ->
            AnimationCategory.entries.filterTo(mutableSetOf()) { it.key in keys }
        },
        disabledHapticCategories = keys("disabled_haptic_categories").let { keys ->
            HapticCategory.entries.filterTo(mutableSetOf()) { it.key in keys }
        },
        verifyOnStartup = boolean("verify_on_startup", false),
        hideRecentPreview = boolean("hide_recent_preview", false),
        verifyAfterBackground = boolean("verify_after_background", false),
        autoLockInterval = int("auto_lock_seconds", AutoLockInterval.FifteenSeconds.seconds).let { seconds ->
            AutoLockInterval.entries.firstOrNull { it.seconds == seconds } ?: AutoLockInterval.FifteenSeconds
        },
        hideRecentCard = boolean("hide_recent_card", false),
        hideGalleryImages = boolean("hide_gallery_images", false),
    )
}

internal fun settingsValues(settings: ReaderSettings): Map<String, Any?> = mapOf(
    "theme" to settings.theme,
    "accent" to settings.accent,
    "dynamic_color" to settings.dynamicColor,
    "verification_follows_theme" to settings.verificationFollowsTheme,
    "reader_dark" to settings.readerDark,
    "vertical" to settings.vertical,
    "paged" to settings.paged,
    "paged_image_fit" to settings.pagedImageFit.key,
    "continuous_image_fit_screen" to settings.continuousImageFitScreen,
    "video_fill_screen" to settings.videoFillScreen,
    "rtl" to settings.rightToLeft,
    "show_page_number" to settings.showPageNumber,
    "keep_screen" to settings.keepScreenOn,
    "horizontal_video_seek" to settings.horizontalVideoSeek,
    "volume_key_paging" to settings.volumeKeyPaging,
    "double_tap_zoom" to settings.doubleTapZoom,
    "reset_progress_on_close" to settings.resetProgressOnClose,
    "columns" to settings.gridColumns,
    "show_file_type" to settings.showFileType,
    "show_favorite_icon" to settings.showFavoriteIcon,
    "startup_page" to settings.startupPage.key,
    "startup_filter" to settings.startupFilter.forTab(settings.startupPage).key,
    "resume_reading_on_startup" to settings.resumeReadingOnStartup,
    "video_loop_single" to settings.videoLoopSingle,
    "video_preview" to settings.videoPreview,
    "loop_mode" to settings.loopMode,
    "diagnostic_log" to settings.diagnosticLog,
    "log_directory" to settings.logDirectory,
    "check_updates_on_startup" to settings.checkUpdatesOnStartup,
    "clear_covers" to settings.clearCovers,
    "current_library_cache_only" to settings.currentLibraryCacheOnly,
    "rebuild_library_covers" to settings.rebuildLibraryCovers,
    "clear_cache_on_task_removal" to settings.clearCacheOnTaskRemoval,
    "clear_progress_on_task_removal" to settings.clearProgressOnTaskRemoval,
    "language" to settings.language.tag,
    "fast_animations" to (settings.animationSpeed == AnimationSpeed.Enhanced),
    "haptic_feedback" to settings.hapticFeedback,
    "disabled_animations" to settings.disabledAnimations.mapTo(mutableSetOf()) { it.key },
    "disabled_haptics" to settings.disabledHaptics.mapTo(mutableSetOf()) { it.key },
    "disabled_animation_categories" to settings.disabledAnimationCategories.mapTo(mutableSetOf()) { it.key },
    "disabled_haptic_categories" to settings.disabledHapticCategories.mapTo(mutableSetOf()) { it.key },
    "verify_on_startup" to settings.verifyOnStartup,
    "hide_recent_preview" to settings.hideRecentPreview,
    "verify_after_background" to settings.verifyAfterBackground,
    "auto_lock_seconds" to settings.autoLockInterval.seconds,
    "hide_recent_card" to settings.hideRecentCard,
    "hide_gallery_images" to settings.hideGalleryImages,
    "cache_limit_mib" to CacheLimits.normalize(settings.cacheLimitMiB),
)
