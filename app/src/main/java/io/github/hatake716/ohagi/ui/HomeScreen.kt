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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
import io.github.hatake716.ohagi.ui.common.AppPickerSheet
import io.github.hatake716.ohagi.ui.common.MenuEntry
import io.github.hatake716.ohagi.ui.common.MenuSheet
import io.github.hatake716.ohagi.ui.dock.DockBar
import io.github.hatake716.ohagi.ui.dock.FolderSheet
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
    /** ホームグリッドの index セルに置く。 */
    data class HomeSlot(val index: Int) : PickTarget
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

    Box(modifier = Modifier.fillMaxSize()) {
        // 背面ジェスチャ層: 主画面は壁紙のみ(ミニマル)。
        // - 画面中〜上部からの大きい上スワイプ → ドロワーを開く。
        // - 画面下端付近からの上スワイプ → 隠れたドックを一時表示する。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // 閾値・画面高はジェスチャ発生時の live な値を使う。
                    // configChanges 宣言で回転しても Activity は再生成されず pointerInput(Unit) も
                    // 貼り直されないため、ブロック先頭で size.height を固定すると回転後に破綻する。
                    val edgeThresholdPx = with(density) { 120.dp.toPx() }
                    val smallSwipePx = with(density) { 40.dp.toPx() }
                    val drawerSwipePx = with(density) { 120.dp.toPx() }
                    var dragged = 0f
                    var fromBottomEdge = false
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragged = 0f
                            // 開始位置が下端エッジ内か(その時点の実画面高で判定)
                            fromBottomEdge = offset.y > size.height - edgeThresholdPx
                        },
                        onVerticalDrag = { _, dragAmount -> dragged += dragAmount },
                        onDragEnd = {
                            when {
                                // 下端からの上スワイプ → ドックを一時表示
                                fromBottomEdge && dragged < -smallSwipePx &&
                                    overlay == Overlay.None -> {
                                    dockVisible = true
                                }
                                // それ以外の大きい上スワイプ → ドロワー
                                dragged < -drawerSwipePx && overlay == Overlay.None -> {
                                    overlay = Overlay.Drawer
                                }
                            }
                        },
                    )
                }
        )

        // ホーム主画面のアイコングリッド(壁紙の上)。
        // ステータスバーを避け、下はドックの高さ分を空ける。userScrollEnabled=false なので
        // グリッド外余白の上スワイプは背面ジェスチャ層に届き、ドロワー起動を妨げない。
        HomeGrid(
            home = layout.home,
            onCellTap = { index ->
                when (val item = layout.home.getOrNull(index)) {
                    is HomeItem.HomeApp -> openApp(item.app)
                    is HomeItem.HomeFolder -> Unit // フォルダ生成 UI は未実装
                    null -> overlay = Overlay.Picker(PickTarget.HomeSlot(index))
                }
            },
            onCellLongPress = { index ->
                if (layout.home.getOrNull(index) is HomeItem.HomeApp) {
                    overlay = Overlay.HomeItemMenu(index)
                } else {
                    // 空きセル長押しでも追加できるようにする
                    overlay = Overlay.Picker(PickTarget.HomeSlot(index))
                }
            },
            onSwap = { from, to -> graph.layoutRepository.swapHomeItems(from, to) },
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = HOME_GRID_BOTTOM_RESERVED),
        )

        // ドック(自動非表示・iOS風スプリング)。BottomCenter にオーバーレイ配置。
        AnimatedVisibility(
            visible = dockVisible,
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
                onSlotTap = { slot ->
                    when (val item = layout.dock.getOrNull(slot)) {
                        is DockItem.DockApp -> openApp(item.app)
                        is DockItem.DockFolder -> overlay = Overlay.FolderView(slot)
                        null -> overlay = Overlay.Picker(PickTarget.DockSlot(slot))
                    }
                },
                onSlotLongPress = { slot -> overlay = Overlay.SlotMenu(slot) },
                onLauncherTap = { overlay = Overlay.Drawer },
                // 中央ボタン長押し = 分割画面の設定(1 つ目 → 2 つ目を選ぶ)のみ
                onLauncherLongPress = { overlay = Overlay.Picker(PickTarget.SplitFirst) },
                onSwapDock = { from, to -> graph.layoutRepository.swapDockItems(from, to) },
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

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
                        is PickTarget.HomeSlot -> {
                            first?.let { graph.layoutRepository.addAppToHomeSlot(target.index, it.ref) }
                            overlay = Overlay.None
                        }
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
