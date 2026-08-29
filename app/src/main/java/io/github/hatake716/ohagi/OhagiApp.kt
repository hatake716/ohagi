package io.github.hatake716.ohagi

import android.app.Application

class OhagiApp : Application() {

    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
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
