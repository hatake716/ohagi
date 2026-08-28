package io.github.hatake716.ohagi.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Splitscreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hatake716.ohagi.LocalGraph
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.data.AppRef
import io.github.hatake716.ohagi.data.DockItem
import io.github.hatake716.ohagi.data.HomeItem
import io.github.hatake716.ohagi.ui.common.AppIcon
import io.github.hatake716.ohagi.ui.common.AppPickerSheet
import io.github.hatake716.ohagi.ui.common.MenuEntry
import io.github.hatake716.ohagi.ui.common.MenuSheet
import io.github.hatake716.ohagi.ui.dock.DockBar
import io.github.hatake716.ohagi.ui.dock.FolderSheet
import io.github.hatake716.ohagi.ui.dragdrop.DragOrigin
import io.github.hatake716.ohagi.ui.dragdrop.DropTarget
import io.github.hatake716.ohagi.ui.dragdrop.rememberDragController
import io.github.hatake716.ohagi.ui.drawer.AppDrawer
import io.github.hatake716.ohagi.ui.home.HomeGrid
import io.github.hatake716.ohagi.util.LaunchUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** いま画面に重なっている UI(同時に 1 つだけ) */
private sealed interface Overlay {
    data object None : Overlay
    data object Drawer : Overlay
    data class FolderView(val slot: Int) : Overlay
    data class RenameFolder(val slot: Int) : Overlay
    data class SlotMenu(val slot: Int) : Overlay
    data class DockSlotChooser(val app: AppRef) : Overlay
    /** ホームグリッドのセル長押しメニュー(index のセル) */
    data class HomeItemMenu(val index: Int) : Overlay
    data class Picker(val target: PickTarget) : Overlay
}

/** アプリピッカーで選んだアプリの追加先 */
private sealed interface PickTarget {
    data class DockSlot(val slot: Int) : PickTarget
    data class FolderAdd(val slot: Int) : PickTarget
    /** 分割で開く 1 つ目を選ぶ(選ぶと [SplitSecond] に進む)。 */
    data object SplitFirst : PickTarget
    /** 分割で開く 2 つ目を選ぶ(1 つ目は [first])。 */
    data class SplitSecond(val first: AppRef) : PickTarget
}

