package io.github.hatake716.ohagi.ui.common

import io.github.hatake716.ohagi.data.AppCategory
import io.github.hatake716.ohagi.data.AppInfo
import io.github.hatake716.ohagi.data.AppRef

/**
 * カテゴリー詳細の全件順は変えず、概要カードの7枠だけを指定された順で優先する。
 * アプリ更新でランチャーActivity名が変わった場合も、同一packageを次点で照合する。
 */
internal fun prioritizeAppsForPreview(
    apps: List<AppInfo>,
    preferredApps: List<AppRef>,
): List<AppInfo> {
    if (apps.size < 2 || preferredApps.isEmpty()) return apps

    val exactRanks = preferredApps
        .withIndex()
        .associate { (index, ref) -> ref to index }
    val availableRefs = apps.asSequence().map(AppInfo::ref).toHashSet()
    val packageRanks = buildMap {
        preferredApps.forEachIndexed { index, ref ->
            // 保存済みActivityが現在の一覧に無い場合だけpackage単位へフォールバックする。
            // 正常な複数ランチャーActivityを同時に優先枠へ持ち上げないための条件。
            if (ref !in availableRefs) putIfAbsent(ref.packageName, index)
        }
    }

    return apps
        .withIndex()
        .sortedWith(
            compareBy<IndexedValue<AppInfo>>(
                { indexed ->
                    exactRanks[indexed.value.ref]
                        ?: packageRanks[indexed.value.ref.packageName]
                        ?: Int.MAX_VALUE
                },
                IndexedValue<AppInfo>::index,
            ),
        )
        .map(IndexedValue<AppInfo>::value)
}

/** Appライブラリ概要で独立表示する「よく使うアプリ」と通常カテゴリー。 */
internal data class AppBrowserOverviewContent(
    val frequentApps: List<AppInfo>,
    val categoryGroups: List<Pair<AppCategory, List<AppInfo>>>,
)

/**
 * 保存済みの優先順を、現在インストールされているランチャーActivityへ解決する。
 * Activity名が更新で変わった参照は同一packageへフォールバックし、重複は表示しない。
 */
internal fun resolvePreferredApps(
    apps: List<AppInfo>,
    preferredApps: List<AppRef>,
    limit: Int = Int.MAX_VALUE,
): List<AppInfo> {
    if (apps.isEmpty() || preferredApps.isEmpty() || limit <= 0) return emptyList()

    val appsByRef = apps.associateBy(AppInfo::ref)
    val appsByPackage = apps.groupBy { it.ref.packageName }
    val visitedPreferences = mutableSetOf<AppRef>()
    val resolvedRefs = mutableSetOf<AppRef>()
    return buildList {
        for (preferred in preferredApps) {
            if (!visitedPreferences.add(preferred)) continue
            val resolved = appsByRef[preferred]
                ?: appsByPackage[preferred.packageName]
                    ?.firstOrNull { it.ref !in resolvedRefs }
                ?: continue
            if (resolvedRefs.add(resolved.ref)) add(resolved)
            if (size >= limit) break
        }
    }
}

/** 専用の頻出枠を通常カテゴリーと分離したまま、概要画面の内容を構築する。 */
internal fun buildAppBrowserOverviewContent(
    apps: List<AppInfo>,
    frequentAppRefs: List<AppRef>,
    preferredApps: List<AppRef>,
): AppBrowserOverviewContent {
    val appsByCategory = apps.groupBy(AppInfo::category)
    return AppBrowserOverviewContent(
        frequentApps = resolvePreferredApps(
            apps = apps,
            preferredApps = frequentAppRefs,
            limit = FREQUENT_APP_LIMIT,
        ),
        categoryGroups = AppCategory.entries.mapNotNull { category ->
            appsByCategory[category]
                ?.takeIf(List<AppInfo>::isNotEmpty)
                ?.let { groupedApps ->
                    category to prioritizeAppsForPreview(
                        apps = groupedApps,
                        preferredApps = preferredApps,
                    )
                }
        },
    )
}

internal const val FREQUENT_APP_LIMIT = 8
