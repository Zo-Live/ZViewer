package dev.zolive.zviewer.reader

/** 连续阅读时，相邻图片共用一个滚动区域，每个视频单独停靠。 */
internal class ReaderPageGroups(private val videos: List<Boolean>, looping: Boolean) {
    // 首尾都是图片时，从第一个视频开始分组，让跨越首尾的图片也连续排列。
    private val start = if (looping && !videos.first() && !videos.last()) videos.indexOf(true) else 0
    private val groupStarts = mutableListOf<Int>()
    private val groups = IntArray(videos.size)

    init {
        var previousWasVideo = true
        for (offset in videos.indices) {
            val page = (start + offset) % videos.size
            val video = videos[page]
            if (video || previousWasVideo) groupStarts.add(offset)
            groups[page] = groupStarts.lastIndex
            previousWasVideo = video
        }
    }

    val size: Int get() = groupStarts.size

    fun groupAt(page: Int): Int = groups[page]

    fun offsetOf(page: Int): Int = Math.floorMod(page - start, videos.size) - groupStarts[groupAt(page)]

    fun pageAt(group: Int, offset: Int = 0): Int = (start + groupStarts[group] + offset) % videos.size

    fun pageCount(group: Int): Int = (groupStarts.getOrNull(group + 1) ?: videos.size) - groupStarts[group]
}
