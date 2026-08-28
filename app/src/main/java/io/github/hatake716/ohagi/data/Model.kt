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

/**
 * ホーム主画面のグリッド 1 セルに入るもの。
 * ドックとは別の型にしている: ドックはスロット固定だが、ホームはグリッドで、
 * 将来ここにだけウィジェット(セル跨ぎ・サイズ属性)が入るため、DockItem を汚さない。
 */
@Serializable
sealed interface HomeItem {
    @Serializable
    @SerialName("app")
    data class HomeApp(val app: AppRef) : HomeItem

    @Serializable
    @SerialName("folder")
    data class HomeFolder(val name: String, val apps: List<AppRef>) : HomeItem

    // 将来: ウィジェット対応時にここへ追加する。
    // @Serializable @SerialName("widget")
    // data class HomeWidget(val provider: String, val appWidgetId: Int, val spanX: Int, val spanY: Int) : HomeItem
}

/**
 * 永続化されるレイアウト全体。
 * home: ホーム主画面のアイコングリッド(固定 [HOME_CELL_COUNT] セル、index=セル位置)。
 * dock: 下部ドックの 4 スロット。
 *
 * split-screen 方式では実ウィンドウはすべて OS のタスク/分割画面管理下にあり、
 * ランチャー側が「今どのアプリが前面か」を知る術は無い(前面タスク照会は signature 権限が必要)。
 * そのため以前の panes(タイリング状態)は永続化しない。ホームグリッドとドック構成を保存する。
 */
@Serializable
data class LayoutState(
    val home: List<HomeItem?> = List(HOME_CELL_COUNT) { null },
    val dock: List<DockItem?> = List(DOCK_SLOT_COUNT) { null },
    // v4: ホームアイコングリッド(home)を追加。旧 v3 json に home が無くても
    // Json { ignoreUnknownKeys = true } + デフォルト値で安全に移行される。
    val version: Int = 4,
) {
    companion object {
        const val DOCK_SLOT_COUNT = 4
        const val HOME_COLUMNS = 5
        const val HOME_ROWS = 6
        const val HOME_CELL_COUNT = HOME_COLUMNS * HOME_ROWS
    }
}
