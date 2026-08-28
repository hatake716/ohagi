package io.github.hatake716.ohagi.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.ohagi.LocalGraph
import io.github.hatake716.ohagi.data.HomeItem
import io.github.hatake716.ohagi.data.LayoutState
import io.github.hatake716.ohagi.ui.common.AppIcon
import io.github.hatake716.ohagi.ui.dragdrop.DragController
import io.github.hatake716.ohagi.ui.dragdrop.DragOrigin
import io.github.hatake716.ohagi.ui.theme.Ink
import io.github.hatake716.ohagi.ui.theme.Kome

/**
 * ホーム主画面のアイコングリッド(固定 [LayoutState.HOME_CELL_COUNT] セル)。
 * ドラッグ状態は共有 [DragController] に委譲し、各セルは矩形を報告し、長押しで
 * ドラッグ開始をトリガーするだけ。指の追従・浮遊アイコン・ドロップ確定は親が担う。
 *
 * @param drag        共有ドラッグ状態
 * @param rootCoords  親(HomeScreen ルート Box)の座標。矩形/指位置をルート座標へ変換
 * @param onCellTap   セルのタップ(アプリなら起動、空きは無反応)
 * @param onCellLongPressNoMove セルを長押しして動かさず離したとき(メニュー)
 * @param onDrop      ドラッグを離したとき(親が commitDrop する)
 */
@Composable
fun HomeGrid(
    home: List<HomeItem?>,
    drag: DragController,
    rootCoords: () -> LayoutCoordinates?,
    onCellTap: (Int) -> Unit,
    onCellLongPressNoMove: (Int) -> Unit,
    onDrop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val movedThresholdPx = remember(density) { with(density) { 12.dp.toPx() } }

    // 6 行を領域いっぱいに均等配置し、最下段がドック直上まで来るようにする。
    // (LazyVerticalGrid は行が上詰めになり下に余白が残るため、各行の高さを固定する)
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val rowHeight = (maxHeight - 8.dp) / LayoutState.HOME_ROWS
        LazyVerticalGrid(
            columns = GridCells.Fixed(LayoutState.HOME_COLUMNS),
            userScrollEnabled = false,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(home, key = { i, _ -> i }) { index, item ->
                HomeCell(
                    index = index,
                    item = item,
                    isDragging = drag.isSource(DragOrigin.Home(index)),
                    drag = drag,
                    rootCoords = rootCoords,
                    movedThresholdPx = movedThresholdPx,
                    rowHeight = rowHeight,
                    onTap = { onCellTap(index) },
                    onLongPressNoMove = { onCellLongPressNoMove(index) },
                    onDrop = onDrop,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeCell(
    index: Int,
    item: HomeItem?,
    isDragging: Boolean,
    drag: DragController,
    rootCoords: () -> LayoutCoordinates?,
    movedThresholdPx: Float,
    rowHeight: androidx.compose.ui.unit.Dp,
    onTap: () -> Unit,
    onLongPressNoMove: () -> Unit,
    onDrop: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "homeCellScale",
    )

    var cellCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var totalDrag by remember { mutableStateOf(Offset.Zero) }

    fun toRoot(local: Offset): Offset {
        val root = rootCoords() ?: return local
        val cell = cellCoords ?: return local
        return root.localPositionOf(cell, local)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .onGloballyPositioned { coords ->
                cellCoords = coords
                val root = rootCoords()
                if (root != null) {
                    val topLeft = root.localPositionOf(coords, Offset.Zero)
                    drag.reportHomeCell(
                        index,
                        Rect(topLeft, Size(coords.size.width.toFloat(), coords.size.height.toFloat())),
                    )
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .alpha(if (isDragging) 0.35f else 1f)
            .clip(RoundedCornerShape(18.dp))
            .pointerInput(item) {
                val draggable = item is HomeItem.HomeApp || item is HomeItem.HomeFolder
                detectDragGesturesAfterLongPress(
                    onDragStart = { local ->
                        totalDrag = Offset.Zero
                        if (draggable) {
                            val size = Offset(this.size.width.toFloat(), this.size.height.toFloat())
                            drag.startHome(index, item, toRoot(local), size)
                        }
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        totalDrag += delta
                        if (draggable) drag.move(toRoot(change.position))
                    },
                    onDragEnd = {
                        if (!draggable || totalDrag.getDistance() < movedThresholdPx) {
                            if (draggable) drag.reset()
                            onLongPressNoMove()
                        } else {
                            onDrop()
                        }
                    },
                    onDragCancel = { drag.reset() },
                )
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onTap,
            )
            .padding(horizontal = 2.dp, vertical = 8.dp),
    ) {
        HomeCellContent(item)
    }
}

/** セルの中身(アイコン + ラベル / 空きセル)。 */
@Composable
internal fun HomeCellContent(item: HomeItem?) {
    when (item) {
        is HomeItem.HomeApp -> {
            AppIcon(app = item.app, size = 56.dp)
            Spacer(Modifier.height(4.dp))
            HomeLabel(text = homeLabelOf(item))
        }
        is HomeItem.HomeFolder -> {
            AppIcon(app = item.apps.firstOrNull() ?: return, size = 56.dp)
            Spacer(Modifier.height(4.dp))
            HomeLabel(text = item.name)
        }
        null -> {
            Box(Modifier.height(56.dp).fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            HomeLabel(text = "")
        }
    }
}

@Composable
private fun homeLabelOf(item: HomeItem.HomeApp): String {
    val graph = LocalGraph.current
    val apps = graph.appRepository.apps.collectAsState().value
    return remember(item.app, apps) { graph.appRepository.labelOf(item.app) }
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
