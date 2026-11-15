package dev.zolive.zviewer

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.zolive.zviewer.data.Book
import dev.zolive.zviewer.data.BookRepository
import dev.zolive.zviewer.data.BookSession
import dev.zolive.zviewer.data.DiagnosticLog
import dev.zolive.zviewer.data.PreferenceStore
import dev.zolive.zviewer.data.ReaderException
import dev.zolive.zviewer.data.ReaderSettings
import dev.zolive.zviewer.data.ReadingProgress
import dev.zolive.zviewer.data.AppVersion
import dev.zolive.zviewer.data.UpdateChecker
import dev.zolive.zviewer.data.UpdateDownloader
import dev.zolive.zviewer.data.UpdateInfo
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
    val checkingUpdate: Boolean = false,
    val downloadingUpdate: Boolean = false,
    val updatePrompt: UpdateInfo? = null,
    val updateNotice: String? = null,
)

class LibraryViewModel(
    application: Application,
    private val updateChecker: UpdateChecker = UpdateChecker(application),
    private val downloadUpdate: suspend (UpdateInfo) -> Long = UpdateDownloader(application)::enqueue,
) : AndroidViewModel(application) {
    // 保留单参数构造器，供 Android 默认 ViewModel 工厂创建。
    constructor(application: Application) : this(application, UpdateChecker(application), UpdateDownloader(application)::enqueue)
    val repository = BookRepository(application)
    private val store = PreferenceStore(application)
    private val mutable = MutableStateFlow(LibraryState(books = store.readLibrary(), treeUri = store.treeUri,
        settings = store.settings(), favorites = store.favorites()))
    val state = mutable.asStateFlow()
    private var scanJob: Job? = null
    private var openJob: Job? = null
    private var manualUpdateRequested = false

    init {
        refreshProgress()
        val settings = store.settings()
        DiagnosticLog.configure(application, settings.diagnosticLog, settings.logDirectory)
        DiagnosticLog.log("App", "应用启动，书库=${store.treeUri != null}")
        DiagnosticLog.snapshot(application, settings, "启动")
        if (store.treeUri != null) refresh()
        viewModelScope.launch { repository.trimCache() }
        if (settings.checkUpdatesOnStartup) checkForUpdates(manual = false)
    }

    fun checkForUpdates(manual: Boolean = true) {
        if (mutable.value.downloadingUpdate) return
        manualUpdateRequested = manualUpdateRequested || manual
        if (mutable.value.checkingUpdate) return
        mutable.update { it.copy(checkingUpdate = true, updateNotice = null) }
        viewModelScope.launch {
            try {
                val info = updateChecker.latest(manual)
                val newer = AppVersion.parse(info.version)!! > AppVersion.parse(BuildConfig.VERSION_NAME)!!
                mutable.update { it.copy(updatePrompt = info.takeIf { newer },
                    updateNotice = if (!newer && manualUpdateRequested) "当前为最新版本" else null) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (manualUpdateRequested) mutable.update { it.copy(updateNotice = "检查更新失败，请检查网络或稍后重试") }
            } finally {
                manualUpdateRequested = false
                mutable.update { it.copy(checkingUpdate = false) }
            }
        }
    }

    fun dismissUpdate() { mutable.update { it.copy(updatePrompt = null) } }
    fun dismissUpdateNotice() { mutable.update { it.copy(updateNotice = null) } }

    fun installUpdate() {
        val offered = mutable.value.updatePrompt ?: return
        if (mutable.value.downloadingUpdate) return
        mutable.update { it.copy(updatePrompt = null, downloadingUpdate = true) }
        viewModelScope.launch {
            try {
                // 确认时后台重新验证发布信息，避免使用已被替换或撤回的安装包地址。
                val latest = updateChecker.latest(manual = true, refresh = true)
                if (AppVersion.parse(latest.version)!! < AppVersion.parse(offered.version)!! ||
                    AppVersion.parse(latest.version)!! <= AppVersion.parse(BuildConfig.VERSION_NAME)!!) {
                    throw IllegalStateException("发布版本已变更")
                }
                downloadUpdate(latest)
                mutable.update { it.copy(updateNotice = "已提交下载，请在系统下载通知中查看") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                DiagnosticLog.error("Update", "启动更新下载失败", error)
                mutable.update { it.copy(updateNotice = "无法下载更新，请稍后重试") }
            } finally { mutable.update { it.copy(downloadingUpdate = false) } }
        }
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
            DiagnosticLog.log("Permission", "已选择书库 authority=${uri.authority}")
        } catch (error: Exception) { showError(error) }
    }

    fun refresh() {
        val uri = store.treeUri ?: return
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            mutable.update { it.copy(scanning = true) }
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                DiagnosticLog.log("Library", "开始扫描书库")
                val books = repository.scan(Uri.parse(uri), store.readLibrary())
                store.saveLibrary(books)
                mutable.update { it.copy(books = books, error = null) }
                refreshProgress()
                DiagnosticLog.log("Library", "扫描完成 count=${books.size} kinds=${books.groupingBy { it.kind }.eachCount()} elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { showError(error) }
            finally { mutable.update { it.copy(scanning = false) } }
        }
    }

    fun open(book: Book) {
        openJob?.cancel()
        openJob = viewModelScope.launch {
            mutable.update { it.copy(opening = "正在打开《${book.title}》…", error = null) }
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                DiagnosticLog.log("Library", "打开 book=${book.id.take(8)} kind=${book.kind} bytes=${book.size}")
                val session = repository.open(book) { status -> mutable.update { it.copy(opening = status) } }
                val page = store.progress(book.id).page.coerceIn(session.pages.indices)
                mutable.update { it.copy(session = session, initialPage = page) }
                saveProgress(page)
                DiagnosticLog.log("Library", "打开完成 book=${book.id.take(8)} pages=${session.pages.size} page=${page + 1} elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { showError(error) }
            finally { mutable.update { it.copy(opening = null) } }
        }
    }

    fun cancelOpen() { DiagnosticLog.log("Library", "取消打开"); openJob?.cancel(); mutable.update { it.copy(opening = null) } }

    fun closeBook() {
        val session = mutable.value.session
        DiagnosticLog.log("Reader", "关闭 book=${session?.book?.id?.take(8)}")
        mutable.update { it.copy(session = null) }
        refreshProgress()
        viewModelScope.launch { if (session != null) repository.close(session) else repository.trimCache() }
    }

    override fun onCleared() { repository.close(); super.onCleared() }

    fun saveProgress(page: Int) {
        val session = mutable.value.session ?: return
        store.saveProgress(session.book.id, page.coerceIn(session.pages.indices), session.pages.size)
    }

    fun videoPosition(page: Int): Long {
        val session = mutable.value.session ?: return 0L
        return store.videoPosition(session.book.id, page)
    }

    fun saveVideoProgress(bookId: String, page: Int, position: Long) {
        store.saveVideoPosition(bookId, page, position)
    }

    fun clearReadingProgress() {
        DiagnosticLog.log("Progress", "清除全部阅读记录")
        store.clearProgress()
        refreshProgress()
    }

    private fun refreshProgress() {
        mutable.update { state -> state.copy(progress = state.books.associate { it.id to store.progress(it.id) }) }
    }

    fun settings(settings: ReaderSettings) {
        val previousLimit = mutable.value.settings.cacheLimitMiB
        store.saveSettings(settings)
        mutable.update { it.copy(settings = settings) }
        DiagnosticLog.configure(getApplication(), settings.diagnosticLog, settings.logDirectory)
        DiagnosticLog.snapshot(getApplication(), settings, "设置变更")
        if (previousLimit != settings.cacheLimitMiB) viewModelScope.launch {
            repository.trimCache()
            mutable.update { it.copy(cacheRevision = it.cacheRevision + 1) }
        }
    }

    fun setLogDirectory(uri: Uri?) {
        val application = getApplication<Application>()
        if (uri == null) {
            DiagnosticLog.log("Log", "恢复默认日志目录")
            settings(mutable.value.settings.copy(logDirectory = null))
            return
        }
        try {
            application.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            DiagnosticLog.log("Permission", "选择日志目录 authority=${uri.authority}")
            settings(mutable.value.settings.copy(diagnosticLog = true, logDirectory = uri.toString()))
        } catch (error: Exception) {
            val message = "选择的目录不允许写入，请换一个目录再试。"
            DiagnosticLog.error("Log", message, error)
            mutable.update { it.copy(error = message) }
        }
    }

    fun toggleFavorite(book: Book) {
        val favorites = mutable.value.favorites.toMutableSet()
        if (!favorites.add(book.id)) favorites.remove(book.id)
        store.saveFavorites(favorites)
        mutable.update { it.copy(favorites = favorites) }
    }

    fun clearCache() {
        if (mutable.value.session != null || mutable.value.opening != null) return
        val includeCovers = mutable.value.settings.clearCovers
        viewModelScope.launch {
            try {
                repository.clearCache(includeCovers)
                DiagnosticLog.log("Cache", "手动清理完成 includeCovers=$includeCovers")
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
        DiagnosticLog.error("Library", message, error)
        mutable.update { it.copy(error = message) }
    }
}
