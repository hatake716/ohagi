package io.github.hatake716.ohagi.ui.common

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import io.github.hatake716.ohagi.data.AppRef
import io.github.hatake716.ohagi.data.DockItem
import io.github.hatake716.ohagi.data.HomeItem
import io.github.hatake716.ohagi.util.LaunchBounds
import kotlin.math.absoluteValue

/**
 * iOSの操作感を全画面で共有するためのモーション定数。
 *
 * 位置は速度を引き継げるspring、短い色／透明度変化は非対称easingを使う。
 * すべてComposeのAnimation Duration Scaleに従うため、端末側でアニメーションを
 * 無効化した場合は待ち時間を残さない。
 */
internal object IosMotion {
    const val PRESS_DOWN_MS = 70
    const val QUICK_FADE_MS = 120
    const val STANDARD_FADE_MS = 180

    const val PAGE_SNAP_DAMPING = 0.91f
    const val PAGE_SNAP_STIFFNESS = 520f
    const val PAGE_POSITIONAL_THRESHOLD = 0.34f

    const val FOLDER_OPEN_DAMPING = 0.86f
    const val FOLDER_OPEN_STIFFNESS = 470f
    const val FOLDER_CLOSE_DAMPING = 0.94f
    const val FOLDER_CLOSE_STIFFNESS = 620f

    val easeOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    val easeIn = CubicBezierEasing(0.32f, 0f, 0.67f, 0f)

    val pressDownSpec: FiniteAnimationSpec<Float> = tween(
        durationMillis = PRESS_DOWN_MS,
        easing = easeIn,
    )
    val pressReleaseSpec: FiniteAnimationSpec<Float> = spring(
        dampingRatio = 0.86f,
        stiffness = 820f,
    )
    val placementSpec: FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = 0.82f,
        stiffness = 430f,
        visibilityThreshold = IntOffset.VisibilityThreshold,
    )
    val itemFadeInSpec: FiniteAnimationSpec<Float> = tween(
        durationMillis = STANDARD_FADE_MS,
        easing = easeOut,
    )
    val itemFadeOutSpec: FiniteAnimationSpec<Float> = tween(
        durationMillis = QUICK_FADE_MS,
        easing = easeIn,
    )
}

/** Pagerの現在位置から、指定ページが中央からどれだけ離れたかを0..1で返す。 */
internal fun iosPageDistance(
    currentPage: Int,
    currentPageOffsetFraction: Float,
    page: Int,
): Float = ((currentPage - page) + currentPageOffsetFraction)
    .absoluteValue
    .coerceIn(0f, 1f)

/** Widget=0、Home=1..N、App Library=N+1 の連続位置に対するDock表示率。 */
internal fun iosHomeSurfaceVisibility(
    pagerPosition: Float,
    homePageCount: Int,
): Float {
    if (homePageCount <= 0) return 0f
    return when {
        pagerPosition < 1f -> pagerPosition.coerceIn(0f, 1f)
        pagerPosition > homePageCount.toFloat() ->
            (homePageCount + 1f - pagerPosition).coerceIn(0f, 1f)
        else -> 1f
    }
}

/** ActivityOptionsへ渡せるよう、Composeルート座標を外側へ丸める。 */
internal fun Rect.toLaunchBounds(): LaunchBounds? = LaunchBounds.fromEdges(
    left = left,
    top = top,
    right = right,
    bottom = bottom,
)

/** Lazy gridでアプリ／フォルダ自身を追跡し、スロット変更を配置アニメーションにする。 */
internal fun homeMotionKeys(
    items: List<HomeItem?>,
    indexOffset: Int,
): List<String> = stableMotionKeys(
    items = items,
    emptyKey = { localIndex -> "home-empty-${indexOffset + localIndex}" },
    identity = ::homeItemMotionIdentity,
)

internal fun dockMotionKeys(items: List<DockItem?>): List<String> = stableMotionKeys(
    items = items,
    emptyKey = { slot -> "dock-empty-$slot" },
    identity = ::dockItemMotionIdentity,
)

internal fun folderMotionKeys(
    items: List<AppRef?>,
    indexOffset: Int,
): List<String> = stableMotionKeys(
    items = items,
    emptyKey = { localIndex -> "folder-empty-${indexOffset + localIndex}" },
    identity = { app -> app?.let { "app:${it.motionIdentity()}" } },
)

private fun homeItemMotionIdentity(item: HomeItem?): String? = when (item) {
    is HomeItem.HomeApp -> "app:${item.app.motionIdentity()}"
    is HomeItem.HomeFolder -> "folder:${folderMotionIdentity(item.apps)}"
    is HomeItem.HomeFile -> "file:${item.uri}"
    is HomeItem.HomeDirectory -> "dir:${item.treeUri}"
    null -> null
}

private fun dockItemMotionIdentity(item: DockItem?): String? = when (item) {
    is DockItem.DockApp -> "app:${item.app.motionIdentity()}"
    is DockItem.DockFolder -> "folder:${folderMotionIdentity(item.apps)}"
    null -> null
}

/** 名前変更やフォルダ内並べ替えでは同じフォルダとして扱う。 */
private fun folderMotionIdentity(apps: List<AppRef>): String = apps
    .map(AppRef::motionIdentity)
    .sorted()
    .joinToString(separator = "|")

private fun AppRef.motionIdentity(): String = "$packageName/$className"

private inline fun <T> stableMotionKeys(
    items: List<T?>,
    emptyKey: (Int) -> String,
    identity: (T?) -> String?,
): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return items.mapIndexed { index, item ->
        val base = identity(item) ?: return@mapIndexed emptyKey(index)
        val occurrence = occurrences.getOrDefault(base, 0)
        occurrences[base] = occurrence + 1
        "$base#$occurrence"
    }
}
