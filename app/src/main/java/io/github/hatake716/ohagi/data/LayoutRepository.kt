package io.github.hatake716.ohagi.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private object LayoutStateSerializer : Serializer<LayoutState> {
    override val defaultValue: LayoutState = LayoutState()

    override suspend fun readFrom(input: InputStream): LayoutState =
        try {
            json.decodeFromString<LayoutState>(input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("layout.json の読み込みに失敗しました", e)
        }

    override suspend fun writeTo(t: LayoutState, output: OutputStream) {
        @Suppress("BlockingMethodInNonBlockingContext")
        output.write(json.encodeToString(LayoutState.serializer(), t).encodeToByteArray())
    }
}

private val Context.layoutDataStore: DataStore<LayoutState> by dataStore(
    fileName = "layout.json",
    serializer = LayoutStateSerializer,
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { LayoutState() },
)

/**
 * ワークスペースとドックのレイアウトを永続化するリポジトリ。
 * すべての変更操作は fire-and-forget で DataStore に直列化される。
 */
class LayoutRepository(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = context.applicationContext.layoutDataStore
    private val updates = Channel<(LayoutState) -> LayoutState>(Channel.UNLIMITED)

    val state: StateFlow<LayoutState> =
        store.data
            .map { it.normalized() }
            .stateIn(scope, SharingStarted.Eagerly, LayoutState())

    init {
        // 1本のconsumerで操作順を保ち、操作ごとのCoroutine生成と書き込み競合を避ける。
        scope.launch {
            for (transform in updates) {
                try {
                    store.updateData { current -> transform(current.normalized()).normalized() }
                } catch (e: Exception) {
                    // 失敗した1操作だけを捨て、後続操作の保存は継続する。
                    Log.w(TAG, "レイアウトの保存に失敗しました", e)
                }
            }
        }
    }

    private fun update(transform: (LayoutState) -> LayoutState) {
        if (updates.trySend(transform).isFailure) {
            Log.w(TAG, "レイアウト更新キューへ操作を追加できませんでした")
        }
    }

    private fun LayoutState.normalized(): LayoutState {
        val dockFixed = when {
            dock.size == LayoutState.DOCK_SLOT_COUNT -> dock
            dock.size < LayoutState.DOCK_SLOT_COUNT ->
                dock + List(LayoutState.DOCK_SLOT_COUNT - dock.size) { null }
            else -> dock.take(LayoutState.DOCK_SLOT_COUNT)
        }
        val migratedHome = when {
            version < LayoutState.CURRENT_VERSION && home.size == LEGACY_HOME_CELL_COUNT ->
                migrateFiveBySixHome(home)
            else -> home
        }
        val maxCells = LayoutState.HOME_CELL_COUNT * LayoutState.MAX_HOME_PAGE_COUNT
        val retainedHome = migratedHome.take(maxCells)
        val pageCount = ((retainedHome.size + LayoutState.HOME_CELL_COUNT - 1) /
            LayoutState.HOME_CELL_COUNT)
            .coerceIn(1, LayoutState.MAX_HOME_PAGE_COUNT)
        val normalizedCellCount = pageCount * LayoutState.HOME_CELL_COUNT
        val homeFixed = retainedHome + List(normalizedCellCount - retainedHome.size) { null }
        return copy(
            // iOS同様、最後のアプリを外して空になったフォルダは自動削除する。
            home = homeFixed.map { item ->
                if (item is HomeItem.HomeFolder && item.apps.isEmpty()) null else item
            },
            dock = dockFixed.map { item ->
                if (item is DockItem.DockFolder && item.apps.isEmpty()) null else item
            },
            widgets = widgets
                .filter { placement ->
                    placement.appWidgetId > 0 &&
                        placement.providerPackage.isNotBlank() &&
                        placement.providerClass.isNotBlank()
                }
                .distinctBy(WidgetPlacement::appWidgetId)
                .map { it.copy(heightDp = it.heightDp.coerceIn(96, 480)) },
            version = LayoutState.CURRENT_VERSION,
        )
    }

    /**
     * v4 の 5列×6行を v5 の 4列×6行へ安全に移す。
     * 各行の左4セルは同じ行・列を保ち、旧5列目の項目は残りの空セルへ順に退避する。
     */
    private fun migrateFiveBySixHome(oldHome: List<HomeItem?>): List<HomeItem?> {
        val migrated = MutableList<HomeItem?>(LayoutState.HOME_CELL_COUNT) { null }
        val overflow = mutableListOf<HomeItem>()
        repeat(LayoutState.HOME_ROWS) { row ->
            repeat(LayoutState.HOME_COLUMNS) { column ->
                migrated[row * LayoutState.HOME_COLUMNS + column] =
                    oldHome.getOrNull(row * LEGACY_HOME_COLUMNS + column)
            }
            oldHome.getOrNull(row * LEGACY_HOME_COLUMNS + LayoutState.HOME_COLUMNS)
                ?.let(overflow::add)
        }
        val emptyIndices = migrated.indices.filter { migrated[it] == null }.iterator()
        overflow.forEach { item ->
            if (emptyIndices.hasNext()) migrated[emptyIndices.next()] = item
        }
        return migrated
    }

    private companion object {
        const val LEGACY_HOME_COLUMNS = 5
        const val LEGACY_HOME_CELL_COUNT = 30
        const val TAG = "LayoutRepository"
    }

    // ---- ホームグリッド操作 ----

    /** ホームグリッドの 1 セルを設定/クリアする。 */
    fun setHomeItem(index: Int, item: HomeItem?) {
        update { state ->
            if (index !in state.home.indices) state
            else state.copy(home = state.home.toMutableList().apply { this[index] = item })
        }
    }

    /** ホームの空きセルにアプリを置く。アプリ入りセルには置けない(no-op)。 */
    fun addAppToHomeSlot(index: Int, app: AppRef) {
        update { state ->
            if (index !in state.home.indices) return@update state
            if (state.home[index] != null) return@update state
            state.copy(home = state.home.toMutableList().apply { this[index] = HomeItem.HomeApp(app) })
        }
    }

    /** ホームグリッドの 2 セルの中身を入れ替える(空きセル=null との入替も可)。 */
    fun swapHomeItems(a: Int, b: Int) {
        update { state ->
            val n = state.home.size
            if (a == b || a !in 0 until n || b !in 0 until n) return@update state
            val home = state.home.toMutableList()
            val tmp = home[a]; home[a] = home[b]; home[b] = tmp
            state.copy(home = home)
        }
    }

    // ---- 領域跨ぎ移動（アプリ／フォルダ） ----

    /**
     * ホームのアプリまたはフォルダをDockへ移動する。
     * 移動先が非空なら、ホーム／Dockの型を変換しながら項目ごと入れ替える。
     */
    fun moveHomeToDock(homeIndex: Int, dockSlot: Int) {
        update { state -> state.moveHomeItemToDock(homeIndex, dockSlot) }
    }

    /** Dockのアプリまたはフォルダをホームへ移動する（対称）。 */
    fun moveDockToHome(dockSlot: Int, homeIndex: Int) {
        update { state -> state.moveDockItemToHome(dockSlot, homeIndex) }
    }

    /** フォルダ内を含むアプリ1件をホームセルへ原子的に移動／入れ替えする。 */
    fun moveAppToHome(index: Int, source: AppMoveSource) {
        update { state -> state.moveAppToHome(index, source) }
    }

    /** フォルダ内を含むアプリ1件をDockスロットへ原子的に移動／入れ替えする。 */
    fun moveAppToDock(slot: Int, source: AppMoveSource) {
        update { state -> state.moveAppToDock(slot, source) }
    }

    /** アプリをホーム上のアプリへ重ねてフォルダ化するか、既存フォルダへ追加する。 */
    fun stackAppOnHome(index: Int, source: AppMoveSource, folderName: String) {
        update { state -> state.stackAppOnHome(index, source, folderName) }
    }

    /** アプリをDock上のアプリへ重ねてフォルダ化するか、既存フォルダへ追加する。 */
    fun stackAppOnDock(slot: Int, source: AppMoveSource, folderName: String) {
        update { state -> state.stackAppOnDock(slot, source, folderName) }
    }

    /** ドロワーからホームの指定セルへアプリを設置する(空/アプリセルは上書き、フォルダは不可)。 */
    fun placeAppOnHome(index: Int, app: AppRef) {
        update { state ->
            if (index !in state.home.indices) return@update state
            if (state.home[index] is HomeItem.HomeFolder) return@update state
            state.copy(home = state.home.toMutableList().apply { this[index] = HomeItem.HomeApp(app) })
        }
    }

    /** ドロワーからドックの指定スロットへアプリを設置する(空/アプリスロットは上書き、フォルダは不可)。 */
    fun placeAppOnDock(slot: Int, app: AppRef) {
        update { state ->
            if (slot !in 0 until LayoutState.DOCK_SLOT_COUNT) return@update state
            if (state.dock[slot] is DockItem.DockFolder) return@update state
            state.copy(dock = state.dock.toMutableList().apply { this[slot] = DockItem.DockApp(app) })
        }
    }

    // ---- ドック操作 ----

    /** ドックの 2 スロットの中身を入れ替える(空きスロット=null との入替も可)。 */
    fun swapDockItems(a: Int, b: Int) {
        update { state ->
            val n = LayoutState.DOCK_SLOT_COUNT
            if (a == b || a !in 0 until n || b !in 0 until n) return@update state
            val dock = state.dock.toMutableList()
            val tmp = dock[a]; dock[a] = dock[b]; dock[b] = tmp
            state.copy(dock = dock)
        }
    }

    fun setDockItem(slot: Int, item: DockItem?) {
        update { state ->
            if (slot !in 0 until LayoutState.DOCK_SLOT_COUNT) state
            else state.copy(dock = state.dock.toMutableList().apply { this[slot] = item })
        }
    }

    // ---- ホームページ操作 ----

    /** Appライブラリ直前へ空のホームページを1枚追加する。 */
    fun appendHomePage() {
        update(LayoutState::appendEmptyHomePage)
    }

    /** D&Dがキャンセルされた時だけ、末尾に残った空ページを取り除く。 */
    fun trimTrailingEmptyHomePages() {
        update(LayoutState::withoutTrailingEmptyHomePages)
    }

    /** Pager再構成中のフォールバックtargetから、アプリ1件を目的ページへ移す。 */
    fun moveAppToHomePage(page: Int, source: AppMoveSource) {
        update { state -> state.moveAppToHomePage(page, source) }
    }

    /** Pager再構成中のフォールバックtargetから、ホーム項目全体を目的ページへ移す。 */
    fun moveHomeItemToHomePage(page: Int, sourceIndex: Int) {
        update { state -> state.moveHomeItemToHomePage(page, sourceIndex) }
    }

    /** Pager再構成中のフォールバックtargetから、Dock項目全体を目的ページへ移す。 */
    fun moveDockItemToHomePage(page: Int, dockSlot: Int) {
        update { state -> state.moveDockItemToHomePage(page, dockSlot) }
    }

    // ---- ウィジェット専用ページ ----

    fun addWidget(placement: WidgetPlacement) {
        update { state ->
            if (state.widgets.any { it.appWidgetId == placement.appWidgetId }) state
            else state.copy(widgets = state.widgets + placement)
        }
    }

    fun removeWidget(appWidgetId: Int) {
        update { state ->
            state.copy(widgets = state.widgets.filterNot { it.appWidgetId == appWidgetId })
        }
    }

    fun reorderWidget(appWidgetId: Int, direction: Int) {
        update { state -> state.withWidgetMoved(appWidgetId, direction) }
    }

    fun pruneInvalidWidgets(validIds: Set<Int>) {
        update { state ->
            state.copy(widgets = state.widgets.filter { it.appWidgetId in validIds })
        }
    }

    /**
     * ドックにアプリを置く。
     * 空きスロット → DockApp、フォルダ → フォルダへ追加。アプリ入りスロットには置けない。
     */
    fun addAppToDockSlot(slot: Int, app: AppRef) {
        update { state ->
            if (slot !in 0 until LayoutState.DOCK_SLOT_COUNT) return@update state
            val dock = state.dock.toMutableList()
            when (val current = dock[slot]) {
                null -> dock[slot] = DockItem.DockApp(app)
                is DockItem.DockFolder ->
                    if (current.apps.none { it == app }) {
                        dock[slot] = current.copy(apps = current.apps + app)
                    }
                is DockItem.DockApp -> return@update state
            }
            state.copy(dock = dock)
        }
    }

    /** ホーム／Dockのアプリ項目を、選択したアプリとまとめてフォルダ化する。 */
    fun createOrAddFolder(location: FolderLocation, name: String, apps: List<AppRef>) {
        update { state -> state.createOrAddFolder(location, name, apps) }
    }

    fun addAppsToFolder(location: FolderLocation, apps: List<AppRef>) {
        update { state -> state.addAppsToFolder(location, apps) }
    }

    fun removeAppFromFolder(location: FolderLocation, app: AppRef) {
        update { state -> state.removeAppFromFolder(location, app) }
    }

    fun renameFolder(location: FolderLocation, name: String) {
        update { state -> state.renameFolder(location, name) }
    }

    fun reorderFolderApps(location: FolderLocation, fromIndex: Int, toIndex: Int) {
        update { state -> state.reorderFolderApps(location, fromIndex, toIndex) }
    }

    // ---- メンテナンス ----

    /** アンインストールされたアプリへの参照をレイアウトから取り除く。 */
    fun pruneMissingPackages(installedPackages: Set<String>) {
        update { state ->
            val dock = state.dock.map { item ->
                when (item) {
                    null -> null
                    is DockItem.DockApp ->
                        if (item.app.packageName in installedPackages) item else null
                    is DockItem.DockFolder -> {
                        val remaining = item.apps.filter { it.packageName in installedPackages }
                        if (remaining.isEmpty()) null else item.copy(apps = remaining)
                    }
                }
            }
            val home = state.home.map { item ->
                when (item) {
                    null -> null
                    is HomeItem.HomeApp ->
                        if (item.app.packageName in installedPackages) item else null
                    is HomeItem.HomeFolder -> {
                        val remaining = item.apps.filter { it.packageName in installedPackages }
                        if (remaining.isEmpty()) null else item.copy(apps = remaining)
                    }
                }
            }
            state.copy(home = home, dock = dock)
        }
    }
}
