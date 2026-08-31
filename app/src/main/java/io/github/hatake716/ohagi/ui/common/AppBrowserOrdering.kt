package io.github.hatake716.ohagi.ui.common

import io.github.hatake716.ohagi.data.AppInfo
import io.github.hatake716.ohagi.data.AppRef

/**
 * カテゴリー詳細の全件順は変えず、概要カードの7枠だけを利用者の配置順で優先する。
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
