package io.github.hatake716.ohagi.ui.dock

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.hatake716.ohagi.LocalGraph
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.data.DockItem
import io.github.hatake716.ohagi.ui.common.AppIcon
import io.github.hatake716.ohagi.ui.theme.Azuki
import io.github.hatake716.ohagi.ui.theme.AzukiDeep
import io.github.hatake716.ohagi.ui.theme.Kome
import io.github.hatake716.ohagi.ui.theme.PanelScrim
import io.github.hatake716.ohagi.ui.theme.PanelScrimLight
import io.github.hatake716.ohagi.ui.theme.TileBorder

/**
 * 画面下部のドックバー。
 * [スロット0][スロット1][中央ランチャーボタン][スロット2][スロット3] の横一列で、
 * 壁紙の上に浮かぶ半透明パネルとして表示される。
 * 各スロットの意味付け(起動/フォルダを開く/割り当て)はホスト側が判断する。
 */
@Composable
fun DockBar(
    dock: List<DockItem?>,
    onSlotTap: (Int) -> Unit,
    onSlotLongPress: (Int) -> Unit,
    onLauncherTap: () -> Unit,
    onLauncherLongPress: () -> Unit,
    onSwapDock: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val movedThresholdPx = remember(density) { with(density) { 12.dp.toPx() } }

    // ドラッグ状態(DockBar 内に閉じる)
    var draggingSlot by remember { mutableStateOf<Int?>(null) }
    var fingerPos by remember { mutableStateOf(Offset.Zero) }
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var draggedCellSize by remember { mutableStateOf(Offset.Zero) }
    // 各スロットの矩形(DockBar ルート座標)。ドロップ先算出に使う。
    val slotBounds = remember { mutableStateMapOf<Int, Rect>() }

    val shape = RoundedCornerShape(28.dp)
    Box(modifier = modifier.onGloballyPositioned { rootCoords = it }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(shape)
                // バー自体は半透明にして壁紙を透かす。中央のランチャーボタン(Azuki)は
                // 別レイヤで不透明のまま残るため、そのまま強調される。
                .background(PanelScrim.copy(alpha = 0.5f))
                .border(1.dp, TileBorder, shape)
                .padding(horizontal = 8.dp),
        ) {
            for (slot in listOf(0, 1)) {
                DockSlot(
                    slot = slot,
                    item = dock.getOrNull(slot),
                    isDragging = draggingSlot == slot,
                    onTap = { onSlotTap(slot) },
                    onLongPressNoMove = { onSlotLongPress(slot) },
                    onSlotBounds = { rect -> slotBounds[slot] = rect },
                    onDragStart = { pos, size -> draggingSlot = slot; fingerPos = pos; draggedCellSize = size },
                    onDragMove = { pos -> fingerPos = pos },
                    onDragEnd = {
                        val from = draggingSlot
                        if (from != null) {
                            val to = slotBounds.entries.firstOrNull { it.value.contains(fingerPos) }?.key
                            if (to != null && to != from) onSwapDock(from, to)
                        }
                        draggingSlot = null
                    },
                    onDragCancel = { draggingSlot = null },
                    rootCoords = { rootCoords },
                    movedThresholdPx = movedThresholdPx,
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
                    isDragging = draggingSlot == slot,
                    onTap = { onSlotTap(slot) },
                    onLongPressNoMove = { onSlotLongPress(slot) },
                    onSlotBounds = { rect -> slotBounds[slot] = rect },
                    onDragStart = { pos, size -> draggingSlot = slot; fingerPos = pos; draggedCellSize = size },
                    onDragMove = { pos -> fingerPos = pos },
                    onDragEnd = {
                        val from = draggingSlot
                        if (from != null) {
                            val to = slotBounds.entries.firstOrNull { it.value.contains(fingerPos) }?.key
                            if (to != null && to != from) onSwapDock(from, to)
                        }
                        draggingSlot = null
                    },
                    onDragCancel = { draggingSlot = null },
                    rootCoords = { rootCoords },
                    movedThresholdPx = movedThresholdPx,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 浮遊コピー(ドラッグ中のみ)。Box を掴んだセルの実寸に固定し、
        // その半分でオフセットして中身の中心を指位置に厳密一致させる。
        val dragSlot = draggingSlot
        if (dragSlot != null) {
            val dragItem = dock.getOrNull(dragSlot)
            val cellW = with(density) { draggedCellSize.x.toDp() }
            val cellH = with(density) { draggedCellSize.y.toDp() }
            Box(
                modifier = Modifier
                    .size(cellW, cellH)
                    .graphicsLayer {
                        translationX = fingerPos.x - draggedCellSize.x / 2f
                        translationY = fingerPos.y - draggedCellSize.y / 2f
                        alpha = 0.9f
                        scaleX = 1.1f
                        scaleY = 1.1f
                    },
                contentAlignment = Alignment.Center,
            ) {
                when (dragItem) {
                    is DockItem.DockApp -> AppIcon(app = dragItem.app, size = 52.dp)
                    is DockItem.DockFolder -> FolderPreview(folder = dragItem)
                    null -> Unit
                }
            }
        }
    }
}

/** ドックの 1 スロット。アプリ/フォルダ/空(+)のいずれかを表示する。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockSlot(
    slot: Int,
    item: DockItem?,
    isDragging: Boolean,
    onTap: () -> Unit,
    onLongPressNoMove: () -> Unit,
    onSlotBounds: (Rect) -> Unit,
    onDragStart: (fingerPos: Offset, cellSize: Offset) -> Unit,
    onDragMove: (fingerPos: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    rootCoords: () -> LayoutCoordinates?,
    movedThresholdPx: Float,
    modifier: Modifier = Modifier,
) {
    val graph = LocalGraph.current
    // TalkBack 向けのスロット説明(アプリ名/フォルダ名/空きスロット)。
    val apps by graph.appRepository.apps.collectAsState()
    val description = when (item) {
        is DockItem.DockApp -> remember(item, apps) { graph.appRepository.labelOf(item.app) }
        is DockItem.DockFolder -> item.name
        null -> stringResource(R.string.dock_slot_empty)
    }

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 520f),
        label = "dockSlotScale",
    )

    // pointerInput が乗る内側 Box の座標(ローカル座標→root 座標変換の基準)
    var innerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var totalDrag by remember { mutableStateOf(Offset.Zero) }

    fun toDock(local: Offset): Offset {
        val root = rootCoords() ?: return local
        val inner = innerCoords ?: return local
        return root.localPositionOf(inner, local)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .onGloballyPositioned { coords ->
                // スロットの当たり判定矩形は外側 Box(セル幅全体)を root 座標で記録する。
                val root = rootCoords()
                if (root != null) {
                    val topLeft = root.localPositionOf(coords, Offset.Zero)
                    onSlotBounds(
                        Rect(topLeft, androidx.compose.ui.geometry.Size(
                            coords.size.width.toFloat(), coords.size.height.toFloat(),
                        ))
                    )
                }
            },
    ) {
        val draggable = item is DockItem.DockApp || item is DockItem.DockFolder
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                // 内側 Box の座標を取得(toDock の変換基準)
                .onGloballyPositioned { innerCoords = it }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .alpha(if (isDragging) 0.35f else 1f)
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                // 長押し→ドラッグ。空きスロットはドラッグ対象にしない(動かしてもメニュー扱い)。
                .pointerInput(item) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { local ->
                            totalDrag = Offset.Zero
                            if (draggable) {
                                val size = Offset(this.size.width.toFloat(), this.size.height.toFloat())
                                onDragStart(toDock(local), size)
                            }
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            totalDrag += delta
                            if (draggable) onDragMove(toDock(change.position))
                        },
                        onDragEnd = {
                            if (!draggable || totalDrag.getDistance() < movedThresholdPx) {
                                onDragCancel()
                                onLongPressNoMove()
                            } else {
                                onDragEnd()
                            }
                        },
                        onDragCancel = { onDragCancel() },
                    )
                }
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onTap,
                )
                .semantics { contentDescription = description },
        ) {
            when (item) {
                is DockItem.DockApp -> AppIcon(app = item.app, size = 52.dp)
                is DockItem.DockFolder -> FolderPreview(folder = item)
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

/** フォルダスロットの 2x2 ミニグリッドプレビュー。空フォルダはフォルダアイコンを表示する。 */
@Composable
private fun FolderPreview(folder: DockItem.DockFolder) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(52.dp)
            .clip(shape)
            .background(PanelScrimLight)
            .border(1.dp, TileBorder, shape),
    ) {
        if (folder.apps.isEmpty()) {
            Icon(
                imageVector = Icons.Rounded.Folder,
                contentDescription = null,
                tint = Kome.copy(alpha = 0.7f),
                modifier = Modifier.size(24.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                folder.apps.take(4).chunked(2).forEach { rowApps ->
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        rowApps.forEach { app ->
                            AppIcon(app = app, size = 20.dp)
                        }
                    }
                }
            }
        }
    }
}

/** 中央のランチャーボタン。タップでドロワー、長押しでホームメニューを開く。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LauncherButton(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 520f),
        label = "launcherScale",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // 影で少し浮いた印象にする
            .shadow(
                elevation = 8.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = AzukiDeep,
                spotColor = AzukiDeep,
            )
            .size(56.dp)
            .clip(CircleShape)
            .background(Azuki)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onTap,
                onLongClick = onLongPress,
            ),
    ) {
        Icon(
            imageVector = Icons.Rounded.Apps,
            contentDescription = stringResource(R.string.dock_open_drawer),
            tint = Kome,
            modifier = Modifier.size(26.dp),
        )
    }
}
