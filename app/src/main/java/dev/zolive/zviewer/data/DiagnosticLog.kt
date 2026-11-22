package dev.zolive.zviewer.data

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import dev.zolive.zviewer.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 本地、按需开启的通用诊断日志；有界队列、单文件限额与默认目录保留上限。 */
object DiagnosticLog {
    private const val TAG = "ZViewerLog"
    private const val MAX_BYTES = 2 * 1024 * 1024L
    private const val MAX_FILES = 5
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Entry(val session: Long, val text: String)
    private val pending = Channel<Entry>(512)
    private val dropped = AtomicLong()
    private val writeLock = Any()
    private val fileFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    private val lineFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private val uriPattern = Regex("(?:content|file|https?)://[^\\s)]+")
    private val logName = Regex("""zviewer-\d{8}-\d{6}(?:-\d{3}-\d+)?(?: \(\d+\))?\.log(?: \(\d+\))?(?:\.txt)?""")

    @Volatile private var enabled = false
    @Volatile private var directory: String? = null
    @Volatile private var location: String? = null
    @Volatile private var session = 0L
    private var output: OutputStream? = null
    private var bytesWritten = 0L
    private var capped = false
    private var crashHandlerInstalled = false
    private var restartContext: Context? = null

    data class LogFile(val uri: Uri, val name: String, val size: Long)

    init {
        scope.launch {
            for (entry in pending) synchronized(writeLock) {
                if (entry.session == session) write(entry.text)
            }
        }
    }

    /** 目录变化或重新开启时新建会话，日志关闭后不会采集事件。 */
    fun configure(context: Context, enabled: Boolean, directory: String?) = synchronized(writeLock) {
        this.enabled = enabled
        if (enabled && (output != null || restartContext != null) && this.directory == directory) return@synchronized
        closeSession()
        restartContext = null
        this.directory = directory
        if (!enabled) return@synchronized
        startSession(context)
    }

    private fun startSession(context: Context) {
        val fallback = runCatching { openSession(context, directory) }.exceptionOrNull()
        if (fallback != null) {
            Log.w(TAG, "所选日志目录不可写，改用默认目录", fallback)
            runCatching { openSession(context, null) }.onFailure {
                Log.e(TAG, "无法创建诊断日志", it)
                this.enabled = false
            }
        }
        write(line("INFO", "Session", "日志开始 schema=2 maxBytes=$MAX_BYTES"))
        write(line("INFO", "App", "ZViewer version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} build=${BuildConfig.BUILD_TYPE}"))
        write(line("INFO", "Device", "Android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT} securityPatch=${Build.VERSION.SECURITY_PATCH} " +
            "manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} abis=${Build.SUPPORTED_ABIS.joinToString()}"))
        if (fallback != null) write(line("ERROR", "Log", "所选目录不可写，已回退默认目录\n${fallback.stackTraceToString()}"))
    }

    fun log(tag: String, message: String) = append("INFO", tag, message)
    fun error(tag: String, message: String, throwable: Throwable? = null) =
        append("ERROR", tag, message + (throwable?.let { "\n${it.stackTraceToString()}" } ?: ""))

    private fun line(level: String, tag: String, message: String): String =
        "${OffsetDateTime.now().format(lineFormat)} $level [$tag] pid=${Process.myPid()} " +
            "thread=${Thread.currentThread().name} uptimeMs=${android.os.SystemClock.elapsedRealtime()} " +
            uriPattern.replace(message, "<uri>").take(16_384)

    private fun append(level: String, tag: String, message: String) {
        if (!enabled) return
        val entry = Entry(session, line(level, tag, message))
        if (!pending.trySend(entry).isSuccess) dropped.incrementAndGet()
    }

