package io.github.hatake716.ohagi.ui.drawer

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dock
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.data.AppInfo
import io.github.hatake716.ohagi.ui.dragdrop.DragController
import io.github.hatake716.ohagi.ui.dragdrop.DragOrigin
import io.github.hatake716.ohagi.ui.common.AppIcon
import io.github.hatake716.ohagi.ui.common.MenuEntry
import io.github.hatake716.ohagi.ui.common.MenuSheet
import io.github.hatake716.ohagi.ui.theme.Ink
import io.github.hatake716.ohagi.ui.theme.Kome

/**
 * アプリドロワー(全画面オーバーレイ)。
 * ホスト側の AnimatedVisibility で出し入れされる。BackHandler はホスト側にある。
 * タップで起動、長押しでメニュー(ワークスペース/ドックへの追加・アプリ情報・アンインストール)。
 */
@Composable
fun AppDrawer(
    apps: List<AppInfo>,
    drag: DragController,
    rootCoords: () -> LayoutCoordinates?,
    onLaunch: (AppInfo) -> Unit,
    onAddToWorkspace: (AppInfo) -> Unit,
    onAddToDock: (AppInfo) -> Unit,
    onAppInfo: (AppInfo) -> Unit,
    onUninstall: (AppInfo) -> Unit,
    onOpenDefaultHome: () -> Unit,
    /** ドラッグ確定(親が設置/削除しドロワーを閉じる)。 */
    onDrop: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ドラッグ中(ドロワー発)はドロワーを透過し、背後のホーム/ドック/削除エリアを見せる。
    val dragging = drag.isDragging && drag.isSource(DragOrigin.Drawer)
    var query by remember { mutableStateOf("") }
    var menuTarget by remember { mutableStateOf<AppInfo?>(null) }

    // ラベル部分一致でフィルタ(大文字小文字は区別しない)
    val visibleApps = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter { it.label.contains(query, ignoreCase = true) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // ドラッグ中は暗幕を消して背後(ホーム/ドック/削除エリア)を見せる。
            .background(if (dragging) Color.Transparent else Ink.copy(alpha = 0.55f))
            // 非ドラッグ時のみ背面へのタッチを吸収(ドラッグ中は各セルの pointerInput に委ねる)。
            .then(
                if (dragging) Modifier
                else Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
            )
            // ステータスバー/ナビバー/IME/ディスプレイカットアウトすべてを避ける
            .safeDrawingPadding()
            // ドラッグ中はドロワーの中身(バー/検索/グリッド)を透明化して背後を見せる。
            // ツリーには残すことで、ドラッグを握っているセルの pointerInput を生かし続ける。
            .graphicsLayer { alpha = if (dragging) 0f else 1f }
    ) {
        // 上部バー: タイトル + 閉じるボタン
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.drawer_title),
                style = MaterialTheme.typography.titleLarge,
                color = Kome,
                modifier = Modifier.weight(1f),
            )
            // デフォルトホーム設定(ランチャーとして必須の導線)
            IconButton(onClick = onOpenDefaultHome) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = stringResource(R.string.menu_set_default_home),
                    tint = Kome,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    tint = Kome,
                )
            }
        }

        // 検索フィールド
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_apps_hint)) },
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        )

        if (visibleApps.isEmpty()) {
            // 検索結果 0 件: 控えめなアイコンのみ表示(文言はハードコードしない)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SearchOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(48.dp),
                )
            }
        } else {
            LazyVerticalGrid(
                // iOS ホームと同じ 4 列固定グリッド
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                items(
                    items = visibleApps,
                    key = { "${it.ref.packageName}/${it.ref.className}" },
                ) { app ->
                    DrawerCell(
                        app = app,
                        drag = drag,
                        rootCoords = rootCoords,
                        onTap = { onLaunch(app) },
                        onLongPressNoMove = { menuTarget = app },
                        onDrop = onDrop,
                    )
                }
            }
        }
    }

    // 長押しメニュー
    menuTarget?.let { app ->
        MenuSheet(
            entries = listOf(
                MenuEntry(stringResource(R.string.drawer_add_to_workspace), Icons.Rounded.Add) {
                    onAddToWorkspace(app)
                },
                MenuEntry(stringResource(R.string.drawer_add_to_dock), Icons.Rounded.Dock) {
                    onAddToDock(app)
                },
                MenuEntry(stringResource(R.string.action_app_info), Icons.Rounded.Info) {
                    onAppInfo(app)
                },
                MenuEntry(
                    stringResource(R.string.action_uninstall),
                    Icons.Rounded.Delete,
                    destructive = true,
                ) {
                    onUninstall(app)
                },
            ),
            onDismiss = { menuTarget = null },
            header = { DrawerMenuHeader(app) },
        )
    }
}

