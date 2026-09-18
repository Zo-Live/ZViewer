package dev.zolive.zviewer

import android.graphics.BitmapFactory
import android.graphics.drawable.Animatable
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.penfeizhou.animation.FrameAnimationDrawable
import dev.zolive.zviewer.data.*
import dev.zolive.zviewer.reader.ImageLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
        assertTrue(drawable is Animatable)
        instrumentation.runOnMainSync {
            drawable.setBounds(0, 0, 1200, 800)
            (drawable as Animatable).start()
        }
        Thread.sleep(500)
        instrumentation.runOnMainSync { (drawable as Animatable).stop() }
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

    @Test fun archivePathsCannotEscapePrivateCache() = runBlocking {
        val uri = DocumentsContract.buildDocumentUri("dev.zolive.zviewer.test.documents", "root/formats/unsafe.zip")
        val book = Book("unsafe", uri.toString(), "unsafe", "zip", 0, 0)
        val session = repository.open(book)
        assertEquals(1, session.pages.size)
        assertTrue(session.pages.all { File(it.filePath!!).canonicalPath.startsWith(File(context.cacheDir, "books").canonicalPath + "/") })
        assertFalse(File(context.cacheDir, "escape.png").exists())
    }
}
