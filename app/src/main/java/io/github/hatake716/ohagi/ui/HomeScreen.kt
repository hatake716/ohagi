package io.github.hatake716.ohagi.ui

import android.app.Activity
import android.appwidget.AppWidgetProviderInfo
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.hatake716.ohagi.LocalGraph
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.data.AppInfo
import io.github.hatake716.ohagi.data.AppMoveSource
import io.github.hatake716.ohagi.data.AppRef
import io.github.hatake716.ohagi.data.DockItem
import io.github.hatake716.ohagi.data.FolderLocation
import io.github.hatake716.ohagi.data.HomeItem
import io.github.hatake716.ohagi.data.folderAt
import io.github.hatake716.ohagi.data.homeGlobalIndex
import io.github.hatake716.ohagi.data.homePage
import io.github.hatake716.ohagi.data.homePageCount
import io.github.hatake716.ohagi.ui.common.AppPickerSheet
import io.github.hatake716.ohagi.ui.common.APP_LIBRARY_MINI_ICON_SIZE
import io.github.hatake716.ohagi.ui.common.APP_LIBRARY_PREVIEW_ICON_SIZE
import io.github.hatake716.ohagi.ui.common.MenuEntry
import io.github.hatake716.ohagi.ui.common.MenuSheet
import io.github.hatake716.ohagi.ui.common.appCategoryTitleRes
import io.github.hatake716.ohagi.ui.common.appLibraryPrefetchBudget
import io.github.hatake716.ohagi.ui.common.buildAppLibraryIconPrefetchRequests
import io.github.hatake716.ohagi.ui.dock.DockBar
import io.github.hatake716.ohagi.ui.dragdrop.DragPayload
import io.github.hatake716.ohagi.ui.dragdrop.isOhagiRemovableDrag
import io.github.hatake716.ohagi.ui.dragdrop.isRemovable
import io.github.hatake716.ohagi.ui.dragdrop.ohagiDropTarget
import io.github.hatake716.ohagi.ui.dragdrop.rememberOhagiDropTarget
import io.github.hatake716.ohagi.ui.drawer.AppDrawer
import io.github.hatake716.ohagi.ui.folder.IosFolderOverlay
import io.github.hatake716.ohagi.ui.home.HomeGrid
import io.github.hatake716.ohagi.ui.widget.WidgetPage
import io.github.hatake716.ohagi.ui.widget.WidgetPickerSheet
import io.github.hatake716.ohagi.util.LaunchUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** いま画面に重なっている UI(同時に 1 つだけ) */
private sealed interface Overlay {
    data object None : Overlay
    data object WidgetPicker : Overlay
    data class FolderView(val location: FolderLocation) : Overlay
    data class RenameFolder(val location: FolderLocation) : Overlay
    data class SlotMenu(val slot: Int) : Overlay
    data class DockSlotChooser(val app: AppRef) : Overlay
    /** ホームグリッドのセル右側「その他」メニュー(index のセル) */
    data class HomeItemMenu(val index: Int) : Overlay
    data class Picker(val target: PickTarget) : Overlay
}

/** アプリピッカーで選んだアプリの追加先 */
private sealed interface PickTarget {
    data class DockSlot(val slot: Int) : PickTarget
    data class FolderAdd(val location: FolderLocation) : PickTarget
    data class FolderCreate(val location: FolderLocation) : PickTarget
}