/** ドロワーの 1 セル。長押し→動かすとドラッグ、動かさず離すとメニュー。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerCell(
    app: AppInfo,
    drag: DragController,
    rootCoords: () -> LayoutCoordinates?,
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
        label = "drawerCellScale",
    )

    val density = LocalDensity.current
    val movedThresholdPx = remember(density) { with(density) { 12.dp.toPx() } }
    var cellCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var totalDrag by remember { mutableStateOf(Offset.Zero) }
    var started by remember { mutableStateOf(false) }

    fun toRoot(local: Offset): Offset {
        val root = rootCoords() ?: return local
        val cell = cellCoords ?: return local
        return root.localPositionOf(cell, local)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .onGloballyPositioned { cellCoords = it }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(18.dp))
            // 長押し→動かすとドラッグ、動かさず離すとメニュー。ドロワーは閉じない。
            // LazyVerticalGrid のスクロールにドラッグを奪われないよう、長押し成立後は
            // Initial パス(親スクロールより先)で pointer を占有し必ず consume する。
            .pointerInput(app) {
                awaitEachGesture {
                    // 1. 最初の down を待つ(まだ consume しない=スクロールに任せる)
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 2. 長押し成立を待つ。この間の縦移動はスクロールに使われてよい。
                    val longPress = awaitLongPressOrCancellation(down.id)
                        ?: return@awaitEachGesture  // 長押し不成立: タップ/スクロールへ譲る
                    // 3. 長押し成立。以後このセルがポインタを占有する。
                    totalDrag = Offset.Zero
                    started = false
                    longPress.consume()

                    var canceled = false
                    // 4. 自前ドラッグループ: Initial パスで親より先にイベントを受け必ず consume
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) { canceled = true; break }
                        if (change.isConsumed) { canceled = true; break }
                        if (change.changedToUp()) { change.consume(); break }
                        val delta = change.positionChange()
                        change.consume()
                        totalDrag += delta
                        if (!started && totalDrag.getDistance() >= movedThresholdPx) {
                            started = true
                            val size = Offset(this.size.width.toFloat(), this.size.height.toFloat())
                            drag.startDrawer(app.ref, toRoot(change.position), size)
                        }
                        if (started) drag.move(toRoot(change.position))
                    }

                    // 5. 終了分岐
                    if (canceled) {
                        // ドラッグ確立後に奪われた場合のみ reset。未確立ならメニューを出さない。
                        if (started) drag.reset()
                    } else {
                        if (started) onDrop() else onLongPressNoMove()
                    }
                    started = false
                }
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null, // 押下スケールで代替
                onClick = onTap,
            )
            .padding(horizontal = 4.dp, vertical = 10.dp)
    ) {
        AppIcon(app = app.ref, size = 56.dp)
        Spacer(Modifier.height(6.dp))
        // iOS ホーム風: アイコン下に最大 2 行のラベル。半透明背景で読めるよう白系。
        Text(
            text = app.label,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.1.sp,
            color = Kome,
            maxLines = 2,
            minLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 30.dp),
        )
    }
}

/** 長押しメニューのヘッダー(アイコン + ラベル) */
@Composable
private fun DrawerMenuHeader(app: AppInfo) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        AppIcon(app = app.ref, size = 36.dp)
        Spacer(Modifier.width(14.dp))
        Text(text = app.label)
    }
}
