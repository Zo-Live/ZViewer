package dev.zolive.zviewer.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.core.content.edit
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dev.zolive.zviewer.BuildConfig
import dev.zolive.zviewer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

sealed interface UpdateDownload {
    val version: String

    data class Pending(override val version: String, val waiting: Boolean) : UpdateDownload
    data class Ready(override val version: String, val id: Long, val uri: Uri, val launchAutomatically: Boolean) : UpdateDownload
    data class Failed(override val version: String, val id: Long, val reason: Int? = null) : UpdateDownload
}

/** 系统下载服务负责传输，应用在返回前台后恢复任务状态。 */
class UpdateDownloader(private val context: Context) {
    private val preferences = context.getSharedPreferences("zviewer_updates", Context.MODE_PRIVATE)

    suspend fun current(): UpdateDownload? = withContext(Dispatchers.IO) {
        tasks.withLock { readDownload() }
    }

    suspend fun enqueue(info: UpdateInfo): Long = withContext(Dispatchers.IO) {
        val url = info.downloadUrl?.takeIf(UpdateChecker::trustedApkUrl)
            ?: throw IOException("此版本暂未提供安装包")
        val version = AppVersion.parse(info.version)
            ?.takeIf { it > installedVersion }?.toString() ?: throw IOException("安装包版本无效")
        tasks.withLock {
            val manager = manager()
            val previous = readDownload()
            val previousId = preferences.getLong("download_id", -1)
            if (previous?.version == version && (previous is UpdateDownload.Pending || previous is UpdateDownload.Ready)) {
                return@withLock previousId
            }
            val fileName = "ZViewer-$version-${UUID.randomUUID()}.apk"
            val id = manager.enqueue(DownloadManager.Request(url.toUri())
                .setTitle("ZViewer $version")
                .setDescription(PreferenceStore(context).language.localizedContext(context).getString(R.string.update_download_description))
                .setMimeType(APK_MIME_TYPE)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "updates/$fileName"))
            if (id < 0) throw IOException("系统下载服务未创建任务")
            val previousFile = preferences.getString("download_file", null)
            if (!preferences.edit().putLong("download_id", id).putString("download_version", version)
                    .putString("download_file", fileName).remove("install_offered_id").commit()) {
                removeDownload(manager, id, fileName)
                throw IOException("无法保存更新下载任务")
            }
            if (previousId >= 0) removeDownload(manager, previousId, previousFile)
            DiagnosticLog.log("Update", "已提交系统下载 version=$version id=$id")
            id
        }
    }

    fun markInstallationOffered(id: Long) {
        preferences.edit { putLong("install_offered_id", id) }
    }

    private fun readDownload(): UpdateDownload? {
        val id = preferences.getLong("download_id", -1)
        if (id < 0) return null
        val version = preferences.getString("download_version", null)?.let(AppVersion::parse)
        val fileName = preferences.getString("download_file", null)
        if (version == null || version <= installedVersion) {
            // 新版安装后清理应用专属目录里的 APK，保留旧版本放在公共下载目录里的文件。
            if (fileName != null) removeDownload(context.getSystemService(DownloadManager::class.java), id, fileName)
            preferences.edit {
                remove("download_id")
                remove("download_version")
                remove("download_file")
                remove("install_offered_id")
            }
            return null
        }
        val name = version.toString()
        return try {
            val manager = manager()
            manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (!cursor.moveToFirst()) return@use UpdateDownload.Failed(name, id)
                when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED -> UpdateDownload.Pending(name, waiting = true)
                    DownloadManager.STATUS_RUNNING -> UpdateDownload.Pending(name, waiting = false)
                    DownloadManager.STATUS_SUCCESSFUL -> ready(manager, name, id, fileName)
                    else -> UpdateDownload.Failed(name, id, cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
                }
            } ?: UpdateDownload.Failed(name, id)
        } catch (error: Exception) {
            DiagnosticLog.error("Update", "无法读取下载任务 version=$name id=$id", error)
            UpdateDownload.Failed(name, id)
        }
    }

    private fun ready(manager: DownloadManager, version: String, id: Long, fileName: String?): UpdateDownload.Ready {
        val apk = updateFile(fileName ?: "ZViewer-$version-$id.apk")
        if (fileName == null && !apk.isFile) {
            // 旧版本的下载在公共目录，通过任务提供的文件描述符转入应用专属目录。
            val directory = checkNotNull(apk.parentFile)
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建更新目录")
            val partial = File(directory, "${apk.name}.part")
            try {
                ParcelFileDescriptor.AutoCloseInputStream(manager.openDownloadedFile(id)).use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
                if (!partial.renameTo(apk)) throw IOException("无法保存安装包")
            } finally {
                partial.delete()
            }
            preferences.edit { putString("download_file", apk.name) }
        }
        val archive = context.packageManager.getPackageArchiveInfo(apk.path, 0)
        if (archive?.packageName != context.packageName || archive.longVersionCode <= BuildConfig.VERSION_CODE ||
            archive.versionName?.let(AppVersion::parse)?.toString() != version) {
            throw IOException("下载的安装包缺失或版本不匹配")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return UpdateDownload.Ready(version, id, uri, preferences.getLong("install_offered_id", -1) != id)
    }

    private fun manager(): DownloadManager = context.getSystemService(DownloadManager::class.java)
        ?: throw IOException("系统下载服务不可用")

    private fun updateFile(name: String): File {
        val downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("更新目录不可用")
        return File(downloads, "updates/$name")
    }

    private fun removeDownload(manager: DownloadManager?, id: Long, fileName: String?) {
        try {
            manager?.remove(id)
            if (fileName != null) updateFile(fileName).delete()
        } catch (error: Exception) {
            DiagnosticLog.error("Update", "清理下载任务失败 id=$id", error)
        }
    }

    companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private val installedVersion = AppVersion.parse(BuildConfig.VERSION_NAME)!!
        private val tasks = Mutex()
    }
}
