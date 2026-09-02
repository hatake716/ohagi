package io.github.hatake716.ohagi.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.data.AppRef
import io.github.hatake716.ohagi.data.HomeItem
import io.github.hatake716.ohagi.data.LayoutState
import io.github.hatake716.ohagi.ui.common.AppIconImage
import io.github.hatake716.ohagi.ui.common.DirectoryPinIcon
import io.github.hatake716.ohagi.ui.common.FilePinIcon
import io.github.hatake716.ohagi.ui.common.IosFolderIcon
import io.github.hatake716.ohagi.ui.common.IosMotion
import io.github.hatake716.ohagi.ui.common.homeMotionKeys
import io.github.hatake716.ohagi.ui.common.rememberIosDragVisualState
import io.github.hatake716.ohagi.ui.common.uprightWithDevice
import io.github.hatake716.ohagi.ui.common.rememberAppIconBitmap
import io.github.hatake716.ohagi.ui.common.rememberFilePinThumbnail
import io.github.hatake716.ohagi.ui.common.rememberAppIconBitmaps
import io.github.hatake716.ohagi.ui.dragdrop.DragPayload
import io.github.hatake716.ohagi.ui.dragdrop.ohagiDragSource
import io.github.hatake716.ohagi.ui.dragdrop.ohagiDropTarget
import io.github.hatake716.ohagi.ui.dragdrop.rememberOhagiDropTarget
import io.github.hatake716.ohagi.ui.theme.Ink
import io.github.hatake716.ohagi.ui.theme.Kome

/**
 * ホーム主画面の固定グリッド。
 * 各セルが公式 Compose D&D の source/target を直接持つため、画面座標による判定は不要。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun HomeGrid(
    home: List<HomeItem?>,
    indexOffset: Int,
    activeDrag: DragPayload?,
    labelOf: (AppRef) -> String,
    onCellTap: (Int, Rect?) -> Unit,
    onCellMenu: (Int) -> Unit,
    /** ページ内セル番号(0..23)とルート座標の矩形。ページ跨ぎドロップの位置解決用。 */
    onCellBounds: (Int, Rect) -> Unit = { _, _ -> },
    onDrop: (Int, DragPayload, Offset, Boolean) -> Boolean,
    canStack: (Int, DragPayload) -> Boolean,
    onDragMoved: (Offset) -> Unit,
    onDragSessionStarted: (DragPayload) -> Unit,
    onDragSessionEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motionKeys = remember(home, indexOffset) { homeMotionKeys(home, indexOffset) }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 6行を領域いっぱいに均等配置し、最下段をドック直上にそろえる。
        val rowHeight = (maxHeight - 8.dp) / LayoutState.HOME_ROWS
        LazyVerticalGrid(
            columns = GridCells.Fixed(LayoutState.HOME_COLUMNS),
            userScrollEnabled = false,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(home, key = { index, _ -> motionKeys[index] }) { index, item ->
                val globalIndex = indexOffset + index
                HomeCell(
                    index = globalIndex,
                    item = item,
                    rowHeight = rowHeight,
                    activeDrag = activeDrag,
                    labelOf = labelOf,
                    isDragging = activeDrag == DragPayload.FromHome(globalIndex),
                    onTap = { bounds -> onCellTap(globalIndex, bounds) },
                    onMenu = { onCellMenu(globalIndex) },
                    onCellBounds = { rect -> onCellBounds(index, rect) },
                    onDrop = { payload, position, stack ->
                        onDrop(globalIndex, payload, position, stack)
                    },
                    canStack = { payload -> canStack(globalIndex, payload) },
                    onDragMoved = onDragMoved,
                    onDragSessionStarted = onDragSessionStarted,
                    onDragSessionEnded = onDragSessionEnded,
                    modifier = Modifier.animateItem(
                        fadeInSpec = IosMotion.itemFadeInSpec,
                        placementSpec = IosMotion.placementSpec,
                        fadeOutSpec = IosMotion.itemFadeOutSpec,
                    ),
                )
            }
        }
    }
}

