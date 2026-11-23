package dev.zolive.zviewer.ui

import android.view.WindowManager
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import dev.zolive.zviewer.R
import dev.zolive.zviewer.data.AnimationEffect
import dev.zolive.zviewer.data.AnimationSpeed

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
    AppAlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        title = title,
        text = text,
        dismissButton = dismissButton,
        resizeForKeyboard = resizeForKeyboard,
        moderateAnimation = R.style.SettingsDialogAnimation,
    )
}

@Suppress("DEPRECATION")
@Composable
internal fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    resizeForKeyboard: Boolean = false,
    moderateAnimation: Int? = null,
) {
    val animation = if (animationSpeed(AnimationEffect.Dialog) == AnimationSpeed.Enhanced) {
        R.style.FastDialogAnimation
    } else {
        moderateAnimation
    }
    val hapticsEnabled = LocalHapticsEnabled.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        text = text,
        icon = icon,
        confirmButton = {
            val view = LocalView.current
            val window = (view.parent as? DialogWindowProvider)?.window
            val defaultWindowAnimation = remember(window) { window?.attributes?.windowAnimations }
            val windowAnimation = animation ?: defaultWindowAnimation
            SideEffect {
                view.isHapticFeedbackEnabled = hapticsEnabled
                if (window != null) {
                    val attributes = window.attributes
                    if (windowAnimation != null && attributes.windowAnimations != windowAnimation) {
                        window.setWindowAnimations(windowAnimation)
                    }
                    if (resizeForKeyboard && attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
                        != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    ) {
                        // 由浮动窗口处理系统栏和键盘的可用空间，避免进场时重新居中。
                        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    }
                }
            }
            confirmButton()
        },
        dismissButton = dismissButton,
    )
}
