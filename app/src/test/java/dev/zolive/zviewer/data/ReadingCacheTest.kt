package dev.zolive.zviewer.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReadingCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = File(temporary.root, "cache")
    private val persistentRoot get() = File(temporary.root, "files")

    private fun file(path: String, size: Int, age: Long): File = File(if (path.startsWith("covers/")) persistentRoot else root, path).apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(size))
        setLastModified(age)
        if (path.startsWith("books/")) parentFile!!.setLastModified(age)
    }

    @Test fun belowLimitKeepsReusablePages() {
        val cache = ReadingCache(root, persistentRoot, limit = 150, target = 100)
        val page = file("books/book/page.png", 149, 1)
        assertEquals(0L, cache.trim())
        assertTrue(page.isFile)
    }

    @Test fun countsCoversAndFramesAndTrimsOldestToTarget() {
        val cache = ReadingCache(root, persistentRoot, limit = 150, target = 100)
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

    @Test fun oldCoversAndFramesSurviveEvenWhenTheyAloneExceedLimit() {
        val cache = ReadingCache(root, persistentRoot, limit = 150, target = 100)
        val old = file("covers/old.jpg", 100, 1)
        val recent = file("covers/frames/recent-0.jpg", 60, 2)
        assertEquals(0L, cache.trim())
        assertTrue(old.isFile)
        assertTrue(recent.isFile)
        assertEquals(160L, cache.size())
    }

    @Test fun concurrentUsersProtectPagesCoversAndTemporaryFiles() {
        val cache = ReadingCache(root, persistentRoot, limit = 150, target = 100)
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
        val cache = ReadingCache(root, persistentRoot, limit = 150, target = 100)
        val lease = cache.acquire("active")
        val page = file("books/active/page.png", 200, 1)
        cache.trim()
        assertTrue(page.isFile)
        lease.close()
        cache.trim()
        assertFalse(page.exists())
    }

    @Test fun recentlyReadBookIsKeptBeforeOlderBook() {
        val cache = ReadingCache(root, persistentRoot, limit = 150, target = 100)
        val recent = file("books/recent/page.png", 80, 1)
        val old = file("books/old/page.png", 80, 2)
        cache.acquire("recent").close()
        cache.trim()
        assertTrue(recent.isFile)
        assertFalse(old.exists())
    }

    @Test fun clearKeepsInUseFilesAndNeverTouchesOriginals() {
        val cache = ReadingCache(root, persistentRoot, limit = 150, target = 100)
        val original = file("library/comic.cbz", 100, 1)
        val active = file("books/active/page.png", 100, 1)
        val old = file("covers/old.jpg", 100, 1)
        val lease = cache.acquire("active")
        cache.clear(includeCovers = true)
        assertTrue(original.isFile)
        assertTrue(active.isFile)
        assertFalse(old.exists())
        lease.close()
        cache.clear(includeCovers = true)
        assertFalse(active.exists())
        assertTrue(original.isFile)
    }

    @Test fun automaticTrimReclaimsPagesAndInterruptedWritesButKeepsOlderCovers() {
        val cache = ReadingCache(root, persistentRoot, limit = 150, target = 100)
        val cover = file("covers/book.jpg", 160, 1)
        val page = file("books/book/page.png", 40, 2)
        val interrupted = file("covers/book.tmp", 20, 3)
        assertEquals(60L, cache.trim())
        assertTrue(cover.isFile)
        assertFalse(page.exists())
        assertFalse(interrupted.exists())
        assertEquals(160L, cache.size())
    }

    @Test fun manualClearPreservesCoversByDefaultAndIncludesThemOnlyWhenEnabled() {
        val cache = ReadingCache(root, persistentRoot)
        val cover = file("covers/book.jpg", 20, 1)
        val frame = file("covers/frames/book-0.jpg", 20, 1)
        val page = file("books/book/page.png", 20, 1)
        val temporary = file("covers/book.tmp", 20, 1)
        cache.clear()
        assertFalse(page.exists())
        assertFalse(temporary.exists())
        assertTrue(cover.isFile)
        assertTrue(frame.isFile)
        cache.clear(includeCovers = true)
        assertFalse(cover.exists())
        assertFalse(frame.exists())
    }

    @Test fun changingThresholdUsesNewLimitAndTargetWithoutEvictingCovers() {
        val cache = ReadingCache(root, persistentRoot)
        val cover = file("covers/book.jpg", 20, 1)
        val old = file("books/old/page.png", 80, 2)
        val recent = file("books/recent/page.png", 80, 3)
        assertEquals(0L, cache.trim(limit = 300, target = 200))
        assertEquals(80L, cache.trim(limit = 150, target = 100))
        assertTrue(cover.isFile)
        assertFalse(old.exists())
        assertTrue(recent.isFile)
        assertEquals(100L, cache.size())
    }

    @Test fun legacyCoversAndFramesMigrateWithoutRegenerationAndSurviveSystemCacheDeletion() {
        val legacy = File(root, "covers/book.jpg").apply {
            parentFile!!.mkdirs()
            writeText("已有封面")
            setLastModified(1000)
        }
        val frame = File(root, "covers/frames/book-0.jpg").apply {
            parentFile!!.mkdirs()
            writeText("已有首帧")
        }
        val scratch = File(root, "covers/book.source").apply { writeText("临时源文件") }
        val cache = ReadingCache(root, persistentRoot)
        cache.acquire("book").close()
        assertFalse(legacy.exists())
        assertFalse(frame.exists())
        assertEquals("已有封面", File(cache.covers, "book.jpg").readText())
        assertEquals(1000L, File(cache.covers, "book.jpg").lastModified())
        assertEquals("已有首帧", File(cache.covers, "frames/book-0.jpg").readText())
        assertTrue(scratch.isFile)
        root.deleteRecursively()
        val restarted = ReadingCache(root, persistentRoot)
        restarted.trim()
        assertEquals("已有封面", File(restarted.covers, "book.jpg").readText())
        assertEquals("已有首帧", File(restarted.covers, "frames/book-0.jpg").readText())
    }

    @Test fun migrationKeepsAlreadyPersistentCoverWhenBothLocationsExist() {
        val persistent = file("covers/book.jpg", 80, 1000)
        val legacy = File(root, "covers/book.jpg").apply {
            parentFile!!.mkdirs()
            writeText("旧封面")
        }
        val cache = ReadingCache(root, persistentRoot)
        assertEquals(80L, cache.size())
        assertTrue(persistent.isFile)
        assertEquals(1000L, persistent.lastModified())
        assertFalse(legacy.exists())
    }

    @Test fun successfulScanRemovesOnlyChangedAndRemovedBooks() {
        val cache = ReadingCache(root, persistentRoot)
        val changed = file("covers/changed.jpg", 10, 1)
        val removed = file("covers/frames/removed-0.jpg", 10, 1)
        val unchanged = file("covers/unchanged.jpg", 10, 1)
        val otherLibrary = file("covers/other.jpg", 10, 1)
        val page = file("books/changed/page.png", 10, 1)
        cache.reconcile(setOf("changed", "removed", "unchanged"), setOf("new", "unchanged"))
        assertFalse(changed.exists())
        assertFalse(removed.exists())
        assertFalse(page.exists())
        assertTrue(unchanged.isFile)
        assertTrue(otherLibrary.isFile)
    }

    @Test fun addingFolderSignatureReusesExistingCoverAndFrames() {
        val cache = ReadingCache(root, persistentRoot)
        val old = file("covers/legacy.jpg", 10, 1000)
        val frame = file("covers/frames/legacy-0.jpg", 20, 1000)
        cache.reconcile(setOf("legacy"), setOf("signed"), mapOf("legacy" to "signed"))
        assertFalse(old.exists())
        assertFalse(frame.exists())
        assertEquals(10L, File(cache.covers, "signed.jpg").length())
        assertEquals(1000L, File(cache.covers, "signed.jpg").lastModified())
        assertEquals(20L, File(cache.covers, "frames/signed-0.jpg").length())
    }

    @Test fun changedBookInUseIsRemovedOnlyAfterLastUserFinishes() {
        val cache = ReadingCache(root, persistentRoot)
        val first = cache.acquire("old")
        val second = cache.acquire("old")
        val cover = file("covers/old.jpg", 10, 1)
        cache.reconcile(setOf("old"), setOf("new"))
        first.close()
        assertTrue(cover.isFile)
        second.close()
        assertFalse(cover.exists())
        // 扫描更新 UI 前启动的旧请求也不能重新留下失效封面。
        val late = cache.acquire("old")
        val stale = file("covers/old.jpg", 10, 1)
        late.close()
        assertFalse(stale.exists())
    }

}
