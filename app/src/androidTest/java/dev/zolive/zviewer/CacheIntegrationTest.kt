package dev.zolive.zviewer

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zolive.zviewer.data.BookRepository
import dev.zolive.zviewer.data.BookSession
import dev.zolive.zviewer.data.PreferenceStore
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

    @Before fun clear() = runBlocking {
        val store = PreferenceStore(context)
        store.saveSettings(store.settings().copy(cacheLimitMiB = 1536))
        repository.clearCache(includeCovers = true)
    }

    @After fun finish() = runBlocking {
        sessions.forEach { repository.close(it) }
        repository.clearCache(includeCovers = true)
        repository.close()
    }

    private fun sparse(path: String, bytes: Long): File = File(
        if (path.startsWith("covers/")) context.filesDir else context.cacheDir, path).apply {
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
            val bitmap = BitmapFactory.decodeFile(repository.pageFile(session, index).absolutePath)
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

    @Test fun coverAndVideoFrameBytesTriggerReclamationButAreNeverEvicted() = runBlocking {
        val book = repository.scan(tree).first { it.kind == "folder" }
        val cover = sparse("covers/old.jpg", limit / 2)
        val frame = sparse("covers/frames/old-0.jpg", limit / 2)
        val pages = sparse("books/old/page.bin", 1024)
        val generated = repository.cover(book)
        withTimeout(10_000) { while (pages.exists()) delay(50) }
        assertTrue(cover.isFile)
        assertTrue(frame.isFile)
        assertTrue(generated.isFile)
        assertTrue(repository.cacheSize() >= limit)
    }

    @Test fun agedCoverAndVideoFrameSurviveRestartAndSystemCacheDeletion() = runBlocking {
        val formats = DocumentsContract.buildTreeDocumentUri("dev.zolive.zviewer.test.documents", "root/formats/video-folder")
        val book = repository.scan(formats).single()
        val cover = repository.cover(book)
        val session = repository.open(book).also { sessions += it }
        val frame = repository.videoFrame(session, 0)!!
        repository.close(session)
        val coverBytes = cover.readBytes()
        val frameBytes = frame.readBytes()
        assertTrue(cover.setLastModified(1000))
        assertTrue(frame.setLastModified(1000))
        repository.close()
        context.cacheDir.deleteRecursively()
        BookRepository(context).use { restarted ->
            restarted.trimCache()
            val scanned = restarted.scan(formats, listOf(book)).single()
            assertEquals(book.cacheKey, scanned.cacheKey)
            assertArrayEquals(coverBytes, restarted.cover(scanned).readBytes())
            assertEquals(1000L, cover.lastModified())
            val reopened = restarted.open(scanned)
            try {
                assertArrayEquals(frameBytes, restarted.videoFrame(reopened, 0)!!.readBytes())
                assertEquals(1000L, frame.lastModified())
            } finally { restarted.close(reopened) }
        }
    }

    @Test fun changedFileRebuildsOnlyItsCoverAndManualClearRemovesAllCovers() = runBlocking {
        val previous = repository.scan(tree)
        val book = previous.first { it.kind == "cbz" }
        val other = previous.first { it.kind == "pdf" }
        val oldCover = repository.cover(book)
        val unchanged = repository.cover(other)
        assertTrue(unchanged.setLastModified(1000))
        val id = DocumentsContract.getDocumentId(Uri.parse(book.uri))
        val oldModified = change("setModified", id, Bundle().apply { putLong("modified", book.modified + 60_000) })!!.getLong("previous")
        try {
            val current = repository.scan(tree, previous)
            assertFalse(oldCover.exists())
            assertEquals(unchanged, repository.cover(current.first { it.id == other.id }))
            assertEquals(1000L, unchanged.lastModified())
            val regenerated = repository.cover(current.first { it.id == book.id })
            assertNotEquals(oldCover, regenerated)
            assertNotNull(BitmapFactory.decodeFile(regenerated.absolutePath).also { it?.recycle() })
            repository.clearCache(includeCovers = true)
            assertFalse(regenerated.exists())
            assertFalse(unchanged.exists())
            assertTrue(repository.cover(current.first { it.id == book.id }).isFile)
        } finally { change("setModified", id, Bundle().apply { putLong("modified", oldModified) }) }
    }

    @Test fun folderRenameInvalidatesCoverEvenWhenTotalSizeAndLatestModificationAreUnchanged() = runBlocking {
        val previous = repository.scan(tree)
        val book = previous.first { it.kind == "folder" }
        val session = repository.open(book).also { sessions += it }
        val id = DocumentsContract.getDocumentId(Uri.parse(session.pages.first().uri))
        repository.close(session)
        val cover = repository.cover(book)
        val changedId = change("rename", id, Bundle().apply { putString("name", "cache-test-last.png") })!!.getString("documentId")!!
        try {
            val current = repository.scan(tree, previous)
            val changed = current.first { it.id == book.id }
            assertEquals(book.modified, changed.modified)
            assertEquals(book.size, changed.size)
            assertNotEquals(book.cacheKey, changed.cacheKey)
            assertFalse(cover.exists())
            assertTrue(repository.cover(changed).isFile)
            val preferences = PreferenceStore(context)
            val saved = preferences.readLibrary()
            try {
                preferences.saveLibrary(current)
                assertEquals(current, preferences.readLibrary())
            } finally { preferences.saveLibrary(saved) }
        } finally { change("rename", changedId, Bundle().apply { putString("name", id.substringAfterLast('/')) }) }
    }

    @Test fun failedScanDoesNotRemoveCoversFromPreviousLibrary() = runBlocking {
        val previous = repository.scan(tree)
        val cover = repository.cover(previous.first { it.kind == "cbz" })
        val folder = previous.first { it.kind == "folder" }
        change("failChildren", DocumentsContract.getDocumentId(Uri.parse(folder.uri)))
        try {
            assertTrue(runCatching { repository.scan(tree, previous) }.isFailure)
            assertTrue(cover.isFile)
        } finally { change("failChildren", null) }
    }

    private fun change(method: String, id: String?, extras: Bundle? = null): Bundle? =
        context.contentResolver.call("dev.zolive.zviewer.test.documents", method, id, extras)

    @Test fun pdfCoverDoesNotLeaveFullDocumentCopy() = runBlocking {
        val book = repository.scan(tree).first { it.kind == "pdf" }
        val bitmap = BitmapFactory.decodeFile(repository.cover(book).absolutePath)
        assertNotNull(bitmap)
        bitmap.recycle()
        assertFalse(File(context.cacheDir, "books/${book.cacheKey}/document.pdf").exists())
        assertFalse(File(context.cacheDir, "covers/${book.cacheKey}.source").exists())
    }

    @Test fun seekablePdfRendersWithoutWritingDocumentOrPagesToCache() = runBlocking {
        val book = repository.scan(tree).first { it.kind == "pdf" }
        val session = repository.open(book).also { sessions += it }
        val bitmap = checkNotNull(session.pdf).render(0, 3200, 12_000_000)
        assertEquals(2560, bitmap.width)
        bitmap.recycle()
        assertTrue(File(context.cacheDir, "books/${book.cacheKey}").listFiles().orEmpty().isEmpty())
    }

    @Test fun nonSeekablePdfRemainsReadableUsingLocalCopy() = runBlocking {
        val original = repository.scan(tree).first { it.kind == "pdf" }
        val book = original.copy(uri = Uri.parse(original.uri).buildUpon()
            .appendQueryParameter("pipe", "true").build().toString())
        val session = repository.open(book).also { sessions += it }
        val bitmap = checkNotNull(session.pdf).render(1, 1000, 12_000_000)
        assertEquals(1000, bitmap.width)
        bitmap.recycle()
        val folder = File(context.cacheDir, "books/${book.cacheKey}")
        assertEquals(setOf("document.pdf"), folder.list().orEmpty().toSet())
    }

    @Test fun closingPdfCancelsLatePageRequests() = runBlocking {
        val book = repository.scan(tree).first { it.kind == "pdf" }
        val session = repository.open(book)
        repository.close(session)
        val result = runCatching { checkNotNull(session.pdf).render(0, 1000, 12_000_000) }
        assertTrue(result.exceptionOrNull() is CancellationException)
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
        val page = repository.pageFile(session, 0)
        withTimeout(10_000) { while (old.exists()) delay(50) }
        assertTrue(page.isFile)
        assertEquals(page, repository.pageFile(session, 0))
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
