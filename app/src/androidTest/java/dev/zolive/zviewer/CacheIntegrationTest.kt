package dev.zolive.zviewer

import android.graphics.BitmapFactory
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zolive.zviewer.data.BookRepository
import dev.zolive.zviewer.data.BookSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class CacheIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = BookRepository(context)
    private val tree = DocumentsContract.buildTreeDocumentUri("dev.zolive.zviewer.test.documents", "root/书库")
    private val sessions = mutableListOf<BookSession>()
    private val limit = 1536L * 1024 * 1024

    @Before fun clear() = runBlocking { repository.clearCache() }

    @After fun finish() = runBlocking {
        sessions.forEach { repository.close(it) }
        repository.clearCache()
        repository.close()
    }

    private fun sparse(path: String, bytes: Long): File = File(context.cacheDir, path).apply {
        parentFile!!.mkdirs()
        RandomAccessFile(this, "rw").use { it.setLength(bytes) }
        setLastModified(1)
        if (path.startsWith("books/")) parentFile!!.setLastModified(1)
    }

    @Test fun openingBookAutomaticallyReclaimsOldCacheWhilePagesStayReadable() = runBlocking {
        val book = repository.scan(tree).first { it.kind == "cbz" }
        val old = sparse("books/old/large.bin", limit)
        val session = repository.open(book).also { sessions += it }
        withTimeout(10_000) { while (old.exists()) delay(50) }
        for (index in session.pages.indices) {
            val bitmap = BitmapFactory.decodeFile(repository.pageFile(session, index, 1200).absolutePath)
            assertNotNull(bitmap)
            bitmap.recycle()
        }
        assertTrue(repository.cacheSize() < limit)
    }

    @Test fun activeOversizedBookSurvivesTrimUntilClosed() = runBlocking {
        val book = repository.scan(tree).first { it.kind == "cbz" }
        val session = repository.open(book).also { sessions += it }
        val large = sparse("books/${book.cacheKey}/large.bin", limit)
        repository.trimCache()
        assertTrue(large.isFile)
        assertTrue(session.pages.all { File(it.filePath!!).isFile })
        repository.close(session)
        assertFalse(large.exists())
        assertTrue(repository.cacheSize() < limit)
    }

    @Test fun coverAndVideoFrameBytesTriggerAutomaticReclamation() = runBlocking {
        val book = repository.scan(tree).first { it.kind == "folder" }
        val cover = sparse("covers/old.jpg", limit / 2)
        val frame = sparse("covers/frames/old-0.jpg", limit / 2)
        repository.cover(book)
        withTimeout(10_000) { while (repository.cacheSize() >= limit) delay(50) }
        assertFalse(cover.exists() && frame.exists())
        assertTrue(File(context.cacheDir, "covers/${book.cacheKey}.jpg").isFile)
    }

    @Test fun pdfCoverDoesNotLeaveFullDocumentCopy() = runBlocking {
        val book = repository.scan(tree).first { it.kind == "pdf" }
        val bitmap = BitmapFactory.decodeFile(repository.cover(book).absolutePath)
        assertNotNull(bitmap)
        bitmap.recycle()
        assertFalse(File(context.cacheDir, "books/${book.cacheKey}/document.pdf").exists())
        assertFalse(File(context.cacheDir, "covers/${book.cacheKey}.source").exists())
    }

    @Test fun equivalentPdfWidthsReuseRenderedPage() = runBlocking {
        val book = repository.scan(tree).first { it.kind == "pdf" }
        val session = repository.open(book).also { sessions += it }
        val first = repository.pageFile(session, 0, 3000)
        assertEquals(first, repository.pageFile(session, 0, 3200))
        assertEquals(first, repository.pageFile(session, 0, 2560))
        val bitmap = BitmapFactory.decodeFile(first.absolutePath)
        assertNotNull(bitmap)
        assertEquals(2560, bitmap.width)
        bitmap.recycle()
    }

    @Test fun nonSeekablePdfCoverUsesTemporaryCopyAndDeletesIt() = runBlocking {
        val original = repository.scan(tree).first { it.kind == "pdf" }
        val book = original.copy(uri = android.net.Uri.parse(original.uri).buildUpon()
            .appendQueryParameter("pipe", "true").build().toString())
        val bitmap = BitmapFactory.decodeFile(repository.cover(book).absolutePath)
        assertNotNull(bitmap)
        bitmap.recycle()
        assertFalse(File(context.cacheDir, "books/${book.cacheKey}/document.pdf").exists())
        assertFalse(File(context.cacheDir, "covers/${book.cacheKey}.source").exists())
        assertFalse(File(context.cacheDir, "covers/${book.cacheKey}.source.part").exists())
    }

    @Test fun pageWriteTriggersReclamationWithoutLeavingReader() = runBlocking {
        val book = repository.scan(tree).first { it.kind == "folder" }
        val session = repository.open(book).also { sessions += it }
        delay(1000)
        val old = sparse("books/old/large.bin", limit)
        val page = repository.pageFile(session, 0, 1200)
        withTimeout(10_000) { while (old.exists()) delay(50) }
        assertTrue(page.isFile)
        assertEquals(page, repository.pageFile(session, 0, 1200))
    }

    @Test fun cancelledOpenReleasesProtectionAndRemovesPartialExtraction() = runBlocking {
        val book = repository.scan(tree).first { it.kind == "cbz" }
        val result = runCatching { repository.open(book) { throw CancellationException("取消解压测试") } }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertFalse(File(context.cacheDir, "books/${book.cacheKey}").exists())
        val stale = sparse("books/${book.cacheKey}/large.bin", limit)
        repository.trimCache()
        assertFalse(stale.exists())
    }
}
