package dev.zolive.zviewer

import android.app.DownloadManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dev.zolive.zviewer.data.UpdateDownload
import dev.zolive.zviewer.data.UpdateDownloader
import dev.zolive.zviewer.data.UpdateInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UpdateDownloadIntegrationTest {
    @Test fun officialApkIsSubmittedToSystemDownloadsAndRepeatedRequestsReuseIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("zviewer_updates", Context.MODE_PRIVATE).edit().clear().commit()
        val manager = context.getSystemService(DownloadManager::class.java)
        val downloader = UpdateDownloader(context)
        val info = UpdateInfo("9.0.0", "https://github.com/Zo-Live/ZViewer/releases/download/v1.8.0/ZViewer-1.8.0-release.apk")
        val ids = List(3) { async { UpdateDownloader(context).enqueue(info) } }.awaitAll()
        val id = ids.first()
        try {
            assertTrue(id > 0)
            assertEquals(setOf(id), ids.toSet())
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(info.downloadUrl, cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)))
                assertEquals("application/vnd.android.package-archive", cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)))
                assertEquals("ZViewer 9.0.0", cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)))
                assertNotEquals(DownloadManager.STATUS_FAILED, cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)))
            }
            manager.remove(id)
            assertEquals(UpdateDownload.Failed(info.version, id), UpdateDownloader(context).current())
            val retry = downloader.enqueue(info)
            try {
                assertTrue(retry > id)
            } finally { manager.remove(retry) }
        } finally {
            manager.remove(id)
            context.getSharedPreferences("zviewer_updates", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun missingOrUntrustedApksNeverCreateDownloadTasks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val downloader = UpdateDownloader(context)
        assertTrue(runCatching { downloader.enqueue(UpdateInfo("9.0.0", null)) }.isFailure)
        assertTrue(runCatching { downloader.enqueue(UpdateInfo("9.0.0", "https://example.org/update.apk")) }.isFailure)
    }
}
