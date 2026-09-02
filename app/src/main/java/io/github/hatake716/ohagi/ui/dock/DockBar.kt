package io.github.hatake716.ohagi.ui.dock

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import io.github.hatake716.ohagi.data.LayoutState
import io.github.hatake716.ohagi.ui.common.AppIconImage
import io.github.hatake716.ohagi.ui.common.IosFolderIcon
import io.github.hatake716.ohagi.ui.common.IosMotion
import io.github.hatake716.ohagi.ui.common.dockMotionKeys
import io.github.hatake716.ohagi.ui.common.rememberIosDragVisualState
import io.github.hatake716.ohagi.ui.common.uprightWithDevice
import io.github.hatake716.ohagi.ui.common.rememberAppIconBitmap
import io.github.hatake716.ohagi.ui.common.rememberAppIconBitmaps
import io.github.hatake716.ohagi.ui.dragdrop.DragPayload
import io.github.hatake716.ohagi.ui.dragdrop.ohagiDragSource
import io.github.hatake716.ohagi.ui.dragdrop.ohagiDropTarget
import io.github.hatake716.ohagi.ui.dragdrop.rememberOhagiDropTarget
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
    onSlotTap: (Int, Rect?) -> Unit,
    onSlotMenu: (Int) -> Unit,
    onDrop: (Int, DragPayload, Offset, Boolean) -> Boolean,
    canStack: (Int, DragPayload) -> Boolean,
    onDragMoved: (Offset) -> Unit,
    onDragSessionStarted: (DragPayload) -> Unit,
    onDragSessionEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(30.dp)
    val slots = remember(dock) {
        List(LayoutState.DOCK_SLOT_COUNT) { slot -> dock.getOrNull(slot) }
    }
    val motionKeys = remember(slots) { dockMotionKeys(slots) }
    BoxWithConstraints(
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
            .border(0.75.dp, TileBorder.copy(alpha = 0.72f), shape),
    ) {
        val slotWidth = (maxWidth - 16.dp) / LayoutState.DOCK_SLOT_COUNT
        LazyRow(
            userScrollEnabled = false,
            contentPadding = PaddingValues(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
        ) {
            itemsIndexed(
                items = slots,
                key = { slot, _ -> motionKeys[slot] },
            ) { slot, item ->
                DockSlot(
                    slot = slot,
                    item = item,
                    activeDrag = activeDrag,
                    labelOf = labelOf,
                    isDragging = activeDrag == DragPayload.FromDock(slot),
                    onTap = { bounds -> onSlotTap(slot, bounds) },
                    onMenu = { onSlotMenu(slot) },
                    onDrop = { payload, position, stack ->
                        onDrop(slot, payload, position, stack)
                    },
                    canStack = { payload -> canStack(slot, payload) },
                    onDragMoved = onDragMoved,
                    onDragSessionStarted = onDragSessionStarted,
                    onDragSessionEnded = onDragSessionEnded,
                    modifier = Modifier
                        .width(slotWidth)
                        .fillMaxHeight()
                        .animateItem(
                            fadeInSpec = IosMotion.itemFadeInSpec,
                            placementSpec = IosMotion.placementSpec,
                            fadeOutSpec = IosMotion.itemFadeOutSpec,
                        ),
                )
            }
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
    onTap: (Rect?) -> Unit,
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
    var dropHovered by remember { mutableStateOf(false) }
    var folderReady by remember { mutableStateOf(false) }
    val dragVisual = rememberIosDragVisualState(
        pressed = pressed,
        isDragging = isDragging,
        dropHovered = dropHovered,
        folderReady = folderReady,
        label = "dockSlot",
    )
    val haptic = LocalHapticFeedback.current

    val payload = item?.let { DragPayload.FromDock(slot) }
    val dragIconApp = (item as? DockItem.DockApp)?.app
    val dragIcon by rememberAppIconBitmap(dragIconApp, DOCK_ICON_SIZE)
    val folderDragIcons by rememberAppIconBitmaps(
        (item as? DockItem.DockFolder)?.apps.orEmpty(),
        size = FOLDER_PREVIEW_ICON_REQUEST_SIZE,
    )

    var folderTargetBounds by remember { mutableStateOf<Rect?>(null) }
    val stackCandidate = activeDrag?.let(canStack) == true
    val hoverColor by animateColorAsState(
        targetValue = when {
            folderReady -> Color.White.copy(alpha = 0.18f)
            dropHovered -> Color.White.copy(alpha = 0.10f)
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
            val accepted = onDrop(dropped, position, stack)
            if (accepted) dragVisual.settle(stack)
            accepted
        },
    )

    val sourceModifier = if (payload == null) {
        // 空きスロット(+表示)。メニューボタン(⋯)撤去後の導線として、
        // タップで割り当てメニューを開けるようにする。
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onTap(null) }
    } else {
        Modifier.ohagiDragSource(
            payload = payload,
            icon = dragIcon,
            folderIcons = folderDragIcons,
            onTap = { onTap(folderTargetBounds) },
            onPressChanged = { pressed = it },
            onLift = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
            onDragStarted = { onDragSessionStarted(payload) },
            onLongPressMenu = onMenu,
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
                    scaleX = dragVisual.scale
                    scaleY = dragVisual.scale
                    alpha = dragVisual.alpha
                }
                .size(64.dp)
                .onGloballyPositioned { folderTargetBounds = it.boundsInRoot() }
                .then(sourceModifier)
                .semantics { contentDescription = description },
        ) {
            // 端末を横へ倒したときは、スロット位置を保ったままアイコンだけ立て直す。
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.uprightWithDevice(),
            ) {
                when (item) {
                    is DockItem.DockApp -> AppIconImage(icon = dragIcon, size = DOCK_ICON_SIZE)
                    is DockItem.DockFolder -> IosFolderIcon(
                        apps = item.apps,
                        size = DOCK_ICON_SIZE,
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
            }

        }
    }
}

private val DOCK_ICON_SIZE = 52.dp
private val FOLDER_PREVIEW_ICON_REQUEST_SIZE = 24.dp
