package io.github.hatake716.ohagi

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.hatake716.ohagi.data.AppRef
import io.github.hatake716.ohagi.ui.common.SplitAppPickerScreen
import io.github.hatake716.ohagi.ui.theme.Ink
import io.github.hatake716.ohagi.ui.theme.OhagiTheme
import io.github.hatake716.ohagi.util.LaunchUtils

/**
 * 通知から2つ目のアプリを選び、そのままOS標準の分割画面へ移行する専用Activity。
 *
 * 通知には、ohagiが通常起動した1つ目のアプリが不変なextrasとして入っている。
 * ユーザーがカテゴリー式ドロワーから2つ目を選ぶと、このActivityをsource activityにして
 * 2つ目を隣接起動し、OSから分割成立が通知された後に主画面側を1つ目へ差し替える。
 * 分割成立はonMultiWindowModeChangedで判断し、端末性能依存の固定待ち時間では決めない。
 */
// 実際に操作するアプリ選択画面であり、独自スプラッシュ画面ではない。
@SuppressLint("CustomSplashScreen")
class SplitLaunchActivity : ComponentActivity() {

    private var splitDispatched = false
    private var firstDispatched = false
    private var pendingFirst: AppRef? = null
    private var launchingSplit by mutableStateOf(false)
    private val cleanupHandler = Handler(Looper.getMainLooper())
    private val finishCoordinator = Runnable {
        if (!isFinishing && !isDestroyed) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 選択中のプロセス再生成はintentから安全に復元する。一方、隣接起動を送出した後は
        // 同じ操作を二重送出せず、OSが既に開始したタスクへ処理を委ねる。
        if (savedInstanceState?.getBoolean(STATE_SPLIT_DISPATCHED) == true) {
            finish()
            return
        }

        val first = appRef(EXTRA_FIRST_PACKAGE, EXTRA_FIRST_CLASS) ?: run {
            finish()
            return
        }
        pendingFirst = first

        val graph = (application as OhagiApp).graph
        graph.appRepository.refreshIfStale()
        setContent {
            OhagiTheme {
                CompositionLocalProvider(LocalGraph provides graph) {
                    if (launchingSplit) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Ink),
                        )
                    } else {
                        SplitAppPickerScreen(
                            firstApp = first,
                            onSelectApp = ::startSplit,
                            onDismiss = ::finish,
                        )
                    }
                }
            }
        }
    }

    private fun startSplit(second: AppRef) {
        val first = pendingFirst ?: return
        if (splitDispatched || second.packageName == first.packageName) return
        splitDispatched = true
        launchingSplit = true

        // このWindowが前面Activityとして有効な間に開始し、LAUNCH_ADJACENTへ
        // source activityを確実に渡す。
        window.decorView.post {
            try {
                startActivity(LaunchUtils.adjacentLaunchIntent(second))
                (application as OhagiApp).graph.usageRepository.recordLaunch(second)
                cleanupHandler.postDelayed(finishCoordinator, MAX_COORDINATOR_LIFETIME_MS)
            } catch (_: RuntimeException) {
                finish()
            }
        }
    }

    override fun onMultiWindowModeChanged(
        isInMultiWindowMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (!isInMultiWindowMode || !splitDispatched || firstDispatched) return

        val first = pendingFirst ?: run {
            finish()
            return
        }
        firstDispatched = true
        cleanupHandler.removeCallbacks(finishCoordinator)
        try {
            // 分割済みのピッカータスク内へ1つ目を積み、主画面側だけを置き換える。
            startActivity(LaunchUtils.inTaskLaunchIntent(first))
            cleanupHandler.postDelayed(finishCoordinator, SPLIT_SETTLE_DELAY_MS)
        } catch (_: RuntimeException) {
            finish()
        }
    }

    override fun onDestroy() {
        cleanupHandler.removeCallbacks(finishCoordinator)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_SPLIT_DISPATCHED, splitDispatched)
        super.onSaveInstanceState(outState)
    }

    private fun appRef(packageKey: String, classKey: String): AppRef? {
        val packageName = intent.getStringExtra(packageKey)?.takeIf(String::isNotBlank)
            ?: return null
        val className = intent.getStringExtra(classKey)?.takeIf(String::isNotBlank)
            ?: return null
        return AppRef(packageName, className)
    }

    companion object {
        fun intent(context: Context, first: AppRef): Intent =
            Intent(context, SplitLaunchActivity::class.java).apply {
                putExtra(EXTRA_FIRST_PACKAGE, first.packageName)
                putExtra(EXTRA_FIRST_CLASS, first.className)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }

        private const val EXTRA_FIRST_PACKAGE = "first_package"
        private const val EXTRA_FIRST_CLASS = "first_class"
        private const val STATE_SPLIT_DISPATCHED = "split_dispatched"
        private const val SPLIT_SETTLE_DELAY_MS = 800L
        private const val MAX_COORDINATOR_LIFETIME_MS = 3_000L
    }
}
