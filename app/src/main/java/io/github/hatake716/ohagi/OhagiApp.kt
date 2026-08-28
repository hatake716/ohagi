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
}
