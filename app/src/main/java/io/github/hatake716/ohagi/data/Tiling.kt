package io.github.hatake716.ohagi.data

import android.graphics.Rect

/**
 * タイリング配置の計算。
 * Linux のタイリング WM(i3 / Sway)に倣い、1 画面を最大 3 分割する。
 *
 * 縦画面(portrait)は上下方向、横画面(landscape)は左右方向に主分割する。
 * - 1 枚: 全面
 * - 2 枚: 均等 2 分割(縦画面=上下 / 横画面=左右)
 * - 3 枚: マスター(主ペイン)が半分、残り 2 枚がもう半分を 2 分割
 *   (縦画面: 上に大 1・下に小 2 を左右 / 横画面: 左に大 1・右に小 2 を上下)
 *
 * @param bounds タイリング対象の画面領域(ドックや余白を除いた実効領域)
 * @param count  配置するペイン数(1..3)
 * @param isPortrait 縦画面なら true
 * @param gap    ペイン間・外周のすき間(px)
 * @return count 個の矩形。panes と同じ順序(先頭がマスター)。
 */
fun computeTiling(
    bounds: Rect,
    count: Int,
    isPortrait: Boolean,
    gap: Int,
): List<Rect> {
    if (count <= 0) return emptyList()

    val area = Rect(
        bounds.left + gap,
        bounds.top + gap,
        bounds.right - gap,
        bounds.bottom - gap,
    )
    val half = gap / 2

    return when (count) {
        1 -> listOf(Rect(area))

        2 -> if (isPortrait) {
            val midY = (area.top + area.bottom) / 2
            listOf(
                Rect(area.left, area.top, area.right, midY - half),
                Rect(area.left, midY + half, area.right, area.bottom),
            )
        } else {
            val midX = (area.left + area.right) / 2
            listOf(
                Rect(area.left, area.top, midX - half, area.bottom),
                Rect(midX + half, area.top, area.right, area.bottom),
            )
        }

        else -> if (isPortrait) {
            // 上: マスター(大)、下: 残り 2 枚を左右
            val midY = (area.top + area.bottom) / 2
            val midX = (area.left + area.right) / 2
            listOf(
                Rect(area.left, area.top, area.right, midY - half),
                Rect(area.left, midY + half, midX - half, area.bottom),
                Rect(midX + half, midY + half, area.right, area.bottom),
            )
        } else {
            // 左: マスター(大)、右: 残り 2 枚を上下
            val midX = (area.left + area.right) / 2
            val midY = (area.top + area.bottom) / 2
            listOf(
                Rect(area.left, area.top, midX - half, area.bottom),
                Rect(midX + half, area.top, area.right, midY - half),
                Rect(midX + half, midY + half, area.right, area.bottom),
            )
        }
    }
}
