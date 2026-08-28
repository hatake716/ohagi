package io.github.hatake716.ohagi.ui

import android.app.Activity
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hatake716.ohagi.LocalGraph
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.data.AppInfo
import io.github.hatake716.ohagi.data.AppRef
import io.github.hatake716.ohagi.data.DockItem
import io.github.hatake716.ohagi.data.LayoutState
import io.github.hatake716.ohagi.data.Tile
import io.github.hatake716.ohagi.ui.common.AppIcon
import io.github.hatake716.ohagi.ui.common.AppPickerSheet
import io.github.hatake716.ohagi.ui.common.MenuEntry
import io.github.hatake716.ohagi.ui.common.MenuSheet
import io.github.hatake716.ohagi.ui.dock.DockBar
import io.github.hatake716.ohagi.ui.dock.FolderSheet
import io.github.hatake716.ohagi.ui.drawer.AppDrawer
import io.github.hatake716.ohagi.ui.workspace.WorkspaceStrip
import io.github.hatake716.ohagi.ui.workspace.centerOnColumn
import io.github.hatake716.ohagi.ui.workspace.rememberFocusedColumnIndex
import io.github.hatake716.ohagi.util.LaunchUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** いま画面に重なっている UI(同時に 1 つだけ) */
private sealed interface Overlay {
    data object None : Overlay
    data object Drawer : Overlay
    data object HomeMenu : Overlay
    data object About : Overlay
    data class FolderView(val slot: Int) : Overlay
    data class RenameFolder(val slot: Int) : Overlay
    data class TileMenu(val columnId: String, val tileId: String) : Overlay
    data class SlotMenu(val slot: Int) : Overlay
    data class DockSlotChooser(val app: AppRef) : Overlay
    data class Picker(val target: PickTarget) : Overlay
}

/** アプリピッカーで選んだアプリの追加先 */
private sealed interface PickTarget {
    data class NewColumn(val afterIndex: Int?) : PickTarget
    data class SplitTile(val columnId: String, val atStart: Boolean) : PickTarget
    data class DockSlot(val slot: Int) : PickTarget
    data class FolderAdd(val slot: Int) : PickTarget
}

