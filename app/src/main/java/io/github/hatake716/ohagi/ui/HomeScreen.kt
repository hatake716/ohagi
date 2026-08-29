package io.github.hatake716.ohagi.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hatake716.ohagi.LocalGraph
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.data.AppRef
import io.github.hatake716.ohagi.data.DockItem
import io.github.hatake716.ohagi.data.HomeItem
import io.github.hatake716.ohagi.ui.common.AppPickerSheet
import io.github.hatake716.ohagi.ui.common.MenuEntry
import io.github.hatake716.ohagi.ui.common.MenuSheet
import io.github.hatake716.ohagi.ui.dock.DockBar
import io.github.hatake716.ohagi.ui.dock.FolderSheet
import io.github.hatake716.ohagi.ui.dragdrop.DragPayload
import io.github.hatake716.ohagi.ui.dragdrop.isOhagiRemovableDrag
import io.github.hatake716.ohagi.ui.dragdrop.ohagiDropTarget
import io.github.hatake716.ohagi.ui.dragdrop.rememberOhagiDropTarget
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
    /** ホームグリッドのセル右側「その他」メニュー(index のセル) */
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

    val layout by graph.layoutRepository.state.collectAsStateWithLifecycle()
    val apps by graph.appRepository.apps.collectAsStateWithLifecycle()

    var overlay by remember { mutableStateOf<Overlay>(Overlay.None) }

    // HOME 再押下ではオーバーレイだけを閉じる。ドックは常時表示。
    LaunchedEffect(Unit) {
        homeEvents.collect {
            overlay = Overlay.None
        }
    }

    // ホーム画面ではバックキーを無効化(ランチャーの標準挙動)
    BackHandler(enabled = overlay == Overlay.None) { }
    BackHandler(enabled = overlay != Overlay.None) { overlay = Overlay.None }

    /** 単体でフルスクリーン起動する。 */
    fun openApp(app: AppRef) {
        overlay = Overlay.None
        LaunchUtils.launch(context, app)
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
    }

    // ---- 公式 Compose Drag and Drop ----
    var activeDrag by remember { mutableStateOf<DragPayload?>(null) }
    var trashHovered by remember { mutableStateOf(false) }
    var trashBounds by remember { mutableStateOf<Rect?>(null) }
    val repo = graph.layoutRepository

    /** ターゲット自身がセルindexを持つため、画面座標のresolve処理は不要。 */
    fun dropOnHome(index: Int, payload: DragPayload): Boolean = when (payload) {
        is DragPayload.FromDrawer -> {
            if (layout.home.getOrNull(index) is HomeItem.HomeFolder) false
            else {
                repo.placeAppOnHome(index, payload.app)
                true
            }
        }
        is DragPayload.FromHome -> {
            if (layout.home.getOrNull(payload.index) == null) false
            else {
                repo.swapHomeItems(payload.index, index)
                true
            }
        }
        is DragPayload.FromDock -> {
            val source = layout.dock.getOrNull(payload.slot)
            val target = layout.home.getOrNull(index)
            if (source !is DockItem.DockApp || target is HomeItem.HomeFolder) false
            else {
                repo.moveDockToHome(payload.slot, index)
                true
            }
        }
    }

    fun dropOnDock(slot: Int, payload: DragPayload): Boolean = when (payload) {
        is DragPayload.FromDrawer -> {
            if (layout.dock.getOrNull(slot) is DockItem.DockFolder) false
            else {
                repo.placeAppOnDock(slot, payload.app)
                true
            }
        }
        is DragPayload.FromDock -> {
            if (layout.dock.getOrNull(payload.slot) == null) false
            else {
                repo.swapDockItems(payload.slot, slot)
                true
            }
        }
        is DragPayload.FromHome -> {
            val source = layout.home.getOrNull(payload.index)
            val target = layout.dock.getOrNull(slot)
            if (source !is HomeItem.HomeApp || target is DockItem.DockFolder) false
            else {
                repo.moveHomeToDock(payload.index, slot)
                true
            }
        }
    }

    fun dropOnTrash(payload: DragPayload): Boolean = when (payload) {
        is DragPayload.FromHome -> {
            if (layout.home.getOrNull(payload.index) == null) false
            else {
                repo.setHomeItem(payload.index, null)
                true
            }
        }
        is DragPayload.FromDock -> {
            if (layout.dock.getOrNull(payload.slot) == null) false
            else {
                repo.setDockItem(payload.slot, null)
                true
            }
        }
        is DragPayload.FromDrawer -> false
    }

    fun isTrashDrop(payload: DragPayload, position: Offset): Boolean =
        payload !is DragPayload.FromDrawer && trashBounds?.contains(position) == true

    // Composeは重なる兄弟targetのうちホームセルを先に選ぶ場合があるため、
    // 受け取った公式DragEventのルート位置がpill内なら削除処理へ委譲する。
    fun routeDropOnHome(index: Int, payload: DragPayload, position: Offset): Boolean =
        if (isTrashDrop(payload, position)) dropOnTrash(payload)
        else dropOnHome(index, payload)

    fun routeDropOnDock(slot: Int, payload: DragPayload, position: Offset): Boolean =
        if (isTrashDrop(payload, position)) dropOnTrash(payload)
        else dropOnDock(slot, payload)

    fun updateTrashHover(position: Offset) {
        val payload = activeDrag
        trashHovered = payload != null && isTrashDrop(payload, position)
    }

    fun endDragSession() {
        trashHovered = false
        activeDrag = null
    }

    val trashTarget = rememberOhagiDropTarget(
        onStarted = { activeDrag = it },
        onEntered = { trashHovered = true },
        onMoved = ::updateTrashHover,
        onExited = { trashHovered = false },
        onEnded = ::endDragSession,
        onDrop = { payload, _ ->
            trashHovered = false
            dropOnTrash(payload)
        },
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // DragAndDropNodeはレイアウトツリーの先頭から候補を探索するため、ホームと重なる
        // 削除領域を先にcomposeする。zIndexで描画も最前面に保つ。
        // 常時composeしてACTION_DRAG_STARTEDを受け取り、通常時だけ透明化する。
        val showTrash = activeDrag is DragPayload.FromHome || activeDrag is DragPayload.FromDock
        val trashVisibility by animateFloatAsState(
            targetValue = if (showTrash) 1f else 0f,
            animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
            label = "trashVisibility",
        )
        val trashColor by animateColorAsState(
            targetValue = if (trashHovered) Color(0xE6D32F2F) else Color(0xB31C1C1E),
            animationSpec = tween(durationMillis = 120),
            label = "trashColor",
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(10f)
                .onGloballyPositioned { trashBounds = it.boundsInRoot() }
                .statusBarsPadding()
                .padding(top = 12.dp)
                .graphicsLayer {
                    alpha = trashVisibility
                    val scale = 0.92f + trashVisibility * 0.08f
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(24.dp))
                .background(trashColor)
                .ohagiDropTarget(
                    target = trashTarget,
                    accept = { event -> event.isOhagiRemovableDrag() },
                )
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

        HomeGrid(
            home = layout.home,
            activeDrag = activeDrag,
            onCellTap = { index ->
                when (val item = layout.home.getOrNull(index)) {
                    is HomeItem.HomeApp -> openApp(item.app)
                    else -> Unit
                }
            },
            onCellMenu = { index ->
                if (layout.home.getOrNull(index) is HomeItem.HomeApp) {
                    overlay = Overlay.HomeItemMenu(index)
                }
            },
            onDrop = ::routeDropOnHome,
            onDragMoved = ::updateTrashHover,
            onDragSessionStarted = { activeDrag = it },
            onDragSessionEnded = ::endDragSession,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = HOME_GRID_BOTTOM_RESERVED),
        )

        // 「賢い自動非表示」は廃止し、ドックを常に同じ位置へ表示する。
        DockBar(
            dock = layout.dock,
            activeDrag = activeDrag,
            onSlotTap = { slot ->
                when (val item = layout.dock.getOrNull(slot)) {
                    is DockItem.DockApp -> openApp(item.app)
                    is DockItem.DockFolder -> overlay = Overlay.FolderView(slot)
                    null -> Unit
                }
            },
            onSlotMenu = { slot -> overlay = Overlay.SlotMenu(slot) },
            onLauncherTap = { overlay = Overlay.Drawer },
            onLauncherLongPress = { overlay = Overlay.Picker(PickTarget.SplitFirst) },
            onDrop = ::routeDropOnDock,
            onDragMoved = ::updateTrashHover,
            onDragSessionStarted = { activeDrag = it },
            onDragSessionEnded = ::endDragSession,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )

        // iOSのApp Libraryに近い、短いフェード＋小さな移動／拡大で表示する。
        AnimatedVisibility(
            visible = overlay == Overlay.Drawer,
            enter = slideInVertically(
                animationSpec = spring(
                    dampingRatio = 0.90f,
                    stiffness = 680f,
                ),
            ) { it / 12 } + scaleIn(
                initialScale = 0.975f,
                animationSpec = spring(
                    dampingRatio = 0.90f,
                    stiffness = 680f,
                ),
                transformOrigin = TransformOrigin(0.5f, 0.88f),
            ) + fadeIn(animationSpec = tween(durationMillis = 150)),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
            ) { it / 16 } + scaleOut(
                targetScale = 0.98f,
                animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                transformOrigin = TransformOrigin(0.5f, 0.88f),
            ) + fadeOut(animationSpec = tween(durationMillis = 130)),
            modifier = Modifier.fillMaxSize(),
        ) {
            AppDrawer(
                apps = apps,
                onLaunch = { app -> openApp(app.ref) },
                onAddToWorkspace = { app ->
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
                onDragStarted = { payload ->
                    activeDrag = payload
                    // startDragAndDrop 後はOSがセッションを保持するため、元セルを安全に外せる。
                    overlay = Overlay.None
                },
                onDismiss = { overlay = Overlay.None },
            )
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
