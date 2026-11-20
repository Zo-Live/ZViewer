package dev.zolive.zviewer.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import dev.zolive.zviewer.R

@Composable
internal fun SettingsAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect {
                if (window != null && window.attributes.windowAnimations != R.style.SettingsDialogAnimation) {
                    window.setWindowAnimations(R.style.SettingsDialogAnimation)
                }
            }
            title()
        },
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
    )
}
