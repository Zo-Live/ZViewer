package dev.zolive.zviewer.data

enum class AnimationEffect(internal val key: String) {
    PageTransition("page_transition"),
    Dialog("dialog"),
    DropdownMenu("dropdown_menu"),
    Switch("switch"),
    CircularProgress("circular_progress"),
    LibrarySearch("library_search"),
    LibraryPaging("library_paging"),
    LibraryTabIndicator("library_tab_indicator"),
    LibraryFilterRebound("library_filter_rebound"),
    LibraryScanProgress("library_scan_progress"),
    LibraryScrollToTop("library_scroll_to_top"),
    ReaderPaging("reader_paging"),
    ReaderToolbar("reader_toolbar"),
    ReaderQuickSettings("reader_quick_settings"),
    ReaderImageZoom("reader_image_zoom"),
    VideoZoom("video_zoom"),
    VideoSpeedMenu("video_speed_menu"),
}
