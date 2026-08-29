package io.github.hatake716.ohagi.ui.widget

import android.appwidget.AppWidgetHostView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.hatake716.ohagi.LocalGraph
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.data.WidgetPlacement
import io.github.hatake716.ohagi.ui.common.IOS_SELECTION_BLUE
import io.github.hatake716.ohagi.ui.common.IosGlassIconButton
import io.github.hatake716.ohagi.ui.theme.Ink
import io.github.hatake716.ohagi.ui.theme.Kome

/** iOS Today Viewに相当する、Dockを持たない左端のウィジェット専用ページ。 */
@Composable
fun WidgetPage(
    widgets: List<WidgetPlacement>,
    onAddWidget: () -> Unit,
    onRemoveWidget: (WidgetPlacement) -> Unit,
    onMoveWidget: (WidgetPlacement, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Ink.copy(alpha = 0.18f))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.widget_page_title),
                color = Kome,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (widgets.isNotEmpty()) {
                TextButton(onClick = { editing = !editing }) {
                    Text(
                        text = stringResource(
                            if (editing) R.string.action_done else R.string.folder_edit,
                        ),
                        color = IOS_SELECTION_BLUE,
                    )
                }
            }
            IosGlassIconButton(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.widget_add),
                onClick = onAddWidget,
            )
        }

        if (widgets.isEmpty()) {
            EmptyWidgetPage(
                onAddWidget = onAddWidget,
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(
                    items = widgets,
                    key = { _, placement -> placement.appWidgetId },
                ) { index, placement ->
                    HostedWidgetCard(
                        placement = placement,
                        editing = editing,
                        canMoveUp = index > 0,
                        canMoveDown = index < widgets.lastIndex,
                        onRemove = { onRemoveWidget(placement) },
                        onMoveUp = { onMoveWidget(placement, -1) },
                        onMoveDown = { onMoveWidget(placement, 1) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item { Spacer(Modifier.height(18.dp)) }
            }
        }
    }
}

@Composable
private fun EmptyWidgetPage(
    onAddWidget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(28.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(Color.White.copy(alpha = 0.10f))
                .border(
                    width = 0.5.dp,
                    color = Color.White.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(30.dp),
                )
                .padding(horizontal = 28.dp, vertical = 34.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Widgets,
                contentDescription = null,
                tint = Kome,
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.widget_empty_title),
                color = Kome,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.widget_empty_body),
                color = Kome.copy(alpha = 0.68f),
            )
            Spacer(Modifier.height(18.dp))
            IosGlassIconButton(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.widget_add),
                onClick = onAddWidget,
            )
        }
    }
}

@Composable
private fun HostedWidgetCard(
    placement: WidgetPlacement,
    editing: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = LocalGraph.current.widgetHost
    val info = remember(placement) { controller.appWidgetInfo(placement.appWidgetId) }
    val label = remember(info, placement) {
        info?.let(controller::providerLabel) ?: placement.providerPackage
    }
    var widthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val heightDp = placement.heightDp.coerceIn(96, 480)

    LaunchedEffect(placement.appWidgetId, widthPx, heightDp) {
        if (widthPx > 0 && info != null) {
            controller.updateSize(
                appWidgetId = placement.appWidgetId,
                widthDp = with(density) { widthPx.toDp().value.toInt() },
                heightDp = heightDp,
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .onSizeChanged { widthPx = it.width }
            .clip(RoundedCornerShape(26.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .border(
                width = 0.5.dp,
                color = Color.White.copy(alpha = 0.20f),
                shape = RoundedCornerShape(26.dp),
            ),
    ) {
        if (info == null || info.provider != controller.componentOf(placement)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = stringResource(R.string.widget_unavailable),
                    color = Kome.copy(alpha = 0.65f),
                )
            }
        } else {
            AndroidView<AppWidgetHostView>(
                factory = { context ->
                    controller.createView(context, placement.appWidgetId, info)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (editing) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                if (canMoveUp) {
                    WidgetEditButton(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.widget_move_up, label),
                        onClick = onMoveUp,
                    )
                }
                if (canMoveDown) {
                    WidgetEditButton(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.widget_move_down, label),
                        onClick = onMoveDown,
                    )
                }
                WidgetEditButton(
                    imageVector = Icons.Rounded.Remove,
                    contentDescription = stringResource(R.string.widget_remove, label),
                    destructive = true,
                    onClick = onRemove,
                )
            }
        }
    }
}

@Composable
private fun WidgetEditButton(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val background = if (destructive) Color(0xFFE84646) else Color(0xCC2C2C2E)
    androidx.compose.material3.IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(background),
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}
