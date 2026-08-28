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
        val columnsFixed = columns
            .map { it.copy(tiles = it.tiles.take(2), widthPreset = it.widthPreset.coerceIn(0, 3)) }
            .filter { it.tiles.isNotEmpty() }
        return copy(columns = columnsFixed, dock = dockFixed)
    }

    // ---- ワークスペース操作 ----

    /**
     * 指定位置の直後に新しいカラムを挿入する。afterIndex が null または範囲外なら末尾。
     * 生成したタイルの id を返す(フリーフォーム起動でタイル位置を待つために使う)。
     */
    fun addColumnAfter(afterIndex: Int?, app: AppRef): String {
        val tileId = newId()
        update { state ->
            val column = WorkColumn(id = newId(), tiles = listOf(Tile(tileId, app)))
            val insertAt = when {
                afterIndex == null -> state.columns.size
                afterIndex < 0 -> 0
                afterIndex >= state.columns.size -> state.columns.size
                else -> afterIndex + 1
            }
            state.copy(
                columns = state.columns.toMutableList().apply { add(insertAt, column) }
            )
        }
        return tileId
    }

    /**
     * カラムにタイルを追加して分割する(最大 2 タイル)。atStart = true で先頭(上/左)に挿入。
     * 生成したタイルの id を返す(満杯で追加できない場合も id は返るが無効)。
     */
    fun addTileToColumn(columnId: String, app: AppRef, atStart: Boolean): String {
        val tileId = newId()
        update { state ->
            state.copy(columns = state.columns.map { column ->
                if (column.id != columnId || column.tiles.size >= 2) column
                else {
                    val tile = Tile(tileId, app)
                    column.copy(
                        tiles = if (atStart) listOf(tile) + column.tiles
                        else column.tiles + tile
                    )
                }
            })
        }
        return tileId
    }

    /** タイルを削除する。カラムが空になったらカラムごと消える。 */
    fun removeTile(columnId: String, tileId: String) {
        update { state ->
            state.copy(columns = state.columns.mapNotNull { column ->
                if (column.id != columnId) column
                else {
                    val rest = column.tiles.filterNot { it.id == tileId }
                    if (rest.isEmpty()) null else column.copy(tiles = rest)
                }
            })
        }
    }

    /** カラム内の 2 タイルの位置を入れ替える。 */
    fun swapTiles(columnId: String) {
        update { state ->
            state.copy(columns = state.columns.map { column ->
                if (column.id != columnId || column.tiles.size != 2) column
                else column.copy(tiles = column.tiles.reversed())
            })
        }
    }

    /** カラムを左右に移動する(delta = -1 / +1)。 */
    fun moveColumn(columnId: String, delta: Int) {
        update { state ->
            val index = state.columns.indexOfFirst { it.id == columnId }
            val target = index + delta
            if (index < 0 || target < 0 || target >= state.columns.size) state
            else {
                val list = state.columns.toMutableList()
                val column = list.removeAt(index)
                list.add(target, column)
                state.copy(columns = list)
            }
        }
    }

    /** カラム幅プリセットを niri 同様に 1/3 → 1/2 → 2/3 → フル → 1/3… と循環させる。 */
    fun cycleWidth(columnId: String) {
        update { state ->
            state.copy(columns = state.columns.map { column ->
                if (column.id != columnId) column
                else column.copy(widthPreset = (column.widthPreset + 1) % 4)
            })
        }
    }

    // ---- ドック操作 ----

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
            val columns = state.columns.mapNotNull { column ->
                val tiles = column.tiles.filter { it.app.packageName in installedPackages }
                if (tiles.isEmpty()) null else column.copy(tiles = tiles)
            }
            val dock = state.dock.map { item ->
                when (item) {
                    null -> null
                    is DockItem.DockApp ->
                        if (item.app.packageName in installedPackages) item else null
                    is DockItem.DockFolder ->
                        item.copy(apps = item.apps.filter { it.packageName in installedPackages })
                }
            }
            state.copy(columns = columns, dock = dock)
        }
    }
}
