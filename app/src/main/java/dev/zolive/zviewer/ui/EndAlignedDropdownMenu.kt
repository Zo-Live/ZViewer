package dev.zolive.zviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun EndAlignedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchor: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box {
        anchor()
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            // 以触发控件的整个高度和末端作为定位基准。
            Box(Modifier.fillMaxHeight().width(0.dp)) {
                DropdownMenu(expanded, onDismissRequest, modifier, content = content)
            }
        }
    }
}
