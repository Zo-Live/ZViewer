package dev.zolive.zviewer

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.zolive.zviewer.data.Book
import dev.zolive.zviewer.data.BookRepository
import dev.zolive.zviewer.data.BookSession
import dev.zolive.zviewer.data.PreferenceStore
import dev.zolive.zviewer.data.ReaderException
import dev.zolive.zviewer.data.ReaderSettings
import dev.zolive.zviewer.data.ReadingProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryState(
    val books: List<Book> = emptyList(),
    val treeUri: String? = null,
    val scanning: Boolean = false,
    val opening: String? = null,
    val session: BookSession? = null,
    val initialPage: Int = 0,
    val settings: ReaderSettings = ReaderSettings(),
    val favorites: Set<String> = emptySet(),
    val progress: Map<String, ReadingProgress> = emptyMap(),
    val error: String? = null,
    val cacheRevision: Int = 0,
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    val repository = BookRepository(application)
    private val store = PreferenceStore(application)
    private val mutable = MutableStateFlow(LibraryState(books = store.readLibrary(), treeUri = store.treeUri,
        settings = store.settings(), favorites = store.favorites()))
    val state = mutable.asStateFlow()
    private var scanJob: Job? = null
    private var openJob: Job? = null

    init {
        refreshProgress()
        if (store.treeUri != null) refresh()
        viewModelScope.launch { repository.trimCache() }
    }

    fun selectLibrary(uri: Uri) {
        try {
            val resolver = getApplication<Application>().contentResolver
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val oldUri = store.treeUri
            scanJob?.cancel()
            store.treeUri = uri.toString()
            store.saveLibrary(emptyList())
            mutable.update { it.copy(treeUri = uri.toString(), books = emptyList(), error = null) }
            if (oldUri != null && oldUri != uri.toString()) {
                runCatching { resolver.releasePersistableUriPermission(Uri.parse(oldUri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            refresh()
        } catch (error: Exception) { showError(error) }
    }

    fun refresh() {
        val uri = store.treeUri ?: return
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            mutable.update { it.copy(scanning = true) }
            try {
                val books = repository.scan(Uri.parse(uri))
                store.saveLibrary(books)
                mutable.update { it.copy(books = books, error = null) }
                refreshProgress()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { showError(error) }
            finally { mutable.update { it.copy(scanning = false) } }
        }
    }

    fun open(book: Book) {
        openJob?.cancel()
        openJob = viewModelScope.launch {
            mutable.update { it.copy(opening = "正在打开《${book.title}》…", error = null) }
            try {
                val session = repository.open(book) { status -> mutable.update { it.copy(opening = status) } }
                val page = store.progress(book.id).page.coerceIn(session.pages.indices)
                mutable.update { it.copy(session = session, initialPage = page) }
                saveProgress(page)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { showError(error) }
            finally { mutable.update { it.copy(opening = null) } }
        }
    }

    fun cancelOpen() { openJob?.cancel(); mutable.update { it.copy(opening = null) } }

    fun closeBook() {
        mutable.update { it.copy(session = null) }
        refreshProgress()
        viewModelScope.launch { repository.trimCache() }
    }

    fun saveProgress(page: Int) {
        val session = mutable.value.session ?: return
        store.saveProgress(session.book.id, page.coerceIn(session.pages.indices), session.pages.size)
    }

    private fun refreshProgress() {
        mutable.update { state -> state.copy(progress = state.books.associate { it.id to store.progress(it.id) }) }
    }

    fun settings(settings: ReaderSettings) {
        store.saveSettings(settings)
        mutable.update { it.copy(settings = settings) }
    }

    fun toggleFavorite(book: Book) {
        val favorites = mutable.value.favorites.toMutableSet()
        if (!favorites.add(book.id)) favorites.remove(book.id)
        store.saveFavorites(favorites)
        mutable.update { it.copy(favorites = favorites) }
    }

    fun clearCache() {
        if (mutable.value.session != null || mutable.value.opening != null) return
        viewModelScope.launch {
            try {
                repository.clearCache()
                mutable.update { it.copy(cacheRevision = it.cacheRevision + 1) }
            } catch (error: Exception) { showError(error) }
        }
    }

    fun dismissError() { mutable.update { it.copy(error = null) } }

    private fun showError(error: Exception) {
        val message = when (error) {
            is ReaderException -> error.message!!
            is SecurityException -> "没有读取权限，或文件使用了密码保护。请重新选择书库；密码保护文件请先解密。"
            is me.zhanghai.android.libarchive.ArchiveException -> "压缩包无法读取，可能已损坏、使用了密码或属于分卷文件。请先解压到图片文件夹后再阅读。"
            else -> "文件读取失败，请检查文件是否完整、存储空间是否充足，然后重试。"
        }
        android.util.Log.w("ZViewer", message, error)
        mutable.update { it.copy(error = message) }
    }
}
