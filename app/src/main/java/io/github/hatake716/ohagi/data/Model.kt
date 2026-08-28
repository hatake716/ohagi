package io.github.hatake716.ohagi.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/** 起動可能なアクティビティへの参照 */
@Serializable
data class AppRef(
    val packageName: String,
    val className: String,
)

/** ワークスペース上の 1 タイル(= 1 アプリ) */
@Serializable
data class Tile(
    val id: String,
    val app: AppRef,
)

/**
 * ワークスペースの 1 カラム。niri のカラムに相当する。
 * タイルは 1〜2 個。2 個のときは縦画面で上下、横画面で左右に分割表示される。
 * widthPreset は niri の幅プリセットに対応: 0 = 1/3, 1 = 1/2, 2 = 2/3, 3 = フル幅
 */
@Serializable
data class WorkColumn(
    val id: String,
    val tiles: List<Tile>,
    val widthPreset: Int = 2,
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

/** 永続化されるレイアウト全体 */
@Serializable
data class LayoutState(
    val columns: List<WorkColumn> = emptyList(),
    val dock: List<DockItem?> = List(DOCK_SLOT_COUNT) { null },
    val version: Int = 1,
) {
    companion object {
        const val DOCK_SLOT_COUNT = 4
    }
}

fun newId(): String = UUID.randomUUID().toString()
