package dev.zolive.zviewer.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** 解析安装包地址后交给系统下载，应用退出后下载也可继续。 */
class UpdateDownloader(private val context: Context) {
    suspend fun enqueue(info: UpdateInfo): Long = withContext(Dispatchers.IO) {
        val url = info.downloadUrl?.takeIf(UpdateChecker::trustedApkUrl)
            ?: throw IOException("此版本暂未提供安装包")
        val manager = context.getSystemService(DownloadManager::class.java)
            ?: throw IOException("系统下载服务不可用")
        val preferences = context.getSharedPreferences("zviewer_updates", Context.MODE_PRIVATE)
        val previousId = preferences.getLong("download_id", -1)
        if (preferences.getString("download_version", null) == info.version && previousId >= 0) {
            manager.query(DownloadManager.Query().setFilterById(previousId))?.use { cursor ->
                if (cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) in
                    setOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED,
                        DownloadManager.STATUS_SUCCESSFUL)) return@withContext previousId
            }
        }
        val id = manager.enqueue(DownloadManager.Request(Uri.parse(url))
            .setTitle("ZViewer ${info.version}")
            .setDescription("下载完成后，点按通知安装更新")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                "ZViewer-${info.version}-${System.currentTimeMillis()}.apk"))
        preferences.edit().putLong("download_id", id).putString("download_version", info.version).apply()
        DiagnosticLog.log("Update", "已提交系统下载 version=${info.version} id=$id")
        id
    }
}
