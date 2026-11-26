package dev.zolive.zviewer.data

internal object CacheLimits {
    const val MIN_MIB = 128
    const val DEFAULT_MIB = 1536
    const val MAX_MIB = 32768
    const val UNLIMITED_MIB = Int.MAX_VALUE

    val optionsMiB = listOf(
        MIN_MIB, 256, 384, 640,
        1024, 1280, DEFAULT_MIB, 1792,
        2048, 2560, 3072, 3584,
        4096, 5120, 6144, 7168,
        8192, 10240, 12288, 14336,
        16384, 20480, 24576,
        MAX_MIB, UNLIMITED_MIB,
    )

    fun normalize(mib: Int): Int {
        if (mib == UNLIMITED_MIB) return mib
        // 旧版阈值向上对齐，避免升级后提前触发清理。
        val limit = mib.coerceAtMost(MAX_MIB)
        return optionsMiB.first { it >= limit }
    }

    fun bytes(mib: Int): Long? =
        normalize(mib).takeUnless { it == UNLIMITED_MIB }?.let { it.toLong() * 1024 * 1024 }
}
