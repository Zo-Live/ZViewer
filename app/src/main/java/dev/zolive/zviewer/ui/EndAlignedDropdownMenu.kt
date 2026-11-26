package dev.zolive.zviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import dev.zolive.zviewer.data.AnimationEffect

@Composable
internal fun EndAlignedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchor: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    openAbove: Boolean = false,
    animationEffect: AnimationEffect = AnimationEffect.DropdownMenu,
    content: @Composable ColumnScope.() -> Unit,
) {
    val anchorHeight = if (openAbove) {
        val windowHeight = LocalWindowInfo.current.containerSize.height
        // 保持定位基准的顶部贴着按钮，底部延伸到窗口之外，让菜单在上方布局。
        Modifier.wrapContentHeight(Alignment.Top, unbounded = true)
            .height(with(LocalDensity.current) { windowHeight.toDp() })
    } else {
        Modifier.fillMaxHeight()
    }
    Box {
        anchor()
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            Box(anchorHeight.width(0.dp)) {
                WithAnimationSpeed(animationSpeed(animationEffect), modifier = Modifier.fillMaxHeight()) {
                    Box(Modifier.fillMaxHeight()) {
                        DropdownMenu(expanded, onDismissRequest, modifier, content = content)
                    }
                }
            }
        }
    }
}