    /** 不包含书名、完整目录 URI、设备标识符或文件内容。 */
    fun snapshot(context: Context, settings: ReaderSettings, reason: String) {
        if (!enabled) return
        val expectedSession = session
        scope.launch {
            if (!enabled || expectedSession != session) return@launch
            runCatching {
                val metrics = context.resources.displayMetrics
                val config = context.resources.configuration
                val manager = context.getSystemService(ActivityManager::class.java)
                val memory = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
                val runtime = Runtime.getRuntime()
                log("Environment", "$reason display=${metrics.widthPixels}x${metrics.heightPixels} density=${metrics.densityDpi} " +
                    "fontScale=${config.fontScale} orientation=${config.orientation} uiMode=${config.uiMode} locales=${config.locales.toLanguageTags()}")
                log("Resources", "heapUsed=${runtime.totalMemory() - runtime.freeMemory()} heapMax=${runtime.maxMemory()} " +
                    "memoryAvailable=${memory.availMem} memoryTotal=${memory.totalMem} lowMemory=${memory.lowMemory} " +
                    "storageFree=${context.filesDir.usableSpace} storageTotal=${context.filesDir.totalSpace}")
                val grants = context.contentResolver.persistedUriPermissions
                log("Permission", "persisted=${grants.size} grants=${grants.map { "${it.uri.authority}:r=${it.isReadPermission}:w=${it.isWritePermission}" }}")
                log("Settings", "${settings.copy(logDirectory = null)} customLogDirectory=${settings.logDirectory != null}")
            }.onFailure { error("Environment", "采集运行环境失败", it) }
        }
    }

