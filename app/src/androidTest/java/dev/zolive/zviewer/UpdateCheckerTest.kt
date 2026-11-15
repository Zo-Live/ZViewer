package dev.zolive.zviewer

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dev.zolive.zviewer.data.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

internal fun releaseJson(version: String = "9.0.0", assets: Boolean = true): String = """
    {"tag_name":"v$version","draft":false,"prerelease":false,"assets":[
    ${if (assets) """{"name":"ZViewer-$version-release.apk","size":1024,
    "browser_download_url":"https://github.com/Zo-Live/ZViewer/releases/download/v$version/ZViewer-$version-release.apk"}""" else ""}]}
""".trimIndent()

class UpdateCheckerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun clear() {
        context.getSharedPreferences("zviewer_updates", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun cacheSurvivesNewCheckerAndRevalidatesWithEtag() = runBlocking {
        var time = 100_000L
        var calls = 0
        val request: (String?) -> ReleaseResponse = { etag ->
            calls++
            if (calls == 1) ReleaseResponse(200, releaseJson(), "release-etag") else {
                assertEquals("release-etag", etag)
                ReleaseResponse(304)
            }
        }
        val first = UpdateChecker(context, request) { time }
        assertEquals("9.0.0", first.latest(manual = true).version)
        assertEquals("9.0.0", UpdateChecker(context, request) { time }.latest(manual = true).version)
        assertEquals(1, calls)
        time += 61_000
        first.latest(manual = false)
        assertEquals(1, calls)
        first.latest(manual = true)
        assertEquals(2, calls)
        time += 6 * 60 * 60_000
        first.latest(manual = false)
        assertEquals(3, calls)
    }

    @Test fun concurrentChecksShareOneResponse() = runBlocking {
        val calls = AtomicInteger()
        val checker = UpdateChecker(context, { calls.incrementAndGet(); Thread.sleep(150); ReleaseResponse(200, releaseJson()) })
        List(8) { async { checker.latest(manual = true) } }.awaitAll()
        assertEquals(1, calls.get())
    }

    @Test fun rateLimitHonorsRetryAfterAndCanRecover() = runBlocking {
        var time = 100_000L
        var calls = 0
        val checker = UpdateChecker(context, { calls++; if (calls == 1) ReleaseResponse(429, retryAfterSeconds = 120)
            else ReleaseResponse(200, releaseJson()) }) { time }
        assertTrue(runCatching { checker.latest(true) }.exceptionOrNull() is IOException)
        time += 61_000
        assertTrue(runCatching { checker.latest(true) }.isFailure)
        assertEquals(1, calls)
        time += 60_000
        assertEquals("9.0.0", checker.latest(true).version)
        assertEquals(2, calls)
    }

    @Test fun timeoutAndMalformedResponsesNeverBecomeLatestResults() = runBlocking {
        var time = 100_000L
        var calls = 0
        val checker = UpdateChecker(context, {
            calls++
            if (calls == 1) throw SocketTimeoutException("test timeout")
            ReleaseResponse(200, "{broken}")
        }) { time }
        assertTrue(runCatching { checker.latest(true) }.isFailure)
        assertTrue(runCatching { checker.latest(true) }.isFailure)
        assertEquals(1, calls)
        time += 61_000
        assertTrue(runCatching { checker.latest(true) }.isFailure)
        assertEquals(2, calls)
    }

    @Test fun parsesOnlyStableReleasesAndExpectedApk() {
        assertEquals("9.0.0", UpdateChecker.parseRelease(releaseJson()).version)
        assertNotNull(UpdateChecker.parseRelease(releaseJson()).downloadUrl)
        assertNull(UpdateChecker.parseRelease(releaseJson(assets = false)).downloadUrl)
        assertNull(UpdateChecker.parseRelease(releaseJson().replace("github.com/", "evil.example/")).downloadUrl)
        assertNull(UpdateChecker.parseRelease(releaseJson().replace("release.apk", "arm64.apk")).downloadUrl)
        assertTrue(runCatching { UpdateChecker.parseRelease(releaseJson().replace("\"prerelease\":false", "\"prerelease\":true")) }.isFailure)
        assertTrue(runCatching { UpdateChecker.parseRelease(releaseJson().replace("\"draft\":false", "\"draft\":true")) }.isFailure)
    }
}
