package io.github.hatake716.ohagi.util

import io.github.hatake716.ohagi.data.AppRef
import kotlin.math.ceil
import kotlin.math.floor

/** Compose上の起動元アイコンを、Activity遷移へ渡す整数座標。 */
data class LaunchBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** 壊れた／画面外の座標を除外し、ActivityOptionsが受け取れる範囲へ収める。 */
    fun clippedTo(widthPx: Int, heightPx: Int): LaunchBounds? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val clipped = LaunchBounds(
            left = left.coerceIn(0, widthPx),
            top = top.coerceIn(0, heightPx),
            right = right.coerceIn(0, widthPx),
            bottom = bottom.coerceIn(0, heightPx),
        )
        return clipped.takeIf { it.width >= MIN_LAUNCH_EDGE_PX && it.height >= MIN_LAUNCH_EDGE_PX }
    }

    companion object {
        /** 浮動小数のCompose座標を、アイコン全体を含むよう外側へ丸める。 */
        fun fromEdges(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
        ): LaunchBounds? {
            if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) {
                return null
            }
            val bounds = LaunchBounds(
                left = floor(left).toInt(),
                top = floor(top).toInt(),
                right = ceil(right).toInt(),
                bottom = ceil(bottom).toInt(),
            )
            return bounds.takeIf {
                it.width >= MIN_LAUNCH_EDGE_PX && it.height >= MIN_LAUNCH_EDGE_PX
            }
        }

        private const val MIN_LAUNCH_EDGE_PX = 2
    }
}

/** アプリと「どのアイコンから開いたか」をActivityまで一緒に運ぶ。 */
data class AppLaunchRequest(
    val app: AppRef,
    val sourceBounds: LaunchBounds? = null,
)
