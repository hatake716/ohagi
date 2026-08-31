package io.github.hatake716.ohagi.data

import kotlin.test.Test
import kotlin.test.assertEquals

class PreferredAppRefsTest {

    @Test
    fun dockThenHomePlacementsProvideStableUniquePreferenceOrder() {
        val dockApp = app("dock")
        val shared = app("shared")
        val dockFolderApp = app("dock-folder")
        val homeApp = app("home")
        val homeFolderApp = app("home-folder")
        val state = LayoutState(
            dock = listOf(
                DockItem.DockApp(dockApp),
                DockItem.DockFolder("Dock folder", listOf(shared, dockFolderApp)),
            ) + List(LayoutState.DOCK_SLOT_COUNT - 2) { null },
            home = listOf(
                HomeItem.HomeApp(homeApp),
                HomeItem.HomeFolder("Home folder", listOf(shared, homeFolderApp)),
            ) + List(LayoutState.HOME_CELL_COUNT - 2) { null },
        )

        assertEquals(
            listOf(dockApp, shared, dockFolderApp, homeApp, homeFolderApp),
            state.preferredAppRefs(),
        )
    }

    @Test
    fun launchRankingComesBeforePlacementFallbacks() {
        val frequent = app("frequent")
        val dock = app("dock")
        val state = LayoutState(
            dock = listOf(DockItem.DockApp(dock)) +
                List(LayoutState.DOCK_SLOT_COUNT - 1) { null },
        )

        assertEquals(
            listOf(frequent, dock),
            state.preferredAppRefs(rankedLaunches = listOf(frequent)),
        )
    }

    private fun app(id: String) = AppRef(
        packageName = "example.$id",
        className = "example.$id.MainActivity",
    )
}
