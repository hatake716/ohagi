package io.github.hatake716.ohagi.ui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Rect
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Wallpaper
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hatake716.ohagi.LocalGraph
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.data.AppInfo
import io.github.hatake716.ohagi.data.AppRef
import io.github.hatake716.ohagi.data.DockItem
import io.github.hatake716.ohagi.data.LayoutState
import io.github.hatake716.ohagi.data.Pane
import io.github.hatake716.ohagi.ui.common.AppIcon
import io.github.hatake716.ohagi.ui.common.AppPickerSheet
import io.github.hatake716.ohagi.ui.common.MenuEntry
import io.github.hatake716.ohagi.ui.common.MenuSheet
import io.github.hatake716.ohagi.ui.dock.DockBar
import io.github.hatake716.ohagi.ui.dock.FolderSheet
import io.github.hatake716.ohagi.ui.drawer.AppDrawer
import io.github.hatake716.ohagi.ui.workspace.TilingWorkspace
import io.github.hatake716.ohagi.util.LaunchUtils
import io.github.hatake716.ohagi.util.TilingManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** いま画面に重なっている UI(同時に 1 つだけ) */
private sealed interface Overlay {
    data object None : Overlay
    data object Drawer : Overlay
    data object HomeMenu : Overlay
    data object About : Overlay
    data class FolderView(val slot: Int) : Overlay
    data class RenameFolder(val slot: Int) : Overlay
    data class PaneMenu(val paneId: String) : Overlay
    data class SlotMenu(val slot: Int) : Overlay
    data class DockSlotChooser(val app: AppRef) : Overlay
    data class Picker(val target: PickTarget) : Overlay
}

/** アプリピッカーで選んだアプリの追加先 */
private sealed interface PickTarget {
    data object NewPane : PickTarget
    data class DockSlot(val slot: Int) : PickTarget
    data class FolderAdd(val slot: Int) : PickTarget
}

