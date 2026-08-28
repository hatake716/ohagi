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

    val state: StateFlow<LayoutState> =
        store.data
            .map { it.normalized() }
            .stateIn(scope, SharingStarted.Eagerly, LayoutState())

    private fun update(transform: (LayoutState) -> LayoutState) {
        scope.launch {
            try {
                store.updateData { current -> transform(current.normalized()).normalized() }
            } catch (e: Exception) {
                // ディスク書き込み失敗でランチャーを落とさない(次回操作で再試行される)
                Log.w("LayoutRepository", "レイアウトの保存に失敗しました", e)
            }
        }
    }

    private fun LayoutState.normalized(): LayoutState {
        val dockFixed = when {
            dock.size == LayoutState.DOCK_SLOT_COUNT -> dock
            dock.size < LayoutState.DOCK_SLOT_COUNT ->
                dock + List(LayoutState.DOCK_SLOT_COUNT - dock.size) { null }
            else -> dock.take(LayoutState.DOCK_SLOT_COUNT)
        }
        val homeFixed = when {
            home.size == LayoutState.HOME_CELL_COUNT -> home
            home.size < LayoutState.HOME_CELL_COUNT ->
                home + List(LayoutState.HOME_CELL_COUNT - home.size) { null }
            else -> home.take(LayoutState.HOME_CELL_COUNT)
        }
        return copy(home = homeFixed, dock = dockFixed)
    }

    // ---- ホームグリッド操作 ----

    /** ホームグリッドの 1 セルを設定/クリアする。 */
    fun setHomeItem(index: Int, item: HomeItem?) {
        update { state ->
            if (index !in 0 until LayoutState.HOME_CELL_COUNT) state
            else state.copy(home = state.home.toMutableList().apply { this[index] = item })
        }
    }

    /** ホームの空きセルにアプリを置く。アプリ入りセルには置けない(no-op)。 */
    fun addAppToHomeSlot(index: Int, app: AppRef) {
        update { state ->
            if (index !in 0 until LayoutState.HOME_CELL_COUNT) return@update state
            if (state.home[index] != null) return@update state
            state.copy(home = state.home.toMutableList().apply { this[index] = HomeItem.HomeApp(app) })
        }
    }

    /** ホームグリッドの 2 セルの中身を入れ替える(空きセル=null との入替も可)。 */
    fun swapHomeItems(a: Int, b: Int) {
        update { state ->
            val n = LayoutState.HOME_CELL_COUNT
            if (a == b || a !in 0 until n || b !in 0 until n) return@update state
            val home = state.home.toMutableList()
            val tmp = home[a]; home[a] = home[b]; home[b] = tmp
            state.copy(home = home)
        }
    }

    // ---- 領域跨ぎ移動(アプリのみ) ----

    /**
     * ホームのアプリをドックへ移動する。ドック先が空なら移動(ホーム側は空に)、
     * 非空アプリなら入替(型変換)。フォルダは対象外(呼び出し側で弾く)。
     */
    fun moveHomeToDock(homeIndex: Int, dockSlot: Int) {
        update { state ->
            if (homeIndex !in 0 until LayoutState.HOME_CELL_COUNT) return@update state
            if (dockSlot !in 0 until LayoutState.DOCK_SLOT_COUNT) return@update state
            val homeItem = state.home[homeIndex] as? HomeItem.HomeApp ?: return@update state
            val home = state.home.toMutableList()
            val dock = state.dock.toMutableList()
            when (val dst = dock[dockSlot]) {
                null -> {
                    dock[dockSlot] = DockItem.DockApp(homeItem.app)
                    home[homeIndex] = null
                }
                is DockItem.DockApp -> {
                    // 入替: ドックのアプリをホームへ、ホームのアプリをドックへ
                    dock[dockSlot] = DockItem.DockApp(homeItem.app)
                    home[homeIndex] = HomeItem.HomeApp(dst.app)
                }
                is DockItem.DockFolder -> return@update state // フォルダ先には落とさない
            }
            state.copy(home = home, dock = dock)
        }
    }

    /** ドックのアプリをホームへ移動する(対称)。 */
    fun moveDockToHome(dockSlot: Int, homeIndex: Int) {
        update { state ->
            if (homeIndex !in 0 until LayoutState.HOME_CELL_COUNT) return@update state
            if (dockSlot !in 0 until LayoutState.DOCK_SLOT_COUNT) return@update state
            val dockItem = state.dock[dockSlot] as? DockItem.DockApp ?: return@update state
            val home = state.home.toMutableList()
            val dock = state.dock.toMutableList()
            when (val dst = home[homeIndex]) {
                null -> {
                    home[homeIndex] = HomeItem.HomeApp(dockItem.app)
                    dock[dockSlot] = null
                }
                is HomeItem.HomeApp -> {
                    home[homeIndex] = HomeItem.HomeApp(dockItem.app)
                    dock[dockSlot] = DockItem.DockApp(dst.app)
                }
                is HomeItem.HomeFolder -> return@update state
            }
            state.copy(home = home, dock = dock)
        }
    }

    /** ドロワーからホームの指定セルへアプリを設置する(空/アプリセルは上書き、フォルダは不可)。 */
    fun placeAppOnHome(index: Int, app: AppRef) {
        update { state ->
            if (index !in 0 until LayoutState.HOME_CELL_COUNT) return@update state
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

    /** スロットをフォルダに変換する。アプリ入りならそのアプリを含むフォルダに、空なら空フォルダに。 */
    fun convertSlotToFolder(slot: Int, name: String) {
        update { state ->
            if (slot !in 0 until LayoutState.DOCK_SLOT_COUNT) return@update state
            val dock = state.dock.toMutableList()
            dock[slot] = when (val current = dock[slot]) {
                is DockItem.DockApp -> DockItem.DockFolder(name, listOf(current.app))
                is DockItem.DockFolder -> current
                null -> DockItem.DockFolder(name, emptyList())
            }
            state.copy(dock = dock)
        }
    }

    fun addAppsToFolder(slot: Int, apps: List<AppRef>) {
        update { state ->
            if (slot !in 0 until LayoutState.DOCK_SLOT_COUNT) return@update state
            val dock = state.dock.toMutableList()
            val folder = dock[slot] as? DockItem.DockFolder ?: return@update state
            val merged = (folder.apps + apps).distinct()
            dock[slot] = folder.copy(apps = merged)
            state.copy(dock = dock)
        }
    }

    fun removeAppFromFolder(slot: Int, app: AppRef) {
        update { state ->
            if (slot !in 0 until LayoutState.DOCK_SLOT_COUNT) return@update state
            val dock = state.dock.toMutableList()
            val folder = dock[slot] as? DockItem.DockFolder ?: return@update state
            dock[slot] = folder.copy(apps = folder.apps.filterNot { it == app })
            state.copy(dock = dock)
        }
    }

    fun renameFolder(slot: Int, name: String) {
        update { state ->
            if (slot !in 0 until LayoutState.DOCK_SLOT_COUNT) return@update state
            val dock = state.dock.toMutableList()
            val folder = dock[slot] as? DockItem.DockFolder ?: return@update state
            dock[slot] = folder.copy(name = name.ifBlank { folder.name })
            state.copy(dock = dock)
        }
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
                    is DockItem.DockFolder ->
                        item.copy(apps = item.apps.filter { it.packageName in installedPackages })
                }
            }
            val home = state.home.map { item ->
                when (item) {
                    null -> null
                    is HomeItem.HomeApp ->
                        if (item.app.packageName in installedPackages) item else null
                    is HomeItem.HomeFolder ->
                        item.copy(apps = item.apps.filter { it.packageName in installedPackages })
                }
            }
            state.copy(home = home, dock = dock)
        }
    }
}
