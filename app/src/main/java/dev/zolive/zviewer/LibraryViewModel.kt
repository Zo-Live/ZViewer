package dev.zolive.zviewer

import android.app.Application
import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.zolive.zviewer.data.Book
import dev.zolive.zviewer.data.BookRepository
import dev.zolive.zviewer.data.BookSession
import dev.zolive.zviewer.data.DiagnosticLog
import dev.zolive.zviewer.data.GalleryVisibility
import dev.zolive.zviewer.data.NaturalOrder
import dev.zolive.zviewer.data.PreferenceStore
import dev.zolive.zviewer.data.ReaderException
import dev.zolive.zviewer.data.ReaderSettings
import dev.zolive.zviewer.data.ReadingProgress
import dev.zolive.zviewer.data.SettingsBackup
import dev.zolive.zviewer.data.SettingsDirectory
import dev.zolive.zviewer.data.SettingsImport
import dev.zolive.zviewer.data.UpdateChecker
import dev.zolive.zviewer.data.UpdateDownload
import dev.zolive.zviewer.data.UpdateDownloader
import dev.zolive.zviewer.data.UpdateInfo
import dev.zolive.zviewer.data.documentTree
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LibraryState(
    val books: List<Book> = emptyList(),
    val treeUri: String? = null,
    val scanning: Boolean = false,
    val opening: String? = null,
    val session: BookSession? = null,
    val initialPage: Int = 0,
    internal val waitingForVideoEntry: Boolean = false,
    val settings: ReaderSettings = ReaderSettings(),
    val favorites: Set<String> = emptySet(),
    val progress: Map<String, ReadingProgress> = emptyMap(),
    val error: String? = null,
    val cacheRevision: Int = 0,
    val coverRevision: Int = 0,
    val clearingCache: Boolean = false,
    val checkingUpdate: Boolean = false,
    val downloadingUpdate: Boolean = false,
    val updateDownload: UpdateDownload? = null,
    val updatePrompt: UpdateInfo? = null,
    @StringRes val updateNotice: Int? = null,
    internal val openingRequest: Any? = null,
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
    private val updateDownloader = UpdateDownloader(application)
    private val mutable = MutableStateFlow(LibraryState(treeUri = store.treeUri,
        settings = store.settings(), favorites = store.favorites()))
    val state = mutable.asStateFlow()
    private val mutableSettingsImport = MutableStateFlow<SettingsImport?>(null)
    internal val settingsImport = mutableSettingsImport.asStateFlow()
    private val mutableTransferringSettings = MutableStateFlow(false)
    internal val transferringSettings = mutableTransferringSettings.asStateFlow()
    private val scanMutex = Mutex()
    private val coverRebuildMutex = Mutex()
    private var scanJob: Job? = null
    private var indexLoaded = false
    private var scannedLibraryFolders: Set<Uri>? = null
    private val galleryRequests = Channel<Unit>(Channel.CONFLATED)
    private var openJob: Job? = null
    private var startupReadingJob: Job? = null
    private var manualUpdateRequested = false

    init {
        val settings = state.value.settings
        viewModelScope.launch(Dispatchers.IO) {
            var previous: ReaderSettings? = null
            state.map { it.settings }.distinctUntilChanged().collect { settings ->
                if (previous?.diagnosticLog != settings.diagnosticLog || previous?.logDirectory != settings.logDirectory) {
                    DiagnosticLog.configure(application, settings.diagnosticLog, settings.logDirectory)
                }
                val reason = if (previous == null) {
                    DiagnosticLog.log("App", "应用启动，书库=${store.treeUri != null}")
                    "启动"
                } else "设置变更"
                DiagnosticLog.snapshot(application, settings, reason)
                previous = settings
            }
        }
        if (store.treeUri != null) refresh()
        updateGalleryVisibility()
        viewModelScope.launch {
            val gallery = GalleryVisibility(application)
            for (request in galleryRequests) {
                val current = state.value
                val scannedFolders = scannedLibraryFolders
                val hiddenFolders = when {
                    scannedFolders == null && current.scanning -> null
                    !current.settings.hideGalleryImages -> emptySet()
                    else -> scannedFolders?.let {
                        current.books.filter { it.kind == "folder" || it.kind == "mixed-folder" }
                            .mapTo(mutableSetOf()) { Uri.parse(it.uri) }
                    }
                }
                val success = withContext(Dispatchers.IO) {
                    gallery.update(current.treeUri?.toUri(), scannedFolders, hiddenFolders)
                }
                releaseRetiredLibraryGrants()
                val latest = state.value
                if (!success && current.treeUri == latest.treeUri &&
                    current.settings.hideGalleryImages == latest.settings.hideGalleryImages) {
                    Toast.makeText(application, message(R.string.gallery_visibility_error), Toast.LENGTH_LONG).show()
                }
            }
        }
        viewModelScope.launch {
            state.map { current ->
                if (current.settings.rebuildLibraryCovers && !current.scanning && !current.clearingCache &&
                    current.books.isNotEmpty()) current.books to current.coverRevision else null
            }.distinctUntilChanged().collectLatest { request ->
                request?.let { (books, _) ->
                    val rebuilt = coverRebuildMutex.withLock { repository.rebuildMissingCovers(books) }
                    if (rebuilt) mutable.update { it.copy(cacheRevision = it.cacheRevision + 1) }
                }
            }
        }
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
                val download = updateDownloader.current()
                if (download is UpdateDownload.Pending || download is UpdateDownload.Ready) {
                    showUpdateDownload(download)
                    return@launch
                }
                val info = updateChecker.latest(manual, refresh = manual && download is UpdateDownload.Failed)
                val newer = info.isNewerThanInstalled
                mutable.update { it.copy(updatePrompt = info.takeIf { newer },
                    updateNotice = if (!newer && manualUpdateRequested) R.string.update_latest else null) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (manualUpdateRequested) mutable.update { it.copy(updateNotice = R.string.update_check_failed) }
            } finally {
                manualUpdateRequested = false
                mutable.update { it.copy(checkingUpdate = false) }
            }
        }
    }

    fun dismissUpdate() { mutable.update { it.copy(updatePrompt = null) } }
    fun dismissUpdateNotice() { mutable.update { it.copy(updateNotice = null) } }

    fun startUpdateDownload() {
        val offered = mutable.value.updatePrompt ?: return
        if (mutable.value.downloadingUpdate) return
        mutable.update { it.copy(updatePrompt = null, downloadingUpdate = true) }
        viewModelScope.launch {
            try {
                // 已选中的正式安装包可直接下载。缺少资源时才重新查询发布信息。
                val info = if (offered.downloadUrl != null) offered else updateChecker.latest(manual = true, refresh = true)
                downloadUpdate(info)
                mutable.update { it.copy(updateNotice = R.string.update_background) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                DiagnosticLog.error("Update", "启动更新下载失败", error)
                mutable.update { it.copy(updateNotice = R.string.update_cannot_download) }
            } finally { mutable.update { it.copy(downloadingUpdate = false) } }
        }
    }

    suspend fun monitorUpdateDownload() {
        state.map { it.downloadingUpdate }.distinctUntilChanged().collectLatest { submitting ->
            if (submitting) return@collectLatest
            do {
                val download = updateDownloader.current()
                showUpdateDownload(download)
                if (download is UpdateDownload.Pending) delay(1_000)
            } while (download is UpdateDownload.Pending)
        }
    }

    private fun showUpdateDownload(download: UpdateDownload?) {
        val failed = download is UpdateDownload.Failed && download != mutable.value.updateDownload
        if (failed) DiagnosticLog.log("Update", "下载失败：$download")
        mutable.update {
            it.copy(updateDownload = download,
                updatePrompt = if (download is UpdateDownload.Pending || download is UpdateDownload.Ready) null else it.updatePrompt,
                updateNotice = if (failed) R.string.update_download_failed else it.updateNotice)
        }
    }

    fun prepareUpdateInstallation(download: UpdateDownload.Ready): Boolean {
        val current = mutable.value
        if (current.updateDownload != download || current.session != null || current.opening != null ||
            current.error != null) return false
        updateDownloader.markInstallationOffered(download.id)
        mutable.update { it.copy(updateDownload = download.copy(launchAutomatically = false), updateNotice = null) }
        return true
    }

    fun showUpdateInstallerError(error: Exception) {
        DiagnosticLog.error("Update", "无法打开系统安装器", error)
        mutable.update { it.copy(updateNotice = R.string.update_installer_failed) }
    }

    fun selectLibrary(uri: Uri) {
        cancelStartupReading()
        try {
            val resolver = getApplication<Application>().contentResolver
            val current = state.value
            SettingsDirectory.Library.takePermission(resolver, uri, current.settings.hideGalleryImages)
            if (current.treeUri == uri.toString()) refresh() else applySettings(current.settings, uri.toString())
            DiagnosticLog.log("Permission", "已选择书库 authority=${uri.authority}")
        } catch (error: Exception) { showError(error) }
    }

    internal fun galleryNeedsWriteAccess(tree: Uri): Boolean =
        getApplication<Application>().contentResolver.persistedUriPermissions.none { it.uri == tree && it.isWritePermission }

    internal fun authorizeGalleryAccess(uri: Uri) {
        val application = getApplication<Application>()
        if (uri.toString() != state.value.treeUri) {
            Toast.makeText(application, message(R.string.gallery_select_current_library), Toast.LENGTH_LONG).show()
            return
        }
        try {
            application.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            settings(state.value.settings.copy(hideGalleryImages = true))
        } catch (error: SecurityException) {
            DiagnosticLog.error("Permission", "书库目录写入授权失败", error)
            Toast.makeText(application, message(R.string.gallery_visibility_error), Toast.LENGTH_LONG).show()
        }
    }

    fun refresh() {
        val current = mutable.value
        val uri = current.treeUri ?: return
        val loadIndex = !indexLoaded
        scanJob?.cancel()
        mutable.update { it.copy(scanning = true) }
        scanJob = viewModelScope.launch {
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                DiagnosticLog.log("Library", "开始扫描书库")
                scanMutex.withLock {
                    if (loadIndex && !indexLoaded) {
                        val cached = withContext(Dispatchers.IO) { NaturalOrder.sorted(store.readLibrary(), Book::title) }
                        indexLoaded = true
                        publishBooks(cached)
                    }
                    val previous = mutable.value.books
                    val (books, folders) = repository.scanLibrary(Uri.parse(uri))
                    val encoded = withContext(Dispatchers.IO) { store.encodeLibrary(books) }
                    val keepOnlyCurrent = mutable.value.settings.currentLibraryCacheOnly
                    store.saveEncodedLibrary(encoded)
                    publishBooks(books)
                    scannedLibraryFolders = folders
                    updateGalleryVisibility()
                    withContext(NonCancellable) {
                        repository.reconcileBooks(previous, books, keepOnlyCurrent)
                    }
                    if (keepOnlyCurrent && isActive) mutable.update { it.copy(cacheRevision = it.cacheRevision + 1) }
                }
                DiagnosticLog.log("Library", "扫描完成 count=${mutable.value.books.size} elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (isActive) showError(error) }
            finally {
                if (isActive) {
                    mutable.update { it.copy(scanning = false) }
                    if (scannedLibraryFolders == null) updateGalleryVisibility()
                }
            }
        }
    }

    private fun publishBooks(books: List<Book>) {
        val progress = books.associate { it.id to store.progress(it.id) }
        mutable.update { it.copy(books = books, progress = progress, error = null) }
    }

    internal fun resumeReadingOnStartup() {
        val initial = mutable.value
        if (!initial.settings.resumeReadingOnStartup || initial.treeUri == null) return
        startupReadingJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val current = state.first { !it.scanning }
            startupReadingJob = null
            if (current.error != null || current.session != null || current.openingRequest != null) return@launch
            val latest = current.books.maxByOrNull { store.progress(it.id).updated } ?: return@launch
            if (store.progress(latest.id).updated > 0L) open(latest)
        }
        startupReadingJob?.start()
    }

    internal fun cancelStartupReading() {
        startupReadingJob?.cancel()
        startupReadingJob = null
    }

    fun open(book: Book) {
        cancelStartupReading()
        val request = Any()
        mutable.update { it.copy(opening = message(R.string.opening_book, book.title), openingRequest = request, error = null) }
        openJob?.cancel()
        openJob = viewModelScope.launch {
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                DiagnosticLog.log("Library", "打开 book=${book.id.take(8)} kind=${book.kind} bytes=${book.size}")
                val session = repository.open(book) { status -> updateOpening(request, status) }
                try {
                    if (mutable.value.openingRequest !== request) return@launch
                    val page = store.resumePage(session)
                    val previous = mutable.value.session
                    store.saveProgress(session, page)
                    mutable.update {
                        it.copy(session = session, initialPage = page, waitingForVideoEntry = session.pages[page].isVideo,
                            opening = null, openingRequest = null)
                    }
                    previous?.let(::releaseSession)
                    DiagnosticLog.log("Library", "打开完成 book=${book.id.take(8)} pages=${session.pages.size} page=${page + 1} elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
                } finally {
                    if (mutable.value.session !== session) releaseSession(session)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (mutable.value.openingRequest === request) showError(error) }
            finally {
                mutable.update { if (it.openingRequest === request) it.copy(opening = null, openingRequest = null) else it }
            }
        }
    }

    private fun updateOpening(request: Any, status: String) {
        mutable.update { if (it.openingRequest === request) it.copy(opening = status) else it }
    }

    fun cancelOpen() {
        if (mutable.value.openingRequest == null) return
        DiagnosticLog.log("Library", "取消打开")
        mutable.update { it.copy(opening = null, openingRequest = null) }
        openJob?.cancel()
    }

    fun closeBook() {
        val current = mutable.value
        val session = current.session
        DiagnosticLog.log("Reader", "关闭 book=${session?.book?.id?.take(8)}")
        if (session != null && current.settings.resetProgressOnClose) {
            store.saveProgress(session.book.id, 0, session.pages.size)
        }
        mutable.update { it.copy(session = null, waitingForVideoEntry = false) }
        refreshProgress()
        if (session != null) releaseSession(session)
        else viewModelScope.launch { repository.trimCache() }
    }

    internal fun finishVideoEntry(session: BookSession) {
        mutable.update { if (it.session === session) it.copy(waitingForVideoEntry = false) else it }
    }

    private fun releaseSession(session: BookSession) {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { repository.close(session) }
    }

    override fun onCleared() {
        cancelOpen()
        mutable.value.session?.let(::releaseSession)
        repository.close()
        super.onCleared()
    }

    fun saveProgress(session: BookSession, page: Int) {
        if (mutable.value.session === session) store.saveProgress(session, page)
    }

    fun videoPosition(session: BookSession, page: Int): Long = store.videoPosition(session, page)

    fun saveVideoProgress(session: BookSession, page: Int, position: Long) {
        store.saveVideoPosition(session, page, position)
    }

    fun clearReadingProgress() {
        DiagnosticLog.log("Progress", "清除全部阅读记录")
        store.clearProgress()
        refreshProgress()
    }

    private fun refreshProgress() {
        mutable.update { state -> state.copy(progress = state.books.associate { it.id to store.progress(it.id) }) }
    }

    fun settings(settings: ReaderSettings) = applySettings(settings, null)

    private fun applySettings(settings: ReaderSettings, library: String?) {
        val current = mutable.value
        val previous = current.settings
        val newLibrary = library?.takeUnless { it == current.treeUri }
        if (newLibrary != null) {
            scanJob?.cancel()
            scannedLibraryFolders = null
            cancelOpen()
            indexLoaded = true
        }
        store.saveSettings(settings, newLibrary)
        mutable.update {
            if (newLibrary == null) it.copy(settings = settings) else it.copy(
                settings = settings, treeUri = newLibrary, books = emptyList(), progress = emptyMap(), error = null,
            )
        }
        if (newLibrary != null || previous.hideGalleryImages != settings.hideGalleryImages) updateGalleryVisibility()
        if (newLibrary != null || (!previous.currentLibraryCacheOnly && settings.currentLibraryCacheOnly)) refresh()
        if (previous.cacheLimitMiB != settings.cacheLimitMiB) viewModelScope.launch {
            repository.trimCache()
            mutable.update { it.copy(cacheRevision = it.cacheRevision + 1) }
        }
    }

    internal fun exportSettings(uri: Uri) {
        val current = state.value
        val backup = SettingsBackup(current.settings, current.treeUri)
        transferSettings(R.string.settings_export_error) { application ->
            withContext(Dispatchers.IO) { backup.write(application.contentResolver, uri) }
            Toast.makeText(application, message(R.string.settings_exported), Toast.LENGTH_SHORT).show()
        }
    }

    internal fun importSettings(uri: Uri) {
        transferSettings(R.string.settings_import_error) { application ->
            val current = state.value
            val pending = withContext(Dispatchers.IO) {
                val backup = SettingsBackup.read(application.contentResolver, uri)
                val changes = listOfNotNull(
                    SettingsDirectory.Library.change(application, current.treeUri, backup.library),
                    SettingsDirectory.Log.change(application, current.settings.logDirectory, backup.settings.logDirectory),
                )
                SettingsImport(
                    backup.copy(library = null, settings = backup.settings.copy(logDirectory = current.settings.logDirectory)),
                    changes,
                )
            }
            advanceSettingsImport(pending)
        }
    }

    private fun transferSettings(@StringRes errorMessage: Int, operation: suspend (Application) -> Unit) {
        if (mutableTransferringSettings.value || mutableSettingsImport.value != null) return
        mutableTransferringSettings.value = true
        val application = getApplication<Application>()
        viewModelScope.launch {
            try {
                operation(application)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val text = message(errorMessage)
                DiagnosticLog.error("Settings", text, error)
                Toast.makeText(application, text, Toast.LENGTH_LONG).show()
            } finally {
                mutableTransferringSettings.value = false
            }
        }
    }

    internal fun confirmSettingsDirectory(): Uri? {
        val pending = mutableSettingsImport.value ?: return null
        val change = pending.changes.first()
        if (pending.awaitingDirectory) return null
        if (change.needsGrant) {
            mutableSettingsImport.value = pending.copy(awaitingDirectory = true)
            return change.target
        }
        resolveSettingsDirectory(accept = true)
        return null
    }

    internal fun resolveSettingsDirectory(accept: Boolean, picked: Uri? = null) {
        val pending = mutableSettingsImport.value ?: return
        val change = pending.changes.first()
        val application = getApplication<Application>()
        val apply = accept && (!change.needsGrant || (picked != null && picked == change.target && runCatching {
            change.directory.takePermission(application.contentResolver, picked, pending.backup.settings.hideGalleryImages)
        }.isSuccess))
        if (accept && !apply && picked != null) {
            Toast.makeText(application, message(R.string.settings_directory_unchanged), Toast.LENGTH_LONG).show()
        }
        val backup = if (!apply) pending.backup else when (change.directory) {
            SettingsDirectory.Library -> pending.backup.copy(library = change.target.toString())
            SettingsDirectory.Log -> pending.backup.copy(
                settings = pending.backup.settings.copy(logDirectory = change.target?.toString()),
            )
        }
        advanceSettingsImport(SettingsImport(backup, pending.changes.drop(1)))
    }

    private fun advanceSettingsImport(pending: SettingsImport) {
        if (pending.changes.isNotEmpty()) {
            mutableSettingsImport.value = pending
            return
        }
        val application = getApplication<Application>()
        val imported = pending.backup.settings
        val verificationUnavailable = (imported.verifyOnStartup || imported.verifyAfterBackground) &&
            !application.getSystemService(KeyguardManager::class.java).isDeviceSecure
        val applied = if (verificationUnavailable) {
            val current = state.value.settings
            imported.copy(verifyOnStartup = current.verifyOnStartup, verifyAfterBackground = current.verifyAfterBackground)
        } else imported
        applySettings(applied, pending.backup.library)
        mutableSettingsImport.value = null
        val feedback = if (verificationUnavailable) R.string.settings_imported_without_verification else R.string.settings_imported
        Toast.makeText(application, message(feedback), Toast.LENGTH_SHORT).show()
    }

    private fun updateGalleryVisibility() {
        galleryRequests.trySend(Unit)
    }

    private fun releaseRetiredLibraryGrants() {
        val retired = store.retiredLibraryTrees()
        if (retired.isEmpty()) return
        val markerTrees = store.galleryMarkers().mapTo(mutableSetOf()) {
            it.substringBefore('\n').toUri().documentTree().toString()
        }
        val logDirectories = store.logDirectories()
        val resolver = getApplication<Application>().contentResolver
        val grants = resolver.persistedUriPermissions.associateBy { it.uri.toString() }
        val released = retired.filterTo(mutableSetOf()) { tree ->
            if (tree in markerTrees) return@filterTo false
            val grant = grants[tree]
            if (tree in logDirectories || grant == null) return@filterTo true
            val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            runCatching { resolver.releasePersistableUriPermission(grant.uri, flags) }
                .onFailure { DiagnosticLog.error("Permission", "无法释放旧书库授权", it) }
                .isSuccess
        }
        store.forgetRetiredLibraryTrees(released)
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
            val message = message(R.string.log_directory_error)
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
        val current = mutable.value
        if (current.session != null || current.opening != null || current.clearingCache) return
        val includeCovers = current.settings.clearCovers
        mutable.update { it.copy(clearingCache = true) }
        viewModelScope.launch {
            try {
                coverRebuildMutex.withLock { repository.clearCache(includeCovers) }
                DiagnosticLog.log("Cache", "手动清理完成 includeCovers=$includeCovers")
                mutable.update { it.copy(cacheRevision = it.cacheRevision + 1,
                    coverRevision = it.coverRevision + if (includeCovers) 1 else 0) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { showError(error) }
            finally { mutable.update { it.copy(clearingCache = false) } }
        }
    }

    fun dismissError() { mutable.update { it.copy(error = null) } }

    private fun message(@StringRes id: Int, vararg args: Any): String =
        store.language.localizedContext(getApplication<Application>()).getString(id, *args)

    private fun showError(error: Exception) {
        val message = when (error) {
            is ReaderException -> error.message!!
            is SecurityException -> message(R.string.permission_error)
            is me.zhanghai.android.libarchive.ArchiveException -> message(R.string.archive_error)
            else -> message(R.string.file_read_error)
        }
        android.util.Log.w("ZViewer", message, error)
        DiagnosticLog.error("Library", message, error)
        mutable.update { it.copy(error = message) }
    }
}