@Composable
fun HomeScreen(homeEvents: Flow<Unit>) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val layout by graph.layoutRepository.state.collectAsStateWithLifecycle()
    val apps by graph.appRepository.apps.collectAsStateWithLifecycle()

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    val listState = rememberLazyListState()
    val focusedIndex by rememberFocusedColumnIndex(listState)

    var overlay by remember { mutableStateOf<Overlay>(Overlay.None) }

    // 各タイルの画面上の矩形。フリーフォームウィンドウの起動先になる。
    val tileRects = remember { mutableStateMapOf<String, android.graphics.Rect>() }
    // タイル領域に実ウィンドウを開けるか(フリーフォーム対応端末)
    val freeform = remember { LaunchUtils.isFreeformAvailable(context) }

    // HOME 再押下: オーバーレイを閉じて先頭カラムへ
    LaunchedEffect(Unit) {
        homeEvents.collect {
            overlay = Overlay.None
            listState.centerOnColumn(0)
        }
    }

    // 新しく追加されたカラムへ自動スクロール(niri のフォーカス追従)
    var knownColumnIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(layout.columns) {
        val ids = layout.columns.map { it.id }
        val added = ids.filterNot { it in knownColumnIds }
        if (knownColumnIds.isNotEmpty() && added.isNotEmpty()) {
            listState.centerOnColumn(ids.indexOf(added.last()))
        }
        knownColumnIds = ids.toSet()
    }

    // 画面回転時はフォーカス中カラムを中央に合わせ直す
    LaunchedEffect(isPortrait) {
        listState.centerOnColumn(focusedIndex, animated = false)
    }

    // ホーム画面ではバックキーを無効化(ランチャーの標準挙動)
    BackHandler(enabled = overlay == Overlay.None) { }
    BackHandler(enabled = overlay != Overlay.None) { overlay = Overlay.None }

    fun launchApp(app: AppRef) = LaunchUtils.launch(context, app)

    /** タイルをタップしたとき: フリーフォーム対応ならタイル矩形に実ウィンドウを開く。 */
    fun launchTile(tile: Tile) {
        val rect = tileRects[tile.id]
        if (freeform && rect != null) {
            LaunchUtils.launchInBounds(context, tile.app, rect)
        } else {
            launchApp(tile.app)
        }
    }

    /**
     * スクロールが落ち着いてタイル矩形が安定するのを待つ。
     * 画面外にはみ出た矩形(スクロール途中)を渡すとシステムが起動 bounds を
     * 破棄してしまうため、完全に画面内へ収まっていることも条件にする。
     */
    suspend fun awaitStableTileRect(tileId: String): android.graphics.Rect? {
        val metrics = context.resources.displayMetrics
        return withTimeoutOrNull(2500) {
            var stable: android.graphics.Rect? = null
            var prev: android.graphics.Rect? = null
            while (stable == null) {
                delay(120)
                val current = tileRects[tileId]
                val onScreen = current != null &&
                    current.left >= 0 && current.top >= 0 &&
                    current.right <= metrics.widthPixels &&
                    current.bottom <= metrics.heightPixels
                if (onScreen && current == prev && !listState.isScrollInProgress) {
                    stable = current
                }
                prev = current
            }
            stable
        }
    }

    /**
     * アプリをワークスペースのウィンドウとして開く。
     * niri と同じく、新しいウィンドウはフォーカス中カラムの右に追加され、
     * リボンが滑らかにスクロールしてからタイル領域に実ウィンドウが立ち上がる。
     * 既にワークスペースにあるアプリは、そのカラムへフォーカスを移して起動する。
     * フリーフォーム非対応端末では従来どおりの起動になる。
     */
    fun openAppInWorkspace(app: AppRef) {
        if (!freeform) {
            launchApp(app)
            return
        }
        val existing = layout.columns.asSequence()
            .flatMap { column -> column.tiles.asSequence().map { column to it } }
            .firstOrNull { (_, tile) -> tile.app == app }
        val tileId = if (existing != null) {
            scope.launch {
                listState.centerOnColumn(layout.columns.indexOf(existing.first))
            }
            existing.second.id
        } else {
            // 自動スクロールは LaunchedEffect(layout.columns) が担う
            graph.layoutRepository.addColumnAfter(focusedIndex, app)
        }
        scope.launch {
            val rect = awaitStableTileRect(tileId)
            if (rect != null) {
                LaunchUtils.launchInBounds(context, app, rect)
            } else {
                launchApp(app)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 上スワイプでドロワーを開く
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var dragged = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dragged = 0f },
                        onVerticalDrag = { _, dragAmount -> dragged += dragAmount },
                        onDragEnd = {
                            if (dragged < -120.dp.toPx() && overlay == Overlay.None) {
                                overlay = Overlay.Drawer
                            }
                        },
                    )
                }
        ) {
            WorkspaceStrip(
                columns = layout.columns,
                listState = listState,
                isPortrait = isPortrait,
                onLaunchTile = { _, tile -> launchTile(tile) },
                onTileLongPress = { column, tile ->
                    overlay = Overlay.TileMenu(column.id, tile.id)
                },
                onTileBounds = { tileId, rect -> tileRects[tileId] = rect },
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(bottom = DOCK_RESERVED_HEIGHT),
            )
        }

        DockBar(
            dock = layout.dock,
            onSlotTap = { slot ->
                when (val item = layout.dock.getOrNull(slot)) {
                    is DockItem.DockApp -> openAppInWorkspace(item.app)
                    is DockItem.DockFolder -> overlay = Overlay.FolderView(slot)
                    null -> overlay = Overlay.Picker(PickTarget.DockSlot(slot))
                }
            },
            onSlotLongPress = { slot -> overlay = Overlay.SlotMenu(slot) },
            onLauncherTap = { overlay = Overlay.Drawer },
            onLauncherLongPress = { overlay = Overlay.HomeMenu },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )

        // ドロワー表示中(開閉アニメーション含む)は背面へのタッチを遮断する
        if (overlay == Overlay.Drawer) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent()
                                    .changes
                                    .forEach { it.consume() }
                            }
                        }
                    }
            )
        }

        // アプリドロワー(全画面オーバーレイ)
        AnimatedVisibility(
            visible = overlay == Overlay.Drawer,
            enter = slideInVertically(initialOffsetY = { it / 4 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 4 }) + fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            AppDrawer(
                apps = apps,
                onLaunch = { app ->
                    overlay = Overlay.None
                    openAppInWorkspace(app.ref)
                },
                onAddToWorkspace = { app ->
                    graph.layoutRepository.addColumnAfter(focusedIndex, app.ref)
                    overlay = Overlay.None
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
                onDismiss = { overlay = Overlay.None },
            )
        }
    }

    // ---- シート/ダイアログ類 ----

    when (val current = overlay) {
        is Overlay.TileMenu -> {
            val column = layout.columns.firstOrNull { it.id == current.columnId }
            val tile = column?.tiles?.firstOrNull { it.id == current.tileId }
            if (column == null || tile == null) {
                overlay = Overlay.None
            } else {
                val columnIndex = layout.columns.indexOf(column)
                val entries = buildList {
                    add(MenuEntry(stringResource(R.string.action_launch), Icons.Rounded.PlayArrow) {
                        launchTile(tile)
                    })
                    if (column.tiles.size == 2) {
                        add(MenuEntry(stringResource(R.string.action_split_launch), Icons.Rounded.VerticalSplit) {
                            val rects = column.tiles.map { tileRects[it.id] }
                            if (freeform && rects.all { it != null }) {
                                // 2 タイルそれぞれの矩形に実ウィンドウを並べて開く(niri の分割そのもの)
                                column.tiles.forEachIndexed { i, t ->
                                    LaunchUtils.launchInBounds(context, t.app, rects[i]!!)
                                }
                            } else {
                                scope.launch {
                                    LaunchUtils.launchSplit(
                                        context,
                                        column.tiles[0].app,
                                        column.tiles[1].app,
                                    )
                                }
                            }
                        })
                        add(MenuEntry(stringResource(R.string.menu_swap_tiles), Icons.Rounded.SwapVert) {
                            graph.layoutRepository.swapTiles(column.id)
                        })
                    } else {
                        add(MenuEntry(
                            stringResource(
                                if (isPortrait) R.string.menu_add_tile_above
                                else R.string.menu_add_tile_left
                            ),
                            Icons.Rounded.Add,
                        ) {
                            overlay = Overlay.Picker(PickTarget.SplitTile(column.id, atStart = true))
                        })
                        add(MenuEntry(
                            stringResource(
                                if (isPortrait) R.string.menu_add_tile_below
                                else R.string.menu_add_tile_right
                            ),
                            Icons.Rounded.Add,
                        ) {
                            overlay = Overlay.Picker(PickTarget.SplitTile(column.id, atStart = false))
                        })
                    }
                    add(MenuEntry(stringResource(R.string.menu_cycle_width), Icons.Rounded.UnfoldMore) {
                        graph.layoutRepository.cycleWidth(column.id)
                    })
                    if (columnIndex > 0) {
                        add(MenuEntry(stringResource(R.string.menu_move_left), Icons.Rounded.ChevronLeft) {
                            graph.layoutRepository.moveColumn(column.id, -1)
                        })
                    }
                    if (columnIndex < layout.columns.size - 1) {
                        add(MenuEntry(stringResource(R.string.menu_move_right), Icons.Rounded.ChevronRight) {
                            graph.layoutRepository.moveColumn(column.id, +1)
                        })
                    }
                    add(MenuEntry(stringResource(R.string.action_app_info), Icons.Rounded.Info) {
                        LaunchUtils.openAppInfo(context, tile.app.packageName)
                    })
                    add(MenuEntry(
                        stringResource(R.string.menu_remove_tile),
                        Icons.Rounded.Delete,
                        destructive = true,
                    ) {
                        graph.layoutRepository.removeTile(column.id, tile.id)
                    })
                }
                MenuSheet(
                    entries = entries,
                    onDismiss = { if (overlay == current) overlay = Overlay.None },
                    header = { TileMenuHeader(tile.app) },
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
                        launchApp(item.app)
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
                // フォルダ作成直後は DataStore への書き込み反映を少し待つ(反映されなければ閉じる)
                LaunchedEffect(current) {
                    delay(600)
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
                    onLaunch = { app ->
                        overlay = Overlay.None
                        openAppInWorkspace(app)
                    },
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
            val excluded = if (target is PickTarget.FolderAdd) {
                (layout.dock.getOrNull(target.slot) as? DockItem.DockFolder)
                    ?.apps?.toSet() ?: emptySet()
            } else {
                emptySet()
            }
            AppPickerSheet(
                apps = apps,
                multiSelect = target is PickTarget.FolderAdd,
                excluded = excluded,
                onConfirm = { picked ->
                    applyPick(graph.layoutRepository, layout, target, picked)
                    overlay = when (target) {
                        is PickTarget.FolderAdd -> Overlay.FolderView(target.slot)
                        else -> Overlay.None
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

        Overlay.HomeMenu -> {
            val entries = listOf(
                MenuEntry(stringResource(R.string.menu_add_app), Icons.Rounded.Add) {
                    overlay = Overlay.Picker(PickTarget.NewColumn(focusedIndex))
                },
                MenuEntry(stringResource(R.string.menu_change_wallpaper), Icons.Rounded.Wallpaper) {
                    LaunchUtils.openWallpaperPicker(context)
                },
                MenuEntry(stringResource(R.string.menu_set_default_home), Icons.Rounded.Home) {
                    (context as? Activity)?.let { LaunchUtils.requestDefaultHome(it) }
                },
                MenuEntry(stringResource(R.string.menu_about), Icons.Rounded.Info) {
                    overlay = Overlay.About
                },
            )
            MenuSheet(
                entries = entries,
                onDismiss = { if (overlay == current) overlay = Overlay.None },
            )
        }

        Overlay.About -> {
            val versionName = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                } catch (_: Exception) {
                    null
                } ?: "?"
            }
            AlertDialog(
                onDismissRequest = { overlay = Overlay.None },
                confirmButton = {
                    TextButton(onClick = { overlay = Overlay.None }) {
                        Text(stringResource(R.string.action_ok))
                    }
                },
                text = { Text(stringResource(R.string.about_text, versionName)) },
            )
        }

        Overlay.Drawer, Overlay.None -> Unit
    }
}

private fun applyPick(
    repository: io.github.hatake716.ohagi.data.LayoutRepository,
    layout: LayoutState,
    target: PickTarget,
    picked: List<AppInfo>,
) {
    val first = picked.firstOrNull() ?: return
    when (target) {
        is PickTarget.NewColumn -> repository.addColumnAfter(
            target.afterIndex ?: (layout.columns.size - 1),
            first.ref,
        )
        is PickTarget.SplitTile -> repository.addTileToColumn(
            target.columnId,
            first.ref,
            target.atStart,
        )
        is PickTarget.DockSlot -> repository.addAppToDockSlot(target.slot, first.ref)
        is PickTarget.FolderAdd -> repository.addAppsToFolder(
            target.slot,
            picked.map { it.ref },
        )
    }
}

@Composable
private fun TileMenuHeader(app: AppRef) {
    val graph = LocalGraph.current
    val label = remember(app) { graph.appRepository.labelOf(app) }
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        AppIcon(app = app, size = 36.dp)
        androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 14.dp))
        Text(text = label)
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

// ドック本体 84dp + 上下マージン 10dp×2。ワークスペース側は navigationBarsPadding 併用のため
// ナビゲーションバー形式(ジェスチャー/3ボタン)に依らずタイルとドックが重ならない。
private val DOCK_RESERVED_HEIGHT = 104.dp
