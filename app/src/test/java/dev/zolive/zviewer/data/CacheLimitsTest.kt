package dev.zolive.zviewer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CacheLimitsTest {
    @Test fun legacyThresholdsRoundUpToTheNextFiniteOption() {
        assertEquals(1536, readLimit(1281))
        assertEquals(5120, readLimit(4352))
        assertEquals(CacheLimits.MAX_MIB, readLimit(CacheLimits.MAX_MIB + 1))
    }

    @Test fun finiteAndUnlimitedThresholdsRoundTripThroughSettings() {
        for (mib in CacheLimits.optionsMiB) {
            val saved = settingsValues(ReaderSettings(cacheLimitMiB = mib))
            assertEquals(mib, readSettings { key, default -> saved.getOrDefault(key, default) }.cacheLimitMiB)
        }
        assertEquals(32L * 1024 * 1024 * 1024, CacheLimits.bytes(CacheLimits.MAX_MIB))
        assertNull(CacheLimits.bytes(CacheLimits.UNLIMITED_MIB))
    }

    private fun readLimit(mib: Int): Int =
        readSettings { key, default -> if (key == "cache_limit_mib") mib else default }.cacheLimitMiB
}
