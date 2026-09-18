package dev.zolive.zviewer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PreferenceStore(context: Context) {
    private val preferences = context.getSharedPreferences("zviewer", Context.MODE_PRIVATE)
    var treeUri: String?
        get() = preferences.getString("library_uri", null)
        set(value) { preferences.edit().putString("library_uri", value).apply() }

    fun settings() = ReaderSettings(
        theme = preferences.getString("theme", "system") ?: "system",
        accent = preferences.getLong("accent", 0xFF49684D),
        readerDark = preferences.getBoolean("reader_dark", true),
        vertical = preferences.getBoolean("vertical", true),
        rightToLeft = preferences.getBoolean("rtl", false),
        keepScreenOn = preferences.getBoolean("keep_screen", true),
        gridColumns = preferences.getInt("columns", 3).coerceIn(2, 5),
    )

    fun saveSettings(settings: ReaderSettings) {
        preferences.edit().putString("theme", settings.theme).putLong("accent", settings.accent)
            .putBoolean("reader_dark", settings.readerDark).putBoolean("vertical", settings.vertical)
            .putBoolean("rtl", settings.rightToLeft).putBoolean("keep_screen", settings.keepScreenOn)
            .putInt("columns", settings.gridColumns).apply()
    }

    fun progress(bookId: String): ReadingProgress = ReadingProgress(
        preferences.getInt("page_$bookId", 0), preferences.getInt("total_$bookId", 0),
        preferences.getLong("read_$bookId", 0),
    )

    fun saveProgress(bookId: String, page: Int, total: Int) {
        preferences.edit().putInt("page_$bookId", page).putInt("total_$bookId", total)
            .putLong("read_$bookId", System.currentTimeMillis()).apply()
    }

    fun favorites(): Set<String> = preferences.getStringSet("favorites", emptySet())!!.toSet()
    fun saveFavorites(ids: Set<String>) { preferences.edit().putStringSet("favorites", ids).apply() }

    fun readLibrary(): List<Book> = runCatching {
        val entries = JSONArray(preferences.getString("books", "[]"))
        (0 until entries.length()).map { index ->
            val book = entries.getJSONObject(index)
            Book(book.getString("id"), book.getString("uri"), book.getString("title"),
                book.getString("kind"), book.getLong("modified"), book.getLong("size"))
        }
    }.getOrDefault(emptyList())

    fun saveLibrary(books: List<Book>) {
        val entries = JSONArray()
        books.forEach { book ->
            entries.put(JSONObject().put("id", book.id).put("uri", book.uri).put("title", book.title)
                .put("kind", book.kind).put("modified", book.modified).put("size", book.size))
        }
        preferences.edit().putString("books", entries.toString()).apply()
    }
}
