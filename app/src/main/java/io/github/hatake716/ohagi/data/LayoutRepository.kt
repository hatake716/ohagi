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
        val panesFixed = panes
            .distinctBy { it.app }
            .take(LayoutState.MAX_PANES)
        return copy(panes = panesFixed, dock = dockFixed)
    }

    // ---- ワークスペース(タイリング)操作 ----

    /**
     * アプリをタイリングに追加する。
     * - 既に開いているアプリなら何もしない(そのペインへフォーカスは呼び出し側で行う)。
     * - 満杯([MAX_PANES] 枚)なら、最も古いペイン(先頭)を押し出して末尾に追加する。
     * 追加(または既存)ペインの id を返す。
     */
    fun addPane(app: AppRef): String {
        val existing = state.value.panes.firstOrNull { it.app == app }
        if (existing != null) return existing.id
        val paneId = newId()
        update { s ->
            if (s.panes.any { it.app == app }) return@update s
            val pane = Pane(paneId, app)
            val panes = if (s.panes.size >= LayoutState.MAX_PANES) {
                s.panes.drop(1) + pane
            } else {
                s.panes + pane
            }
            s.copy(panes = panes)
        }
        return paneId
    }

    /** ペインを閉じる(タイリングから外す)。 */
    fun removePane(paneId: String) {
        update { s -> s.copy(panes = s.panes.filterNot { it.id == paneId }) }
    }

    /** すべてのペインを閉じる。 */
    fun clearPanes() {
        update { s -> s.copy(panes = emptyList()) }
    }

    /** ペインをマスター(先頭)に昇格させる。 */
    fun promotePane(paneId: String) {
        update { s ->
            val pane = s.panes.firstOrNull { it.id == paneId } ?: return@update s
            s.copy(panes = listOf(pane) + s.panes.filterNot { it.id == paneId })
        }
    }

    /** 2 つのペインの位置を入れ替える。 */
    fun swapPanes(idA: String, idB: String) {
        update { s ->
            val ia = s.panes.indexOfFirst { it.id == idA }
            val ib = s.panes.indexOfFirst { it.id == idB }
            if (ia < 0 || ib < 0) return@update s
            val list = s.panes.toMutableList()
            val tmp = list[ia]; list[ia] = list[ib]; list[ib] = tmp
            s.copy(panes = list)
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
            val panes = state.panes.filter { it.app.packageName in installedPackages }
            val dock = state.dock.map { item ->
                when (item) {
                    null -> null
                    is DockItem.DockApp ->
                        if (item.app.packageName in installedPackages) item else null
                    is DockItem.DockFolder ->
                        item.copy(apps = item.apps.filter { it.packageName in installedPackages })
                }
            }
            state.copy(panes = panes, dock = dock)
        }
    }
}
