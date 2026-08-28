package io.github.hatake716.ohagi.ui.dragdrop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.hatake716.ohagi.data.AppRef
import io.github.hatake716.ohagi.data.DockItem
import io.github.hatake716.ohagi.data.HomeItem

/** ドラッグ元の場所。 */
sealed interface DragOrigin {
    data class Home(val index: Int) : DragOrigin
    data class Dock(val slot: Int) : DragOrigin
    /** アプリドロワーから(既存アイテムではなく AppRef の新規設置)。 */
    data object Drawer : DragOrigin
}

/** ドロップ先候補。画面(ルート Box)座標の矩形と結びつける。 */
sealed interface DropTarget {
    val bounds: Rect
    data class HomeCell(val index: Int, override val bounds: Rect) : DropTarget
    data class DockSlot(val slot: Int, override val bounds: Rect) : DropTarget
    /** 画面上部の削除エリア。 */
    data class Trash(override val bounds: Rect) : DropTarget
}

/**
 * 画面全体で 1 つだけ生きるドラッグ状態ホルダ。
 * - HomeGrid/DockBar は自分のセル矩形を「ルート Box 座標」で report する。
 * - ドラッグ追従(指の移動/離し)は、掴んだ元セル(HomeGrid/DockBar/DrawerCell)自身の
 *   pointerInput(detectDragGesturesAfterLongPress)が最後まで担う。onDrag で move、
 *   onDragEnd で親の commitDrop を呼ぶ。ドロワー発でもドロワーを閉じない(透明化するだけ)
 *   ため、そのセルの pointerInput が生き続けドラッグが途切れない。
 * - HomeScreen ルートには追従用 pointerInput は無く、削除エリアと浮遊アイコンの描画のみ。
 * - 浮遊アイコンの描画情報(掴んだ中身/指位置/セル寸)もここに集約する。
 */
class DragController {
    // ---- 登録された全ドロップ先(ルート Box 座標矩形) ----
    private val targets: SnapshotStateMap<String, DropTarget> = mutableStateMapOf()

    fun reportHomeCell(index: Int, bounds: Rect) { targets["H$index"] = DropTarget.HomeCell(index, bounds) }
    fun reportDockSlot(slot: Int, bounds: Rect) { targets["D$slot"] = DropTarget.DockSlot(slot, bounds) }
    fun reportTrash(bounds: Rect) { targets["TRASH"] = DropTarget.Trash(bounds) }
    fun clearTrash() { targets.remove("TRASH") }

    /** 指が削除エリアの上にあるか(ハイライト用)。 */
    fun isOverTrash(): Boolean {
        val trash = targets["TRASH"] ?: return false
        return trash.bounds.contains(fingerPos)
    }

    // ---- ドラッグ中の状態 ----
    var origin by mutableStateOf<DragOrigin?>(null)
        private set

    /** 掴んでいるアプリ(跨ぎ/設置のための AppRef)。フォルダ時は null。 */
    var draggingApp by mutableStateOf<AppRef?>(null)
        private set

    /** 浮遊描画用に掴んだ中身。 */
    var draggingHomeItem by mutableStateOf<HomeItem?>(null)
        private set
    var draggingDockItem by mutableStateOf<DockItem?>(null)
        private set

    /** アプリを掴んだ=跨ぎ許可。フォルダを掴んだ=領域内のみ。 */
    var crossRegionAllowed by mutableStateOf(false)
        private set

    /** ルート Box 原点の指位置。 */
    var fingerPos by mutableStateOf(Offset.Zero)
        private set

    /** 掴んだセルの実寸(px)。浮遊アイコンの中心合わせに使う。 */
    var cellSize by mutableStateOf(Offset.Zero)
        private set

    val isDragging: Boolean get() = origin != null

    fun isSource(o: DragOrigin): Boolean = origin == o

    fun startHome(index: Int, item: HomeItem?, fingerRoot: Offset, size: Offset) {
        origin = DragOrigin.Home(index)
        draggingHomeItem = item
        draggingDockItem = null
        val app = (item as? HomeItem.HomeApp)?.app
        draggingApp = app
        crossRegionAllowed = app != null
        fingerPos = fingerRoot
        cellSize = size
    }

    fun startDock(slot: Int, item: DockItem?, fingerRoot: Offset, size: Offset) {
        origin = DragOrigin.Dock(slot)
        draggingDockItem = item
        draggingHomeItem = null
        val app = (item as? DockItem.DockApp)?.app
        draggingApp = app
        crossRegionAllowed = app != null
        fingerPos = fingerRoot
        cellSize = size
    }

    /** ドロワーからアプリを掴む(新規設置)。跨ぎ相当で Home/Dock どちらにも置ける。 */
    fun startDrawer(app: AppRef, fingerRoot: Offset, size: Offset) {
        origin = DragOrigin.Drawer
        draggingApp = app
        draggingHomeItem = HomeItem.HomeApp(app)
        draggingDockItem = null
        crossRegionAllowed = true
        fingerPos = fingerRoot
        cellSize = size
    }

    fun move(fingerRoot: Offset) { fingerPos = fingerRoot }

    /** 指位置直下のドロップ先。削除エリアを最優先。跨ぎ不可(フォルダ)なら元領域内のみに絞る。 */
    fun resolveDrop(): DropTarget? {
        // 削除エリアが最優先(上部でホーム/ドックと重ならない想定だが明示優先)
        (targets["TRASH"] as? DropTarget.Trash)?.let {
            if (it.bounds.contains(fingerPos)) return it
        }
        val hit = targets.values
            .firstOrNull { it !is DropTarget.Trash && it.bounds.contains(fingerPos) }
            ?: return null
        if (crossRegionAllowed) return hit
        return when (origin) {
            is DragOrigin.Home -> hit as? DropTarget.HomeCell
            is DragOrigin.Dock -> hit as? DropTarget.DockSlot
            else -> null
        }
    }

    fun reset() {
        origin = null
        draggingApp = null
        draggingHomeItem = null
        draggingDockItem = null
        crossRegionAllowed = false
        cellSize = Offset.Zero
    }
}

@Composable
fun rememberDragController(): DragController = remember { DragController() }
