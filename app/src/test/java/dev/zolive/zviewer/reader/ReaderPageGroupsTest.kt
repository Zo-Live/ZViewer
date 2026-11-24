package dev.zolive.zviewer.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPageGroupsTest {
    @Test fun separatesEachVideoFromConsecutiveImages() {
        val groups = ReaderPageGroups(listOf(false, false, true, true, false, false), looping = false)
        assertEquals(listOf(2, 1, 1, 2), (0 until groups.size).map(groups::pageCount))
        assertEquals(listOf(0, 0, 1, 2, 3, 3), (0..5).map(groups::groupAt))
    }

    @Test fun loopingJoinsImagesAcrossTheBookBoundary() {
        val groups = ReaderPageGroups(listOf(false, false, true, false, true, false), looping = true)
        val group = groups.groupAt(0)
        assertEquals(3, groups.pageCount(group))
        assertEquals(listOf(5, 0, 1), (0..2).map { groups.pageAt(group, it) })
        for (page in 0..5) {
            assertEquals(page, groups.pageAt(groups.groupAt(page), groups.offsetOf(page)))
        }
    }
}
