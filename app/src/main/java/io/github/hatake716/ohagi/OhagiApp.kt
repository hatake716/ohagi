package io.github.hatake716.ohagi

import android.app.Application
import io.github.hatake716.ohagi.util.SplitLaunchNotification

class OhagiApp : Application() {

    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        SplitLaunchNotification.createChannel(this)
        graph = Graph(this)
        graph.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::graph.isInitialized) graph.trimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::graph.isInitialized) graph.clearMemoryCaches()
    }
}
