package io.github.hatake716.ohagi.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 起動可能なアクティビティへの参照 */
@Serializable
data class AppRef(
    val packageName: String,
    val className: String,
)

/** ドックのスロットに入るもの: アプリ単体またはフォルダ */
@Serializable
sealed interface DockItem {
    @Serializable
    @SerialName("app")
    data class DockApp(val app: AppRef) : DockItem

    @Serializable
    @SerialName("folder")
    data class DockFolder(val name: String, val apps: List<AppRef>) : DockItem
}

/** ホームページのグリッド1セルに入るもの。 */
@Serializable
sealed interface HomeItem {
    @Serializable
    @SerialName("app")
    data class HomeApp(val app: AppRef) : HomeItem

    @Serializable
    @SerialName("folder")
    data class HomeFolder(val name: String, val apps: List<AppRef>) : HomeItem
}

/** 左端のウィジェット専用ページへ縦に配置するAndroid App Widget。 */
@Serializable
data class WidgetPlacement(
    val appWidgetId: Int,
    val providerPackage: String,
    val providerClass: String,
    /** 0は端末幅に追従する全幅。正数はユーザーが決めたホスト上の表示幅。 */
    val widthDp: Int = MATCH_PARENT_WIDTH_DP,
    /** ホスト上の表示高。追加時の推奨値またはユーザーのリサイズ結果。 */
    val heightDp: Int = DEFAULT_WIDGET_HEIGHT_DP,
) {
    companion object {
        const val MATCH_PARENT_WIDTH_DP = 0
        const val DEFAULT_WIDGET_HEIGHT_DP = 180
        const val MIN_WIDGET_WIDTH_DP = 96
        const val MAX_WIDGET_WIDTH_DP = 1024
        const val MIN_WIDGET_HEIGHT_DP = 96
        const val MAX_WIDGET_HEIGHT_DP = 480
    }
}

/** ホームとDockのどちらにあるフォルダかを共通処理へ渡すための位置。 */
sealed interface FolderLocation {
    data class Home(val index: Int) : FolderLocation
    data class Dock(val slot: Int) : FolderLocation
}

/**
 * D&Dで移動するアプリの取得元。
 *
 * トップレベルのアプリ、フォルダ内アプリ、Appライブラリからの追加を同じ
 * DataStoreトランザクションで処理し、移動途中にアプリが複製・消失しないようにする。
 */
sealed interface AppMoveSource {
    data class Home(val index: Int) : AppMoveSource
    data class Dock(val slot: Int) : AppMoveSource
    data class HomeFolder(
        val index: Int,
        val appIndex: Int,
        val expectedApp: AppRef,
    ) : AppMoveSource
    data class DockFolder(
        val slot: Int,
        val appIndex: Int,
        val expectedApp: AppRef,
    ) : AppMoveSource
    data class External(val app: AppRef) : AppMoveSource
}

/** 表示中のフォルダをホーム／Dock共通UIへ渡す読み取り専用スナップショット。 */
data class FolderContent(
    val name: String,
    val apps: List<AppRef>,
)

/**
 * 永続化されるレイアウト全体。
 * home: 24セル単位で連結したホームページ。既存JSONの24セルはそのまま1ページ目になる。
 * dock: 下部ドックの 5 スロット。すべてアプリまたはフォルダを配置できる。
 * widgets: 左端のウィジェット専用ページへ置くAppWidgetHostのインスタンス。
 *
 * split-screen 方式では実ウィンドウはすべて OS のタスク/分割画面管理下にあり、
 * ランチャー側が「今どのアプリが前面か」を知る術は無い(前面タスク照会は signature 権限が必要)。
 * そのため以前の panes(タイリング状態)は永続化しない。ホームグリッドとドック構成を保存する。
 */
@Serializable
data class LayoutState(
    val home: List<HomeItem?> = List(HOME_CELL_COUNT) { null },
    val dock: List<DockItem?> = List(DOCK_SLOT_COUNT) { null },
    val widgets: List<WidgetPlacement> = emptyList(),
    // v8: ウィジェットの幅と高さをユーザー変更可能にし、サイズを永続化。
    // v7: Dock中央の固定ランチャーボタンを、通常の5番目スロットへ置換。
    // v6: homeを24セル単位の複数ページとして扱い、ウィジェット専用ページを追加。
    // v5: ホームグリッドを 5列×6行から 4列×6行へ変更。
    // v4: ホームアイコングリッド(home)を追加。旧 v3 json に home が無くても
    // Json { ignoreUnknownKeys = true } + デフォルト値で安全に移行される。
    val version: Int = CURRENT_VERSION,
) {
    companion object {
        const val CURRENT_VERSION = 8
        const val DOCK_SLOT_COUNT = 5
        const val HOME_COLUMNS = 4
        const val HOME_ROWS = 6
        /** 1ホームページあたりのセル数。 */
        const val HOME_CELL_COUNT = HOME_COLUMNS * HOME_ROWS
        const val MAX_HOME_PAGE_COUNT = 10
    }
}

/** 永続化されたホームページ数。正規化前の空配列でも最低1ページとして扱う。 */
val LayoutState.homePageCount: Int
    get() = ((home.size + LayoutState.HOME_CELL_COUNT - 1) / LayoutState.HOME_CELL_COUNT)
        .coerceIn(1, LayoutState.MAX_HOME_PAGE_COUNT)

/** [page] の24セル。範囲外は空ページとして返し、Compose側のサイズを常に一定にする。 */
fun LayoutState.homePage(page: Int): List<HomeItem?> {
    if (page !in 0 until homePageCount) return List(LayoutState.HOME_CELL_COUNT) { null }
    val start = page * LayoutState.HOME_CELL_COUNT
    return List(LayoutState.HOME_CELL_COUNT) { cell -> home.getOrNull(start + cell) }
}

fun homeGlobalIndex(page: Int, cell: Int): Int =
    page * LayoutState.HOME_CELL_COUNT + cell

fun LayoutState.folderAt(location: FolderLocation): FolderContent? = when (location) {
    is FolderLocation.Home ->
        (home.getOrNull(location.index) as? HomeItem.HomeFolder)
            ?.let { FolderContent(it.name, it.apps) }
    is FolderLocation.Dock ->
        (dock.getOrNull(location.slot) as? DockItem.DockFolder)
            ?.let { FolderContent(it.name, it.apps) }
}
