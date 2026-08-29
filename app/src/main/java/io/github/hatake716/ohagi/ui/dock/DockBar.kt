package io.github.hatake716.ohagi.ui.dock

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.data.AppRef
import io.github.hatake716.ohagi.data.DockItem
import io.github.hatake716.ohagi.ui.common.AppIconImage
import io.github.hatake716.ohagi.ui.common.IosMoreButton
import io.github.hatake716.ohagi.ui.common.IosFolderIcon
import io.github.hatake716.ohagi.ui.common.animateIosPressScale
import io.github.hatake716.ohagi.ui.common.iosIconShape
import io.github.hatake716.ohagi.ui.common.rememberAppIconBitmap
import io.github.hatake716.ohagi.ui.common.rememberAppIconBitmaps
import io.github.hatake716.ohagi.ui.dragdrop.DragPayload
import io.github.hatake716.ohagi.ui.dragdrop.ohagiDragSource
import io.github.hatake716.ohagi.ui.dragdrop.ohagiDropTarget
import io.github.hatake716.ohagi.ui.dragdrop.rememberOhagiDropTarget
import io.github.hatake716.ohagi.ui.theme.Azuki
import io.github.hatake716.ohagi.ui.theme.AzukiDeep
import io.github.hatake716.ohagi.ui.theme.Kome
import io.github.hatake716.ohagi.ui.theme.TileBorder

/**
 * 画面下部に常時表示するドックバー。
 * 各スロット自身が公式 Compose D&D の source/target になる。
 */
