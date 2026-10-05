package dev.zolive.zviewer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class BookRepository(private val context: Context) {
    private val resolver = context.contentResolver
    private val cache = File(context.cacheDir, "books").apply { mkdirs() }
    private val covers = File(context.cacheDir, "covers").apply { mkdirs() }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val coverSlots = Semaphore(2)
    private val coverLocks = ConcurrentHashMap<String, Mutex>()

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
        } ?: throw ReaderException("无法读取目录，请重新选择书库并允许访问。")
    }

    suspend fun scan(treeUri: Uri): List<Book> = withContext(Dispatchers.IO) {
        val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val rootName = resolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "图片集"
        val books = mutableListOf<Book>()
        suspend fun visit(uri: Uri, name: String, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (depth > 32) throw ReaderException("目录层级超过 32 层，请选择更具体的书库目录。")
            val entries = children(uri)
            val images = entries.filter { !it.directory && isImage(it.name) }
            val videos = entries.filter { !it.directory && isVideo(it.name) }
            if (images.isNotEmpty() || videos.isNotEmpty()) {
                val kind = when {
                    images.isNotEmpty() && videos.isNotEmpty() -> "mixed-folder"
                    videos.isNotEmpty() -> "video-folder"
                    else -> "folder"
                }
                books += Book(uri.toString().stableId(), uri.toString(), name, kind,
                    (images + videos).maxOf { it.modified }, (images + videos).sumOf { it.size })
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
        books.sortedWith { first, second -> NaturalOrder.compare(first.title, second.title) }
    }

    suspend fun open(book: Book, status: (String) -> Unit = {}): BookSession = withContext(Dispatchers.IO) {
        locks.getOrPut(book.cacheKey) { Mutex() }.withLock {
            val folder = File(cache, book.cacheKey).apply { mkdirs(); setLastModified(System.currentTimeMillis()) }
            when (book.kind) {
                "folder", "video-folder", "mixed-folder" -> {
                    val entries = children(Uri.parse(book.uri)).filter { !it.directory && (isImage(it.name) || isVideo(it.name)) }
                    val pages = entries.sortedWith { first, second -> NaturalOrder.compare(first.name, second.name) }
                        .map { entry ->
                            PageSource(entry.name, uri = entry.uri.toString(), mediaType = if (isVideo(entry.name)) "video" else "image")
                        }
                    if (pages.isEmpty()) throw ReaderException("这个文件夹中没有可阅读的图片或视频。")
                    BookSession(book, pages)
                }
                "pdf" -> {
                    status("正在准备 PDF…")
                    val file = localPdf(book, folder)
                    val count = pdfRenderer(file).use { it.pageCount }
                    if (count == 0) throw ReaderException("这份 PDF 没有页面。")
                    BookSession(book, List(count) { PageSource("第 ${it + 1} 页", pdfPage = it) }, file.absolutePath)
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
                            return@withLock BookSession(book, files.map { (name, path, type) ->
                                PageSource(name, File(folder, path).absolutePath, mediaType = type)
                            })
                        }
                    }
                    val legacyManifest = File(folder, "pages.json")
                    if (legacyManifest.exists()) {
                        val files = runCatching {
                            val array = JSONArray(legacyManifest.readText())
                            (0 until array.length()).map { array.getString(it) }
                        }.getOrDefault(emptyList())
                        if (files.isNotEmpty() && files.all { File(folder, it).isFile }) {
                            return@withLock BookSession(book, files.map { PageSource(it, File(folder, it).absolutePath) })
                        }
                    }
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
                                    if (extracted.size >= 20_000) throw ReaderException("漫画超过 20,000 页，无法继续解压。")
                                    checkEntrySize(entry, if (video) 4L * 1024 * 1024 * 1024 else 256L * 1024 * 1024)
                                    val output = File(folder, "${extracted.size}.${name.extensionLower()}")
                                    totalBytes += extract(archive, output, 4L * 1024 * 1024 * 1024 - totalBytes,
                                        if (video) 4L * 1024 * 1024 * 1024 else 256L * 1024 * 1024)
                                    extracted += Triple(name, output, if (video) "video" else "image")
                                    status("正在准备第 ${extracted.size} ${if (video) "个视频" else "页"}…")
                                }
                                entry = Archive.readNextHeader(archive)
                            }
                        }
                        if (foundPdf) throw ReaderException("压缩包本身可以读取，但其中的 PDF 暂不支持直接阅读，请先解压到书库目录后再打开。")
                        if (extracted.isEmpty()) throw ReaderException("压缩包中没有支持的图片或视频。")
                        val sorted = extracted.sortedWith { first, second -> NaturalOrder.compare(first.first, second.first) }
                        manifest.writeText(JSONArray(sorted.map { (name, file, type) ->
                            org.json.JSONObject().put("name", name).put("path", file.name).put("type", type)
                        }).toString())
                        BookSession(book, sorted.map { (name, file, type) -> PageSource(name, file.absolutePath, mediaType = type) })
                    } catch (error: Exception) {
                        folder.deleteRecursively()
                        throw error
                    }
                }
            }
        }
    }

    suspend fun cover(book: Book): File = withContext(Dispatchers.IO) {
        val cover = File(covers, "${book.cacheKey}.jpg")
        if (cover.isFile) return@withContext cover
        coverLocks.getOrPut(book.cacheKey) { Mutex() }.withLock {
            coverSlots.withPermit {
                if (cover.isFile) return@withPermit cover
                val scratch = File(covers, "${book.cacheKey}.source")
                try {
                    val bitmap = when (book.kind) {
                        "folder" -> {
                            val first = children(Uri.parse(book.uri)).filter { !it.directory && isImage(it.name) }
                                .minWithOrNull { first, second -> NaturalOrder.compare(first.name, second.name) }
                                ?: throw ReaderException("图片文件夹为空。")
                            decodeBitmap(ImageDecoder.createSource(resolver, first.uri), 1000)
                        }
                        "pdf" -> locks.getOrPut(book.cacheKey) { Mutex() }.withLock {
                            val folder = File(cache, book.cacheKey).apply { mkdirs() }
                            renderPdf(localPdf(book, folder), 0, 1000)
                        }
                        "video-folder" -> {
                            val first = children(Uri.parse(book.uri)).filter { !it.directory && isVideo(it.name) }
                                .minWithOrNull { first, second -> NaturalOrder.compare(first.name, second.name) }
                                ?: throw ReaderException("视频文件夹为空。")
                            videoCover(first.uri)
                        }
                        "mixed-folder" -> {
                            val first = children(Uri.parse(book.uri)).filter { !it.directory && (isImage(it.name) || isVideo(it.name)) }
                                .minWithOrNull { first, second -> NaturalOrder.compare(first.name, second.name) }
                                ?: throw ReaderException("图片和视频文件夹为空。")
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
                            if (foundPdf) throw ReaderException("压缩包本身可以读取，但其中的 PDF 暂不支持直接阅读，请先解压到书库目录后再打开。")
                            if (firstName == null) throw ReaderException("未找到封面。")
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
                        temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 93, it) }
                        if (!temporary.renameTo(cover)) throw ReaderException("封面缓存写入失败。")
                    } finally { bitmap.recycle(); temporary.delete() }
                    cover
                } finally { scratch.delete() }
            }
        }
    }

    private fun videoCover(uri: Uri): Bitmap = MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(context, uri)
        retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1000, 1000)
            ?: retriever.getScaledFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1000, 1000)
            ?: throw ReaderException("无法从第一个视频生成封面，设备可能不支持此编码。")
    }

    suspend fun pageFile(session: BookSession, index: Int, width: Int): File = withContext(Dispatchers.IO) {
        val page = session.pages[index]
        page.filePath?.let { return@withContext File(it) }
        val lockKey = "${session.book.cacheKey}-page-$index-${if (page.pdfPage != null) width else 0}"
        locks.getOrPut(lockKey) { Mutex() }.withLock {
            val folder = File(cache, session.book.cacheKey).apply { mkdirs() }
            if (page.pdfPage != null) {
                val output = File(folder, "pdf-${page.pdfPage}-$width.png")
                if (!output.isFile) {
                    val bitmap = renderPdf(File(session.pdfPath!!), page.pdfPage, width)
                    val temporary = File(folder, "${output.name}.part")
                    try {
                        temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        if (!temporary.renameTo(output)) throw ReaderException("页面缓存写入失败，请检查剩余存储空间。")
                    } finally { bitmap.recycle(); temporary.delete() }
                }
                return@withLock output
            }
            val output = File(folder, "image-$index.${page.name.extensionLower()}")
            if (!output.isFile) copyUri(Uri.parse(page.uri!!), output,
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
                        if (total > limit) throw ReaderException("文件超出安全大小限制。")
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw ReaderException("原文件不可用，请刷新书库或重新授权目录。")
            if (!temporary.renameTo(destination)) throw ReaderException("缓存写入失败，请检查剩余存储空间。")
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
        } ?: throw ReaderException("无法打开压缩包，请重新授权书库。")
    }

    private fun entryName(entry: Long): String = ArchiveEntry.pathnameUtf8(entry)
        ?: ArchiveEntry.pathname(entry)?.toString(Charsets.UTF_8) ?: ""

    private fun checkEntrySize(entry: Long, limit: Long = 256L * 1024 * 1024) {
        if (ArchiveEntry.size(entry) > limit) throw ReaderException("单个媒体文件超过安全大小限制，无法解压。")
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
                total += buffer.remaining()
                if (total > minOf(remaining, entryLimit)) throw ReaderException("解压大小超过安全限制（单个媒体 4 GB / 每本 4 GB）。")
                while (buffer.hasRemaining()) channel.write(buffer)
            }
        }
        return total
    }

    private fun pdfRenderer(file: File): PdfRenderer {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try { PdfRenderer(descriptor) } catch (error: Exception) { descriptor.close(); throw error }
    }

    fun renderPdf(file: File, index: Int, width: Int): Bitmap = pdfRenderer(file).use { renderer ->
        renderer.openPage(index).use { page ->
            val renderWidth = width.coerceIn(480, 2560)
            val renderHeight = (renderWidth.toFloat() * page.height / page.width).roundToInt().coerceAtLeast(1)
            val factor = minOf(1f, kotlin.math.sqrt(12_000_000f / (renderWidth.toFloat() * renderHeight)))
            val bitmap = Bitmap.createBitmap((renderWidth * factor).toInt().coerceAtLeast(1),
                (renderHeight * factor).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    fun decodeBitmap(source: ImageDecoder.Source, maxWidth: Int): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val scale = minOf(1f, maxWidth.toFloat() / info.size.width,
            kotlin.math.sqrt(12_000_000f / (info.size.width.toFloat() * info.size.height)))
        decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
            (info.size.height * scale).roundToInt().coerceAtLeast(1))
    }

    suspend fun cacheSize(): Long = withContext(Dispatchers.IO) {
        cache.walkTopDown().filter { it.isFile }.sumOf { it.length() } +
            covers.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        cache.deleteRecursively(); covers.deleteRecursively(); cache.mkdirs(); covers.mkdirs()
    }

    suspend fun trimCache(protectedKey: String? = null) = withContext(Dispatchers.IO) {
        val folders = cache.listFiles()?.sortedBy { it.lastModified() } ?: return@withContext
        var size = folders.sumOf { folder -> folder.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        for (folder in folders) {
            if (size < 1536L * 1024 * 1024) break
            if (folder.name == protectedKey) continue
            val bytes = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (folder.deleteRecursively()) size -= bytes
        }
    }
}
