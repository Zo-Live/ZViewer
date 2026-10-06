package dev.zolive.zviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zolive.zviewer.data.DiagnosticLog
import dev.zolive.zviewer.ui.ZViewerApp
import dev.zolive.zviewer.ui.ZViewerTheme

class MainActivity : ComponentActivity() {
    private val model: LibraryViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.zolive.zviewer.data.DiagnosticLog.installCrashHandler()
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            ZViewerTheme(state.settings) { ZViewerApp(model, state) }
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
}
