package io.github.hatake716.ohagi.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hatake716.ohagi.data.AppRef

/**
 * iPhoneのホームフォルダを意識した3×3プレビュー。
 * 先頭ページの最大9アプリを、半透明の角丸コンテナ内へ並べる。
 */
@Composable
fun IosFolderIcon(
    apps: List<AppRef>,
    size: Dp,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    preloadedIcons: List<ImageBitmap?>? = null,
) {
    val shape = iosIconShape(size)
    val scale by animateFloatAsState(
        // セル全体のtarget拡大と組み合わせ、合成後がおよそ1.12倍に収まる値。
        targetValue = if (highlighted) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 520f),
        label = "iosFolderTargetScale",
    )
    val borderColor by animateColorAsState(
        targetValue = if (highlighted) {
            Color.White.copy(alpha = 0.72f)
        } else {
            Color.White.copy(alpha = 0.22f)
        },
        animationSpec = tween(durationMillis = 130),
        label = "iosFolderBorder",
    )
    val miniSize = size * 0.205f
    val spacing = size * 0.055f

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = size * 0.045f,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.20f),
                spotColor = Color.Black.copy(alpha = 0.26f),
            )
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = if (highlighted) {
                        listOf(Color(0xB3FFFFFF), Color(0x8AC9CAD0))
                    } else {
                        listOf(Color(0x8FFFFFFF), Color(0x703F4148))
                    },
                ),
            )
            .border(0.75.dp, borderColor, shape)
            .padding(size * 0.10f),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            repeat(3) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    repeat(3) { column ->
                        val app = apps.getOrNull(row * 3 + column)
                        if (app == null) {
                            Spacer(Modifier.size(miniSize))
                        } else if (preloadedIcons != null) {
                            AppIconImage(
                                icon = preloadedIcons.getOrNull(row * 3 + column),
                                size = miniSize,
                            )
                        } else {
                            AppIcon(app = app, size = miniSize)
                        }
                    }
                }
            }
        }
    }
}