@Composable
fun HomeScreen(homeEvents: Flow<Unit>) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val layout by graph.layoutRepository.state.collectAsStateWithLifecycle()
    val apps by graph.appRepository.apps.collectAsStateWithLifecycle()

    var overlay by remember { mutableStateOf<Overlay>(Overlay.None) }

    // ---- ドックの賢い自動非表示 ----
    // アプリをまだ一度も開いていないセッション初期は常時表示。
    // アプリを開いたら隠し、下端エッジ上スワイプ or HOME 再押下で再表示する。
    var hasLaunchedApp by remember { mutableStateOf(false) }
    var dockVisible by remember { mutableStateOf(true) }

    // ohagi が前面に戻ったら(ON_RESUME): アプリを開いた実績があればドックは隠したまま、
    // まだなら常時表示。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                dockVisible = !hasLaunchedApp
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // HOME 再押下: オーバーレイを閉じ、隠れているドックを呼び戻す(ホームに戻ってきた合図)。
    LaunchedEffect(Unit) {
        homeEvents.collect {
            overlay = Overlay.None
            dockVisible = true
        }
    }

    // ホーム画面ではバックキーを無効化(ランチャーの標準挙動)
    BackHandler(enabled = overlay == Overlay.None) { }
    BackHandler(enabled = overlay != Overlay.None) { overlay = Overlay.None }

    /** アプリを開いたことを記録し、ドックを隠す。 */
    fun onAppLaunched() {
        hasLaunchedApp = true
        dockVisible = false
    }

    /** 単体でフルスクリーン起動する。 */
    fun openApp(app: AppRef) {
        overlay = Overlay.None
        LaunchUtils.launch(context, app)
        onAppLaunched()
    }

    /**
     * 2 アプリを OS 分割画面(split-screen)で開く。
     * ohagi は HOME ランチャーのため「1 つ目を開く→ohagi に戻る→2 つ目を開く」という
     * 順次操作では、戻る時点で必ず ON_RESUME を挟むため 1 つ目の記憶を保てない。
     * よって split は「分割で開く」導線で 1 つの前面セッション内に 2 アプリを選び、
     * まとめて起動する明示方式に一本化している。
     */
    fun openSplit(first: AppRef, second: AppRef) {
        overlay = Overlay.None
        scope.launch { LaunchUtils.launchSplit(context, first, second) }
        onAppLaunched()
    }

    // ---- 横断ドラッグ&ドロップ ----
    val drag = rememberDragController()
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    /** ドラッグ確定: origin×target で入替/移動/設置を振り分ける。 */
    fun commitDrop() {
        val origin = drag.origin
        val target = drag.resolveDrop()
        val repo = graph.layoutRepository
        if (origin != null && target != null) {
            when {
                // 削除エリアへドロップ: 元の場所から取り除く(ドロワー発は元々未配置なので no-op)
                target is DropTarget.Trash && origin is DragOrigin.Home ->
                    repo.setHomeItem(origin.index, null)
                target is DropTarget.Trash && origin is DragOrigin.Dock ->
                    repo.setDockItem(origin.slot, null)
                origin is DragOrigin.Home && target is DropTarget.HomeCell ->
                    repo.swapHomeItems(origin.index, target.index)
                origin is DragOrigin.Dock && target is DropTarget.DockSlot ->
                    repo.swapDockItems(origin.slot, target.slot)
                origin is DragOrigin.Home && target is DropTarget.DockSlot ->
                    repo.moveHomeToDock(origin.index, target.slot)
                origin is DragOrigin.Dock && target is DropTarget.HomeCell ->
                    repo.moveDockToHome(origin.slot, target.index)
                origin is DragOrigin.Drawer && target is DropTarget.HomeCell ->
                    drag.draggingApp?.let { repo.placeAppOnHome(target.index, it) }
                origin is DragOrigin.Drawer && target is DropTarget.DockSlot ->
                    drag.draggingApp?.let { repo.placeAppOnDock(target.slot, it) }
            }
        }
        // ドロワー発ドラッグはドロワーを開いたままなので、確定時に閉じる。
        if (origin is DragOrigin.Drawer) overlay = Overlay.None
        drag.reset()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootCoords = it },
    ) {
        // 背面ジェスチャ層: 主画面は壁紙のみ(ミニマル)。
        // 画面下端付近からの上スワイプ → 隠れたドックを一時表示する。
        // (ドロワーは中央ランチャーボタンからのみ開く。上スワイプでのドロワー起動は廃止)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val edgeThresholdPx = with(density) { 120.dp.toPx() }
                    val smallSwipePx = with(density) { 40.dp.toPx() }
                    var dragged = 0f
                    var fromBottomEdge = false
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragged = 0f
                            fromBottomEdge = offset.y > size.height - edgeThresholdPx
                        },
                        onVerticalDrag = { _, dragAmount -> dragged += dragAmount },
                        onDragEnd = {
                            // 下端からの上スワイプ → ドックを一時表示
                            if (fromBottomEdge && dragged < -smallSwipePx && overlay == Overlay.None) {
                                dockVisible = true
                            }
                        },
                    )
                }
        )

        // ホーム主画面のアイコングリッド(壁紙の上)。
        HomeGrid(
            home = layout.home,
            drag = drag,
            rootCoords = { rootCoords },
            onCellTap = { index ->
                // 空きセルタップは無反応(アプリ設置は D&D のみ)。
                when (val item = layout.home.getOrNull(index)) {
                    is HomeItem.HomeApp -> openApp(item.app)
                    else -> Unit
                }
            },
            onCellLongPressNoMove = { index ->
                // アプリセルは長押しでメニュー。空きセルは無反応。
                if (layout.home.getOrNull(index) is HomeItem.HomeApp) {
                    overlay = Overlay.HomeItemMenu(index)
                }
            },
            onDrop = { commitDrop() },
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = HOME_GRID_BOTTOM_RESERVED),
        )

        // ドック(自動非表示・iOS風スプリング)。BottomCenter にオーバーレイ配置。
        // ドラッグ中は隠さない(ドロップ先として見せ続ける)。
        AnimatedVisibility(
            visible = dockVisible || drag.isDragging,
            enter = slideInVertically(
                animationSpec = spring(
                    dampingRatio = 0.8f,
                    stiffness = Spring.StiffnessMedium,
                ),
            ) { it } + fadeIn(),
            exit = slideOutVertically(
                animationSpec = spring(
                    dampingRatio = 0.9f,
                    stiffness = Spring.StiffnessMedium,
                ),
            ) { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            DockBar(
                dock = layout.dock,
                drag = drag,
                rootCoords = { rootCoords },
                onSlotTap = { slot ->
                    // 空きスロットタップは無反応(アプリ設置は D&D のみ)。
                    when (val item = layout.dock.getOrNull(slot)) {
                        is DockItem.DockApp -> openApp(item.app)
                        is DockItem.DockFolder -> overlay = Overlay.FolderView(slot)
                        null -> Unit
                    }
                },
                onSlotLongPressNoMove = { slot -> overlay = Overlay.SlotMenu(slot) },
                onLauncherTap = { overlay = Overlay.Drawer },
                // 中央ボタン長押し = 分割画面の設定(1 つ目 → 2 つ目を選ぶ)のみ
                onLauncherLongPress = { overlay = Overlay.Picker(PickTarget.SplitFirst) },
                onDrop = { commitDrop() },
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        // ドロワー表示中(開閉アニメーション含む)は背面へのタッチを遮断する。
        // 【重要な不変条件】この遮断 Box は必ず下の AppDrawer より前(z 下層)に宣言すること。
        // AppDrawer より後(上)に置くと、hit-test が最前面のこの Box を先に掴み、
        // DrawerCell の長押しドラッグ開始を奪ってドロワー発 D&D が壊れる。
        if (overlay == Overlay.Drawer) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    }
            )
        }

        // ドロワー: 下からの弾性スプリングでせり上がり + スケールイン + フェード(iPhone風)
        AnimatedVisibility(
            visible = overlay == Overlay.Drawer,
            enter = slideInVertically(
                animationSpec = spring(
                    dampingRatio = 0.78f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) { it } + scaleIn(
                initialScale = 0.92f,
                animationSpec = spring(
                    dampingRatio = 0.8f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) + fadeIn(),
            exit = slideOutVertically(
                animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium),
            ) { it } + scaleOut(targetScale = 0.92f) + fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            AppDrawer(
                apps = apps,
                drag = drag,
                rootCoords = { rootCoords },
                onLaunch = { app -> openApp(app.ref) },
                onAddToWorkspace = { app ->
                    // 「分割で開く」: このアプリを 1 つ目にして、2 つ目の相方を選ばせる。
                    overlay = Overlay.Picker(PickTarget.SplitSecond(app.ref))
                },
                onAddToDock = { app -> overlay = Overlay.DockSlotChooser(app.ref) },
                onAppInfo = { app ->
                    overlay = Overlay.None
                    LaunchUtils.openAppInfo(context, app.ref.packageName)
                },
                onUninstall = { app ->
                    overlay = Overlay.None
                    LaunchUtils.requestUninstall(context, app.ref.packageName)
                },
                onOpenDefaultHome = {
                    (context as? Activity)?.let { LaunchUtils.requestDefaultHome(it) }
                },
                // ドラッグ確定: commitDrop が設置/削除し、ドロワーを閉じる。
                onDrop = { commitDrop() },
                onDismiss = { overlay = Overlay.None },
            )
        }

        // 削除エリア(ドラッグ中のみ・画面上部)。ドラッグ中のアイコンをここへ落とすと削除。
        // 指の移動/ドロップは各領域(ホーム/ドック/ドロワー)のジェスチャが担うため、
        // このエリアと浮遊アイコン層は pointerInput を持たず、描画と矩形報告のみ行う。
        if (drag.isDragging) {
            val overTrash = drag.isOverTrash()
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        if (overTrash) Color(0xCCD32F2F) else Color(0x66000000)
                    )
                    .onGloballyPositioned { coords ->
                        val root = rootCoords
                        if (root != null) {
                            val tl = root.localPositionOf(coords, Offset.Zero)
                            drag.reportTrash(
                                androidx.compose.ui.geometry.Rect(
                                    tl,
                                    androidx.compose.ui.geometry.Size(
                                        coords.size.width.toFloat(), coords.size.height.toFloat(),
                                    ),
                                )
                            )
                        }
                    }
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
                        tint = Color.White,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.action_remove),
                        color = Color.White,
                    )
                }
            }

            // 浮遊アイコン(最前面)。指位置に追従。
            val di = drag.draggingHomeItem
            val cellW = with(density) { drag.cellSize.x.toDp() }
            Box(
                modifier = Modifier
                    .width(if (cellW.value > 0f) cellW else 72.dp)
                    .graphicsLayer {
                        translationX = drag.fingerPos.x - drag.cellSize.x / 2f
                        translationY = drag.fingerPos.y - drag.cellSize.y / 2f
                        alpha = 0.9f
                        scaleX = 1.1f
                        scaleY = 1.1f
                    },
                contentAlignment = Alignment.Center,
            ) {
                val app = drag.draggingApp
                if (app != null) {
                    AppIcon(app = app, size = 56.dp)
                } else if (di is HomeItem.HomeFolder) {
                    di.apps.firstOrNull()?.let { AppIcon(app = it, size = 56.dp) }
                }
            }
        }
    }

    // ---- シート/ダイアログ類 ----

    when (val current = overlay) {
        is Overlay.HomeItemMenu -> {
            val item = layout.home.getOrNull(current.index) as? HomeItem.HomeApp
            if (item == null) {
                overlay = Overlay.None
            } else {
                val app = item.app
                MenuSheet(
                    entries = listOf(
                        MenuEntry(stringResource(R.string.action_launch), Icons.Rounded.PlayArrow) {
                            openApp(app)
                        },
                        MenuEntry(stringResource(R.string.menu_split_open), Icons.Rounded.Splitscreen) {
                            overlay = Overlay.Picker(PickTarget.SplitSecond(app))
                        },
                        MenuEntry(stringResource(R.string.action_app_info), Icons.Rounded.Info) {
                            LaunchUtils.openAppInfo(context, app.packageName)
                        },
                        MenuEntry(
                            stringResource(R.string.action_remove),
                            Icons.Rounded.Delete,
                            destructive = true,
                        ) {
                            graph.layoutRepository.setHomeItem(current.index, null)
                        },
                        MenuEntry(
                            stringResource(R.string.action_uninstall),
                            Icons.Rounded.Delete,
                            destructive = true,
                        ) {
                            LaunchUtils.requestUninstall(context, app.packageName)
                        },
                    ),
                    onDismiss = { if (overlay == current) overlay = Overlay.None },
                )
            }
        }

        is Overlay.SlotMenu -> {
            val item = layout.dock.getOrNull(current.slot)
            val defaultFolderName = stringResource(R.string.dock_folder_default_name)
            val entries = when (item) {
                null -> listOf(
                    MenuEntry(stringResource(R.string.dock_assign_app), Icons.Rounded.Add) {
                        overlay = Overlay.Picker(PickTarget.DockSlot(current.slot))
                    },
                    MenuEntry(stringResource(R.string.dock_create_folder), Icons.Rounded.Folder) {
                        graph.layoutRepository.convertSlotToFolder(current.slot, defaultFolderName)
                        overlay = Overlay.FolderView(current.slot)
                    },
                )
                is DockItem.DockApp -> listOf(
                    MenuEntry(stringResource(R.string.action_launch), Icons.Rounded.PlayArrow) {
                        openApp(item.app)
                    },
                    MenuEntry(stringResource(R.string.dock_convert_to_folder), Icons.Rounded.Folder) {
                        graph.layoutRepository.convertSlotToFolder(current.slot, defaultFolderName)
                        overlay = Overlay.FolderView(current.slot)
                    },
                    MenuEntry(stringResource(R.string.action_app_info), Icons.Rounded.Info) {
                        LaunchUtils.openAppInfo(context, item.app.packageName)
                    },
                    MenuEntry(
                        stringResource(R.string.action_remove),
                        Icons.Rounded.Delete,
                        destructive = true,
                    ) {
                        graph.layoutRepository.setDockItem(current.slot, null)
                    },
                )
                is DockItem.DockFolder -> listOf(
                    MenuEntry(stringResource(R.string.action_rename), Icons.Rounded.Edit) {
                        overlay = Overlay.RenameFolder(current.slot)
                    },
                    MenuEntry(
                        stringResource(R.string.action_remove),
                        Icons.Rounded.Delete,
                        destructive = true,
                    ) {
                        graph.layoutRepository.setDockItem(current.slot, null)
                    },
                )
            }
            MenuSheet(
                entries = entries,
                onDismiss = { if (overlay == current) overlay = Overlay.None },
            )
        }

        is Overlay.FolderView -> {
            val folder = layout.dock.getOrNull(current.slot) as? DockItem.DockFolder
            if (folder == null) {
                LaunchedEffect(current) {
                    kotlinx.coroutines.delay(600)
                    if (overlay == current &&
                        layout.dock.getOrNull(current.slot) !is DockItem.DockFolder
                    ) {
                        overlay = Overlay.None
                    }
                }
            } else {
                FolderSheet(
                    folderName = folder.name,
                    apps = folder.apps,
                    onLaunch = { app -> openApp(app) },
                    onAddApps = { overlay = Overlay.Picker(PickTarget.FolderAdd(current.slot)) },
                    onRemoveApp = { app ->
                        graph.layoutRepository.removeAppFromFolder(current.slot, app)
                    },
                    onRename = { overlay = Overlay.RenameFolder(current.slot) },
                    onDismiss = { if (overlay == current) overlay = Overlay.None },
                )
            }
        }

        is Overlay.RenameFolder -> {
            val folder = layout.dock.getOrNull(current.slot) as? DockItem.DockFolder
            if (folder == null) {
                overlay = Overlay.None
            } else {
                RenameFolderDialog(
                    currentName = folder.name,
                    onConfirm = { name ->
                        graph.layoutRepository.renameFolder(current.slot, name)
                        overlay = Overlay.FolderView(current.slot)
                    },
                    onDismiss = { overlay = Overlay.FolderView(current.slot) },
                )
            }
        }

        is Overlay.DockSlotChooser -> {
            val slotEmptyLabel = stringResource(R.string.dock_slot_empty)
            val dockFullMessage = stringResource(R.string.toast_dock_full)
            val entries = layout.dock.mapIndexedNotNull { slot, item ->
                when (item) {
                    null -> MenuEntry("${slot + 1}: $slotEmptyLabel", Icons.Rounded.Add) {
                        graph.layoutRepository.addAppToDockSlot(slot, current.app)
                        overlay = Overlay.Drawer
                    }
                    is DockItem.DockFolder -> MenuEntry(
                        "${slot + 1}: ${item.name}",
                        Icons.Rounded.Folder,
                    ) {
                        graph.layoutRepository.addAppToDockSlot(slot, current.app)
                        overlay = Overlay.Drawer
                    }
                    is DockItem.DockApp -> null
                }
            }
            if (entries.isEmpty()) {
                LaunchedEffect(current) {
                    Toast.makeText(context, dockFullMessage, Toast.LENGTH_SHORT).show()
                    overlay = Overlay.Drawer
                }
            } else {
                MenuSheet(
                    entries = entries,
                    onDismiss = { if (overlay == current) overlay = Overlay.Drawer },
                    header = {
                        Text(
                            text = stringResource(R.string.dock_slot_pick_title),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                        )
                    },
                )
            }
        }

        is Overlay.Picker -> {
            val target = current.target
            val excluded = when (target) {
                is PickTarget.FolderAdd ->
                    (layout.dock.getOrNull(target.slot) as? DockItem.DockFolder)
                        ?.apps?.toSet() ?: emptySet()
                // 分割の 2 つ目に 1 つ目と同じアプリは選べない
                is PickTarget.SplitSecond -> setOf(target.first)
                else -> emptySet()
            }
            val pickerTitle = when (target) {
                is PickTarget.SplitFirst -> stringResource(R.string.picker_split_first_title)
                is PickTarget.SplitSecond -> stringResource(R.string.picker_split_second_title)
                else -> stringResource(R.string.picker_title)
            }
            AppPickerSheet(
                apps = apps,
                multiSelect = target is PickTarget.FolderAdd,
                excluded = excluded,
                title = pickerTitle,
                onConfirm = { picked ->
                    val first = picked.firstOrNull()
                    when (target) {
                        // openApp / openSplit は内部で overlay を閉じる
                        // 1 つ目を選んだら 2 つ目の選択へ進む
                        is PickTarget.SplitFirst ->
                            overlay = if (first != null) {
                                Overlay.Picker(PickTarget.SplitSecond(first.ref))
                            } else Overlay.None
                        is PickTarget.SplitSecond -> first?.let { openSplit(target.first, it.ref) }
                        is PickTarget.DockSlot -> {
                            first?.let { graph.layoutRepository.addAppToDockSlot(target.slot, it.ref) }
                            overlay = Overlay.None
                        }
                        is PickTarget.FolderAdd -> {
                            graph.layoutRepository.addAppsToFolder(target.slot, picked.map { it.ref })
                            overlay = Overlay.FolderView(target.slot)
                        }
                    }
                },
                onDismiss = {
                    overlay = when (target) {
                        is PickTarget.FolderAdd -> Overlay.FolderView(target.slot)
                        else -> Overlay.None
                    }
                },
            )
        }

        Overlay.Drawer, Overlay.None -> Unit
    }
}

@Composable
private fun RenameFolderDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** ホームグリッド下部に確保するドック領域の高さ(ドック本体 84dp + 上下マージン)。 */
private val HOME_GRID_BOTTOM_RESERVED = 104.dp
