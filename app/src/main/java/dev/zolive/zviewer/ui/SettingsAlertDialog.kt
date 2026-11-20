package dev.zolive.zviewer.ui

import android.view.WindowManager
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import dev.zolive.zviewer.R

@Suppress("DEPRECATION")
@Composable
internal fun SettingsAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    resizeForKeyboard: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect {
                if (window != null) {
                    val attributes = window.attributes
                    if (attributes.windowAnimations != R.style.SettingsDialogAnimation) {
                        window.setWindowAnimations(R.style.SettingsDialogAnimation)
                    }
                    if (resizeForKeyboard && attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
                        != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    ) {
                        // 由浮动窗口处理系统栏和键盘的可用空间，避免进场时重新居中。
                        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    }
                }
            }
            title()
        },
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
    )
}
