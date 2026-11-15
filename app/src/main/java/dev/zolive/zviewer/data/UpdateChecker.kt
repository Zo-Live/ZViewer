package dev.zolive.zviewer.data

import android.content.Context
import dev.zolive.zviewer.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 只接受正式版本号，避免草稿、预发布或不规范标签被误判为更新。 */
data class AppVersion(val major: Long, val minor: Long, val patch: Long) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int = compareValuesBy(this, other,
        AppVersion::major, AppVersion::minor, AppVersion::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        fun parse(value: String): AppVersion? {
            val match = Regex("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(value.trim()) ?: return null
            val parts = match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
            return AppVersion(parts[0], parts[1], parts[2])
        }
    }
}

data class UpdateInfo(val version: String, val downloadUrl: String?)
data class ReleaseResponse(val status: Int, val body: String = "", val etag: String? = null,
    val retryAfterSeconds: Long? = null)

/** 缓存可跨进程复用；短期手动检查合并，自动检查降低 GitHub 请求频率。 */
class UpdateChecker(
    context: Context,
    private val request: (String?) -> ReleaseResponse = ::requestGitHubRelease,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val preferences = context.getSharedPreferences("zviewer_updates", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    suspend fun latest(manual: Boolean, refresh: Boolean = false): UpdateInfo = withContext(Dispatchers.IO) {
        mutex.withLock {
            val time = now()
            val cached = preferences.getString("release", null)?.let { runCatching { parseRelease(it) }.getOrNull() }
            val age = time - preferences.getLong("checked_at", 0)
            val ttl = if (manual) 60_000L else 6 * 60 * 60_000L
            if (!refresh && cached != null && age in 0 until ttl) {
                DiagnosticLog.log("Update", "使用版本缓存 version=${cached.version} ageMs=$age")
                return@withLock cached
            }
            val retryAt = preferences.getLong("retry_at", 0)
            if (retryAt > time && retryAt - time <= 60 * 60_000L) throw IOException("更新检查暂时不可用，请稍后重试")
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                val response = request(if (cached != null) preferences.getString("etag", null) else null)
                DiagnosticLog.log("Update", "检查完成 http=${response.status} elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
                val info = when (response.status) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> cached ?: throw IOException("版本缓存不可用")
                    HttpURLConnection.HTTP_OK -> parseRelease(response.body)
                    else -> {
                        val seconds = (response.retryAfterSeconds ?: 60).coerceIn(60, 3600)
                        preferences.edit().putLong("retry_at", time + seconds * 1000).apply()
                        throw IOException("版本服务响应异常：${response.status}")
                    }
                }
                val editor = preferences.edit().putLong("checked_at", now()).remove("retry_at")
                if (response.status == HttpURLConnection.HTTP_OK) {
                    editor.putString("release", response.body).putString("etag", response.etag)
                }
                editor.apply()
                info
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (preferences.getLong("retry_at", 0) <= time) {
                    preferences.edit().putLong("retry_at", time + 60_000).apply()
                }
                DiagnosticLog.error("Update", "检查更新失败 elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}", error)
                throw error
            }
        }
    }

    companion object {
        fun parseRelease(body: String): UpdateInfo {
            val release = JSONObject(body)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) throw IOException("不是正式发布版本")
            val version = AppVersion.parse(release.optString("tag_name")) ?: throw IOException("版本号格式不可识别")
            val assets = release.optJSONArray("assets")
            val expected = "ZViewer-$version-release.apk"
            val download = (0 until (assets?.length() ?: 0)).asSequence().map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name") == expected && it.optLong("size") > 0 }
                ?.optString("browser_download_url")?.takeIf { trustedApkUrl(it) }
            return UpdateInfo(version.toString(), download)
        }

        fun trustedApkUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null &&
                uri.port == -1 && uri.query == null && uri.fragment == null &&
                uri.path.startsWith("/Zo-Live/ZViewer/releases/download/") && uri.path.endsWith(".apk") &&
                uri.normalize().path == uri.path
        }.getOrDefault(false)
    }
}

private val requestTimer = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "update-timeout").apply { isDaemon = true }
}

/** 连接 5 秒、单次读取 8 秒，总请求 15 秒；限制响应大小，关闭所有连接。 */
private fun requestGitHubRelease(etag: String?): ReleaseResponse {
    val connection = (URL("https://api.github.com/repos/Zo-Live/ZViewer/releases/latest")
        .openConnection() as HttpURLConnection).apply {
        connectTimeout = 5_000
        readTimeout = 8_000
        instanceFollowRedirects = false
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        setRequestProperty("User-Agent", "ZViewer/${BuildConfig.VERSION_NAME}")
        if (etag != null) setRequestProperty("If-None-Match", etag)
    }
    val timeout = requestTimer.schedule({ connection.disconnect() }, 15, TimeUnit.SECONDS)
    try {
        val status = connection.responseCode
        val body = if (status == HttpURLConnection.HTTP_OK) connection.inputStream.use { stream ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > 512 * 1024) throw IOException("版本信息过大")
                bytes.write(buffer, 0, count)
            }
            bytes.toString("UTF-8")
        } else ""
        return ReleaseResponse(status, body, connection.getHeaderField("ETag"),
            connection.getHeaderField("Retry-After")?.toLongOrNull())
    } finally {
        timeout.cancel(false)
        connection.disconnect()
    }
}