    /** 崩溃时同步写入剩余队列及完整异常，随后交回系统处理器。 */
    @Synchronized fun installCrashHandler() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                synchronized(writeLock) {
                    drain()
                    write(line("ERROR", "Crash", "未捕获异常 thread=${thread.name}\n${throwable.stackTraceToString()}"), force = true)
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun currentLocation(): String? = location

    private fun defaultDirectory(context: Context) =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")

    internal data class LogListing(val files: List<LogFile>, val error: Exception?)

    internal fun listFiles(context: Context): LogListing {
        var error: Exception? = null
        fun readDirectory(read: () -> List<LogFile>): List<LogFile> = try {
            read()
        } catch (failure: Exception) {
            if (error == null) error = failure
            Log.w(TAG, "无法读取日志目录", failure)
            emptyList()
        }

        val local = readDirectory {
            val dir = defaultDirectory(context)
            val entries = if (dir.exists()) dir.listFiles() ?: throw IOException("无法读取默认日志目录")
            else emptyArray()
            entries.filter { it.isFile && logName.matches(it.name) }
                .map { LogFile(Uri.fromFile(it), it.name, it.length()) }
        }
        val resolver = context.contentResolver
        val custom = resolver.persistedUriPermissions.filter { it.isReadPermission && it.isWritePermission }
            .flatMap { grant -> readDirectory { filesInTree(resolver, grant.uri) } }
            .distinctBy { it.uri.authority to DocumentsContract.getDocumentId(it.uri) }
        return LogListing((local + custom).sortedByDescending { it.name }, error)
    }

    private fun filesInTree(resolver: ContentResolver, tree: Uri): List<LogFile> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return resolver.query(children, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    if (!logName.matches(name) || cursor.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    add(LogFile(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, cursor.getLong(2).coerceAtLeast(0)))
                }
            }
        } ?: throw IOException("无法读取所选日志目录")
    }

    fun read(context: Context, uri: Uri): String =
        logInput(context, uri).bufferedReader().use { it.readText() }

    fun shareUri(context: Context, uri: Uri, name: String, text: String? = null): Uri {
        val folder = File(context.cacheDir, "shared-logs").apply { mkdirs() }
        val file = File.createTempFile("log-", if (text == null) ".log" else ".txt", folder)
        try {
            if (text == null) logInput(context, uri).use { input -> file.outputStream().use { input.copyTo(it) } }
            else file.writeText(text)
            return FileProvider.getUriForFile(context, "${context.packageName}.logs", file, name)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    fun export(context: Context, tree: Uri): Boolean {
        val resolver = context.contentResolver
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
        val listing = listFiles(context)
        listing.error?.let { throw it }
        val logs = listing.files
        if (logs.isEmpty()) return false
        val single = logs.singleOrNull()?.takeUnless {
            it.uri.authority == tree.authority && DocumentsContract.getTreeDocumentId(it.uri) == treeId
        }
        val target = if (single != null) {
            DocumentsContract.createDocument(resolver, parent, "application/octet-stream", single.name)
        } else {
            val timestamp = OffsetDateTime.now().format(fileFormat)
            DocumentsContract.createDocument(resolver, parent, "application/zip", "ZViewer-logs-$timestamp.zip")
        } ?: throw IOException("无法创建导出文件")
        try {
            val output = resolver.openOutputStream(target, "w") ?: throw IOException("无法写入导出文件")
            if (single != null) {
                output.use { stream -> logInput(context, single.uri).use { it.copyTo(stream) } }
            } else {
                ZipOutputStream(output.buffered()).use { archive ->
                    val occurrences = mutableMapOf<String, Int>()
                    for (log in logs) {
                        val occurrence = (occurrences[log.name] ?: 0) + 1
                        occurrences[log.name] = occurrence
                        archive.putNextEntry(ZipEntry(if (occurrence == 1) log.name else "$occurrence/${log.name}"))
                        logInput(context, log.uri).use { it.copyTo(archive) }
                        archive.closeEntry()
                    }
                }
            }
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            throw error
        }
        return true
    }

    fun clear(context: Context): Boolean = synchronized(writeLock) {
        restartContext = null
        closeSession()
        try {
            val listing = listFiles(context)
            val deleted = listing.files.map { log ->
                runCatching {
                    if (log.uri.scheme == "file") File(checkNotNull(log.uri.path)).delete()
                    else DocumentsContract.deleteDocument(context.contentResolver, log.uri)
                }.getOrDefault(false)
            }.all { it }
            deleted && listing.error == null
        } finally {
            if (enabled) restartContext = context.applicationContext
        }
    }

    private fun logInput(context: Context, uri: Uri) =
        context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取日志文件")

    private fun openSession(context: Context, directory: String?) {
        val name = "zviewer-${OffsetDateTime.now().format(fileFormat)}-$session.log"
        if (directory != null) {
            val resolver = context.contentResolver
            val tree = directory.toUri()
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val file = DocumentsContract.createDocument(resolver, parent, "text/plain", name)
                ?: throw IOException("无法创建日志文件")
            try {
                output = resolver.openOutputStream(file, "wa") ?: throw IOException("无法写入日志文件")
            } catch (error: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, file) }
                throw error
            }
            location = "所选目录/$name"
        } else {
            val dir = defaultDirectory(context).apply { mkdirs() }
            dir.listFiles()?.filter { it.isFile && logName.matches(it.name) }
                ?.sortedByDescending(File::lastModified)?.drop(MAX_FILES - 1)?.forEach { it.delete() }
            val file = File(dir, name)
            output = file.outputStream()
            location = file.absolutePath
        }
    }

    private fun write(text: String, force: Boolean = false) {
        restartContext?.let { context ->
            restartContext = null
            startSession(context)
        }
        val stream = output ?: return
        if (capped && !force) return
        runCatching {
            val missing = dropped.getAndSet(0)
            val bytes = ((if (missing > 0) line("WARN", "Log", "队列已满，省略 $missing 条事件") + "\n" else "") + text + "\n").toByteArray()
            if (!force && bytesWritten + bytes.size > MAX_BYTES) {
                stream.write((line("WARN", "Log", "达到单文件大小上限，停止记录常规事件") + "\n").toByteArray())
                capped = true
            } else {
                stream.write(bytes)
                bytesWritten += bytes.size
            }
            stream.flush()
        }.onFailure {
            Log.e(TAG, "日志写入失败", it)
            runCatching { stream.close() }
            output = null
            location = null
        }
    }

    private fun drain() {
        while (true) {
            val entry = pending.tryReceive().getOrNull() ?: break
            if (entry.session == session) write(entry.text)
        }
    }

    private fun closeSession() {
        drain()
        runCatching { output?.close() }
        output = null
        location = null
        session++
        bytesWritten = 0
        capped = false
        dropped.set(0)
    }
}
