package io.github.hatake716.ohagi

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.hatake716.ohagi.data.AppRepository
import io.github.hatake716.ohagi.data.LayoutRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** アプリ全体で共有する依存グラフ(手動 DI) */
class Graph(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val appRepository = AppRepository(appContext)
    val layoutRepository = LayoutRepository(appContext)

    fun start() {
        appRepository.startWatching()
        // 本当にアンインストールされたパッケージへの参照だけをレイアウトから掃除する。
        // ドロワー一覧に見えないだけ(更新中・無効化中・alias切替中など)のアプリは
        // PackageManager で実在確認し、削除しない。誤削除は恒久的なデータ喪失になるため。
        scope.launch {
            appRepository.apps
                .filter { it.isNotEmpty() }
                .collect { apps ->
                    val visible = apps.mapTo(mutableSetOf()) { it.ref.packageName }
                    val layout = layoutRepository.state.value
                    val referenced = buildSet {
                        layout.columns.forEach { column ->
                            column.tiles.forEach { add(it.app.packageName) }
                        }
                        layout.dock.forEach { item ->
                            when (item) {
                                is io.github.hatake716.ohagi.data.DockItem.DockApp ->
                                    add(item.app.packageName)
                                is io.github.hatake716.ohagi.data.DockItem.DockFolder ->
                                    item.apps.forEach { add(it.packageName) }
                                null -> Unit
                            }
                        }
                    }
                    val stillInstalled = referenced
                        .filter { it !in visible }
                        .filter { appRepository.isPackageInstalled(it) }
                    val reallyGone = referenced - visible - stillInstalled.toSet()
                    if (reallyGone.isNotEmpty()) {
                        layoutRepository.pruneMissingPackages(visible + stillInstalled)
                    }
                }
        }
    }
}

val LocalGraph = staticCompositionLocalOf<Graph> {
    error("Graph が提供されていません")
}
