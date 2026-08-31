package io.github.hatake716.ohagi.ui.common

import io.github.hatake716.ohagi.data.AppCategory
import io.github.hatake716.ohagi.data.AppIconRequest
import io.github.hatake716.ohagi.data.AppInfo
import io.github.hatake716.ohagi.data.AppRef

/**
 * Appライブラリ上端で直ちに見えるカテゴリーカードのアイコンだけを先読みする。
 *
 * 全アプリを先読みするとLRUを押し出してRAMも増えるため、完全表示カードは
 * 最初の3個を大サイズ、続く4個をミニサイズとし、下端の部分表示カードは
 * 上段2個だけに限定する。
 */
internal fun buildAppLibraryIconPrefetchRequests(
    apps: List<AppInfo>,
    previewIconSizePx: Int,
    miniIconSizePx: Int,
    categoryLimit: Int = APP_LIBRARY_PREFETCH_CATEGORY_COUNT,
    partialCategoryLimit: Int = 0,
    preferredApps: List<AppRef> = emptyList(),
): List<AppIconRequest> {
    if (apps.isEmpty() || categoryLimit + partialCategoryLimit <= 0) return emptyList()

    val appsByCategory = apps.groupBy { it.category }
    val categoryApps = AppCategory.entries
        .asSequence()
        .mapNotNull(appsByCategory::get)
        .filter(List<AppInfo>::isNotEmpty)
        .map { appsInCategory ->
            prioritizeAppsForPreview(
                apps = appsInCategory,
                preferredApps = preferredApps,
            )
        }
        .toList()
    return buildList {
        categoryApps.take(categoryLimit.coerceAtLeast(0)).forEach { appsInCategory ->
            appsInCategory.take(3).forEach { app ->
                add(AppIconRequest(app.ref, previewIconSizePx))
            }
            appsInCategory.drop(3).take(4).forEach { app ->
                add(AppIconRequest(app.ref, miniIconSizePx))
            }
        }
        // 下端に一部だけ見えるカードは、初期viewportへ入る上段2個だけを用意する。
        categoryApps
            .drop(categoryLimit.coerceAtLeast(0))
            .take(partialCategoryLimit.coerceAtLeast(0))
            .forEach { appsInCategory ->
                appsInCategory.take(2).forEach { app ->
                    add(AppIconRequest(app.ref, previewIconSizePx))
                }
            }
        }.distinct()
}

internal data class AppLibraryPrefetchBudget(
    val fullCategoryCount: Int,
    val partialCategoryCount: Int,
)

internal fun appLibraryPrefetchBudget(isLowRamDevice: Boolean): AppLibraryPrefetchBudget =
    if (isLowRamDevice) {
        AppLibraryPrefetchBudget(
            fullCategoryCount = APP_LIBRARY_LOW_RAM_PREFETCH_CATEGORY_COUNT,
            partialCategoryCount = 0,
        )
    } else {
        AppLibraryPrefetchBudget(
            fullCategoryCount = APP_LIBRARY_PREFETCH_CATEGORY_COUNT,
            partialCategoryCount = APP_LIBRARY_PARTIAL_PREFETCH_CATEGORY_COUNT,
        )
    }

internal const val APP_LIBRARY_PREFETCH_CATEGORY_COUNT = 6
internal const val APP_LIBRARY_PARTIAL_PREFETCH_CATEGORY_COUNT = 2
internal const val APP_LIBRARY_LOW_RAM_PREFETCH_CATEGORY_COUNT = 4
