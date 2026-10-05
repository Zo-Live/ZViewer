package dev.zolive.zviewer.reader

import org.junit.Assert.*
import org.junit.Test

class ReaderPagingTest {
    @Test fun imageBoundariesAreAdjacentInBothDirections() {
        val paging = ReaderPaging(3, true)
        val first = paging.anchor(0)
        assertEquals(2, paging.pageAt(first - 1))
        assertEquals(0, paging.pageAt(first + 3))
        assertEquals(first - 1, paging.nearestItem(2, first))
        assertEquals(first + 3, paging.nearestItem(0, first + 2))
    }

    @Test fun repeatedLoopsKeepRealPageNumbers() {
        val paging = ReaderPaging(7, true)
        for (offset in -1000..1000) {
            assertEquals(Math.floorMod(offset, 7), paging.pageAt(paging.anchor(0) + offset))
        }
    }

    @Test fun disabledLoopAndSinglePageHaveFiniteBounds() {
        val paging = ReaderPaging(3, false)
        assertEquals(3, paging.itemCount)
        assertEquals(0, paging.destination(-1))
        assertEquals(2, paging.destination(3))
        assertEquals(1, ReaderPaging(1, true).itemCount)
        assertEquals(0, ReaderPaging(1, true).destination(1))
    }

    @Test fun largeBooksAndFarJumpsStayWithinIntegerBounds() {
        val paging = ReaderPaging(20_000, true)
        assertTrue(paging.anchor(19_999) in 0 until paging.itemCount)
        assertEquals(19_999, paging.destination(-1))
        assertEquals(0, paging.destination(20_000))
        assertEquals(0, paging.pageAt(paging.nearestItem(0, paging.itemCount - 1)))
        assertTrue(paging.nearestItem(19_999, 0) in 0 until paging.itemCount)
    }
}
