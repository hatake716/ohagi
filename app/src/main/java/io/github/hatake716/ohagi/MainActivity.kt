package io.github.hatake716.ohagi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import io.github.hatake716.ohagi.ui.HomeScreen
import io.github.hatake716.ohagi.ui.theme.OhagiTheme
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {

    /** HOME ボタン再押下(onNewIntent)をホーム画面に伝えるイベント */
    private val homeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as OhagiApp).graph
        setContent {
            OhagiTheme {
                CompositionLocalProvider(LocalGraph provides graph) {
                    HomeScreen(homeEvents = homeEvents)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homeEvents.tryEmit(Unit)
    }

    override fun onResume() {
        super.onResume()
        (application as OhagiApp).graph.appRepository.refresh()
    }
}
