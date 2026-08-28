package io.github.hatake716.ohagi.ui.dock

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
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
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(shape)
            .background(PanelScrim)
            .border(1.dp, TileBorder, shape)
            .padding(horizontal = 8.dp),
    ) {
        DockSlot(
            item = dock.getOrNull(0),
            onTap = { onSlotTap(0) },
            onLongPress = { onSlotLongPress(0) },
            modifier = Modifier.weight(1f),
        )
        DockSlot(
            item = dock.getOrNull(1),
            onTap = { onSlotTap(1) },
            onLongPress = { onSlotLongPress(1) },
            modifier = Modifier.weight(1f),
        )
        LauncherButton(
            onTap = onLauncherTap,
            onLongPress = onLauncherLongPress,
        )
        DockSlot(
            item = dock.getOrNull(2),
            onTap = { onSlotTap(2) },
            onLongPress = { onSlotLongPress(2) },
            modifier = Modifier.weight(1f),
        )
        DockSlot(
            item = dock.getOrNull(3),
            onTap = { onSlotTap(3) },
            onLongPress = { onSlotLongPress(3) },
            modifier = Modifier.weight(1f),
        )
    }
}

/** ドックの 1 スロット。アプリ/フォルダ/空(+)のいずれかを表示する。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockSlot(
    item: DockItem?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = LocalGraph.current
    // TalkBack 向けのスロット説明(アプリ名/フォルダ名/空きスロット)。
    // アプリ一覧の読込完了に追従してラベルを再解決する。
    val apps by graph.appRepository.apps.collectAsState()
    val description = when (item) {
        is DockItem.DockApp -> remember(item, apps) { graph.appRepository.labelOf(item.app) }
        is DockItem.DockFolder -> item.name
        null -> stringResource(R.string.dock_slot_empty)
    }

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // 押下時にスプリングでふっと縮む(niri 風のマイクロインタラクション)
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 520f),
        label = "dockSlotScale",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onTap,
                    onLongClick = onLongPress,
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
