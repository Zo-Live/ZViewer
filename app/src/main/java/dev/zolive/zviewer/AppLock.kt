package dev.zolive.zviewer

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import dev.zolive.zviewer.data.PreferenceStore
import dev.zolive.zviewer.data.ReaderSettings

internal class AppLock(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    var locked by mutableStateOf(PreferenceStore(application).settings().verifyOnStartup ||
        savedState.get<Boolean>("locked") == true)
        private set
    private var promptOnResume = locked

    fun backgrounded(pausedAt: Long) {
        savedState["backgrounded_at"] = pausedAt
    }

    fun foregrounded(settings: ReaderSettings) {
        val started = savedState.remove<Long>("backgrounded_at") ?: return
        val now = SystemClock.elapsedRealtime()
        if (!locked && settings.verifyAfterBackground &&
            (now < started || now - started >= settings.autoLockInterval.seconds * 1_000L)) {
            locked = true
            savedState["locked"] = true
            promptOnResume = true
        }
    }

    fun takePromptOnResume(): Boolean {
        val pending = promptOnResume
        promptOnResume = false
        return pending
    }

    fun unlock() {
        locked = false
        savedState.remove<Boolean>("locked")
        savedState.remove<Long>("backgrounded_at")
    }
}
