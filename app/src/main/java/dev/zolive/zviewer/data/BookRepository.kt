package dev.zolive.zviewer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.LruCache
import android.util.Size
import androidx.annotation.StringRes
import dev.zolive.zviewer.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import org.json.JSONArray
import java.io.File
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class BookRepository(private val context: Context) : Closeable {
    private companion object {
        val readingCaches = ConcurrentHashMap<Pair<File, File>, ReadingCache>()
    }

    private val resolver = context.contentResolver
    private val preferences = PreferenceStore(context)
    private val readingCache = readingCaches.computeIfAbsent(context.cacheDir to context.filesDir) { (cacheDir, filesDir) ->
        ReadingCache(cacheDir, filesDir)
    }
    private val cache = readingCache.books
    private val covers = readingCache.covers
    private val frames = File(covers, "frames").apply { mkdirs() }
    private val coverSlots = Semaphore(2)
    private data class ThumbnailKey(val bookKey: String, val size: Size, val revision: Int)
    private val thumbnailSlots = Semaphore(2)
    private val thumbnails = object : LruCache<ThumbnailKey, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: ThumbnailKey, value: Bitmap) = value.allocationByteCount
    }
    private val maintenance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val trimRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        maintenance.launch {
            for (request in trimRequests) {
                delay(500)
                trimRequests.tryReceive()
                trimCache()
            }
        }
    }

    private fun readerError(@StringRes id: Int): ReaderException =
        ReaderException(preferences.language.localizedContext(context).getString(id))

    private fun requestTrim() { trimRequests.trySend(Unit) }

    internal fun previewDirectory(session: BookSession, width: Int): File =
        File(File(cache, session.book.cacheKey), "previews-v1-$width")

    override fun close() { maintenance.cancel(); trimRequests.close() }

    suspend fun close(session: BookSession) = withContext(NonCancellable + Dispatchers.IO) {
        try { session.pdf?.close() } finally { session.cacheLease?.close() }
        trimCache()
    }

    suspend fun <Result> withBookCache(book: Book, block: suspend () -> Result): Result = withContext(Dispatchers.IO) {
        val lease = readingCache.acquire(book.cacheKey)
        try { block() } finally { lease.close(); requestTrim() }
    }

    private data class Document(val uri: Uri, val name: String, val directory: Boolean, val modified: Long, val size: Long)

    private fun children(uri: Uri): List<Document> {
        val documentId = DocumentsContract.getDocumentId(uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE)
        return resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    if (name.startsWith('.') || name == "__MACOSX") continue
                    add(Document(DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0)), name,
                        cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        cursor.getLong(3), cursor.getLong(4)))
                }
            }
        } ?: throw readerError(R.string.directory_read_error)
    }

    suspend fun scan(treeUri: Uri, previousBooks: List<Book> = emptyList()): List<Book> {
        val books = scanLibrary(treeUri).books
        reconcileBooks(previousBooks, books,
            preferences.treeUri == treeUri.toString() && preferences.settings().currentLibraryCacheOnly)
        return books
    }

    internal data class LibraryScan(val books: List<Book>, val folders: Set<Uri>)

    internal suspend fun scanLibrary(treeUri: Uri): LibraryScan = withContext(Dispatchers.IO) {
        val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val rootName = resolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "图片集"
        val books = mutableListOf<Book>()
        suspend fun visit(uri: Uri, name: String, depth: Int) {
            currentCoroutineContext().ensureActive()
        val folders = mutableSetOf<Uri>()
            if (depth > 32) throw readerError(R.string.directory_depth_error)
            val entries = children(uri)
            val images = entries.filter { !it.directory && isImage(it.name) }
            val videos = entries.filter { !it.directory && isVideo(it.name) }
            folders.add(uri)
            if (images.isNotEmpty() || videos.isNotEmpty()) {
                val kind = when {
                    images.isNotEmpty() && videos.isNotEmpty() -> "mixed-folder"
                    videos.isNotEmpty() -> "video-folder"
                    else -> "folder"
                }
                books += Book(uri.toString().stableId(), uri.toString(), name, kind,
                    (images + videos).maxOf { it.modified }, (images + videos).sumOf { it.size },
                    (images + videos).sortedBy { it.uri.toString() }.joinToString("\n") {
                        "${it.uri}\u0000${it.name}\u0000${it.modified}\u0000${it.size}"
                    }.stableId())
            }
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                if (entry.directory) visit(entry.uri, entry.name, depth + 1)
                else {
                    val extension = entry.name.extensionLower()
                    if (extension in archiveExtensions || extension == "pdf") {
                        books += Book(entry.uri.toString().stableId(), entry.uri.toString(),
                            entry.name.substringBeforeLast('.'), extension, entry.modified, entry.size)
                    }
                }
            }
        }
        visit(root, rootName, 0)
        currentCoroutineContext().ensureActive()
        LibraryScan(NaturalOrder.sorted(books, Book::title), folders)
    }

    internal suspend fun reconcileBooks(previousBooks: List<Book>, books: List<Book>, keepOnlyCurrent: Boolean) =
        withContext(Dispatchers.IO) {
            val currentById = books.associateBy { it.id }
            val upgradedKeys = previousBooks.mapNotNull { previous ->
                val current = currentById[previous.id] ?: return@mapNotNull null
                if (previous.contentSignature.isEmpty() && current.contentSignature.isNotEmpty() &&
                    previous.modified == current.modified && previous.size == current.size && previous.kind == current.kind)
                    previous.cacheKey to current.cacheKey else null
            }.toMap()
            val previousKeys = previousBooks.map { it.cacheKey }.toSet()
            val currentKeys = books.map { it.cacheKey }.toSet()
            currentCoroutineContext().ensureActive()
            readingCache.reconcile(previousKeys, currentKeys, upgradedKeys, keepOnlyCurrent)
        }

    suspend fun open(book: Book, status: (String) -> Unit = {}): BookSession {
        var lease: Closeable? = null
        var prepared: BookSession? = null
        try {
            return withContext(Dispatchers.IO) {
                lease = readingCache.acquire(book.cacheKey)
                prepare(book, status, lease).also { prepared = it; requestTrim() }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                try { prepared?.pdf?.close() } finally { lease?.close(); requestTrim() }
            }
            throw error
        }
    }

    private suspend fun prepare(book: Book, status: (String) -> Unit, lease: Closeable): BookSession =
        readingCache.fileLock(book.cacheKey).withLock {
            val resources = preferences.language.localizedContext(context).resources
            val folder = File(cache, book.cacheKey).apply { mkdirs(); setLastModified(System.currentTimeMillis()) }
            when (book.kind) {
                "folder", "video-folder", "mixed-folder" -> {
                    val entries = children(Uri.parse(book.uri)).filter { !it.directory && (isImage(it.name) || isVideo(it.name)) }
                    val pages = entries.map { entry ->
                        PageSource(entry.name, uri = entry.uri.toString(), mediaType = if (isVideo(entry.name)) "video" else "image")
                    }
                    if (pages.isEmpty()) throw readerError(R.string.no_folder_media)
                    orderedSession(book, pages, lease)
                }
                "pdf" -> {
                    status(resources.getString(R.string.preparing_pdf))
                    val local = File(folder, "document.pdf")
                    val renderer = if (local.isFile) pdfRenderer(local)
                        else openSeekablePdf(Uri.parse(book.uri)) ?: pdfRenderer(localPdf(book, folder))
                    try {
                        val pdf = PdfDocument(renderer)
                        if (pdf.pageCount == 0) throw readerError(R.string.empty_pdf)
                        val pages = List(pdf.pageCount) { index ->
                            PageSource(resources.getString(R.string.pdf_page_name, index + 1))
                        }
                        BookSession(book, pages, pdf, lease)
                    } catch (error: Throwable) {
                        renderer.close()
                        throw error
                    }
                }
                else -> {
                    val manifest = File(folder, "media.json")
                    if (manifest.exists()) {
                        val files = runCatching {
                            val array = JSONArray(manifest.readText())
                            (0 until array.length()).map { index ->
                                val item = array.getJSONObject(index)
                                Triple(item.getString("name"), item.getString("path"), item.optString("type", "image"))
                            }
                        }.getOrDefault(emptyList())
                        if (files.isNotEmpty() && files.all { File(folder, it.second).isFile }) {
                            return@withLock orderedSession(book, files.map { (name, path, type) ->
                                PageSource(name, File(folder, path).absolutePath, mediaType = type)
                            }, lease)
                        }
                    }
                    // 最早的 pages.json 只记录解压后的编号，重新解压才能取得原始文件名。
                    folder.deleteRecursively()
                    folder.mkdirs()
                    try {
                        val extracted = mutableListOf<Triple<String, File, String>>()
                        var totalBytes = 0L
                        var foundPdf = false
                        withArchive(book) { archive ->
                            var entry = Archive.readNextHeader(archive)
                            while (entry != 0L) {
                                currentCoroutineContext().ensureActive()
                                val name = entryName(entry)
                                val regular = ArchiveEntry.filetype(entry) == 0x8000
                                val image = regular && isImage(name)
                                val video = regular && isVideo(name)
                                foundPdf = foundPdf || (regular && isPdf(name))
                                if (image || video) {
                                    if (extracted.size >= 20_000) throw readerError(R.string.archive_page_limit)
                                    checkEntrySize(entry, if (video) 4L * 1024 * 1024 * 1024 else 256L * 1024 * 1024)
                                    val output = File(folder, "${extracted.size}.${name.extensionLower()}")
                                    totalBytes += extract(archive, output, 4L * 1024 * 1024 * 1024 - totalBytes,
                                        if (video) 4L * 1024 * 1024 * 1024 else 256L * 1024 * 1024)
                                    extracted += Triple(name, output, if (video) "video" else "image")
                                    status(resources.getString(
                                        if (video) R.string.preparing_archive_video else R.string.preparing_archive_page,
                                        extracted.size,
                                    ))
                                }
                                entry = Archive.readNextHeader(archive)
                            }
                        }
                        if (foundPdf) throw readerError(R.string.archive_contains_pdf)
                        if (extracted.isEmpty()) throw readerError(R.string.no_archive_media)
                        manifest.writeText(JSONArray(extracted.map { (name, file, type) ->
                            org.json.JSONObject().put("name", name).put("path", file.name).put("type", type)
                        }).toString())
                        orderedSession(book, extracted.map { (name, file, type) ->
                            PageSource(name, file.absolutePath, mediaType = type)
                        }, lease)
                    } catch (error: Exception) {
                        folder.deleteRecursively()
                        throw error
                    }
                }
            }
        }

    private fun orderedSession(book: Book, pages: List<PageSource>, lease: Closeable): BookSession {
        val sorted = NaturalOrder.sorted(pages, PageSource::name)
        val migrated = preferences.migratePageOrder(book.id, pages.size) {
            val newIndices = sorted.withIndex().associate { it.value to it.index }
            pages.sortedWith(compareBy(PreviousPageOrder, PageSource::name)).map { newIndices.getValue(it) }
        }
        if (migrated) for (index in pages.indices) File(frames, "${book.cacheKey}-$index.jpg").delete()
        return BookSession(book, sorted, cacheLease = lease).also { session ->
            if (sorted.any(PageSource::isVideo)) preferences.migrateVideoPositions(session)
        }
    }

    private fun coverFile(book: Book) =
        File(covers, if (book.kind == "pdf") "${book.cacheKey}.jpg" else "${book.cacheKey}-pinyin.jpg")

    fun cachedCoverBitmap(book: Book, size: Size, revision: Int): Bitmap? =
        thumbnails.get(ThumbnailKey(book.cacheKey, size, revision))

    suspend fun coverBitmap(book: Book, size: Size, revision: Int): Bitmap = withContext(Dispatchers.IO) {
        val key = ThumbnailKey(book.cacheKey, size, revision)
        thumbnails.get(key)?.let { return@withContext it }
        readingCache.acquire(book.cacheKey).use {
            readingCache.fileLock("cover-${book.cacheKey}").withLock {
                thumbnails.get(key)?.let { return@withLock it }
                val file = coverFile(book)
                val bitmap = if (isCompleteCover(file)) {
                    try {
                        decodeThumbnail(file, size)
                    } catch (_: IOException) {
                        file.delete()
                        decodeThumbnail(generateCover(book, file), size)
                    }
                } else decodeThumbnail(generateCover(book, file), size)
                thumbnails.put(key, bitmap)
                bitmap
            }
        }
    }

    private suspend fun decodeThumbnail(file: File, size: Size): Bitmap = thumbnailSlots.withPermit {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val source = info.size
            val cropScale = maxOf(size.width.toFloat() / source.width, size.height.toFloat() / source.height)
            val pixelBudget = 2f * size.width * size.height
            val scale = minOf(1f, cropScale, kotlin.math.sqrt(pixelBudget / (source.width.toFloat() * source.height)))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize((source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1))
        }
    }

    private fun isCompleteCover(file: File): Boolean {
        if (file.length() < 4) return false
        return try {
            RandomAccessFile(file, "r").use { input ->
                if (input.readUnsignedShort() != 0xffd8) return false
                input.seek(input.length() - 2)
                input.readUnsignedShort() == 0xffd9
            }
        } catch (_: IOException) { false }
    }

    suspend fun rebuildMissingCovers(books: List<Book>): Boolean = withContext(Dispatchers.IO) {
        var rebuilt = false
        for (book in books) {
            ensureActive()
            if (isCompleteCover(coverFile(book))) continue
            try {
                cover(book)
                rebuilt = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DiagnosticLog.error("Cover", "补全封面失败 book=${book.id.take(8)} kind=${book.kind}", error)
            }
        }
        rebuilt
    }

    suspend fun cover(book: Book): File = withBookCache(book) {
        val cover = coverFile(book)
        readingCache.fileLock("cover-${book.cacheKey}").withLock {
            if (isCompleteCover(cover)) cover else generateCover(book, cover)
        }
    }

    private suspend fun generateCover(book: Book, cover: File): File = coverSlots.withPermit {
        readingCache.temporaryCovers.mkdirs()
        val scratch = File(readingCache.temporaryCovers, "${book.cacheKey}.source")
        try {
            val bitmap = when (book.kind) {
                "folder" -> {
                    val first = children(Uri.parse(book.uri)).filter { !it.directory && isImage(it.name) }
                        .minWithOrNull { first, second -> NaturalOrder.compare(first.name, second.name) }
                        ?: throw readerError(R.string.empty_image_folder)
                    decodeBitmap(ImageDecoder.createSource(resolver, first.uri), 1000)
                }
                "pdf" -> {
                    val local = File(cache, "${book.cacheKey}/document.pdf")
                    val renderer = if (local.isFile) pdfRenderer(local)
                        else openSeekablePdf(Uri.parse(book.uri)) ?: run {
                            copyUri(Uri.parse(book.uri), scratch, 4L * 1024 * 1024 * 1024)
                            pdfRenderer(scratch)
                        }
                    renderer.use { renderPdfPage(it, 0, 1000) }
                }
                "video-folder" -> {
                    val first = children(Uri.parse(book.uri)).filter { !it.directory && isVideo(it.name) }
                        .minWithOrNull { first, second -> NaturalOrder.compare(first.name, second.name) }
                        ?: throw readerError(R.string.empty_video_folder)
                    videoCover(first.uri)
                }
                "mixed-folder" -> {
                    val first = children(Uri.parse(book.uri)).filter { !it.directory && (isImage(it.name) || isVideo(it.name)) }
                        .minWithOrNull { first, second -> NaturalOrder.compare(first.name, second.name) }
                        ?: throw readerError(R.string.empty_mixed_folder)
                    if (isVideo(first.name)) videoCover(first.uri)
                    else decodeBitmap(ImageDecoder.createSource(resolver, first.uri), 1000)
                }
                else -> {
                    var firstName: String? = null
                    var firstVideo = false
                    var foundVideos = false
                    var foundPdf = false
                    withArchive(book) { archive ->
                        var entry = Archive.readNextHeader(archive)
                        while (entry != 0L) {
                            currentCoroutineContext().ensureActive()
                            val name = entryName(entry)
                            if (ArchiveEntry.filetype(entry) == 0x8000) {
                                val image = isImage(name)
                                val video = isVideo(name)
                                foundPdf = foundPdf || isPdf(name)
                                foundVideos = foundVideos || video
                                if ((image || video) && (firstName == null || NaturalOrder.compare(name, firstName!!) < 0)) {
                                    firstName = name
                                    firstVideo = video
                                }
                            }
                            entry = Archive.readNextHeader(archive)
                        }
                    }
                    if (foundPdf) throw readerError(R.string.archive_contains_pdf)
                    if (firstName == null) throw readerError(R.string.no_cover)
                    val limit = if (foundVideos) 4L * 1024 * 1024 * 1024 else 256L * 1024 * 1024
                    withArchive(book) { archive ->
                        var entry = Archive.readNextHeader(archive)
                        while (entry != 0L) {
                            currentCoroutineContext().ensureActive()
                            if (entryName(entry) == firstName) {
                                checkEntrySize(entry, limit)
                                extract(archive, scratch, limit, limit)
                                break
                            }
                            entry = Archive.readNextHeader(archive)
                        }
                    }
                    if (firstVideo) videoCover(Uri.fromFile(scratch))
                    else decodeBitmap(ImageDecoder.createSource(scratch), 1000)
                }
            }
            val temporary = File(covers, "${book.cacheKey}.tmp")
            try {
                currentCoroutineContext().ensureActive()
                val written = temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 93, it) }
                if (!written) throw readerError(R.string.cover_cache_write_error)
                currentCoroutineContext().ensureActive()
                if (!temporary.renameTo(cover)) throw readerError(R.string.cover_cache_write_error)
                if (book.kind != "pdf") File(covers, "${book.cacheKey}.jpg").delete()
                requestTrim()
            } finally { bitmap.recycle(); temporary.delete() }
            cover
        } finally { scratch.delete() }
    }

    private fun videoCover(uri: Uri): Bitmap = MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(context, uri)
        retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1000, 1000)
            ?: retriever.getScaledFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1000, 1000)
            ?: throw readerError(R.string.video_cover_error)
    }

    /**
     * 抽帧并缓存某个视频的首帧，用作切换过渡和离屏暂停时的封面。
     * 视频页在播放器尚未渲染画面时显示这一帧，避免出现黑屏。
     */
    suspend fun videoFrame(session: BookSession, index: Int): File? = withBookCache(session.book) {
        val page = session.pages.getOrNull(index) ?: return@withBookCache null
        if (!page.isVideo) return@withBookCache null
        frames.mkdirs()
        val pageKey = (page.uri ?: File(page.filePath!!).name).stableId()
        val output = File(frames, "${session.book.cacheKey}-$pageKey.jpg")
        if (output.isFile) return@withBookCache output
        readingCache.fileLock("frame-${session.book.cacheKey}-$pageKey").withLock {
            if (output.isFile) return@withLock output
            DiagnosticLog.log("Cover", "开始抽帧 book=${session.book.id.take(8)} index=$index")
            val started = android.os.SystemClock.elapsedRealtime()
            val bitmap = runCatching {
                MediaMetadataRetriever().use { retriever ->
                    page.filePath?.let { retriever.setDataSource(it) }
                        ?: retriever.setDataSource(context, Uri.parse(page.uri))
                    retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1280, 1280)
                        ?: retriever.getFrameAtTime()
                }
            }.onFailure { DiagnosticLog.error("Cover", "视频抽帧失败 book=${session.book.id.take(8)} index=$index", it) }.getOrNull() ?: return@withLock null
            DiagnosticLog.log("Cover", "抽帧完成 index=$index 耗时=${android.os.SystemClock.elapsedRealtime() - started}ms")
            val temporary = File(frames, "${output.name}.part")
            try {
                temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                if (!temporary.renameTo(output)) return@withLock null
            } finally {
                bitmap.recycle()
                temporary.delete()
            }
            output
        }
    }

    suspend fun pageFile(session: BookSession, index: Int): File = withBookCache(session.book) {
        val page = session.pages[index]
        page.filePath?.let { return@withBookCache File(it) }
        val uri = checkNotNull(page.uri)
        val fileName = "image-${uri.stableId()}.${page.name.extensionLower()}"
        val lockKey = "${session.book.cacheKey}-$fileName"
        readingCache.fileLock(lockKey).withLock {
            val folder = File(cache, session.book.cacheKey).apply { mkdirs() }
            val output = File(folder, fileName)
            if (!output.isFile) copyUri(Uri.parse(uri), output,
                if (page.isVideo) 4L * 1024 * 1024 * 1024 else 256L * 1024 * 1024)
            output
        }
    }

    private suspend fun localPdf(book: Book, folder: File): File {
        val file = File(folder, "document.pdf")
        if (!file.isFile) copyUri(Uri.parse(book.uri), file, 4L * 1024 * 1024 * 1024)
        return file
    }

    private suspend fun copyUri(uri: Uri, destination: File, limit: Long) {
        val temporary = File(destination.parentFile, "${destination.name}.part")
        try {
            resolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        if (total > limit) throw readerError(R.string.file_size_limit)
                        output.write(buffer, 0, count)
                        if (total / (16 * 1024 * 1024) != (total - count) / (16 * 1024 * 1024)) requestTrim()
                    }
                }
            } ?: throw readerError(R.string.source_unavailable)
            if (!temporary.renameTo(destination)) throw readerError(R.string.cache_write_error)
        } finally { temporary.delete() }
    }

    private suspend fun <Result> withArchive(book: Book, block: suspend (Long) -> Result): Result {
        return resolver.openFileDescriptor(Uri.parse(book.uri), "r")?.use { descriptor ->
            val archive = Archive.readNew()
            try {
                Archive.readSupportFilterAll(archive)
                Archive.readSupportFormatAll(archive)
                // Android 的 C locale 无法把 UTF-8 文件名转换为当前 locale，libarchive 会因此在读取中文名条目时抛出 ARCHIVE_WARN。
                // libarchive-android 提供 setCharset 覆盖当前 locale，使中文 UTF-8 文件名可以正常读取。
                Archive.setCharset(archive, "UTF-8".toByteArray(Charsets.UTF_8))
                Archive.readOpenFd(archive, descriptor.fd, 128 * 1024)
                block(archive)
            } finally { Archive.free(archive) }
        } ?: throw readerError(R.string.archive_open_error)
    }

    private fun entryName(entry: Long): String = ArchiveEntry.pathnameUtf8(entry)
        ?: ArchiveEntry.pathname(entry)?.toString(Charsets.UTF_8) ?: ""

    private fun checkEntrySize(entry: Long, limit: Long = 256L * 1024 * 1024) {
        if (ArchiveEntry.size(entry) > limit) throw readerError(R.string.archive_entry_size_limit)
    }

    private suspend fun extract(archive: Long, output: File, remaining: Long, entryLimit: Long = 256L * 1024 * 1024): Long {
        var total = 0L
        val buffer = ByteBuffer.allocateDirect(128 * 1024)
        FileOutputStream(output).channel.use { channel ->
            while (true) {
                currentCoroutineContext().ensureActive()
                buffer.clear()
                Archive.readData(archive, buffer)
                buffer.flip()
                if (!buffer.hasRemaining()) break
                val count = buffer.remaining()
                total += count
                if (total > minOf(remaining, entryLimit)) throw readerError(R.string.archive_total_size_limit)
                while (buffer.hasRemaining()) channel.write(buffer)
                if (total / (16 * 1024 * 1024) != (total - count) / (16 * 1024 * 1024)) requestTrim()
            }
        }
        requestTrim()
        return total
    }

    private fun pdfRenderer(file: File): PdfRenderer =
        pdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))

    private fun pdfRenderer(descriptor: ParcelFileDescriptor): PdfRenderer =
        try { PdfRenderer(descriptor) } catch (error: Throwable) { descriptor.close(); throw error }

    private fun openSeekablePdf(uri: Uri): PdfRenderer? {
        val descriptor = try {
            resolver.openFileDescriptor(uri, "r") ?: return null
        } catch (_: FileNotFoundException) {
            return null
        }
        try {
            Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
        } catch (_: ErrnoException) {
            descriptor.close()
            return null
        }
        return pdfRenderer(descriptor)
    }

    fun decodeBitmap(source: ImageDecoder.Source, maxWidth: Int): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val scale = minOf(1f, maxWidth.toFloat() / info.size.width,
            kotlin.math.sqrt(12_000_000f / (info.size.width.toFloat() * info.size.height)))
        decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
            (info.size.height * scale).roundToInt().coerceAtLeast(1))
    }

    suspend fun cacheSize(): Long = withContext(Dispatchers.IO) {
        readingCache.size()
    }

    suspend fun clearCache(includeCovers: Boolean = false, afterUse: Boolean = false) = withContext(Dispatchers.IO) {
        val before = readingCache.size()
        readingCache.clear(includeCovers, afterUse)
        if (includeCovers) thumbnails.evictAll()
        DiagnosticLog.log("Cache", "手动清理 includeCovers=$includeCovers beforeBytes=$before afterBytes=${readingCache.size()}")
    }

    suspend fun trimCache(protectedKey: String? = null) = withContext(Dispatchers.IO) {
        val limit = CacheLimits.bytes(preferences.settings().cacheLimitMiB)
        val reclaimed = readingCache.trim(protectedKey, limit = limit, target = limit * 2 / 3)
        if (reclaimed > 0) DiagnosticLog.log("Cache", "自动回收 reclaimedBytes=$reclaimed limitBytes=$limit remainingBytes=${readingCache.size()}")
    }
}