@Composable
fun DockBar(
    dock: List<DockItem?>,
    activeDrag: DragPayload?,
    labelOf: (AppRef) -> String,
    onSlotTap: (Int) -> Unit,
    onSlotMenu: (Int) -> Unit,
    onLauncherTap: () -> Unit,
    onLauncherLongPress: () -> Unit,
    onDrop: (Int, DragPayload, Offset, Boolean) -> Boolean,
    canStack: (Int, DragPayload) -> Boolean,
    onDragMoved: (Offset) -> Unit,
    onDragSessionStarted: (DragPayload) -> Unit,
    onDragSessionEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(30.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp)
            .shadow(
                elevation = 14.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.20f),
                spotColor = Color.Black.copy(alpha = 0.28f),
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x8A27222B), Color(0xA317141A)),
                ),
            )
            .border(0.75.dp, TileBorder.copy(alpha = 0.72f), shape)
            .padding(horizontal = 8.dp),
    ) {
        for (slot in listOf(0, 1)) {
            DockSlot(
                slot = slot,
                item = dock.getOrNull(slot),
                activeDrag = activeDrag,
                labelOf = labelOf,
                isDragging = activeDrag == DragPayload.FromDock(slot),
                onTap = { onSlotTap(slot) },
                onMenu = { onSlotMenu(slot) },
                onDrop = { payload, position, stack ->
                    onDrop(slot, payload, position, stack)
                },
                canStack = { payload -> canStack(slot, payload) },
                onDragMoved = onDragMoved,
                onDragSessionStarted = onDragSessionStarted,
                onDragSessionEnded = onDragSessionEnded,
                modifier = Modifier.weight(1f),
            )
        }
        LauncherButton(
            onTap = onLauncherTap,
            onLongPress = onLauncherLongPress,
        )
        for (slot in listOf(2, 3)) {
            DockSlot(
                slot = slot,
                item = dock.getOrNull(slot),
                activeDrag = activeDrag,
                labelOf = labelOf,
                isDragging = activeDrag == DragPayload.FromDock(slot),
                onTap = { onSlotTap(slot) },
                onMenu = { onSlotMenu(slot) },
                onDrop = { payload, position, stack ->
                    onDrop(slot, payload, position, stack)
                },
                canStack = { payload -> canStack(slot, payload) },
                onDragMoved = onDragMoved,
                onDragSessionStarted = onDragSessionStarted,
                onDragSessionEnded = onDragSessionEnded,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** ドックの1スロット。アプリ／フォルダ／空（+）のいずれかを表示する。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockSlot(
    slot: Int,
    item: DockItem?,
    activeDrag: DragPayload?,
    labelOf: (AppRef) -> String,
    isDragging: Boolean,
    onTap: () -> Unit,
    onMenu: () -> Unit,
    onDrop: (DragPayload, Offset, Boolean) -> Boolean,
    canStack: (DragPayload) -> Boolean,
    onDragMoved: (Offset) -> Unit,
    onDragSessionStarted: (DragPayload) -> Unit,
    onDragSessionEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = when (item) {
        is DockItem.DockApp -> labelOf(item.app)
        is DockItem.DockFolder -> item.name
        null -> stringResource(R.string.dock_slot_empty)
    }

    var pressed by remember { mutableStateOf(false) }
    val scale = animateIosPressScale(
        pressed = pressed,
        label = "dockSlotScale",
    )
    val haptic = LocalHapticFeedback.current

    val payload = item?.let { DragPayload.FromDock(slot) }
    val dragIconApp = when (item) {
        is DockItem.DockApp -> item.app
        is DockItem.DockFolder -> item.apps.firstOrNull()
        null -> null
    }
    val dragIcon by rememberAppIconBitmap(dragIconApp)
    val folderDragIcons by rememberAppIconBitmaps(
        (item as? DockItem.DockFolder)?.apps.orEmpty(),
    )

    var dropHovered by remember { mutableStateOf(false) }
    var folderTargetBounds by remember { mutableStateOf<Rect?>(null) }
    var folderReady by remember { mutableStateOf(false) }
    val stackCandidate = activeDrag?.let(canStack) == true
    val hoverColor by animateColorAsState(
        targetValue = when {
            folderReady -> Color.White.copy(alpha = 0.17f)
            dropHovered -> Azuki.copy(alpha = 0.22f)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 120),
        label = "dockDropHover",
    )
    val dropTarget = rememberOhagiDropTarget(
        onStarted = onDragSessionStarted,
        onEntered = { dropHovered = true },
        onMoved = { position ->
            folderReady = stackCandidate &&
                folderTargetBounds?.contains(position) == true
            onDragMoved(position)
        },
        onExited = {
            dropHovered = false
            folderReady = false
        },
        onEnded = {
            dropHovered = false
            folderReady = false
            onDragSessionEnded()
        },
        onDrop = { dropped, position ->
            dropHovered = false
            val stack = canStack(dropped) &&
                folderTargetBounds?.contains(position) == true
            folderReady = false
            onDrop(dropped, position, stack)
        },
    )

    val sourceModifier = if (payload == null) {
        Modifier
    } else {
        Modifier.ohagiDragSource(
            payload = payload,
            icon = dragIcon,
            folderIcons = folderDragIcons,
            onTap = onTap,
            onPressChanged = { pressed = it },
            onDragStarted = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDragSessionStarted(payload)
            },
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(hoverColor)
            .ohagiDropTarget(dropTarget),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .alpha(if (isDragging) 0.20f else 1f)
                .size(64.dp)
                .onGloballyPositioned { folderTargetBounds = it.boundsInRoot() }
                .then(sourceModifier)
                .semantics { contentDescription = description },
        ) {
            when (item) {
                is DockItem.DockApp -> AppIconImage(icon = dragIcon, size = 52.dp)
                is DockItem.DockFolder -> IosFolderIcon(
                    apps = item.apps,
                    size = 52.dp,
                    highlighted = folderReady,
                    preloadedIcons = folderDragIcons,
                )
                null -> Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = Kome.copy(alpha = 0.35f),
                    modifier = Modifier.size(26.dp),
                )
            }

            IosMoreButton(
                contentDescription = stringResource(R.string.action_more),
                onClick = onMenu,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // 48dpへ拡張されるアクセシブルなタッチ領域を、
                    // アイコン中央の起動領域と重ねないよう外上方へ寄せる。
                    .offset(x = 6.dp, y = (-6).dp),
                size = 20.dp,
            )
        }
    }
}

/** 中央ランチャーボタン。タップでドロワー、長押しで分割起動設定。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LauncherButton(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateIosPressScale(
        pressed = pressed,
        pressedScale = 0.93f,
        label = "launcherScale",
    )
    val haptic = LocalHapticFeedback.current
    val size = 56.dp
    val shape = iosIconShape(size)
    val launcherDescription = stringResource(R.string.dock_open_drawer)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = 5.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.24f),
                spotColor = AzukiDeep.copy(alpha = 0.36f),
            )
            .size(size)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(Azuki.copy(alpha = 0.98f), AzukiDeep.copy(alpha = 0.98f)),
                ),
            )
            .border(0.75.dp, Color.White.copy(alpha = 0.24f), shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onTap,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            )
            .semantics { contentDescription = launcherDescription },
    ) {
        LauncherGridGlyph()
    }
}

/** Apple固有アセットを使わず、App Libraryを連想できる3x3グリッドを自前描画する。 */
@Composable
private fun LauncherGridGlyph() {
    Canvas(Modifier.size(27.dp)) {
        val tile = size.minDimension * 0.20f
        val gap = size.minDimension * 0.105f
        val contentSize = tile * 3f + gap * 2f
        val startX = (size.width - contentSize) / 2f
        val startY = (size.height - contentSize) / 2f
        for (row in 0..2) {
            for (column in 0..2) {
                drawRoundRect(
                    color = Kome.copy(alpha = if (row == 1 && column == 1) 1f else 0.9f),
                    topLeft = Offset(
                        x = startX + column * (tile + gap),
                        y = startY + row * (tile + gap),
                    ),
                    size = Size(tile, tile),
                    cornerRadius = CornerRadius(tile * 0.28f, tile * 0.28f),
                )
            }
        }
    }
}
