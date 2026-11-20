package dev.zolive.zviewer

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import dev.zolive.zviewer.data.DiagnosticLog
import dev.zolive.zviewer.data.ReaderSettings
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiagnosticLogTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.getExternalFilesDir(null), "logs")

    @Before fun clear() {
        DiagnosticLog.configure(context, false, null)
        directory.deleteRecursively()
    }
    @After fun close() { DiagnosticLog.configure(context, false, null) }

    private fun awaitContent(fragment: String): String {
        val deadline = System.currentTimeMillis() + 5_000
        var content = ""
        while (System.currentTimeMillis() < deadline) {
            content = directory.listFiles()?.filter { it.name.endsWith(".log") }?.joinToString("\n") { it.readText() } ?: ""
            if (content.contains(fragment)) return content
            Thread.sleep(50)
        }
        fail("日志未包含 $fragment：$content")
        return content
    }

    @Test fun recordsEnvironmentResourcesSettingsAndErrorsWithoutUris() {
        DiagnosticLog.configure(context, true, null)
        DiagnosticLog.snapshot(context, ReaderSettings(), "测试")
        awaitContent("[Settings]")
        DiagnosticLog.error("Image", "读取 content://private.example/secret/book.jpg", IOException("解码失败"))
        val content = awaitContent("java.io.IOException: 解码失败")
        listOf("schema=2", "version=${BuildConfig.VERSION_NAME}", "[Device]", "[Environment]", "[Resources]",
            "[Permission]", "heapMax=", "storageFree=", "fontScale=", "INFO", "ERROR", "thread=", "uptimeMs=").forEach {
            assertTrue(it, content.contains(it))
        }
        assertFalse(content.contains("private.example"))
        assertFalse(content.contains("secret/book.jpg"))
        assertNotNull(DiagnosticLog.currentLocation())
    }

    @Test fun concurrentErrorsKeepCompleteLinesAndDisablingStopsWrites() {
        DiagnosticLog.configure(context, true, null)
        val workers = Executors.newFixedThreadPool(4)
        repeat(80) { index -> workers.submit { DiagnosticLog.log("Concurrent", "event=$index") } }
        workers.shutdown()
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        DiagnosticLog.log("Test", "barrier")
        val content = awaitContent("barrier")
        assertEquals(80, content.lineSequence().count { it.contains("[Concurrent]") })
        DiagnosticLog.configure(context, false, null)
        DiagnosticLog.log("Test", "must-not-be-written")
        assertNull(DiagnosticLog.currentLocation())
        assertFalse(directory.listFiles()!!.any { it.readText().contains("must-not-be-written") })
    }

    @Test fun defaultDirectoryRetainsAtMostFiveSessionsAndFallsBackFromInvalidDirectory() {
        repeat(7) {
            DiagnosticLog.configure(context, true, null)
            DiagnosticLog.log("Test", "session=$it")
            DiagnosticLog.configure(context, false, null)
        }
        assertEquals(5, directory.listFiles()!!.size)
        DiagnosticLog.configure(context, true, "content://missing.provider/tree/logs")
        assertTrue(awaitContent("已回退默认目录").contains("[Log]"))
        assertEquals(5, directory.listFiles()!!.size)
    }

    @Test fun largeSessionsAreBounded() {
        DiagnosticLog.configure(context, true, null)
        repeat(300) { DiagnosticLog.log("Large", "x".repeat(16_000)) }
        DiagnosticLog.configure(context, false, null)
        val file = directory.listFiles()!!.single()
        assertTrue(file.length() <= 2 * 1024 * 1024L + 1024)
        assertTrue(file.readText().contains("达到单文件大小上限"))
    }

    @Test fun clearingOldAndActiveLogsPreservesOtherFilesAndKeepsLoggingEnabled() {
        DiagnosticLog.configure(context, true, null)
        val legacy = File(directory, "zviewer-20261006-120000.log").apply { writeText("旧日志") }
        val other = File(directory, "notes.log").apply { writeText("其他文件") }
        DiagnosticLog.log("Test", "before-clear")
        awaitContent("before-clear")
        assertEquals(2, DiagnosticLog.listFiles(context).files.size)

        assertTrue(DiagnosticLog.clear(context))
        assertTrue(DiagnosticLog.listFiles(context).files.isEmpty())
        assertFalse(legacy.exists())
        assertTrue(other.isFile)

        DiagnosticLog.log("Test", "after-clear")
        val content = awaitContent("after-clear")
        assertTrue(content.contains("schema=2"))
        assertFalse(content.contains("before-clear"))
        assertEquals(1, DiagnosticLog.listFiles(context).files.size)
    }

    @Test fun unreadableLogDirectoryFailsExportAndClear() {
        directory.writeText("普通文件")
        val target = DocumentsContract.buildTreeDocumentUri("dev.zolive.zviewer.test.documents", "root")

        assertNotNull(DiagnosticLog.listFiles(context).error)
        assertFalse(DiagnosticLog.clear(context))
        assertThrows(IOException::class.java) { DiagnosticLog.export(context, target) }
        assertEquals("普通文件", directory.readText())
    }
}
