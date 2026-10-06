package dev.zolive.zviewer.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内诊断日志。开启后，把应用运行事件（视频切换、播放器错误、异常等）追加到
 * 每个进程一个的日志文件。
 *
 * 不需要「所有文件访问」权限：默认写入应用专属外部目录；也可以在设置里通过 SAF
 * 选择一个目录（例如 Download），此时使用该目录的持久读写授权写入。
 */
object DiagnosticLog {
    private const val TAG = "ZViewerLog"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<String>(Channel.UNLIMITED)
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private val fileFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)

    @Volatile private var enabled = false
    @Volatile private var directory: String? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var location: String? = null
    @Volatile private var crashHandlerInstalled = false
    private val writeLock = Any()

    init {
        scope.launch {
            for (line in pending) {
                val stream = output ?: continue
                runCatching {
                    synchronized(writeLock) {
                        stream.write((line + "\n").toByteArray())
                        stream.flush()
                    }
                }
            }
        }
    }

    /** 根据设置开启 / 关闭日志；目录变化或重新开启时新建一个日志文件。 */
    fun configure(context: Context, enabled: Boolean, directory: String?) {
        val sameDirectory = this.directory == directory
        this.enabled = enabled
        if (!enabled) {
            closeSession()
            return
        }
        if (output != null && sameDirectory) return
        closeSession()
        this.directory = directory
        runCatching { openSession(context, directory) }
            .onFailure {
                Log.w(TAG, "所选日志目录不可写，改用默认目录", it)
                runCatching { openSession(context, null) }
            }
    }

    fun log(tag: String, message: String) {
        if (!enabled) return
        val line = "${timeFormat.format(Date())} [$tag] $message"
        Log.d(TAG, line)
        pending.trySend(line)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        if (!enabled) return
        val line = "${timeFormat.format(Date())} [$tag] ERROR $message"
        Log.e(TAG, line, throwable)
        pending.trySend(line)
        throwable?.stackTraceToString()?.let { pending.trySend(it) }
    }

    /** 记录未捕获异常并同步刷盘，随后交回默认处理器。 */
    fun installCrashHandler() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val line = "${timeFormat.format(Date())} [Crash] 未捕获异常于线程 ${thread.name}\n" +
                    throwable.stackTraceToString()
                val stream = output
                if (stream != null) {
                    synchronized(writeLock) {
                        stream.write((line + "\n").toByteArray())
                        stream.flush()
                    }
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun currentLocation(): String? = location

    private fun openSession(context: Context, directory: String?) {
        val name = "zviewer-${fileFormat.format(Date())}.log"
        val resolver = context.contentResolver
        val stream: OutputStream?
        if (directory != null) {
            val tree = directory.toUri()
            val parentId = DocumentsContract.getTreeDocumentId(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            val file = DocumentsContract.createDocument(resolver, parent, "text/plain", name)
                ?: throw IllegalStateException("无法在所选目录创建日志文件")
            stream = resolver.openOutputStream(file, "wa")
            location = "${tree.lastPathSegment?.substringAfterLast('/') ?: "所选目录"}/$name"
        } else {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").apply { mkdirs() }
            val file = File(dir, name)
            stream = file.outputStream()
            location = file.absolutePath
        }
        output = stream
        log("Session", "日志开始 $location")
        log("App", "ZViewer ${dev.zolive.zviewer.BuildConfig.VERSION_NAME}，" +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})，" +
            "设备 ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    private fun closeSession() {
        synchronized(writeLock) {
            runCatching { output?.flush() }
            runCatching { output?.close() }
        }
        output = null
        location = null
    }
}
