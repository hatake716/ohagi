package io.github.hatake716.ohagi.ui.common

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hatake716.ohagi.ui.theme.Ink
import io.github.hatake716.ohagi.ui.theme.Kome
import kotlinx.coroutines.launch

/** iOS のホームアイコンマスクに近づけるための丸角比率。 */
const val IOS_ICON_CORNER_RATIO = 0.2237f

const val IOS_PRESSED_SCALE = 0.94f

/** 長押しで持ち上げた後、元位置に残す半透明のプレースホルダー。 */
const val IOS_DRAG_SOURCE_ALPHA = 0.14f

data class IosDragVisualState(
    val scale: Float,
    val alpha: Float,
    /** ドロップ成立時に、着地点を一度だけ弾ませて等倍へ収束させる。 */
    val settle: (emphasized: Boolean) -> Unit,
)

fun iosIconShape(size: Dp) = RoundedCornerShape(size * IOS_ICON_CORNER_RATIO)

val IosSheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)

/**
 * 指が触れた瞬間は短く沈み、離したときだけ軽いスプリングで戻す。
 * 大きく跳ねさせず、頻繁な操作でも待ち時間を感じない設定にする。
 */
@Composable
fun animateIosPressScale(
    pressed: Boolean,
    label: String,
    pressedScale: Float = IOS_PRESSED_SCALE,
): Float = animateFloatAsState(
    targetValue = if (pressed) pressedScale else 1f,
    animationSpec = if (pressed) {
        tween(durationMillis = 85, easing = FastOutLinearInEasing)
    } else {
        spring(dampingRatio = 0.82f, stiffness = 780f)
    },
    label = label,
).value

/**
 * iOSホームのD&Dに近い、沈み込み → lift → target拡大 → 着地の共通モーション。
 *
 * ドラッグ中の元アイコンは小さく薄く残し、指下のOS drag decorationを主役にする。
 * 通常targetは控えめに、フォルダ化targetは一段大きくし、成立時は低減衰の
 * springで等倍へ戻す。各セルが独自の無限アニメーションを持たないため、
 * ページ数が増えても待機中のCPU/RAM負荷は増えにくい。
 */
@Composable
fun rememberIosDragVisualState(
    pressed: Boolean,
    isDragging: Boolean,
    dropHovered: Boolean = false,
    folderReady: Boolean = false,
    label: String,
): IosDragVisualState {
    val interactionScale by animateFloatAsState(
        targetValue = when {
            isDragging -> 0.87f
            folderReady -> 1.065f
            dropHovered -> 1.04f
            pressed -> IOS_PRESSED_SCALE
            else -> 1f
        },
        animationSpec = when {
            pressed && !isDragging && !dropHovered ->
                tween(durationMillis = 85, easing = FastOutLinearInEasing)
            isDragging -> spring(dampingRatio = 0.84f, stiffness = 760f)
            folderReady -> spring(dampingRatio = 0.68f, stiffness = 430f)
            dropHovered -> spring(dampingRatio = 0.76f, stiffness = 560f)
            else -> spring(dampingRatio = 0.64f, stiffness = 620f)
        },
        label = "${label}InteractionScale",
    )
    val sourceAlpha by animateFloatAsState(
        targetValue = if (isDragging) IOS_DRAG_SOURCE_ALPHA else 1f,
        animationSpec = tween(durationMillis = if (isDragging) 90 else 140),
        label = "${label}SourceAlpha",
    )
    val landingScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val settle = remember(landingScale, scope) {
        { emphasized: Boolean ->
            scope.launch {
                landingScale.stop()
                // フォルダtargetはinteraction側ですでに約1.12倍なので、着地pulseを重ねすぎない。
                landingScale.snapTo(if (emphasized) 1.02f else 1.065f)
                landingScale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = 0.56f, stiffness = 520f),
                )
            }
            Unit
        }
    }

    return IosDragVisualState(
        scale = interactionScale * landingScale.value,
        alpha = sourceAlpha,
        settle = settle,
    )
}

/** iOS の ellipsis.circle に近い、独自描画の横三点メニューボタン。 */
@Composable
fun IosMoreButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateIosPressScale(
        pressed = pressed,
        pressedScale = 0.88f,
        label = "iosMoreButtonScale",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(Ink.copy(alpha = 0.44f))
            .border(0.5.dp, Color.White.copy(alpha = 0.16f), CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
    ) {
        Canvas(Modifier.size(size * 0.48f)) {
            val dotRadius = this.size.minDimension * 0.09f
            val centerY = this.size.height / 2f
            val gap = this.size.width * 0.30f
            for (offset in -1..1) {
                drawCircle(
                    color = Kome.copy(alpha = 0.96f),
                    radius = dotRadius,
                    center = androidx.compose.ui.geometry.Offset(
                        x = this.size.width / 2f + offset * gap,
                        y = centerY,
                    ),
                )
            }
        }
    }
}

/** ナビゲーションバー上で使う、半透明の丸いシンボルボタン。 */
@Composable
fun IosGlassIconButton(
    imageVector: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateIosPressScale(
        pressed = pressed,
        pressedScale = 0.9f,
        label = "iosGlassButtonScale",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(Ink.copy(alpha = 0.36f))
            .border(0.5.dp, Color.White.copy(alpha = 0.20f), CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
                role = Role.Button
            },
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = Kome,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}

/** iOS の検索フィールドに寄せた、塗り型の丸角検索欄。 */
@Composable
fun IosSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearContentDescription: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        placeholder = { androidx.compose.material3.Text(placeholder) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = if (value.isNotEmpty()) {
            {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Kome.copy(alpha = 0.14f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onValueChange("") }
                        .semantics {
                            contentDescription = clearContentDescription
                            role = Role.Button
                        },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        } else {
            null
        },
        shape = RoundedCornerShape(15.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Kome,
            unfocusedTextColor = Kome,
            cursorColor = Kome,
            focusedContainerColor = Kome.copy(alpha = 0.14f),
            unfocusedContainerColor = Kome.copy(alpha = 0.10f),
            focusedBorderColor = Kome.copy(alpha = 0.36f),
            unfocusedBorderColor = Kome.copy(alpha = 0.16f),
            focusedLeadingIconColor = Kome.copy(alpha = 0.72f),
            unfocusedLeadingIconColor = Kome.copy(alpha = 0.62f),
            focusedTrailingIconColor = Kome.copy(alpha = 0.78f),
            unfocusedTrailingIconColor = Kome.copy(alpha = 0.70f),
            focusedPlaceholderColor = Kome.copy(alpha = 0.58f),
            unfocusedPlaceholderColor = Kome.copy(alpha = 0.52f),
        ),
        modifier = modifier,
    )
}
