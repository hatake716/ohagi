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

/**
 * タイリング配置された 1 枚のペイン(= 1 アプリ)。
 * niri のような横スクロールは廃止し、1 画面固定・最大 3 分割のタイリングとする。
 */
@Serializable
data class Pane(
    val id: String,
    val app: AppRef,
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
 * 永続化されるレイアウト全体。
 * panes: 現在タイリング表示しているアプリ(最大 [MAX_PANES] 枚)。先頭がマスター(主ペイン)。
 * dock: 下部ドックの 4 スロット。
 */
@Serializable
data class LayoutState(
    val panes: List<Pane> = emptyList(),
    val dock: List<DockItem?> = List(DOCK_SLOT_COUNT) { null },
    val version: Int = 2,
) {
    companion object {
        const val DOCK_SLOT_COUNT = 4
        const val MAX_PANES = 3
    }
}

fun newId(): String = UUID.randomUUID().toString()
