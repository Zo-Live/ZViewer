package dev.zolive.zviewer

import android.app.DownloadManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dev.zolive.zviewer.data.UpdateDownloader
import dev.zolive.zviewer.data.UpdateInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UpdateDownloadIntegrationTest {
    @Test fun officialApkIsSubmittedToSystemDownloadsAndRepeatedRequestsReuseIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("zviewer_updates", Context.MODE_PRIVATE).edit().clear().commit()
        val manager = context.getSystemService(DownloadManager::class.java)
        val downloader = UpdateDownloader(context)
        val info = UpdateInfo("1.6.1", "https://github.com/Zo-Live/ZViewer/releases/download/v1.6.1/ZViewer-1.6.1-release.apk")
        val id = downloader.enqueue(info)
        try {
            assertTrue(id > 0)
            assertEquals(id, downloader.enqueue(info))
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(info.downloadUrl, cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)))
                assertEquals("application/vnd.android.package-archive", cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)))
                assertEquals("ZViewer 1.6.1", cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)))
                assertNotEquals(DownloadManager.STATUS_FAILED, cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)))
            }
        } finally { manager.remove(id) }
    }

    @Test fun missingOrUntrustedApksNeverCreateDownloadTasks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val downloader = UpdateDownloader(context)
        assertTrue(runCatching { downloader.enqueue(UpdateInfo("9.0.0", null)) }.isFailure)
        assertTrue(runCatching { downloader.enqueue(UpdateInfo("9.0.0", "https://example.org/update.apk")) }.isFailure)
    }
}
