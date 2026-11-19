package dev.zolive.zviewer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.zolive.zviewer.data.DiagnosticLog
import dev.zolive.zviewer.ui.ZViewerApp
import dev.zolive.zviewer.ui.ZViewerTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: LibraryViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.zolive.zviewer.data.DiagnosticLog.installCrashHandler()
        enableEdgeToEdge()
        val taskPreparation = TaskRemovalCleanup.track(application, taskId)
        lifecycleScope.launch {
            taskPreparation.join()
            setContent {
                val state by model.state.collectAsStateWithLifecycle()
                ZViewerTheme(state.settings) { ZViewerApp(model, state) }
            }
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.state.map { it.settings.clearCacheOnTaskRemoval || it.settings.clearProgressOnTaskRemoval }
                    .distinctUntilChanged().collect { enabled ->
                        val service = Intent(this@MainActivity, TaskRemovalService::class.java)
                        if (enabled) startService(service) else stopService(service)
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DiagnosticLog.log("App", "onResume")
    }

    override fun onPause() {
        DiagnosticLog.log("App", "onPause")
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DiagnosticLog.log("Resources", "内存回收通知 level=$level")
    }

    override fun onDestroy() {
        DiagnosticLog.log("App", "onDestroy changingConfigurations=$isChangingConfigurations")
        super.onDestroy()
        if (isFinishing) TaskRemovalCleanup.onTaskRemoved(application)
    }
}