@Composable
private fun HomeCell(
    index: Int,
    item: HomeItem?,
    rowHeight: Dp,
    activeDrag: DragPayload?,
    labelOf: (AppRef) -> String,
    isDragging: Boolean,
    onTap: (Rect?) -> Unit,
    onMenu: () -> Unit,
    onCellBounds: (Rect) -> Unit = {},
    onDrop: (DragPayload, Offset, Boolean) -> Boolean,
    canStack: (DragPayload) -> Boolean,
    onDragMoved: (Offset) -> Unit,
    onDragSessionStarted: (DragPayload) -> Unit,
    onDragSessionEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    var dropHovered by remember { mutableStateOf(false) }
    var folderReady by remember { mutableStateOf(false) }
    val dragVisual = rememberIosDragVisualState(
        pressed = pressed,
        isDragging = isDragging,
        dropHovered = dropHovered,
        folderReady = folderReady,
        label = "homeCell",
    )
    val haptic = LocalHapticFeedback.current

    val payload = item?.let { DragPayload.FromHome(index) }
    val dragIconApp = (item as? HomeItem.HomeApp)?.app
    val appDragIcon by rememberAppIconBitmap(dragIconApp, HOME_ICON_SIZE)
    // ファイルピンはサムネイルをドラッグ影に使う(表示と同じ Bitmap をキャッシュ共有)。
    val pinDragThumb by rememberFilePinThumbnail(item as? HomeItem.HomeFile, HOME_ICON_SIZE)
    val dragIcon = appDragIcon ?: pinDragThumb
    val folderDragIcons by rememberAppIconBitmaps(
        (item as? HomeItem.HomeFolder)?.apps.orEmpty(),
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
        label = "homeDropHover",
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
        // 空きセル: タップは無反応のまま、長押しでピン追加メニューを開く
        // (ファイル/フォルダの配置導線。ohagiDragSource はドラッグ対象が無いので付けない)。
        Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
            onLongClick = onMenu,
        )
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
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight)
            .onGloballyPositioned { onCellBounds(it.boundsInRoot()) }
            .graphicsLayer {
                scaleX = dragVisual.scale
                scaleY = dragVisual.scale
                alpha = dragVisual.alpha
            }
            .clip(RoundedCornerShape(18.dp))
            .background(hoverColor)
            .ohagiDropTarget(dropTarget)
            .then(sourceModifier),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp, vertical = 8.dp),
        ) {
            HomeCellContent(
                item = item,
                appIcon = dragIcon,
                folderIcons = folderDragIcons,
                labelOf = labelOf,
                folderHighlighted = folderReady,
                onIconBounds = { folderTargetBounds = it },
            )
        }

    }
}

/** セルの中身（アイコン + ラベル / 空きセル）。 */
@Composable
internal fun HomeCellContent(
    item: HomeItem?,
    appIcon: ImageBitmap?,
    folderIcons: List<ImageBitmap?>,
    labelOf: (AppRef) -> String,
    folderHighlighted: Boolean = false,
    onIconBounds: (Rect) -> Unit = {},
) {
    // 端末を横へ倒したときは、セル位置を保ったままアイコンと名称のブロックだけ立て直す。
    when (item) {
        is HomeItem.HomeApp -> UprightCellBlock {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(HOME_ICON_TARGET_SIZE)
                    .onGloballyPositioned { onIconBounds(it.boundsInRoot()) },
            ) {
                AppIconImage(icon = appIcon, size = HOME_ICON_SIZE)
            }
            Spacer(Modifier.height(4.dp))
            HomeLabel(text = labelOf(item.app))
        }
        is HomeItem.HomeFolder -> UprightCellBlock {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(HOME_ICON_TARGET_SIZE)
                    .onGloballyPositioned { onIconBounds(it.boundsInRoot()) },
            ) {
                IosFolderIcon(
                    apps = item.apps,
                    preloadedIcons = folderIcons,
                    size = HOME_ICON_SIZE,
                    highlighted = folderHighlighted,
                )
            }
            Spacer(Modifier.height(4.dp))
            HomeLabel(text = item.name)
        }
        is HomeItem.HomeFile -> {
            val thumbnail by rememberFilePinThumbnail(item, HOME_ICON_SIZE)
            UprightCellBlock {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(HOME_ICON_TARGET_SIZE)
                        .onGloballyPositioned { onIconBounds(it.boundsInRoot()) },
                ) {
                    FilePinIcon(
                        mimeType = item.mimeType,
                        size = HOME_ICON_SIZE,
                        thumbnail = thumbnail,
                    )
                }
                Spacer(Modifier.height(4.dp))
                HomeLabel(text = item.displayName)
            }
        }
        is HomeItem.HomeDirectory -> UprightCellBlock {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(HOME_ICON_TARGET_SIZE)
                    .onGloballyPositioned { onIconBounds(it.boundsInRoot()) },
            ) {
                DirectoryPinIcon(size = HOME_ICON_SIZE)
            }
            Spacer(Modifier.height(4.dp))
            HomeLabel(text = item.displayName)
        }
        null -> {
            Box(
                Modifier
                    .size(HOME_ICON_TARGET_SIZE)
                    .onGloballyPositioned { onIconBounds(it.boundsInRoot()) },
            )
            Spacer(Modifier.height(4.dp))
            HomeLabel(text = "")
        }
    }
}

/** iPhoneの4列ホームに近い、アイコン本体と隣接余白の視覚比率。 */
private val HOME_ICON_SIZE = 60.dp
private val HOME_ICON_TARGET_SIZE = 68.dp
private val FOLDER_PREVIEW_ICON_REQUEST_SIZE = 24.dp

/** アイコン+名称を端末の向きへ立て直す共通ブロック(セル位置は不変)。 */
@Composable
private fun UprightCellBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.uprightWithDevice(),
        content = content,
    )
}

/** 壁紙の上でも読める白 + ドロップシャドウのラベル。空文字なら高さだけ確保。 */
@Composable
private fun HomeLabel(text: String) {
    androidx.compose.material3.Text(
        text = text,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        color = Kome,
        style = androidx.compose.ui.text.TextStyle(
            shadow = Shadow(color = Ink, offset = Offset(0f, 1f), blurRadius = 4f),
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 16.dp),
    )
}