@Composable
fun HomeScreen(homeEvents: Flow<Unit>) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val layout by graph.layoutRepository.state.collectAsStateWithLifecycle()
    val apps by graph.appRepository.apps.collectAsStateWithLifecycle()

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    val freeform = remember { LaunchUtils.isFreeformAvailable(context) }

    var overlay by remember { mutableStateOf<Overlay>(Overlay.None) }

    // タイリング領域(ドックを除いた実効領域, px)。ワークスペースのレイアウトから受け取る。
    var tilingArea by remember { mutableStateOf<Rect?>(null) }
    val gapPx = remember(density) { with(density) { 10.dp.toPx() }.toInt() }

    // HOME 再押下: オーバーレイを閉じる
    LaunchedEffect(Unit) {
        homeEvents.collect {
            overlay = Overlay.None
        }
    }

    // ホーム画面ではバックキーを無効化(ランチャーの標準挙動)
    BackHandler(enabled = overlay == Overlay.None) { }
    BackHandler(enabled = overlay != Overlay.None) { overlay = Overlay.None }

    fun launchApp(app: AppRef) = LaunchUtils.launch(context, app)

    /** 現在の panes を計算矩形へタイリング配置して(再)起動する。 */
    fun retile(panes: List<Pane>, onlyLast: Boolean) {
        val area = tilingArea
        if (!freeform || area == null || panes.isEmpty()) {
            // フリーフォーム非対応 or 領域未確定なら、単に末尾(新規)を通常起動
            panes.lastOrNull()?.let { launchApp(it.app) }
            return
        }
        scope.launch {
            TilingManager.retile(
                context = context,
                panes = panes,
                tilingArea = area,
                isPortrait = isPortrait,
                gapPx = gapPx,
                onlyLast = onlyLast,
            )
        }
    }

    /**
     * アプリをタイリングに開く。
     * 既に開いていればそのペインを前面化、なければ追加(最大 [MAX_PANES]、超過分は最古を押し出し)。
     * 追加後は全ペインを再タイリングして 1 画面に並べる。
     */
    fun openApp(app: AppRef) {
        val before = graph.layoutRepository.state.value.panes.size
        graph.layoutRepository.addPane(app)
        // addPane は非同期反映のため、次フレームの panes を使って retile する。
        scope.launch {
            delay(60)
            val panes = graph.layoutRepository.state.value.panes
            // ペイン数が増えた(分割数が変わった)ときは全ペインを開き直して並べ直す。
            // 数が変わらない(既存アプリの前面化)ときは末尾だけで足りる。
            retile(panes, onlyLast = panes.size == before)
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
            TilingWorkspace(
                panes = layout.panes,
                isPortrait = isPortrait,
                // ペインをタップ: そのアプリのウィンドウを前面化(タイル位置で再起動)
                onPaneTap = { pane -> launchApp(pane.app) },
                onPaneLongPress = { pane -> overlay = Overlay.PaneMenu(pane.id) },
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(bottom = DOCK_RESERVED_HEIGHT)
                    .onGloballyPositioned { coords ->
                        val b = coords.boundsInWindow()
                        tilingArea = Rect(
                            b.left.toInt(), b.top.toInt(),
                            b.right.toInt(), b.bottom.toInt(),
                        )
                    },
            )
        }

        DockBar(
            dock = layout.dock,
            onSlotTap = { slot ->
                when (val item = layout.dock.getOrNull(slot)) {
                    is DockItem.DockApp -> openApp(item.app)
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
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    }
            )
        }

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
                    openApp(app.ref)
                },
                onAddToWorkspace = { app ->
                    overlay = Overlay.None
                    openApp(app.ref)
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
        is Overlay.PaneMenu -> {
            val pane = layout.panes.firstOrNull { it.id == current.paneId }
            if (pane == null) {
                overlay = Overlay.None
            } else {
                val isMaster = layout.panes.firstOrNull()?.id == pane.id
                val entries = buildList {
                    add(MenuEntry(stringResource(R.string.action_launch), Icons.Rounded.PlayArrow) {
                        launchApp(pane.app)
                    })
                    if (!isMaster && layout.panes.size >= 2) {
                        add(MenuEntry(stringResource(R.string.menu_make_master), Icons.Rounded.Star) {
                            graph.layoutRepository.promotePane(pane.id)
                            scope.launch {
                                delay(60)
                                retile(graph.layoutRepository.state.value.panes, onlyLast = false)
                            }
                        })
                    }
                    add(MenuEntry(stringResource(R.string.action_app_info), Icons.Rounded.Info) {
                        LaunchUtils.openAppInfo(context, pane.app.packageName)
                    })
                    add(MenuEntry(
                        stringResource(R.string.menu_close_pane),
                        Icons.Rounded.Delete,
                        destructive = true,
                    ) {
                        graph.layoutRepository.removePane(pane.id)
                        scope.launch {
                            delay(60)
                            val rest = graph.layoutRepository.state.value.panes
                            if (rest.isNotEmpty()) retile(rest, onlyLast = false)
                        }
                    })
                }
                MenuSheet(
                    entries = entries,
                    onDismiss = { if (overlay == current) overlay = Overlay.None },
                    header = { PaneMenuHeader(pane.app) },
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
                        openApp(app)
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
                    applyPick(graph.layoutRepository, target, picked) { app -> openApp(app) }
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
            val entries = buildList {
                add(MenuEntry(stringResource(R.string.menu_add_app), Icons.Rounded.Add) {
                    overlay = Overlay.Picker(PickTarget.NewPane)
                })
                if (layout.panes.size >= 2) {
                    add(MenuEntry(stringResource(R.string.menu_retile), Icons.Rounded.GridView) {
                        // 全ペインを新しいタイル位置で開き直す(整列)。
                        // 既存ウィンドウは前面化されるだけなので、事前に一度閉じるヒントを出す。
                        Toast.makeText(context, R.string.toast_retile_hint, Toast.LENGTH_SHORT).show()
                        retile(layout.panes, onlyLast = false)
                    })
                }
                add(MenuEntry(stringResource(R.string.menu_close_all), Icons.Rounded.Close) {
                    graph.layoutRepository.clearPanes()
                })
                add(MenuEntry(stringResource(R.string.menu_change_wallpaper), Icons.Rounded.Wallpaper) {
                    LaunchUtils.openWallpaperPicker(context)
                })
                add(MenuEntry(stringResource(R.string.menu_set_default_home), Icons.Rounded.Home) {
                    (context as? Activity)?.let { LaunchUtils.requestDefaultHome(it) }
                })
                add(MenuEntry(stringResource(R.string.menu_about), Icons.Rounded.Info) {
                    overlay = Overlay.About
                })
            }
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
    target: PickTarget,
    picked: List<AppInfo>,
    openApp: (AppRef) -> Unit,
) {
    val first = picked.firstOrNull() ?: return
    when (target) {
        is PickTarget.NewPane -> openApp(first.ref)
        is PickTarget.DockSlot -> repository.addAppToDockSlot(target.slot, first.ref)
        is PickTarget.FolderAdd -> repository.addAppsToFolder(target.slot, picked.map { it.ref })
    }
}

@Composable
private fun PaneMenuHeader(app: AppRef) {
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

private val DOCK_RESERVED_HEIGHT = 104.dp
