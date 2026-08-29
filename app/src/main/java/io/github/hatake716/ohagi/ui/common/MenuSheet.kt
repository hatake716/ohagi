package io.github.hatake716.ohagi.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.hatake716.ohagi.ui.theme.Ink
import io.github.hatake716.ohagi.ui.theme.Kome

/** ボトムシートメニューの 1 項目 */
data class MenuEntry(
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 共通のボトムシートメニュー。
 * header にはタイトルやアプリ情報などの任意コンテンツを表示できる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(
    entries: List<MenuEntry>,
    onDismiss: () -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = IosSheetShape,
        containerColor = Ink.copy(alpha = 0.92f),
        contentColor = Kome,
        scrimColor = Color.Black.copy(alpha = 0.32f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            if (header != null) {
                header()
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    color = Color(0x22FFFFFF),
                )
            }
            entries.forEachIndexed { index, entry ->
                val interactionSource = remember(entry.label) { MutableInteractionSource() }
                val pressed by interactionSource.collectIsPressedAsState()
                val scale = animateIosPressScale(
                    pressed = pressed,
                    pressedScale = 0.99f,
                    label = "menuRowScale",
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            alpha = if (pressed) 0.76f else 1f
                        }
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                        ) {
                            onDismiss()
                            entry.onClick()
                        }
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                ) {
                    if (entry.icon != null) {
                        Icon(
                            imageVector = entry.icon,
                            contentDescription = null,
                            tint = if (entry.destructive) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(18.dp))
                    }
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (entry.destructive) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (index != entries.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 64.dp, end = 20.dp),
                        color = Color.White.copy(alpha = 0.08f),
                    )
                }
            }
        }
    }
}
