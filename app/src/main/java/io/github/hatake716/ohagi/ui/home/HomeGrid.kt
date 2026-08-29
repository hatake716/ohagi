package io.github.hatake716.ohagi.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.ohagi.LocalGraph
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.data.HomeItem
import io.github.hatake716.ohagi.data.LayoutState
import io.github.hatake716.ohagi.ui.common.AppIcon
import io.github.hatake716.ohagi.ui.common.IosMoreButton
import io.github.hatake716.ohagi.ui.common.animateIosPressScale
import io.github.hatake716.ohagi.ui.common.rememberAppIconBitmap
import io.github.hatake716.ohagi.ui.dragdrop.DragPayload
import io.github.hatake716.ohagi.ui.dragdrop.ohagiDragSource
import io.github.hatake716.ohagi.ui.dragdrop.ohagiDropTarget
import io.github.hatake716.ohagi.ui.dragdrop.rememberOhagiDropTarget
import io.github.hatake716.ohagi.ui.theme.Azuki
import io.github.hatake716.ohagi.ui.theme.Ink
import io.github.hatake716.ohagi.ui.theme.Kome

/**
 * ホーム主画面の固定グリッド。
 * 各セルが公式 Compose D&D の source/target を直接持つため、画面座標による判定は不要。
 */
@Composable
fun HomeGrid(
    home: List<HomeItem?>,
    activeDrag: DragPayload?,
    onCellTap: (Int) -> Unit,
    onCellMenu: (Int) -> Unit,
    onDrop: (Int, DragPayload, Offset) -> Boolean,
    onDragMoved: (Offset) -> Unit,
    onDragSessionStarted: (DragPayload) -> Unit,
    onDragSessionEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 6行を領域いっぱいに均等配置し、最下段をドック直上にそろえる。
        val rowHeight = (maxHeight - 8.dp) / LayoutState.HOME_ROWS
        LazyVerticalGrid(
            columns = GridCells.Fixed(LayoutState.HOME_COLUMNS),
            userScrollEnabled = false,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(home, key = { index, _ -> index }) { index, item ->
                HomeCell(
                    index = index,
                    item = item,
                    rowHeight = rowHeight,
                    isDragging = activeDrag == DragPayload.FromHome(index),
                    onTap = { onCellTap(index) },
                    onMenu = { onCellMenu(index) },
                    onDrop = { payload, position -> onDrop(index, payload, position) },
                    onDragMoved = onDragMoved,
                    onDragSessionStarted = onDragSessionStarted,
                    onDragSessionEnded = onDragSessionEnded,
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
    isDragging: Boolean,
    onTap: () -> Unit,
    onMenu: () -> Unit,
    onDrop: (DragPayload, Offset) -> Boolean,
    onDragMoved: (Offset) -> Unit,
    onDragSessionStarted: (DragPayload) -> Unit,
    onDragSessionEnded: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale = animateIosPressScale(
        pressed = pressed,
        label = "homeCellScale",
    )
    val haptic = LocalHapticFeedback.current

    val payload = item?.let { DragPayload.FromHome(index) }
    val dragIconApp = when (item) {
        is HomeItem.HomeApp -> item.app
        is HomeItem.HomeFolder -> item.apps.firstOrNull()
        null -> null
    }
    val dragIcon by rememberAppIconBitmap(dragIconApp)

    var dropHovered by remember { mutableStateOf(false) }
    val hoverColor by animateColorAsState(
        targetValue = if (dropHovered) Azuki.copy(alpha = 0.24f) else Color.Transparent,
        animationSpec = tween(durationMillis = 120),
        label = "homeDropHover",
    )
    val dropTarget = rememberOhagiDropTarget(
        onStarted = onDragSessionStarted,
        onEntered = { dropHovered = true },
        onMoved = onDragMoved,
        onExited = { dropHovered = false },
        onEnded = {
            dropHovered = false
            onDragSessionEnded()
        },
        onDrop = { dropped, position ->
            dropHovered = false
            onDrop(dropped, position)
        },
    )

    val sourceModifier = if (payload == null) {
        Modifier
    } else {
        Modifier.ohagiDragSource(
            payload = payload,
            icon = dragIcon,
            onTap = onTap,
            onPressChanged = { pressed = it },
            onDragStarted = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDragSessionStarted(payload)
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .alpha(if (isDragging) 0.20f else 1f)
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
            HomeCellContent(item)
        }

        if (item is HomeItem.HomeApp) {
            IosMoreButton(
                contentDescription = stringResource(R.string.action_more),
                onClick = onMenu,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 3.dp),
            )
        }
    }
}

/** セルの中身（アイコン + ラベル / 空きセル）。 */
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
