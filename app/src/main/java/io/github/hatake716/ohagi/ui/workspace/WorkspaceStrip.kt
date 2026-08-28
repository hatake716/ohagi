package io.github.hatake716.ohagi.ui.workspace

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hatake716.ohagi.LocalGraph
import io.github.hatake716.ohagi.R
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

/** niri のカラム幅プリセットに相当する幅係数 */
fun columnWidthFraction(preset: Int, isPortrait: Boolean): Float = when (preset) {
    0 -> if (isPortrait) 0.62f else 0.44f
    2 -> if (isPortrait) 0.96f else 0.86f
    else -> if (isPortrait) 0.80f else 0.62f
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
    onAddFirst: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (columns.isEmpty()) {
        EmptyWorkspace(onAddFirst = onAddFirst, modifier = modifier)
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

    // 出現時に少し縮んだ状態からスプリングで立ち上がる(niri のウィンドウオープン風)
    val appear = remember { Animatable(0.88f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, spring(dampingRatio = 0.68f, stiffness = 320f))
    }

    val tileArrangement = Arrangement.spacedBy(12.dp)
    val content: @Composable (Modifier) -> Unit = { tileModifier ->
        column.tiles.forEach { tile ->
            TileCard(
                tile = tile,
                focused = focused,
                onTap = { onTileTap(tile) },
                onLongPress = { onTileLongPress(tile) },
                modifier = tileModifier,
            )
        }
    }

    Box(
        modifier = modifier
            .width(width)
            .graphicsLayer {
                scaleX = scale * appear.value
                scaleY = scale * appear.value
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
    modifier: Modifier = Modifier,
) {
    val graph = LocalGraph.current
    val label = remember(tile.app) { graph.appRepository.labelOf(tile.app) }

    val borderColor by animateColorAsState(
        targetValue = if (focused) Azuki else TileBorder,
        animationSpec = spring(stiffness = 380f),
        label = "tileBorder",
    )
    val shape = RoundedCornerShape(24.dp)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
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

@Composable
private fun EmptyWorkspace(
    onAddFirst: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .clip(shape)
                .background(PanelScrimLight)
                .border(1.dp, TileBorder, shape)
                .combinedClickableCompat(onClick = onAddFirst)
                .padding(horizontal = 24.dp, vertical = 40.dp),
        ) {
            Text(
                text = stringResource(R.string.workspace_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.workspace_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick)
