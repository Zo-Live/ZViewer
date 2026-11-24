package dev.zolive.zviewer.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class PreferenceStore(context: Context) {
    private val preferences = context.getSharedPreferences("zviewer", Context.MODE_PRIVATE)
    var treeUri: String?
        get() = preferences.getString("library_uri", null)
        set(value) { preferences.edit().putString("library_uri", value).apply() }

    private fun SharedPreferences.Editor.selectLibrary(treeUri: String) {
        val retired = retiredLibraryTrees() + listOfNotNull(this@PreferenceStore.treeUri) - treeUri
        putString("library_uri", treeUri).putStringSet("retired_library_uris", retired).remove("books")
    }

    internal fun retiredLibraryTrees(): Set<String> =
        preferences.getStringSet("retired_library_uris", emptySet()).orEmpty().toSet()

    internal fun forgetRetiredLibraryTrees(trees: Set<String>) {
        if (trees.isNotEmpty()) {
            preferences.edit().putStringSet("retired_library_uris", retiredLibraryTrees() - trees).apply()
        }
    }

    internal fun logDirectories(): Set<String> =
        preferences.getStringSet("log_directories", emptySet()).orEmpty() +
            listOfNotNull(preferences.getString("log_directory", null))

    val language: AppLanguage
        get() = AppLanguage.fromTag(preferences.getString("language", null))

    fun settings() = readSettings { key, default ->
        when (default) {
            is Boolean -> preferences.getBoolean(key, default)
            is Int -> preferences.getInt(key, default)
            is Long -> preferences.getLong(key, default)
            is Set<*> -> preferences.getStringSet(key, emptySet()).orEmpty()
            else -> preferences.getString(key, default as String?)
        }
    }

    fun saveSettings(settings: ReaderSettings) = saveSettings(settings, null)

    internal fun saveSettings(settings: ReaderSettings, newLibrary: String?) {
        val editor = preferences.edit().putStringSet("log_directories", logDirectories() + listOfNotNull(settings.logDirectory))
        if (newLibrary != null) editor.selectLibrary(newLibrary)
        for ((key, value) in settingsValues(settings)) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> editor.putString(key, value as String?)
            }
        }
        editor.apply()
    }

    internal fun galleryMarkers(): Set<String> =
        preferences.getStringSet("gallery_markers", emptySet()).orEmpty().toSet()

    internal fun saveGalleryMarkers(markers: Set<String>): Boolean =
        preferences.edit().putStringSet("gallery_markers", markers.toSet()).commit()

    fun progress(bookId: String): ReadingProgress = ReadingProgress(
        preferences.getInt("page_$bookId", 0), preferences.getInt("total_$bookId", 0),
        preferences.getLong("read_$bookId", 0),
    )

    internal fun resumePage(session: BookSession): Int {
        val bookId = session.book.id
        val mediaKey = preferences.getString("page_media_$bookId", null)
        val index = mediaKey?.let { saved -> session.mediaKeys.indexOfFirst { it == saved }.takeIf { it >= 0 } }
            ?: progress(bookId).page
        return index.coerceIn(session.pages.indices)
    }

    internal fun saveProgress(session: BookSession, page: Int) {
        val index = page.coerceIn(session.pages.indices)
        saveProgress(session.book.id, index, session.pages.size,
            if (session.pdf == null) session.mediaKeys[index] else null)
    }

    fun saveProgress(bookId: String, page: Int, total: Int, mediaKey: String? = null) {
        preferences.edit().putInt("page_$bookId", page).putInt("total_$bookId", total)
            .putLong("read_$bookId", System.currentTimeMillis()).putBoolean("page_order_$bookId", true)
            .putString("page_media_$bookId", mediaKey).apply()
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

    internal fun migrateVideoPositions(session: BookSession) {
        val bookId = session.book.id
        val marker = "video_media_migrated_$bookId"
        if (preferences.getBoolean(marker, false) || !preferences.contains("page_$bookId")) return
        val prefix = "video_${bookId}_"
        val samePageCount = progress(bookId).total == session.pages.size
        val editor = preferences.edit().putBoolean(marker, true)
        for ((key, value) in preferences.all) {
            if (!key.startsWith(prefix)) continue
            val index = key.removePrefix(prefix).toIntOrNull() ?: continue
            if (samePageCount && session.pages.getOrNull(index)?.isVideo == true && value is Long) {
                val destination = videoKey(session, index)
                if (!preferences.contains(destination)) editor.putLong(destination, value)
            }
            editor.remove(key)
        }
        editor.apply()
    }

    private fun videoKey(session: BookSession, index: Int): String =
        "video_media_${session.book.id}_${session.mediaKeys[index]}"

    internal fun videoPosition(session: BookSession, index: Int): Long =
        preferences.getLong(videoKey(session, index), 0L)

    internal fun saveVideoPosition(session: BookSession, index: Int, position: Long) {
        preferences.edit().putLong(videoKey(session, index), position.coerceAtLeast(0L)).apply()
    }

    fun clearProgress() {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith("page_") || it.startsWith("total_") ||
            it.startsWith("read_") || it.startsWith("video_media_") ||
            (it.startsWith("video_") && it.substringAfterLast('_').toIntOrNull() != null)
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

    internal fun encodeLibrary(books: List<Book>): String {
        val entries = JSONArray()
        books.forEach { book ->
            entries.put(JSONObject().put("id", book.id).put("uri", book.uri).put("title", book.title)
                .put("kind", book.kind).put("modified", book.modified).put("size", book.size)
                .put("contentSignature", book.contentSignature))
        }
        return entries.toString()
    }

    internal fun saveEncodedLibrary(books: String) { preferences.edit().putString("books", books).apply() }

    fun saveLibrary(books: List<Book>) = saveEncodedLibrary(encodeLibrary(books))
}
