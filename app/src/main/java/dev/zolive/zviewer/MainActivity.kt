package dev.zolive.zviewer

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.zolive.zviewer.data.AppLanguage
import dev.zolive.zviewer.data.DefaultThemeAccent
import dev.zolive.zviewer.data.DiagnosticLog
import dev.zolive.zviewer.data.PreferenceStore
import dev.zolive.zviewer.ui.AppLockScreen
import dev.zolive.zviewer.ui.ZViewerApp
import dev.zolive.zviewer.ui.ZViewerTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: LibraryViewModel by viewModels()
    private val appLock: AppLock by viewModels()
    private lateinit var appliedLanguage: AppLanguage
    private var verification: CancellationSignal? = null
    private var pausedAt = 0L
    private var startupReadingPending = true
    internal var onVolumePage: ((Int) -> Unit)? = null

    override fun attachBaseContext(newBase: Context) {
        appliedLanguage = PreferenceStore(newBase).language
        super.attachBaseContext(appliedLanguage.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startupReadingPending = savedInstanceState?.getBoolean("startup_reading_pending") ?: true
        DiagnosticLog.installCrashHandler()
        enableEdgeToEdge()
        val taskPreparation = TaskRemovalCleanup.track(application, taskId)
        setRecentPreviewHidden(model.state.value.settings.hideRecentPreview)
        lifecycleScope.launch {
            model.state.map { it.settings.hideRecentCard }.distinctUntilChanged().collect(::setRecentCardHidden)
        }
        lifecycleScope.launch {
            taskPreparation.join()
            setContent {
                val state by model.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.settings.language) {
                    if (state.settings.language != appliedLanguage) recreate()
                }
                val pageState = rememberSaveableStateHolder()
                val locked = appLock.locked
                val themeSettings = if (locked && !state.settings.verificationFollowsTheme) {
                    state.settings.copy(accent = DefaultThemeAccent, dynamicColor = false)
                } else state.settings
                ZViewerTheme(themeSettings) {
                    if (locked) {
                        AppLockScreen(themeSettings, ::verify)
                    } else {
                        LaunchedEffect(Unit) {
                            if (startupReadingPending) {
                                startupReadingPending = false
                                model.resumeReadingOnStartup()
                            }
                        }
                        pageState.SaveableStateProvider("app") { ZViewerApp(model, state) }
                    }
                }
            }
            launch {
                model.state.map { it.settings.hideRecentPreview }.distinctUntilChanged().collect(::setRecentPreviewHidden)
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("startup_reading_pending", startupReadingPending)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        appLock.foregrounded(model.state.value.settings)
    }

    override fun onPostResume() {
        super.onPostResume()
        if (appLock.takePromptOnResume()) verify()
    }

    override fun onResume() {
        super.onResume()
        DiagnosticLog.log("App", "onResume")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val turnPage = onVolumePage ?: return super.onKeyDown(keyCode, event)
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> -1
            KeyEvent.KEYCODE_VOLUME_DOWN -> 1
            else -> return super.onKeyDown(keyCode, event)
        }
        if (event.repeatCount == 0) turnPage(direction)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (onVolumePage != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onPause() {
        pausedAt = SystemClock.elapsedRealtime()
        DiagnosticLog.log("App", "onPause")
        super.onPause()
    }

    override fun onStop() {
        if (!isChangingConfigurations) appLock.backgrounded(pausedAt)
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DiagnosticLog.log("Resources", "内存回收通知 level=$level")
    }

    override fun onDestroy() {
        verification?.cancel()
        verification = null
        DiagnosticLog.log("App", "onDestroy changingConfigurations=$isChangingConfigurations")
        super.onDestroy()
        if (isFinishing) TaskRemovalCleanup.onTaskRemoved(application)
    }

    private fun setRecentPreviewHidden(hidden: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!hidden)
        } else if (hidden) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun setRecentCardHidden(hidden: Boolean) {
        getSystemService(ActivityManager::class.java).appTasks
            .firstOrNull { it.taskInfo.taskId == taskId }
            ?.setExcludeFromRecents(hidden)
    }

    @Suppress("DEPRECATION")
    private fun verify() {
        if (!appLock.locked || verification != null) return
        if (!getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            Toast.makeText(this, R.string.verification_needs_screen_lock, Toast.LENGTH_SHORT).show()
            return
        }
        val builder = BiometricPrompt.Builder(this).setTitle(getString(R.string.verify_to_access))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL)
        } else {
            builder.setDeviceCredentialAllowed(true)
        }
        val signal = CancellationSignal()
        verification = signal
        builder.build().authenticate(signal, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (verification !== signal) return
                verification = null
                appLock.unlock()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (verification === signal) verification = null
            }
        })
    }
}
