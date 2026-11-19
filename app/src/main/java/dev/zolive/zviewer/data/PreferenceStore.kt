package dev.zolive.zviewer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PreferenceStore(context: Context) {
    private val preferences = context.getSharedPreferences("zviewer", Context.MODE_PRIVATE)
    var treeUri: String?
        get() = preferences.getString("library_uri", null)
        set(value) { preferences.edit().putString("library_uri", value).apply() }

    val language: AppLanguage
        get() = AppLanguage.fromTag(preferences.getString("language", null))

    fun settings() = ReaderSettings(
        theme = preferences.getString("theme", "system") ?: "system",
        accent = preferences.getLong("accent", 0xFF49684D),
        readerDark = preferences.getBoolean("reader_dark", true),
        vertical = preferences.getBoolean("vertical", true),
        rightToLeft = preferences.getBoolean("rtl", false),
        keepScreenOn = preferences.getBoolean("keep_screen", true),
        gridColumns = preferences.getInt("columns", 3).coerceIn(2, 5),
        showFileType = preferences.getBoolean("show_file_type", false),
        showFavoriteIcon = preferences.getBoolean("show_favorite_icon", true),
        videoLoopSingle = preferences.getBoolean("video_loop_single", false),
        videoPreview = preferences.getBoolean("video_preview", false),
        loopMode = preferences.getBoolean("loop_mode", false),
        diagnosticLog = preferences.getBoolean("diagnostic_log", false),
        logDirectory = preferences.getString("log_directory", null),
        checkUpdatesOnStartup = preferences.getBoolean("check_updates_on_startup", false),
        clearCovers = preferences.getBoolean("clear_covers", false),
        cacheLimitMiB = CacheLimits.normalize(preferences.getInt("cache_limit_mib", CacheLimits.DEFAULT_MIB)),
        clearCacheOnTaskRemoval = preferences.getBoolean("clear_cache_on_task_removal", false),
        clearProgressOnTaskRemoval = preferences.getBoolean("clear_progress_on_task_removal", false),
        language = language,
    )

    fun saveSettings(settings: ReaderSettings) {
        preferences.edit().putString("theme", settings.theme).putLong("accent", settings.accent)
            .putBoolean("reader_dark", settings.readerDark).putBoolean("vertical", settings.vertical)
            .putBoolean("rtl", settings.rightToLeft).putBoolean("keep_screen", settings.keepScreenOn)
            .putInt("columns", settings.gridColumns)
            .putBoolean("show_file_type", settings.showFileType)
            .putBoolean("show_favorite_icon", settings.showFavoriteIcon)
            .putBoolean("video_loop_single", settings.videoLoopSingle)
            .putBoolean("video_preview", settings.videoPreview)
            .putBoolean("loop_mode", settings.loopMode)
            .putBoolean("diagnostic_log", settings.diagnosticLog)
            .putString("log_directory", settings.logDirectory)
            .putBoolean("check_updates_on_startup", settings.checkUpdatesOnStartup)
            .putBoolean("clear_covers", settings.clearCovers)
            .putBoolean("clear_cache_on_task_removal", settings.clearCacheOnTaskRemoval)
            .putBoolean("clear_progress_on_task_removal", settings.clearProgressOnTaskRemoval)
            .putString("language", settings.language.tag)
            .putInt("cache_limit_mib", CacheLimits.normalize(settings.cacheLimitMiB)).apply()
    }

    fun progress(bookId: String): ReadingProgress = ReadingProgress(
        preferences.getInt("page_$bookId", 0), preferences.getInt("total_$bookId", 0),
        preferences.getLong("read_$bookId", 0),
    )

    fun saveProgress(bookId: String, page: Int, total: Int) {
        preferences.edit().putInt("page_$bookId", page).putInt("total_$bookId", total)
            .putLong("read_$bookId", System.currentTimeMillis()).putBoolean("page_order_$bookId", true).apply()
    }

    internal fun migratePageOrder(bookId: String, pageCount: Int, newIndices: () -> List<Int>): Boolean {
        if (preferences.getBoolean("page_order_$bookId", false) || !preferences.contains("page_$bookId")) return false
        val editor = preferences.edit().putBoolean("page_order_$bookId", true)
        val previous = progress(bookId)
        if (previous.total == pageCount) {
            val indices = newIndices()
            indices.getOrNull(previous.page)?.let { editor.putInt("page_$bookId", it) }
            val positions = indices.mapIndexedNotNull { oldIndex, newIndex ->
                val key = "video_${bookId}_$oldIndex"
                if (!preferences.contains(key)) return@mapIndexedNotNull null
                editor.remove(key)
                newIndex to preferences.getLong(key, 0L)
            }
            positions.forEach { (index, position) -> editor.putLong("video_${bookId}_$index", position) }
        }
        editor.apply()
        return true
    }

    fun videoPosition(bookId: String, page: Int): Long = preferences.getLong("video_${bookId}_$page", 0L)

    fun saveVideoPosition(bookId: String, page: Int, position: Long) {
        preferences.edit().putLong("video_${bookId}_$page", position.coerceAtLeast(0L)).apply()
    }

    fun clearProgress() {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith("page_") || it.startsWith("total_") ||
            it.startsWith("read_") || (it.startsWith("video_") && it.substringAfterLast('_').toIntOrNull() != null)
        }.forEach(editor::remove)
        editor.apply()
    }

    fun favorites(): Set<String> = preferences.getStringSet("favorites", emptySet())!!.toSet()
    fun saveFavorites(ids: Set<String>) { preferences.edit().putStringSet("favorites", ids).apply() }

    fun readLibrary(): List<Book> = runCatching {
        val entries = JSONArray(preferences.getString("books", "[]"))
        (0 until entries.length()).map { index ->
            val book = entries.getJSONObject(index)
            Book(book.getString("id"), book.getString("uri"), book.getString("title"),
                book.getString("kind"), book.getLong("modified"), book.getLong("size"),
                book.optString("contentSignature"))
        }
    }.getOrDefault(emptyList())

    fun saveLibrary(books: List<Book>) {
        val entries = JSONArray()
        books.forEach { book ->
            entries.put(JSONObject().put("id", book.id).put("uri", book.uri).put("title", book.title)
                .put("kind", book.kind).put("modified", book.modified).put("size", book.size)
                .put("contentSignature", book.contentSignature))
        }
        preferences.edit().putString("books", entries.toString()).apply()
    }
}
