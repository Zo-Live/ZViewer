package dev.zolive.zviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.penfeizhou.animation.FrameAnimationDrawable
import dev.zolive.zviewer.data.*
import dev.zolive.zviewer.reader.ImageLoader
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class FormatIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository = BookRepository(context)
    private val tree = DocumentsContract.buildTreeDocumentUri("dev.zolive.zviewer.test.documents", "root/书库")

    private fun fixture(name: String): File {
        val file = File(context.cacheDir, "test-$name")
        instrumentation.context.assets.open("formats/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    @Test fun safDiscoversBooksAndFolders() = runBlocking {
        val books = repository.scan(tree)
        assertEquals(8, books.size)
        assertEquals(setOf("cbz", "zip", "7z", "cbr", "rar", "pdf", "folder"), books.map { it.kind }.toSet())
        assertTrue(books.first().title.startsWith("01"))
    }

    @Test fun allArchiveFormatsExtractInNaturalOrderAndCreateLargeCovers() = runBlocking {
        val books = repository.scan(tree).filter { it.kind in archiveExtensions }
        assertEquals(5, books.size)
        for (book in books) {
            val session = repository.open(book)
            assertEquals(book.title, 3, session.pages.size)
            val first = BitmapFactory.decodeFile(repository.pageFile(session, 0, 1200).absolutePath)
            assertNotNull(book.title, first)
            assertEquals(1000, first.width)
            first.recycle()
            val cover = BitmapFactory.decodeFile(repository.cover(book).absolutePath)
            assertNotNull(book.title, cover)
            assertEquals(book.title, 1000, cover.width)
            cover.recycle()
            assertEquals(3, repository.open(book).pages.size)
        }
    }

    @Test fun cachedArchiveUsesPinyinAndKeepsExistingProgressOnTheSameFile() = runBlocking {
        val archive = File.createTempFile("pinyin", ".cbz", context.cacheDir)
        val book = Book(archive.name, Uri.fromFile(archive).toString(), "排序验收", "cbz", 0, 0)
        val image = fixture("static.png").readBytes()
        val oldOrder = listOf("2.png", "10.png", "Alpha.png", "あ10.png", "ア2.png", "中文.mp4", "大连.png",
            "重庆2.png", "重庆10.png")
        val expected = listOf("2.png", "10.png", "Alpha.png", "重庆2.png", "重庆10.png", "大连.png", "中文.mp4",
            "ア2.png", "あ10.png")
        val store = PreferenceStore(context)
        val legacy = context.getSharedPreferences("zviewer", Context.MODE_PRIVATE)
        val id = book.id
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                for (name in oldOrder) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(image)
                    zip.closeEntry()
                }
            }
            repository.open(book).let { session ->
                assertEquals(expected, session.pages.map(PageSource::name))
                repository.close(session)
            }
            legacy.edit().putInt("page_$id", 5).putInt("total_$id", expected.size)
                .putLong("video_${id}_5", 3210L).apply()
            // 删除源文件，验证新版直接用旧格式的 media.json 和已解压文件。
            assertTrue(archive.delete())
            repeat(2) {
                val session = repository.open(book)
                try {
                    assertEquals(expected, session.pages.map(PageSource::name))
                    assertEquals("中文.mp4", session.pages[store.progress(id).page].name)
                    assertEquals(3210L, store.videoPosition(id, 6))
                    assertEquals(0L, store.videoPosition(id, 5))
                } finally { repository.close(session) }
            }
        } finally {
            archive.delete()
            File(context.cacheDir, "books/${book.cacheKey}").deleteRecursively()
            legacy.edit().remove("page_$id").remove("total_$id").remove("video_${id}_5")
                .remove("video_${id}_6").remove("page_order_$id").apply()
        }
    }

    @Test fun pdfAndImageFolderHaveCorrectPages() = runBlocking {
        val books = repository.scan(tree)
        for (book in books.filter { it.kind == "pdf" || it.title.startsWith("07") }) {
            val session = repository.open(book)
            assertEquals(3, session.pages.size)
            for (index in session.pages.indices) {
                val file = repository.pageFile(session, index, 1440)
                val drawable = ImageLoader.load(file, 1440)
                assertTrue(drawable.intrinsicWidth >= 1000)
                assertTrue(drawable.intrinsicHeight > drawable.intrinsicWidth)
            }
            assertTrue(repository.cover(book).length() > 0)
        }
    }

    @Test fun repeatedLoopPagesShareCompleteFileCache() = runBlocking {
        val book = repository.scan(tree).first { it.title.startsWith("07") }
        val session = repository.open(book)
        repository.pageFile(session, 0, 1200).delete()
        val files = List(8) { async { repository.pageFile(session, 0, 1200) } }.awaitAll()
        assertEquals(1, files.toSet().size)
        val bitmap = BitmapFactory.decodeFile(files.first().absolutePath)
        assertNotNull(bitmap)
        assertEquals(1000, bitmap.width)
        bitmap.recycle()
    }

    @Test fun everyStaticFormatDecodes() {
        for (extension in listOf("jpg", "jpeg", "png", "webp", "bmp", "heif", "heic", "avif")) {
            val drawable = ImageLoader.load(fixture("static.$extension"), 1200)
            assertTrue(extension, drawable.intrinsicWidth > 0)
            assertTrue(extension, drawable.intrinsicHeight > 0)
            if (drawable is FrameAnimationDrawable<*>) {
                val bitmap = drawable.frameSeqDecoder.getFrameBitmap(0)
                assertNotNull(extension, bitmap)
                bitmap.recycle()
            }
            (drawable as? Animatable)?.stop()
        }
    }

    @Test fun animatedFormatsProvideAnimationsAndDistinctFrames() {
        for (extension in listOf("apng", "png", "webp", "gif", "avif")) {
            val file = fixture("animated.$extension")
            val drawable = ImageLoader.load(file, 800)
            assertTrue(extension, drawable is Animatable)
            if (drawable is FrameAnimationDrawable<*>) {
                assertTrue(extension, drawable.frameSeqDecoder.frameCount > 1)
                val first = drawable.frameSeqDecoder.getFrameBitmap(0)
                val later = drawable.frameSeqDecoder.getFrameBitmap(4)
                assertFalse(extension, first.sameAs(later))
                first.recycle(); later.recycle()
            }
            (drawable as Animatable).stop()
        }
        assertTrue(ImageLoader.isApng(fixture("animated.png")))
        assertFalse(ImageLoader.isApng(fixture("static.png")))
    }

    @Test fun largeAnimationUsesBoundedCanvasAndPlays() {
        val drawable = ImageLoader.load(fixture("large.apng"), 1200)
        assertEquals(1200, drawable.intrinsicWidth)
        assertEquals(800, drawable.intrinsicHeight)
        val animation = drawable as Animatable
        val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(pixel).apply { scale(1f / 1200, 1f / 800) }
        val colors = mutableSetOf<Int>()
        instrumentation.runOnMainSync {
            drawable.setBounds(0, 0, 1200, 800)
            animation.start()
        }
        try {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (colors.size < 2 && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    pixel.eraseColor(Color.TRANSPARENT)
                    drawable.draw(canvas)
                    val color = pixel.getPixel(0, 0)
                    if (Color.alpha(color) == 255) colors.add(color)
                }
                Thread.sleep(16)
            }
            assertEquals(setOf(Color.rgb(56, 87, 78), Color.rgb(232, 237, 222)), colors)
        } finally {
            instrumentation.runOnMainSync { animation.stop() }
            pixel.recycle()
        }
    }

    @Test fun nativeAnimationsUseViewportWidth() {
        for (extension in listOf("gif", "webp")) {
            val drawable = ImageLoader.load(fixture("animated.$extension"), width = 400, animationWidth = 160)
            assertTrue(extension, drawable is Animatable)
            assertEquals(extension, 160, drawable.intrinsicWidth)
            assertEquals(extension, 240, drawable.intrinsicHeight)
        }
        assertEquals(400, ImageLoader.load(fixture("static.png"), width = 400, animationWidth = 160).intrinsicWidth)
    }

    @Test fun emptyAndBrokenArchivesFailWithoutCreatingPages() = runBlocking {
        for (name in listOf("empty.cbz", "broken.cbz")) {
            val uri = DocumentsContract.buildDocumentUri("dev.zolive.zviewer.test.documents", "root/formats/$name")
            val book = Book(name.stableId(), uri.toString(), name, "cbz", 0, 0)
            val result = runCatching { repository.open(book) }
            assertTrue(name, result.isFailure)
            assertTrue(name, result.exceptionOrNull() is ReaderException || result.exceptionOrNull() is me.zhanghai.android.libarchive.ArchiveException)
        }
    }

    @Test fun archiveWithPdfReportsExtractionPromptEvenWhenMixed() = runBlocking {
        for (name in listOf("mixed-pdf.zip", "mixed-pdf-ascii.zip", "chinese-mixed-pdf.zip")) {
            val uri = DocumentsContract.buildDocumentUri("dev.zolive.zviewer.test.documents", "root/formats/$name")
            val result = runCatching { repository.open(Book(name.stableId(), uri.toString(), name, "zip", 0, 0)) }
            val error = result.exceptionOrNull()
            assertTrue("$name 应提示先解压 PDF，实际：$error",
                error is ReaderException && error.message!!.contains("PDF"))
        }
    }

    @Test fun archiveWithUtf8ChineseNamesExtractsInOrder() = runBlocking {
        val uri = DocumentsContract.buildDocumentUri("dev.zolive.zviewer.test.documents", "root/formats/chinese-images.zip")
        val session = repository.open(Book("chinese-images", uri.toString(), "chinese-images", "zip", 0, 0))
        assertEquals(listOf("第 1 页.png", "第 2 页.png"), session.pages.map { it.name })
    }

    @Test fun archivePathsCannotEscapePrivateCache() = runBlocking {
        val uri = DocumentsContract.buildDocumentUri("dev.zolive.zviewer.test.documents", "root/formats/unsafe.zip")
        val book = Book("unsafe", uri.toString(), "unsafe", "zip", 0, 0)
        val session = repository.open(book)
        assertEquals(1, session.pages.size)
        assertTrue(session.pages.all { File(it.filePath!!).canonicalPath.startsWith(File(context.cacheDir, "books").canonicalPath + "/") })
        assertFalse(File(context.cacheDir, "escape.png").exists())
    }
}
