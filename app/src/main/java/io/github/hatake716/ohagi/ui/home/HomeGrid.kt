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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import io.github.hatake716.ohagi.ui.theme.Ink
import io.github.hatake716.ohagi.ui.theme.Kome

/**
 * ホーム主画面のアイコングリッド(固定 [LayoutState.HOME_CELL_COUNT] セル)。
 * 壁紙の上に直接描かれ、各セルはアプリアイコン + ラベル。index がそのままセル位置。
 *
 * アイコンを長押しするとドラッグが始まり、別セルの上でドロップすると 2 セルを入れ替える
 * (空きセルへ落とすと移動)。長押しして動かさず離すとメニュー/追加ピッカーを開く。
 *
 * @param onCellTap       セルのタップ(空きセルなら追加、アプリなら起動)
 * @param onCellLongPress セルの長押し(動かさず離したとき。メニュー/追加)
 * @param onSwap          ドラッグ&ドロップで from→to のセルを入れ替える
 */
@Composable
fun HomeGrid(
    home: List<HomeItem?>,
    onCellTap: (Int) -> Unit,
    onCellLongPress: (Int) -> Unit,
    onSwap: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current
    val movedThresholdPx = remember(density) { with(density) { 12.dp.toPx() } }

    // ドラッグ状態(この Composable 内に閉じる)
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    // グリッドのルート Box を原点とした現在の指位置。graphicsLayer 内で読んで描画フェーズのみ更新。
    var fingerPos by remember { mutableStateOf(Offset.Zero) }
    // グリッドのルート Box の LayoutCoordinates(セル座標→グリッド座標の変換に使う)
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // ドラッグ中のセルのサイズ(浮遊コピーの中心合わせ用)
    var cellSize by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier.onGloballyPositioned { rootCoords = it },
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(LayoutState.HOME_COLUMNS),
            // 単一ページ・固定セル数。縦スクロールを無効化し、背面の上スワイプジェスチャと競合させない。
            userScrollEnabled = false,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(home, key = { i, _ -> i }) { index, item ->
                HomeCell(
                    item = item,
                    isDragging = draggingIndex == index,
                    onTap = { onCellTap(index) },
                    onLongPressNoMove = { onCellLongPress(index) },
                    onDragStart = { gridLocalPos, size ->
                        draggingIndex = index
                        fingerPos = gridLocalPos
                        cellSize = size
                    },
                    onDragMove = { gridLocalPos -> fingerPos = gridLocalPos },
                    onDragEnd = {
                        val from = draggingIndex
                        if (from != null) {
                            val to = gridState.cellIndexAt(fingerPos)
                            if (to != null && to != from) onSwap(from, to)
                        }
                        draggingIndex = null
                    },
                    onDragCancel = { draggingIndex = null },
                    rootCoords = { rootCoords },
                    movedThresholdPx = movedThresholdPx,
                )
            }
        }

        // 浮遊コピー(ドラッグ中のみ)。1 枚だけ描き、指位置へ graphicsLayer で移動する。
        val dragIdx = draggingIndex
        val dragItem = if (dragIdx != null) home.getOrNull(dragIdx) else null
        if (dragItem != null) {
            // 浮遊 Box の幅をドラッグ元セル幅に固定する。こうしないと内部ラベルの
            // fillMaxWidth() がルート全幅に展開し、中心合わせ(translationX)が破綻する。
            val cellWidthDp = with(density) { cellSize.x.toDp() }
            Box(
                modifier = Modifier
                    .width(cellWidthDp)
                    .graphicsLayer {
                        // 指位置にコピーの中心が来るよう半セル分オフセット
                        translationX = fingerPos.x - cellSize.x / 2f
                        translationY = fingerPos.y - cellSize.y / 2f
                        alpha = 0.9f
                        scaleX = 1.1f
                        scaleY = 1.1f
                    },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HomeCellContent(dragItem)
                }
            }
        }
    }
}

/** グリッド座標(ルート Box 原点)の点が、どのセル index の上か。無ければ null。 */
private fun LazyGridState.cellIndexAt(pos: Offset): Int? {
    return layoutInfo.visibleItemsInfo.firstOrNull { item ->
        val x = item.offset.x
        val y = item.offset.y
        pos.x >= x && pos.x < x + item.size.width &&
            pos.y >= y && pos.y < y + item.size.height
    }?.index
}

/** ホームグリッドの 1 セル。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeCell(
    item: HomeItem?,
    isDragging: Boolean,
    onTap: () -> Unit,
    onLongPressNoMove: () -> Unit,
    onDragStart: (gridLocalPos: Offset, cellSize: Offset) -> Unit,
    onDragMove: (gridLocalPos: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    rootCoords: () -> LayoutCoordinates?,
    movedThresholdPx: Float,
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

    // このセルの LayoutCoordinates(セル内ローカル座標→グリッド座標変換に使う)
    var cellCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var totalDrag by remember { mutableStateOf(Offset.Zero) }

    /** セル内ローカル座標を、グリッドのルート Box 原点の座標へ変換する。 */
    fun toGrid(local: Offset): Offset {
        val root = rootCoords() ?: return local
        val cell = cellCoords ?: return local
        return root.localPositionOf(cell, local)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { cellCoords = it }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // ドラッグ中の元セルは半透明にする(浮遊コピーが本体)
            .alpha(if (isDragging) 0.35f else 1f)
            .clip(RoundedCornerShape(18.dp))
            // 長押し→ドラッグ。動かさず離したらメニュー扱い(onLongPressNoMove)。
            // 空きセルはドラッグ対象にしない(アプリ/フォルダのみ移動可)。動かしても
            // 常にメニュー/追加扱いにし、不可視ドラッグでアプリを奪う誤操作を防ぐ。
            .pointerInput(item) {
                val draggable = item is HomeItem.HomeApp || item is HomeItem.HomeFolder
                detectDragGesturesAfterLongPress(
                    onDragStart = { local ->
                        totalDrag = Offset.Zero
                        if (draggable) {
                            val size = Offset(this.size.width.toFloat(), this.size.height.toFloat())
                            onDragStart(toGrid(local), size)
                        }
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        totalDrag += delta
                        if (draggable) onDragMove(toGrid(change.position))
                    },
                    onDragEnd = {
                        if (!draggable || totalDrag.getDistance() < movedThresholdPx) {
                            // 空セル、またはほぼ動かさず解放 → メニュー/追加
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
            .padding(horizontal = 2.dp, vertical = 8.dp),
    ) {
        HomeCellContent(item)
    }
}

/** セルの中身(アイコン + ラベル / 空きセル)。浮遊コピーと共有できるよう切り出し。 */
@Composable
private fun HomeCellContent(item: HomeItem?) {
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
            // 空きセル: アイコン分の高さだけ確保した透明タップ領域(壁紙をクリーンに保つ)
            Box(Modifier.height(56.dp).fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            HomeLabel(text = "")
        }
    }
}

/** アプリラベルを AppRef から解決する(AppInfo を引き回さない、DockSlot と同手法)。 */
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
