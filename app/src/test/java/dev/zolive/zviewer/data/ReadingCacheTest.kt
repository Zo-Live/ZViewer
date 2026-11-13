package dev.zolive.zviewer.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReadingCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = temporary.root

    private fun file(path: String, size: Int, age: Long): File = File(root, path).apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(size))
        setLastModified(age)
        if (path.startsWith("books/")) parentFile!!.setLastModified(age)
    }

    @Test fun belowLimitKeepsReusablePages() {
        val cache = ReadingCache(root, limit = 150, target = 100)
        val page = file("books/book/page.png", 149, 1)
        assertEquals(0L, cache.trim())
        assertTrue(page.isFile)
    }

    @Test fun countsCoversAndFramesAndTrimsOldestToTarget() {
        val cache = ReadingCache(root, limit = 150, target = 100)
        val old = file("books/old/page.png", 60, 1)
        val cover = file("covers/recent.jpg", 40, 2)
        val frame = file("covers/frames/recent-0.jpg", 50, 3)
        assertEquals(150L, cache.size())
        assertEquals(60L, cache.trim())
        assertFalse(old.exists())
        assertTrue(cover.isFile)
        assertTrue(frame.isFile)
        assertEquals(90L, cache.size())
    }

    @Test fun coverOnlyCacheIsReclaimed() {
        val cache = ReadingCache(root, limit = 150, target = 100)
        val old = file("covers/old.jpg", 100, 1)
        val recent = file("covers/frames/recent-0.jpg", 60, 2)
        cache.trim()
        assertFalse(old.exists())
        assertTrue(recent.isFile)
    }

    @Test fun concurrentUsersProtectPagesCoversAndTemporaryFiles() {
        val cache = ReadingCache(root, limit = 150, target = 100)
        val first = cache.acquire("active")
        val second = cache.acquire("active")
        val page = file("books/active/page.png", 100, 1)
        val cover = file("covers/active.source", 50, 1)
        val frame = file("covers/frames/active-0.jpg.part", 50, 1)
        val old = file("books/old/page.png", 50, 2)
        first.close()
        first.close()
        cache.trim()
        assertFalse(old.exists())
        assertTrue(page.isFile)
        assertTrue(cover.isFile)
        assertTrue(frame.isFile)
        second.close()
        cache.trim()
        assertTrue(cache.size() <= 100)
    }

    @Test fun oversizedBookSurvivesReadingAndIsReclaimedAfterClose() {
        val cache = ReadingCache(root, limit = 150, target = 100)
        val lease = cache.acquire("active")
        val page = file("books/active/page.png", 200, 1)
        cache.trim()
        assertTrue(page.isFile)
        lease.close()
        cache.trim()
        assertFalse(page.exists())
    }

    @Test fun recentlyReadBookIsKeptBeforeOlderBook() {
        val cache = ReadingCache(root, limit = 150, target = 100)
        val recent = file("books/recent/page.png", 80, 1)
        val old = file("books/old/page.png", 80, 2)
        cache.acquire("recent").close()
        cache.trim()
        assertTrue(recent.isFile)
        assertFalse(old.exists())
    }

    @Test fun clearKeepsInUseFilesAndNeverTouchesOriginals() {
        val cache = ReadingCache(root, limit = 150, target = 100)
        val original = file("library/comic.cbz", 100, 1)
        val active = file("books/active/page.png", 100, 1)
        val old = file("covers/old.jpg", 100, 1)
        val lease = cache.acquire("active")
        cache.clear()
        assertTrue(original.isFile)
        assertTrue(active.isFile)
        assertFalse(old.exists())
        lease.close()
        cache.clear()
        assertFalse(active.exists())
        assertTrue(original.isFile)
    }
}
