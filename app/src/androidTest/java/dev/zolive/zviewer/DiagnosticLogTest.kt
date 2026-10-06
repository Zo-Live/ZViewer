package dev.zolive.zviewer

import androidx.test.platform.app.InstrumentationRegistry
import dev.zolive.zviewer.data.DiagnosticLog
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiagnosticLogTest {
    @Test fun writesSessionLogToDefaultDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "logs")
        directory.deleteRecursively()
        DiagnosticLog.configure(context, true, null)
        try {
            DiagnosticLog.log("Test", "hello-log")
            var file: File? = null
            var content = ""
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                file = directory.listFiles()?.firstOrNull { it.name.endsWith(".log") }
                content = file?.readText() ?: ""
                if (content.contains("hello-log")) break
                Thread.sleep(100)
            }
            assertNotNull("应创建日志文件", file)
            assertTrue("日志应包含写入内容", content.contains("hello-log"))
        } finally {
            DiagnosticLog.configure(context, false, null)
        }
    }
}
