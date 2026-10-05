package dev.zolive.zviewer

import android.graphics.BitmapFactory
import android.graphics.Color
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zolive.zviewer.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = BookRepository(context)

    private fun book(name: String, kind: String): Book {
        val tree = DocumentsContract.buildTreeDocumentUri("dev.zolive.zviewer.test.documents", "root/formats")
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "root/formats/$name")
        return Book(name.stableId(), uri.toString(), name, kind, 0, 0)
    }

    @Test fun folderAndArchiveCoversUseFirstVideoInNaturalOrder() = runBlocking {
        for (book in listOf(book("video-folder", "video-folder"), book("videos.zip", "zip"))) {
            File(context.cacheDir, "covers/${book.cacheKey}.jpg").delete()
            val cover = repository.cover(book)
            val bitmap = BitmapFactory.decodeFile(cover.absolutePath)
            assertNotNull(bitmap)
            assertTrue(bitmap.width > bitmap.height)
            assertTrue(maxOf(bitmap.width, bitmap.height) <= 1000)
            val center = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            assertTrue(Color.red(center) > 200)
            assertTrue(Color.blue(center) < 50)
            bitmap.recycle()
            assertEquals(cover, repository.cover(book))
            assertFalse(File(context.cacheDir, "covers/${book.cacheKey}.source").exists())
            val session = repository.open(book)
            assertEquals(2, session.pages.size)
            assertTrue(session.pages.all { it.isVideo })
            assertTrue(session.pages.first().name.endsWith("video2.mp4"))
        }
    }
}
