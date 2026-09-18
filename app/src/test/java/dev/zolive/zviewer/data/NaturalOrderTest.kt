package dev.zolive.zviewer.data

import org.junit.Assert.*
import org.junit.Test

class NaturalOrderTest {
    @Test fun pageNumbersUseNaturalOrderWithoutOverflow() {
        val pages = listOf("第100000000000000000000000页.png", "第10页.png", "第2页.png", "第1页.png")
        assertEquals(listOf(pages[3], pages[2], pages[1], pages[0]), pages.sortedWith(NaturalOrder))
    }

    @Test fun nestedPathsRemainDeterministic() {
        val pages = listOf("chapter10/1.jpg", "chapter2/10.jpg", "chapter2/2.jpg")
        assertEquals(listOf(pages[2], pages[1], pages[0]), pages.sortedWith(NaturalOrder))
        assertEquals(0, NaturalOrder.compare("01.png", "01.png"))
        assertTrue(NaturalOrder.compare("a00002.png", "a10.png") < 0)
    }

    @Test fun metadataAndUnsupportedFilesAreNotPages() {
        assertFalse(isImage("__MACOSX/cover.jpg"))
        assertFalse(isImage("chapter/.hidden.png"))
        assertFalse(isImage("chapter\\._page.jpg"))
        assertFalse(isImage("page.txt"))
        listOf("JPG", "jpeg", "png", "apng", "webp", "bmp", "heif", "heic", "gif", "avif")
            .forEach { assertTrue(isImage("漫画/1.$it")) }
    }

    @Test fun contentChangesInvalidateCoverCache() {
        val book = Book("book", "content://book", "漫画", "cbz", 1, 20)
        assertNotEquals(book.cacheKey, book.copy(modified = 2).cacheKey)
        assertNotEquals(book.cacheKey, book.copy(size = 30).cacheKey)
        assertEquals(book.cacheKey, book.copy(title = "新书名").cacheKey)
    }
}
