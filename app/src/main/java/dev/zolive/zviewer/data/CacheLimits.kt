package dev.zolive.zviewer.data

internal object CacheLimits {
    const val MIN_MIB = 256
    const val MAX_MIB = 8192
    const val STEP_MIB = 256
    const val DEFAULT_MIB = 1536

    fun normalize(mib: Int): Int = mib.coerceIn(MIN_MIB, MAX_MIB) / STEP_MIB * STEP_MIB
    fun bytes(mib: Int): Long = normalize(mib).toLong() * 1024 * 1024
}
