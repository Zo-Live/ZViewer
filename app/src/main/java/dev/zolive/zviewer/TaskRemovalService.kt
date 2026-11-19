package dev.zolive.zviewer

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.edit
import dev.zolive.zviewer.data.BookRepository
import dev.zolive.zviewer.data.DiagnosticLog
import dev.zolive.zviewer.data.PreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TaskRemovalService : Service() {
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        TaskRemovalCleanup.onTaskRemoved(application)
    }
}

internal object TaskRemovalCleanup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()

    fun track(application: Application, taskId: Int) = scope.launch {
        mutex.withLock {
            val tasks = application.getSharedPreferences("zviewer_task", Context.MODE_PRIVATE)
            val bootCount = Settings.Global.getInt(application.contentResolver, Settings.Global.BOOT_COUNT, -1)
            val previousTask = tasks.getInt("task_id", -1)
            val previousPid = tasks.getInt("pid", 0)
            if (previousTask != taskId && previousTask != -1 && tasks.getInt("boot_count", -1) == bootCount &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            ) {
                val activityManager = application.getSystemService(ActivityManager::class.java)
                val previousExit = activityManager.getHistoricalProcessExitReasons(application.packageName, previousPid, 1)
                    .firstOrNull()
                // 强行停止后，系统可能用新任务替换旧任务卡片。
                if (previousExit?.reason != ApplicationExitInfo.REASON_USER_REQUESTED ||
                    previousExit.description?.startsWith("[FORCE STOP]") != true
                ) {
                    clearRemovedTask(application, previousTask)
                }
            }
            if (previousTask != taskId || previousPid != Process.myPid()) {
                tasks.edit {
                    putInt("task_id", taskId)
                    putInt("boot_count", bootCount)
                    putInt("pid", Process.myPid())
                }
            }
        }
    }

    fun onTaskRemoved(application: Application) = scope.launch {
        mutex.withLock {
            val tasks = application.getSharedPreferences("zviewer_task", Context.MODE_PRIVATE)
            val taskId = tasks.getInt("task_id", -1)
            if (taskId != -1) clearRemovedTask(application, taskId)
        }
    }

    private suspend fun clearRemovedTask(application: Application, taskId: Int) {
        val activityManager = application.getSystemService(ActivityManager::class.java)
        if (activityManager.appTasks.any { it.taskInfo.taskId == taskId }) return

        // 保留旧任务 ID，下次启动时可补做被系统中断的清理。
        val store = PreferenceStore(application)
        val settings = store.settings()
        if (settings.clearProgressOnTaskRemoval) store.clearProgress()
        if (settings.clearCacheOnTaskRemoval) {
            try {
                BookRepository(application).use { it.clearCache(settings.clearCovers) }
            } catch (error: Exception) {
                DiagnosticLog.error("Cache", "移除后台时清理阅读缓存失败", error)
            }
        }
    }
}
