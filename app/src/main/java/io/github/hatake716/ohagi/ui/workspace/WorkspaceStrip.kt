package io.github.hatake716.ohagi.ui.workspace

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hatake716.ohagi.LocalGraph
import io.github.hatake716.ohagi.data.Tile
import io.github.hatake716.ohagi.data.WorkColumn
import io.github.hatake716.ohagi.ui.common.AppIcon
import io.github.hatake716.ohagi.ui.theme.Azuki
import io.github.hatake716.ohagi.ui.theme.PanelScrim
import io.github.hatake716.ohagi.ui.theme.PanelScrimLight
import io.github.hatake716.ohagi.ui.theme.TileBorder
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** niri のカラム幅プリセット: 1/3 → 1/2 → 2/3 → フル幅 */
fun columnWidthFraction(preset: Int, @Suppress("UNUSED_PARAMETER") isPortrait: Boolean): Float =
    when (preset) {
        0 -> 1f / 3f
        1 -> 1f / 2f
        3 -> 1f
        else -> 2f / 3f
    }

private fun minWidthFraction(isPortrait: Boolean): Float = columnWidthFraction(0, isPortrait)

/** ビューポート中央に最も近いカラムのインデックス(= フォーカス中カラム) */
@Composable
fun rememberFocusedColumnIndex(state: LazyListState): State<Int> = remember(state) {
    derivedStateOf {
        val info = state.layoutInfo
        val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
        info.visibleItemsInfo
            .minByOrNull { abs(it.offset + it.size / 2 - center) }
            ?.index ?: 0
    }
}

/** 指定カラムをビューポート中央へスクロールする(niri のフォーカス移動と同じ動き) */
suspend fun LazyListState.centerOnColumn(index: Int, animated: Boolean = true) {
    if (layoutInfo.totalItemsCount == 0 || index < 0) return
    var item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        scrollToItem(index.coerceAtMost(layoutInfo.totalItemsCount - 1))
        item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    }
    val center = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
    val delta = (item.offset + item.size / 2 - center).toFloat()
    if (animated) {
        animateScrollBy(delta, spring(dampingRatio = 0.85f, stiffness = 260f))
    } else {
        scrollBy(delta)
    }
}

/**
 * niri 風の横スクロールワークスペース。
 * カラムが横一列に並び、フォーカス中カラムが中央にスナップされ、隣のカラムが端から覗く。
 * 各カラムは 1〜2 タイルを持ち、縦画面では上下・横画面では左右に分割される。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkspaceStrip(
    columns: List<WorkColumn>,
    listState: LazyListState,
    isPortrait: Boolean,
    onLaunchTile: (WorkColumn, Tile) -> Unit,
    onTileLongPress: (WorkColumn, Tile) -> Unit,
    onTileBounds: (String, android.graphics.Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (columns.isEmpty()) {
        // 空のワークスペースには何も表示しない(壁紙とドックのみ)
        Box(modifier = modifier.fillMaxSize())
        return
    }

    val focusedIndex by rememberFocusedColumnIndex(listState)
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportWidth = maxWidth
        val sidePadding = (viewportWidth * (1f - minWidthFraction(isPortrait))) / 2

        LaunchedEffect(Unit) {
            listState.centerOnColumn(focusedIndex, animated = false)
        }

        LazyRow(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(listState),
            contentPadding = PaddingValues(horizontal = sidePadding),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                count = columns.size,
                key = { columns[it].id },
            ) { index ->
                val column = columns[index]
                val focused = index == focusedIndex
                WorkspaceColumn(
                    column = column,
                    focused = focused,
                    isPortrait = isPortrait,
                    width = viewportWidth * columnWidthFraction(column.widthPreset, isPortrait),
                    onTileTap = { tile ->
                        if (focused) {
                            onLaunchTile(column, tile)
                        } else {
                            scope.launch { listState.centerOnColumn(index) }
                        }
                    },
                    onTileLongPress = { tile -> onTileLongPress(column, tile) },
                    onTileBounds = onTileBounds,
                    modifier = Modifier
                        .fillParentMaxHeight()
                        .animateItem(
                            fadeInSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            placementSpec = spring(
                                dampingRatio = 0.78f,
                                stiffness = 300f,
                            ),
                            fadeOutSpec = spring(stiffness = Spring.StiffnessMedium),
                        ),
                )
            }
        }
    }
}

@Composable
private fun WorkspaceColumn(
    column: WorkColumn,
    focused: Boolean,
    isPortrait: Boolean,
    width: androidx.compose.ui.unit.Dp,
    onTileTap: (Tile) -> Unit,
    onTileLongPress: (Tile) -> Unit,
    onTileBounds: (String, android.graphics.Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (focused) 1f else 0.94f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 340f),
        label = "columnScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0.82f,
        animationSpec = spring(stiffness = 340f),
        label = "columnAlpha",
    )

    val tileArrangement = Arrangement.spacedBy(12.dp)
    val content: @Composable (Modifier) -> Unit = { tileModifier ->
        column.tiles.forEach { tile ->
            TileCard(
                tile = tile,
                focused = focused,
                onTap = { onTileTap(tile) },
                onLongPress = { onTileLongPress(tile) },
                onBounds = { rect -> onTileBounds(tile.id, rect) },
                modifier = tileModifier,
            )
        }
    }

    Box(
        modifier = modifier
            .width(width)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .padding(vertical = 10.dp)
    ) {
        if (isPortrait) {
            Column(
                verticalArrangement = tileArrangement,
                modifier = Modifier.fillMaxSize(),
            ) {
                content(Modifier.fillMaxWidth().weight(1f))
            }
        } else {
            Row(
                horizontalArrangement = tileArrangement,
                modifier = Modifier.fillMaxSize(),
            ) {
                content(Modifier.fillMaxHeight().weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileCard(
    tile: Tile,
    focused: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onBounds: (android.graphics.Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = LocalGraph.current
    // アプリ一覧の読込完了・更新に追従してラベルを再解決する
    val apps by graph.appRepository.apps.collectAsState()
    val label = remember(tile.app, apps) { graph.appRepository.labelOf(tile.app) }

    val borderColor by animateColorAsState(
        targetValue = if (focused) Azuki else TileBorder,
        animationSpec = spring(stiffness = 380f),
        label = "tileBorder",
    )
    val shape = RoundedCornerShape(24.dp)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                val b = coordinates.boundsInWindow()
                onBounds(
                    android.graphics.Rect(
                        b.left.roundToInt(),
                        b.top.roundToInt(),
                        b.right.roundToInt(),
                        b.bottom.roundToInt(),
                    )
                )
            }
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(PanelScrimLight, PanelScrim),
                )
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = borderColor,
                shape = shape,
            )
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
            )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(12.dp),
        ) {
            AppIcon(app = tile.app, size = 64.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

