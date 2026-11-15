package dev.zolive.zviewer.data

import org.junit.Assert.*
import org.junit.Test

class AppVersionTest {
    @Test fun versionsUseNumericComponentsRatherThanLexicalOrdering() {
        assertTrue(AppVersion.parse("1.10.0")!! > AppVersion.parse("1.9.99")!!)
        assertTrue(AppVersion.parse("2.0.0")!! > AppVersion.parse("1.99.99")!!)
        assertTrue(AppVersion.parse("1.6.2")!! > AppVersion.parse("1.6.1")!!)
        assertEquals(AppVersion.parse("v1.6.1"), AppVersion.parse("1.6.1"))
        assertEquals(AppVersion.parse("1.6.1"), AppVersion.parse(" V1.6.1 "))
    }

    @Test fun rejectsAmbiguousPrereleaseAndOverflowVersions() {
        listOf("", "1.2", "release-1.2.3", "1.2.3.4", "1.2.3-beta.1", "1.2.3+build", "1.-2.3",
            "999999999999999999999.2.3").forEach { assertNull(it, AppVersion.parse(it)) }
    }

    @Test fun apkUrlsStayWithinTheOfficialReleaseRepository() {
        assertTrue(UpdateChecker.trustedApkUrl("https://github.com/Zo-Live/ZViewer/releases/download/v2.0.0/ZViewer-2.0.0-release.apk"))
        listOf("http://github.com/Zo-Live/ZViewer/releases/download/v2/app.apk",
            "https://github.com.evil.example/Zo-Live/ZViewer/releases/download/v2/app.apk",
            "https://github.com/other/ZViewer/releases/download/v2/app.apk",
            "https://github.com/Zo-Live/ZViewer/releases/download/../../other.apk",
            "https://user@github.com/Zo-Live/ZViewer/releases/download/v2/app.apk",
            "https://github.com/Zo-Live/ZViewer/releases/download/v2/app.apk?target=other",
            "https://github.com/Zo-Live/ZViewer/releases/download/v2/app.txt").forEach {
            assertFalse(it, UpdateChecker.trustedApkUrl(it))
        }
    }
}