@Composable
fun HomeScreen(
    homeEvents: Flow<Unit>,
    onRequestWidget: (AppWidgetProviderInfo) -> Unit,
    onLaunchApp: (AppRef) -> Unit,
) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val latestLayout by graph.layoutRepository.state.collectAsStateWithLifecycle()
    val latestApps by graph.appRepository.apps.collectAsStateWithLifecycle()

    var overlay by remember { mutableStateOf<Overlay>(Overlay.None) }
    var layout by remember { mutableStateOf(latestLayout) }
    val pagerState = rememberPagerState(
        initialPage = PRIMARY_HOME_PAGER_INDEX,
        pageCount = { layout.homePageCount + STATIC_PAGE_COUNT },
    )
    // DataStoreの初回読込や直前操作の書込み完了も、指が動いている間は
    // Pagerのページ数・全セルを差し替えず、settle後に一度で反映する。
    LaunchedEffect(latestLayout, pagerState) {
        if (pagerState.isScrollInProgress) {
            snapshotFlow { pagerState.isScrollInProgress }
                .first { scrolling -> !scrolling }
        }
        layout = latestLayout
    }
    var apps by remember { mutableStateOf(latestApps) }
    // PackageManagerの全件結果がPager操作中に届いても、ホームとAppライブラリを
    // 同じフレームで総入れ替えしない。指が止まった直後に最新一覧へ追従する。
    LaunchedEffect(latestApps, pagerState) {
        if (pagerState.isScrollInProgress) {
            snapshotFlow { pagerState.isScrollInProgress }
                .first { scrolling -> !scrolling }
        }
        apps = latestApps
    }
    val appLabelOf = remember(apps) { buildAppLabelResolver(apps) }
    val appLibraryPage = layout.homePageCount + 1

    // HOME再押下ではオーバーレイを閉じ、必ず1枚目のホームへ戻る。
    LaunchedEffect(Unit) {
        homeEvents.collect {
            overlay = Overlay.None
            pagerState.scrollToPage(PRIMARY_HOME_PAGER_INDEX)
        }
    }

    BackHandler(enabled = overlay == Overlay.None && pagerState.currentPage != PRIMARY_HOME_PAGER_INDEX) {
        scope.launch { pagerState.animateScrollToPage(PRIMARY_HOME_PAGER_INDEX) }
    }
    // 1枚目のホームではバックキーを無効化(ランチャーの標準挙動)
    BackHandler(enabled = overlay == Overlay.None && pagerState.currentPage == PRIMARY_HOME_PAGER_INDEX) { }
    BackHandler(enabled = overlay != Overlay.None) { overlay = Overlay.None }

    /** 単体でフルスクリーン起動する。 */
    fun openApp(app: AppRef) {
        overlay = Overlay.None
        onLaunchApp(app)
    }

    // ---- 公式 Compose Drag and Drop ----
    var activeDrag by remember { mutableStateOf<DragPayload?>(null) }
    var trashHovered by remember { mutableStateOf(false) }
    var trashBounds by remember { mutableStateOf<Rect?>(null) }
    var rootBounds by remember { mutableStateOf<Rect?>(null) }
    var edgeTransitionInProgress by remember { mutableStateOf(false) }
    var edgeDestinationHomePage by remember { mutableStateOf<Int?>(null) }
    var createdPageDuringDrag by remember { mutableStateOf(false) }
    var edgeDropCommitted by remember { mutableStateOf(false) }
    var edgeDropBaselineHome by remember { mutableStateOf<List<HomeItem?>?>(null) }
    var pageLimitToastShown by remember { mutableStateOf(false) }
    val repo = graph.layoutRepository
    val defaultFolderName = stringResource(R.string.dock_folder_default_name)
    val density = LocalDensity.current
    val edgeZonePx = with(density) { HOME_PAGE_EDGE_ZONE.toPx() }
    val appLibraryIconRequests = remember(apps, density.density) {
        val prefetchBudget = appLibraryPrefetchBudget(graph.appRepository.isLowRamDevice)
        buildAppLibraryIconPrefetchRequests(
            apps = apps,
            previewIconSizePx = with(density) {
                APP_LIBRARY_PREVIEW_ICON_SIZE.roundToPx()
            },
            miniIconSizePx = with(density) {
                APP_LIBRARY_MINI_ICON_SIZE.roundToPx()
            },
            categoryLimit = prefetchBudget.fullCategoryCount,
            partialCategoryLimit = prefetchBudget.partialCategoryCount,
        )
    }
    // 初期ホームが落ち着いた時だけ、Appライブラリ上端の画像を画面外で用意する。
    // onPauseでJobを止めるため、通常のアプリ起動とCPUを奪い合わない。
    LifecycleResumeEffect(appLibraryIconRequests, pagerState) {
        val prefetchJob = scope.launch {
            if (appLibraryIconRequests.isEmpty()) return@launch
            snapshotFlow {
                pagerState.currentPage == PRIMARY_HOME_PAGER_INDEX &&
                    !pagerState.isScrollInProgress &&
                    overlay == Overlay.None &&
                    activeDrag == null
            }.collectLatest { idleOnHome ->
                if (idleOnHome) {
                    // collectLatestにより、指が動き始めた時点で先読みをcancelする。
                    // 進行中の1画像だけを終えた後、Pagerの表示要求へworkerを譲る。
                    delay(APP_LIBRARY_PREFETCH_DELAY_MS)
                    if (
                        pagerState.currentPage == PRIMARY_HOME_PAGER_INDEX &&
                        !pagerState.isScrollInProgress &&
                        overlay == Overlay.None &&
                        activeDrag == null
                    ) {
                        graph.appRepository.prefetchIcons(appLibraryIconRequests)
                    }
                }
            }
        }
        onPauseOrDispose { prefetchJob.cancel() }
    }

    LaunchedEffect(layout.home, edgeDropCommitted, edgeDropBaselineHome) {
        val baseline = edgeDropBaselineHome ?: return@LaunchedEffect
        if (edgeDropCommitted && layout.home != baseline) {
            // 元の追加ページが空になって同時回収されても、生成後の最終ホームを表示する。
            pagerState.scrollToPage(layout.homePageCount)
            edgeDestinationHomePage = null
            edgeDropCommitted = false
            edgeDropBaselineHome = null
            delay(HOME_PAGE_EDGE_COOLDOWN_MS)
            edgeTransitionInProgress = false
        }
    }

    // 空になった中間／末尾ページが消えた時も、表示していた論理ページを維持する。
    LaunchedEffect(pagerState) {
        var previousPage = pagerState.currentPage
        var previousHomePageCount = layout.homePageCount
        snapshotFlow { pagerState.currentPage to layout.homePageCount }
            .collect { (currentPage, currentHomePageCount) ->
                if (currentHomePageCount < previousHomePageCount) {
                    val destination = when {
                        previousPage == previousHomePageCount + 1 -> currentHomePageCount + 1
                        previousPage in 1..previousHomePageCount ->
                            previousPage.coerceAtMost(currentHomePageCount)
                        else -> currentPage.coerceIn(0, currentHomePageCount + 1)
                    }
                    if (destination != currentPage) {
                        pagerState.animateScrollToPage(destination)
                        previousPage = destination
                    } else {
                        previousPage = currentPage
                    }
                } else {
                    previousPage = currentPage
                }
                previousHomePageCount = currentHomePageCount
            }
    }

    LaunchedEffect(activeDrag) {
        if (activeDrag == null) {
            edgeTransitionInProgress = false
            if (!edgeDropCommitted) {
                edgeDestinationHomePage = null
                edgeDropBaselineHome = null
            }
            pageLimitToastShown = false
        }
    }

    fun appMoveSource(payload: DragPayload): AppMoveSource? = when (payload) {
        is DragPayload.FromDrawer -> AppMoveSource.External(payload.app)
        is DragPayload.FromHome ->
            if (layout.home.getOrNull(payload.index) is HomeItem.HomeApp) {
                AppMoveSource.Home(payload.index)
            } else null
        is DragPayload.FromDock ->
            if (layout.dock.getOrNull(payload.slot) is DockItem.DockApp) {
                AppMoveSource.Dock(payload.slot)
            } else null
        is DragPayload.FromHomeFolder ->
            AppMoveSource.HomeFolder(payload.index, payload.appIndex, payload.app)
        is DragPayload.FromDockFolder ->
            AppMoveSource.DockFolder(payload.slot, payload.appIndex, payload.app)
    }

    fun draggedApp(payload: DragPayload): AppRef? = when (payload) {
        is DragPayload.FromDrawer -> payload.app
        is DragPayload.FromHome ->
            (layout.home.getOrNull(payload.index) as? HomeItem.HomeApp)?.app
        is DragPayload.FromDock ->
            (layout.dock.getOrNull(payload.slot) as? DockItem.DockApp)?.app
        is DragPayload.FromHomeFolder -> payload.app
        is DragPayload.FromDockFolder -> payload.app
    }

    fun suggestedFolderName(refs: List<AppRef>): String {
        val categories = refs
            .mapNotNull { ref -> apps.firstOrNull { it.ref == ref }?.category }
            .distinct()
        return if (categories.size == 1) {
            context.getString(appCategoryTitleRes(categories.single()))
        } else {
            defaultFolderName
        }
    }

    fun canStackOnHome(index: Int, payload: DragPayload): Boolean {
        val target = layout.home.getOrNull(index) ?: return false
        val sourceApp = draggedApp(payload) ?: return false
        if (payload is DragPayload.FromHome && payload.index == index) return false
        if (payload is DragPayload.FromHomeFolder && payload.index == index) return false
        return when (target) {
            is HomeItem.HomeApp -> target.app != sourceApp
            is HomeItem.HomeFolder -> true
        }
    }

    fun canStackOnDock(slot: Int, payload: DragPayload): Boolean {
        val target = layout.dock.getOrNull(slot) ?: return false
        val sourceApp = draggedApp(payload) ?: return false
        if (payload is DragPayload.FromDock && payload.slot == slot) return false
        if (payload is DragPayload.FromDockFolder && payload.slot == slot) return false
        return when (target) {
            is DockItem.DockApp -> target.app != sourceApp
            is DockItem.DockFolder -> true
        }
    }

    fun folderNameForHome(index: Int, payload: DragPayload): String {
        val target = layout.home.getOrNull(index)
        if (target is HomeItem.HomeFolder) return target.name
        val refs = buildList {
            (target as? HomeItem.HomeApp)?.app?.let(::add)
            draggedApp(payload)?.let(::add)
        }
        return suggestedFolderName(refs)
    }

    fun folderNameForDock(slot: Int, payload: DragPayload): String {
        val target = layout.dock.getOrNull(slot)
        if (target is DockItem.DockFolder) return target.name
        val refs = buildList {
            (target as? DockItem.DockApp)?.app?.let(::add)
            draggedApp(payload)?.let(::add)
        }
        return suggestedFolderName(refs)
    }

    /**
     * ターゲット自身がセルindexを持つ。アイコン中央へのドロップだけをフォルダ化とし、
     * セル外周へのドロップは従来どおり移動／入れ替えに使う。
     */
    fun dropOnHome(
        index: Int,
        payload: DragPayload,
        stackIntent: Boolean,
    ): Boolean {
        val source = appMoveSource(payload)
        val targetIsFolder = layout.home.getOrNull(index) is HomeItem.HomeFolder
        if (source != null) {
            if ((stackIntent || targetIsFolder) && canStackOnHome(index, payload)) {
                repo.stackAppOnHome(index, source, folderNameForHome(index, payload))
            } else {
                repo.moveAppToHome(index, source)
            }
            return true
        }
        return when (payload) {
            is DragPayload.FromHome -> {
                if (layout.home.getOrNull(payload.index) == null) false
                else {
                    repo.swapHomeItems(payload.index, index)
                    true
                }
            }
            is DragPayload.FromDock -> {
                if (layout.dock.getOrNull(payload.slot) == null) false
                else {
                    repo.moveDockToHome(payload.slot, index)
                    true
                }
            }
            else -> false
        }
    }

    fun dropOnDock(
        slot: Int,
        payload: DragPayload,
        stackIntent: Boolean,
    ): Boolean {
        val source = appMoveSource(payload)
        val targetIsFolder = layout.dock.getOrNull(slot) is DockItem.DockFolder
        if (source != null) {
            if ((stackIntent || targetIsFolder) && canStackOnDock(slot, payload)) {
                repo.stackAppOnDock(slot, source, folderNameForDock(slot, payload))
            } else {
                repo.moveAppToDock(slot, source)
            }
            return true
        }
        return when (payload) {
            is DragPayload.FromDock -> {
                if (layout.dock.getOrNull(payload.slot) == null) false
                else {
                    repo.swapDockItems(payload.slot, slot)
                    true
                }
            }
            is DragPayload.FromHome -> {
                if (layout.home.getOrNull(payload.index) == null) false
                else {
                    repo.moveHomeToDock(payload.index, slot)
                    true
                }
            }
            else -> false
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
        is DragPayload.FromHomeFolder -> {
            val folder = layout.home.getOrNull(payload.index) as? HomeItem.HomeFolder
            if (folder?.apps?.contains(payload.app) != true) false
            else {
                repo.removeAppFromFolder(FolderLocation.Home(payload.index), payload.app)
                true
            }
        }
        is DragPayload.FromDockFolder -> {
            val folder = layout.dock.getOrNull(payload.slot) as? DockItem.DockFolder
            if (folder?.apps?.contains(payload.app) != true) false
            else {
                repo.removeAppFromFolder(FolderLocation.Dock(payload.slot), payload.app)
                true
            }
        }
        is DragPayload.FromDrawer -> false
    }

    fun isTrashDrop(payload: DragPayload, position: Offset): Boolean =
        payload.isRemovable() && trashBounds?.contains(position) == true

    fun dropOnDestinationHomePage(
        destinationPage: Int,
        payload: DragPayload,
    ): Boolean {
        appMoveSource(payload)?.let { source ->
            if (createdPageDuringDrag) {
                edgeDropBaselineHome = layout.home
                edgeDropCommitted = true
            }
            repo.moveAppToHomePage(destinationPage, source)
            return true
        }
        val accepted = when (payload) {
            is DragPayload.FromHome -> {
                if (layout.home.getOrNull(payload.index) == null) false
                else {
                    repo.moveHomeItemToHomePage(destinationPage, payload.index)
                    true
                }
            }

            is DragPayload.FromDock -> {
                if (layout.dock.getOrNull(payload.slot) == null) false
                else {
                    repo.moveDockItemToHomePage(destinationPage, payload.slot)
                    true
                }
            }

            else -> false
        }
        if (accepted && createdPageDuringDrag) {
            edgeDropBaselineHome = layout.home
            edgeDropCommitted = true
        }
        return accepted
    }

    // Composeは重なる兄弟targetのうちホームセルを先に選ぶ場合があるため、
    // 受け取った公式DragEventのルート位置がpill内なら削除処理へ委譲する。
    fun routeDropOnHome(
        index: Int,
        payload: DragPayload,
        position: Offset,
        stackIntent: Boolean,
    ): Boolean {
        val destinationPage = edgeDestinationHomePage
        return if (createdPageDuringDrag && destinationPage != null) {
            dropOnDestinationHomePage(destinationPage, payload)
        } else if (isTrashDrop(payload, position)) dropOnTrash(payload)
        else dropOnHome(index, payload, stackIntent)
    }

    fun routeDropOnDock(
        slot: Int,
        payload: DragPayload,
        position: Offset,
        stackIntent: Boolean,
    ): Boolean {
        val destinationPage = edgeDestinationHomePage
        return if (createdPageDuringDrag && destinationPage != null) {
            dropOnDestinationHomePage(destinationPage, payload)
        } else if (isTrashDrop(payload, position)) dropOnTrash(payload)
        else dropOnDock(slot, payload, stackIntent)
    }

    fun updateDragPosition(position: Offset) {
        val payload = activeDrag
        trashHovered = payload != null && isTrashDrop(payload, position)
        if (payload == null || trashHovered || edgeTransitionInProgress) return
        val bounds = rootBounds ?: return
        val currentPage = pagerState.currentPage
        if (currentPage !in 1..layout.homePageCount) return
        val atRightEdge = position.x >= bounds.right - edgeZonePx
        if (createdPageDuringDrag && !atRightEdge) {
            createdPageDuringDrag = false
            edgeDestinationHomePage = null
        }

        when {
            atRightEdge -> {
                edgeTransitionInProgress = true
                when {
                    currentPage < layout.homePageCount -> scope.launch {
                        edgeDestinationHomePage = currentPage
                        pagerState.scrollToPage(currentPage + 1)
                        delay(HOME_PAGE_EDGE_COOLDOWN_MS)
                        edgeTransitionInProgress = false
                    }

                    createdPageDuringDrag -> {
                        // 1回のドラッグで新規作成するページは1枚だけにする。
                        edgeTransitionInProgress = false
                    }

                    layout.homePageCount < io.github.hatake716.ohagi.data.LayoutState.MAX_HOME_PAGE_COUNT -> {
                        createdPageDuringDrag = true
                        edgeDestinationHomePage = layout.homePageCount
                        // OSのDROPまでは現在のセルtargetを維持し、DROP内で生成と移動を原子的に行う。
                        edgeTransitionInProgress = false
                    }

                    else -> {
                        edgeTransitionInProgress = false
                        if (!pageLimitToastShown) {
                            pageLimitToastShown = true
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.toast_home_pages_full,
                                    io.github.hatake716.ohagi.data.LayoutState.MAX_HOME_PAGE_COUNT,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }

            position.x <= bounds.left + edgeZonePx && currentPage > PRIMARY_HOME_PAGER_INDEX -> {
                edgeTransitionInProgress = true
                edgeDestinationHomePage = currentPage - 2
                scope.launch {
                    // D&D中はウィジェットページへは入らず、ホームページ間だけを移動する。
                    pagerState.scrollToPage(currentPage - 1)
                    delay(HOME_PAGE_EDGE_COOLDOWN_MS)
                    edgeTransitionInProgress = false
                }
            }
        }
    }

    /**
     * ページ追加／切替で元のセルtargetが破棄された場合のフォールバック。
     * 目的ページの先頭空きセルへ移し、ページ生成も同じDataStore更新内で保証する。
     */
    fun dropOnPagerFallback(payload: DragPayload, position: Offset): Boolean {
        if (isTrashDrop(payload, position)) return dropOnTrash(payload)
        val visibleHomePage = (pagerState.currentPage - 1)
            .coerceIn(0, layout.homePageCount - 1)
        val destinationPage = edgeDestinationHomePage ?: visibleHomePage
        if (destinationPage !in 0 until io.github.hatake716.ohagi.data.LayoutState.MAX_HOME_PAGE_COUNT) {
            return false
        }

        return dropOnDestinationHomePage(destinationPage, payload)
    }

    fun endDragSession() {
        trashHovered = false
        activeDrag = null
        createdPageDuringDrag = false
    }

    // HomeGridの各セルがPager再構成で入れ替わっても、この親targetはセッション中存続する。
    val pagerFallbackTarget = rememberOhagiDropTarget(
        onStarted = { activeDrag = it },
        onMoved = ::updateDragPosition,
        onEnded = ::endDragSession,
        onDrop = ::dropOnPagerFallback,
    )

    val trashTarget = rememberOhagiDropTarget(
        onStarted = { activeDrag = it },
        onEntered = { trashHovered = true },
        onMoved = { position ->
            trashHovered = activeDrag?.let { isTrashDrop(it, position) } == true
        },
        onExited = { trashHovered = false },
        onEnded = ::endDragSession,
        onDrop = { payload, _ ->
            trashHovered = false
            dropOnTrash(payload)
        },
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootBounds = it.boundsInRoot() }
            .ohagiDropTarget(pagerFallbackTarget),
    ) {
        // DragAndDropNodeはレイアウトツリーの先頭から候補を探索するため、ホームと重なる
        // 削除領域を先にcomposeする。zIndexで描画も最前面に保つ。
        // 常時composeしてACTION_DRAG_STARTEDを受け取り、通常時だけ透明化する。
        val showTrash = activeDrag?.isRemovable() == true
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

        // DragAndDropNodeは同じComposeView内で先に登録された候補を優先する。
        // フォルダをホーム／Dockより先にcomposeし、背面セルがフォルダ内D&Dを
        // 奪わないようにする。描画順はzIndexで前面、削除領域より下に保つ。
        (overlay as? Overlay.FolderView)?.let { current ->
            val folder = layout.folderAt(current.location)
            if (folder == null) {
                LaunchedEffect(current) {
                    kotlinx.coroutines.delay(500)
                    if (overlay == current && layout.folderAt(current.location) == null) {
                        overlay = Overlay.None
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(9f),
                ) {
                    IosFolderOverlay(
                        location = current.location,
                        folderName = folder.name,
                        apps = folder.apps,
                        labelOf = appLabelOf,
                        activeDrag = activeDrag,
                        onLaunch = ::openApp,
                        onAddApps = {
                            overlay = Overlay.Picker(PickTarget.FolderAdd(current.location))
                        },
                        onRemoveApp = { app ->
                            val removingLastApp = folder.apps.size == 1 && folder.apps.single() == app
                            repo.removeAppFromFolder(current.location, app)
                            if (removingLastApp && overlay == current) {
                                overlay = Overlay.None
                            }
                        },
                        onRename = {
                            overlay = Overlay.RenameFolder(current.location)
                        },
                        onReorder = { from, to ->
                            repo.reorderFolderApps(current.location, from, to)
                        },
                        onDragMoved = ::updateDragPosition,
                        onDragStarted = { activeDrag = it },
                        onDragEnded = ::endDragSession,
                        onDragOutside = {
                            if (overlay == current) overlay = Overlay.None
                        },
                        onDismiss = {
                            if (overlay == current) overlay = Overlay.None
                        },
                    )
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            userScrollEnabled = activeDrag == null && overlay == Overlay.None,
            key = { page ->
                when {
                    page == WIDGET_PAGER_INDEX -> "widgets"
                    page in 1..layout.homePageCount -> "home-${page - 1}"
                    else -> "app-library"
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when {
                page == WIDGET_PAGER_INDEX -> WidgetPage(
                    widgets = layout.widgets,
                    onAddWidget = { overlay = Overlay.WidgetPicker },
                    onRemoveWidget = { placement ->
                        graph.widgetHost.deleteAppWidgetId(placement.appWidgetId)
                        repo.removeWidget(placement.appWidgetId)
                    },
                    onMoveWidget = { placement, direction ->
                        repo.reorderWidget(placement.appWidgetId, direction)
                    },
                    onResizeWidget = { placement, widthDp, heightDp ->
                        repo.resizeWidget(placement.appWidgetId, widthDp, heightDp)
                    },
                )

                page in 1..layout.homePageCount -> {
                    val homePage = page - 1
                    HomeGrid(
                        home = layout.homePage(homePage),
                        indexOffset = homeGlobalIndex(homePage, 0),
                        activeDrag = activeDrag,
                        labelOf = appLabelOf,
                        onCellTap = { index ->
                            when (val item = layout.home.getOrNull(index)) {
                                is HomeItem.HomeApp -> openApp(item.app)
                                is HomeItem.HomeFolder ->
                                    overlay = Overlay.FolderView(FolderLocation.Home(index))
                                else -> Unit
                            }
                        },
                        onCellMenu = { index ->
                            if (layout.home.getOrNull(index) != null) {
                                overlay = Overlay.HomeItemMenu(index)
                            }
                        },
                        onDrop = ::routeDropOnHome,
                        canStack = ::canStackOnHome,
                        onDragMoved = ::updateDragPosition,
                        onDragSessionStarted = { activeDrag = it },
                        onDragSessionEnded = ::endDragSession,
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(bottom = HOME_GRID_BOTTOM_RESERVED),
                    )
                }

                page == appLibraryPage -> AppDrawer(
                    apps = apps,
                    onLaunch = { app -> openApp(app.ref) },
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
                        // OSのD&Dセッションを保ったまま、最後のホームページを露出する。
                        scope.launch { pagerState.scrollToPage(layout.homePageCount) }
                    },
                    onDismiss = {
                        scope.launch { pagerState.animateScrollToPage(PRIMARY_HOME_PAGER_INDEX) }
                    },
                )
            }
        }

        if (layout.homePageCount > 1 && pagerState.currentPage in 1..layout.homePageCount) {
            HomePageIndicator(
                pageCount = layout.homePageCount,
                selectedPage = pagerState.currentPage - 1,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = HOME_PAGE_INDICATOR_BOTTOM),
            )
        }

        // Dockはホームページだけに表示し、WidgetページとAppライブラリでは隠す。
        AnimatedVisibility(
            visible = pagerState.currentPage in 1..layout.homePageCount,
            enter = fadeIn(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(120)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            DockBar(
                dock = layout.dock,
                activeDrag = activeDrag,
                labelOf = appLabelOf,
                onSlotTap = { slot ->
                    when (val item = layout.dock.getOrNull(slot)) {
                        is DockItem.DockApp -> openApp(item.app)
                        is DockItem.DockFolder ->
                            overlay = Overlay.FolderView(FolderLocation.Dock(slot))
                        null -> Unit
                    }
                },
                onSlotMenu = { slot -> overlay = Overlay.SlotMenu(slot) },
                onDrop = ::routeDropOnDock,
                canStack = ::canStackOnDock,
                onDragMoved = ::updateDragPosition,
                onDragSessionStarted = { payload ->
                    activeDrag = payload
                    if (pagerState.currentPage == appLibraryPage) {
                        scope.launch { pagerState.scrollToPage(layout.homePageCount) }
                    }
                },
                onDragSessionEnded = ::endDragSession,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

    }

    // ---- シート/ダイアログ類 ----

    when (val current = overlay) {
        is Overlay.HomeItemMenu -> {
            when (val item = layout.home.getOrNull(current.index)) {
                null -> overlay = Overlay.None
                is HomeItem.HomeApp -> {
                    val app = item.app
                    MenuSheet(
                        entries = listOf(
                            MenuEntry(stringResource(R.string.action_launch), Icons.Rounded.PlayArrow) {
                                openApp(app)
                            },
                            MenuEntry(
                                stringResource(R.string.folder_create_with_apps),
                                Icons.Rounded.Folder,
                            ) {
                                overlay = Overlay.Picker(
                                    PickTarget.FolderCreate(FolderLocation.Home(current.index)),
                                )
                            },
                            MenuEntry(stringResource(R.string.action_app_info), Icons.Rounded.Info) {
                                LaunchUtils.openAppInfo(context, app.packageName)
                            },
                            MenuEntry(
                                stringResource(R.string.action_remove),
                                Icons.Rounded.Delete,
                                destructive = true,
                            ) {
                                repo.setHomeItem(current.index, null)
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

                is HomeItem.HomeFolder -> {
                    val location = FolderLocation.Home(current.index)
                    MenuSheet(
                        entries = listOf(
                            MenuEntry(item.name, Icons.Rounded.Folder) {
                                overlay = Overlay.FolderView(location)
                            },
                            MenuEntry(stringResource(R.string.folder_add_apps), Icons.Rounded.Add) {
                                overlay = Overlay.Picker(PickTarget.FolderAdd(location))
                            },
                            MenuEntry(stringResource(R.string.action_rename), Icons.Rounded.Edit) {
                                overlay = Overlay.RenameFolder(location)
                            },
                            MenuEntry(
                                stringResource(R.string.action_remove),
                                Icons.Rounded.Delete,
                                destructive = true,
                            ) {
                                repo.setHomeItem(current.index, null)
                            },
                        ),
                        onDismiss = { if (overlay == current) overlay = Overlay.None },
                    )
                }
            }
        }

        is Overlay.SlotMenu -> {
            val item = layout.dock.getOrNull(current.slot)
            val entries = when (item) {
                null -> listOf(
                    MenuEntry(stringResource(R.string.dock_assign_app), Icons.Rounded.Add) {
                        overlay = Overlay.Picker(PickTarget.DockSlot(current.slot))
                    },
                )
                is DockItem.DockApp -> listOf(
                    MenuEntry(stringResource(R.string.action_launch), Icons.Rounded.PlayArrow) {
                        openApp(item.app)
                    },
                    MenuEntry(
                        stringResource(R.string.folder_create_with_apps),
                        Icons.Rounded.Folder,
                    ) {
                        overlay = Overlay.Picker(
                            PickTarget.FolderCreate(FolderLocation.Dock(current.slot)),
                        )
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
                is DockItem.DockFolder -> {
                    val location = FolderLocation.Dock(current.slot)
                    listOf(
                        MenuEntry(item.name, Icons.Rounded.Folder) {
                            overlay = Overlay.FolderView(location)
                        },
                        MenuEntry(stringResource(R.string.folder_add_apps), Icons.Rounded.Add) {
                            overlay = Overlay.Picker(PickTarget.FolderAdd(location))
                        },
                        MenuEntry(stringResource(R.string.action_rename), Icons.Rounded.Edit) {
                            overlay = Overlay.RenameFolder(location)
                        },
                        MenuEntry(
                            stringResource(R.string.action_remove),
                            Icons.Rounded.Delete,
                            destructive = true,
                        ) {
                            repo.setDockItem(current.slot, null)
                        },
                    )
                }
            }
            MenuSheet(
                entries = entries,
                onDismiss = { if (overlay == current) overlay = Overlay.None },
            )
        }

        is Overlay.FolderView -> Unit

        is Overlay.RenameFolder -> {
            val folder = layout.folderAt(current.location)
            if (folder == null) {
                overlay = Overlay.None
            } else {
                RenameFolderDialog(
                    currentName = folder.name,
                    onConfirm = { name ->
                        repo.renameFolder(current.location, name)
                        overlay = Overlay.FolderView(current.location)
                    },
                    onDismiss = { overlay = Overlay.FolderView(current.location) },
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
                        overlay = Overlay.None
                    }
                    is DockItem.DockFolder -> MenuEntry(
                        "${slot + 1}: ${item.name}",
                        Icons.Rounded.Folder,
                    ) {
                        graph.layoutRepository.addAppToDockSlot(slot, current.app)
                        overlay = Overlay.None
                    }
                    is DockItem.DockApp -> null
                }
            }
            if (entries.isEmpty()) {
                LaunchedEffect(current) {
                    Toast.makeText(context, dockFullMessage, Toast.LENGTH_SHORT).show()
                    overlay = Overlay.None
                }
            } else {
                MenuSheet(
                    entries = entries,
                    onDismiss = { if (overlay == current) overlay = Overlay.None },
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
            fun appsAt(location: FolderLocation): List<AppRef> = when (location) {
                is FolderLocation.Home -> when (val item = layout.home.getOrNull(location.index)) {
                    is HomeItem.HomeApp -> listOf(item.app)
                    is HomeItem.HomeFolder -> item.apps
                    null -> emptyList()
                }
                is FolderLocation.Dock -> when (val item = layout.dock.getOrNull(location.slot)) {
                    is DockItem.DockApp -> listOf(item.app)
                    is DockItem.DockFolder -> item.apps
                    null -> emptyList()
                }
            }
            val excluded = when (target) {
                is PickTarget.FolderAdd -> appsAt(target.location).toSet()
                is PickTarget.FolderCreate -> appsAt(target.location).toSet()
                else -> emptySet()
            }
            val pickerTitle = when (target) {
                is PickTarget.FolderAdd -> stringResource(R.string.folder_add_apps)
                is PickTarget.FolderCreate -> stringResource(R.string.folder_create_with_apps)
                else -> stringResource(R.string.picker_title)
            }
            AppPickerSheet(
                apps = apps,
                multiSelect = target is PickTarget.FolderAdd ||
                    target is PickTarget.FolderCreate,
                excluded = excluded,
                title = pickerTitle,
                onConfirm = { picked ->
                    val first = picked.firstOrNull()
                    when (target) {
                        is PickTarget.DockSlot -> {
                            first?.let { graph.layoutRepository.addAppToDockSlot(target.slot, it.ref) }
                            overlay = Overlay.None
                        }
                        is PickTarget.FolderAdd -> {
                            repo.addAppsToFolder(target.location, picked.map { it.ref })
                            overlay = Overlay.FolderView(target.location)
                        }
                        is PickTarget.FolderCreate -> {
                            val refs = appsAt(target.location) + picked.map { it.ref }
                            repo.createOrAddFolder(
                                location = target.location,
                                name = suggestedFolderName(refs),
                                apps = picked.map { it.ref },
                            )
                            overlay = Overlay.FolderView(target.location)
                        }
                    }
                },
                onDismiss = {
                    overlay = when (target) {
                        is PickTarget.FolderAdd -> Overlay.FolderView(target.location)
                        else -> Overlay.None
                    }
                },
            )
        }

        Overlay.WidgetPicker -> {
            val providers = remember(apps) { graph.widgetHost.installedProviders() }
            WidgetPickerSheet(
                providers = providers,
                onSelect = { provider ->
                    overlay = Overlay.None
                    onRequestWidget(provider)
                },
                onDismiss = { overlay = Overlay.None },
            )
        }

        Overlay.None -> Unit
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

@Composable
private fun HomePageIndicator(
    pageCount: Int,
    selectedPage: Int,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(
        R.string.home_page_description,
        selectedPage + 1,
        pageCount,
    )
    Row(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .semantics { contentDescription = description }
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.22f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        repeat(pageCount) { page ->
            Box(
                modifier = Modifier
                    .size(if (page == selectedPage) 7.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (page == selectedPage) Color.White
                        else Color.White.copy(alpha = 0.42f),
                    ),
            )
        }
    }
}

/** ホームグリッド下部に確保するドック領域の高さ(ドック本体 84dp + 上下マージン)。 */
private val HOME_GRID_BOTTOM_RESERVED = 104.dp
private val HOME_PAGE_EDGE_ZONE = 36.dp
private val HOME_PAGE_INDICATOR_BOTTOM = 100.dp
private const val HOME_PAGE_EDGE_COOLDOWN_MS = 420L
private const val APP_LIBRARY_PREFETCH_DELAY_MS = 260L
private const val WIDGET_PAGER_INDEX = 0
private const val PRIMARY_HOME_PAGER_INDEX = 1
private const val STATIC_PAGE_COUNT = 2

private fun buildAppLabelResolver(apps: List<AppInfo>): (AppRef) -> String {
    val exactLabels = apps.associate { it.ref to it.label }
    val packageLabels = apps
        .distinctBy { it.ref.packageName }
        .associate { it.ref.packageName to it.label }
    return { ref ->
        exactLabels[ref]
            ?: packageLabels[ref.packageName]
            ?: ref.packageName.substringAfterLast('.')
    }
}
