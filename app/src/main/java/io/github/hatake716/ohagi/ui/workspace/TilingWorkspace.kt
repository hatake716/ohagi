package io.github.hatake716.ohagi.ui.workspace

import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hatake716.ohagi.LocalGraph
import io.github.hatake716.ohagi.data.Pane
import io.github.hatake716.ohagi.ui.common.AppIcon
import io.github.hatake716.ohagi.ui.theme.Azuki
import io.github.hatake716.ohagi.ui.theme.PanelScrim
import io.github.hatake716.ohagi.ui.theme.PanelScrimLight
import io.github.hatake716.ohagi.ui.theme.TileBorder

/**
 * タイリングワークスペースのプレビュー / コントロール層。
 *
 * 実アプリは手前にフリーフォームウィンドウとして開くため、この層は
 * 「いまどのアプリがどのタイル位置にあるか」を映すプレビューであり、
 * 各ペインをタップして前面化、長押しで操作メニューを開くための操作面でもある。
 *
 * 横スクロールは廃止。1 画面固定で、panes の枚数に応じて上下 / 左右に分割する。
 */
@Composable
fun TilingWorkspace(
    panes: List<Pane>,
    isPortrait: Boolean,
    onPaneTap: (Pane) -> Unit,
    onPaneLongPress: (Pane) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (panes.isEmpty()) {
        // 空のワークスペースには何も表示しない(壁紙とドックのみ)
        Box(modifier = modifier.fillMaxSize())
        return
    }

    val gap = 10.dp
    Box(modifier = modifier.fillMaxSize().padding(gap)) {
        when (panes.size) {
            1 -> PaneCard(
                pane = panes[0],
                master = true,
                onTap = { onPaneTap(panes[0]) },
                onLongPress = { onPaneLongPress(panes[0]) },
                modifier = Modifier.fillMaxSize(),
            )

            2 -> TwoSplit(panes, isPortrait, gap, onPaneTap, onPaneLongPress)

            else -> ThreeSplit(panes, isPortrait, gap, onPaneTap, onPaneLongPress)
        }
    }
}

@Composable
private fun TwoSplit(
    panes: List<Pane>,
    isPortrait: Boolean,
    gap: androidx.compose.ui.unit.Dp,
    onTap: (Pane) -> Unit,
    onLong: (Pane) -> Unit,
) {
    val a: @Composable (Modifier) -> Unit = { m ->
        PaneCard(panes[0], master = true, onTap = { onTap(panes[0]) }, onLongPress = { onLong(panes[0]) }, modifier = m)
    }
    val b: @Composable (Modifier) -> Unit = { m ->
        PaneCard(panes[1], master = false, onTap = { onTap(panes[1]) }, onLongPress = { onLong(panes[1]) }, modifier = m)
    }
    if (isPortrait) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
            a(Modifier.fillMaxWidth().weight(1f))
            b(Modifier.fillMaxWidth().weight(1f))
        }
    } else {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            a(Modifier.fillMaxHeight().weight(1f))
            b(Modifier.fillMaxHeight().weight(1f))
        }
    }
}

@Composable
private fun ThreeSplit(
    panes: List<Pane>,
    isPortrait: Boolean,
    gap: androidx.compose.ui.unit.Dp,
    onTap: (Pane) -> Unit,
    onLong: (Pane) -> Unit,
) {
    val master: @Composable (Modifier) -> Unit = { m ->
        PaneCard(panes[0], master = true, onTap = { onTap(panes[0]) }, onLongPress = { onLong(panes[0]) }, modifier = m)
    }
    val second: @Composable (Modifier) -> Unit = { m ->
        PaneCard(panes[1], master = false, onTap = { onTap(panes[1]) }, onLongPress = { onLong(panes[1]) }, modifier = m)
    }
    val third: @Composable (Modifier) -> Unit = { m ->
        PaneCard(panes[2], master = false, onTap = { onTap(panes[2]) }, onLongPress = { onLong(panes[2]) }, modifier = m)
    }
    if (isPortrait) {
        // 上: マスター、下: 2 枚を左右
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
            master(Modifier.fillMaxWidth().weight(1f))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                second(Modifier.fillMaxHeight().weight(1f))
                third(Modifier.fillMaxHeight().weight(1f))
            }
        }
    } else {
        // 左: マスター、右: 2 枚を上下
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            master(Modifier.fillMaxHeight().weight(1f))
            Column(Modifier.fillMaxHeight().weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
                second(Modifier.fillMaxWidth().weight(1f))
                third(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaneCard(
    pane: Pane,
    master: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = LocalGraph.current
    val apps by graph.appRepository.apps.collectAsStateWithLifecycle()
    val label = remember(pane.app, apps) { graph.appRepository.labelOf(pane.app) }

    // すべてのペインに小豆色の縁取り。マスターは少し太く。
    val shape = RoundedCornerShape(28.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(PanelScrimLight, PanelScrim)))
            .border(
                width = if (master) 2.5.dp else 1.5.dp,
                color = if (master) Azuki else TileBorder,
                shape = shape,
            )
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(10.dp),
        ) {
            AppIcon(app = pane.app, size = if (master) 60.dp else 44.dp)
            Spacer(Modifier.height(10.dp))
            Text(
                text = label,
                style = if (master) MaterialTheme.typography.titleSmall
                else MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
