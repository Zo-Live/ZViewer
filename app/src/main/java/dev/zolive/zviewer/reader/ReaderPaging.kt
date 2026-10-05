package dev.zolive.zviewer.reader

class ReaderPaging(val pageCount: Int, loop: Boolean) {
    init { require(pageCount > 0) }

    val looping = loop && pageCount > 1
    val itemCount = if (looping) Int.MAX_VALUE / pageCount * pageCount else pageCount

    fun pageAt(item: Int): Int = Math.floorMod(item, pageCount)

    fun anchor(page: Int): Int = if (looping) (itemCount / pageCount / 2) * pageCount + page else page

    fun destination(target: Int): Int = if (looping) Math.floorMod(target, pageCount) else target.coerceIn(0, pageCount - 1)

    fun nearestItem(page: Int, currentItem: Int): Int {
        if (!looping) return destination(page)
        val sameCycle = currentItem.toLong() - pageAt(currentItem) + destination(page)
        return listOf(sameCycle - pageCount, sameCycle, sameCycle + pageCount)
            .filter { it in 0L until itemCount.toLong() }
            .minBy { kotlin.math.abs(it - currentItem) }.toInt()
    }
}
