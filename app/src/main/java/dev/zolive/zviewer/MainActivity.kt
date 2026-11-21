package dev.zolive.zviewer

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
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

    override fun attachBaseContext(newBase: Context) {
        appliedLanguage = PreferenceStore(newBase).language
        super.attachBaseContext(appliedLanguage.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLog.installCrashHandler()
        enableEdgeToEdge()
        val taskPreparation = TaskRemovalCleanup.track(application, taskId)
        lifecycleScope.launch {
            taskPreparation.join()
            setContent {
                val state by model.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.settings.language) {
                    if (state.settings.language != appliedLanguage) recreate()
                }
                val pageState = rememberSaveableStateHolder()
                ZViewerTheme(state.settings) {
                    if (appLock.locked) {
                        AppLockScreen(state.settings.theme, ::verify)
                    } else {
                        pageState.SaveableStateProvider("app") { ZViewerApp(model, state) }
                    }
                }
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
