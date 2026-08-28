package io.github.hatake716.ohagi.util

import android.content.Context
import android.graphics.Rect
import io.github.hatake716.ohagi.data.LayoutState
import io.github.hatake716.ohagi.data.Pane
import io.github.hatake716.ohagi.data.computeTiling
import kotlinx.coroutines.delay

/**
 * タイリングされたペイン群を、実フリーフォームウィンドウとして画面に並べる。
 *
 * Android は通常アプリに「起動後のウィンドウ移動」を許さない
 * (MANAGE_ACTIVITY_TASKS が signature 権限のため。killBackgroundProcesses も
 * 可視ウィンドウには効かない)。したがって各ペインは、起動される時点で
 * ActivityOptions.setLaunchBounds に渡した矩形へ配置される。
 * 既に開いているウィンドウをあとから別の矩形へ動かすことはできない。
 *
 * このため retile は「まだ開いていないペインを、確定した分割数の正しい位置に開く」役割を担う。
 * 全ペインを綺麗に並べ直したいときは、いったん全て閉じてから開き直す(整列)。
 */
object TilingManager {

    /**
     * panes を tilingArea 内のタイリング位置へ順に(再)起動する。
     * 先頭から順に起動するため、最後のペインが最前面になる。
     *
     * @param tilingArea ドック等を除いた実効領域(px, 画面座標)
     * @param isPortrait 縦画面
     * @param gapPx      ペイン間・外周のすき間(px)
     * @param onlyLast   true なら末尾(新規)のみを、その分割数の位置へ開く。
     *                   false なら全ペインを順に開き直す(整列時)。
     */
    suspend fun retile(
        context: Context,
        panes: List<Pane>,
        tilingArea: Rect,
        isPortrait: Boolean,
        gapPx: Int,
        onlyLast: Boolean,
    ) {
        if (panes.isEmpty()) return
        val count = panes.size.coerceAtMost(LayoutState.MAX_PANES)
        val rects = computeTiling(tilingArea, count, isPortrait, gapPx)
        if (rects.size != count) return

        val indices = if (onlyLast) listOf(count - 1) else (0 until count).toList()
        for (i in indices) {
            LaunchUtils.launchInBounds(context, panes[i].app, rects[i], reposition = true)
            delay(RELAUNCH_INTERVAL_MS)
        }
    }

    private const val RELAUNCH_INTERVAL_MS = 300L
}
