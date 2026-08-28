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
        // アプリ一覧が得られたら、消えたパッケージへの参照をレイアウトから掃除する。
        // 空リスト(起動直後の未取得状態)ではレイアウトを消さないようガードする。
        scope.launch {
            appRepository.apps
                .filter { it.isNotEmpty() }
                .collect { apps ->
                    layoutRepository.pruneMissingPackages(
                        apps.mapTo(mutableSetOf()) { it.ref.packageName }
                    )
                }
        }
    }
}

val LocalGraph = staticCompositionLocalOf<Graph> {
    error("Graph が提供されていません")
}
