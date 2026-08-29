package io.github.hatake716.ohagi.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LayoutPageOperationsTest {

    @Test
    fun legacySinglePageRemainsFirstPage() {
        val app = app("legacy")
        val state = LayoutState(
            home = List(LayoutState.HOME_CELL_COUNT) { index ->
                if (index == 7) HomeItem.HomeApp(app) else null
            },
        )

        assertEquals(1, state.homePageCount)
        assertEquals(HomeItem.HomeApp(app), state.homePage(0)[7])
    }

    @Test
    fun appendedPageAcceptsAtomicCrossPageMove() {
        val app = app("moved")
        val state = LayoutState(
            home = List(LayoutState.HOME_CELL_COUNT) { index ->
                if (index == 0) HomeItem.HomeApp(app) else null
            },
        ).appendEmptyHomePage()

        val target = homeGlobalIndex(page = 1, cell = 5)
        val result = state.moveAppToHome(target, AppMoveSource.Home(0))

        assertEquals(2, result.homePageCount)
        assertNull(result.home[0])
        assertEquals(HomeItem.HomeApp(app), result.home[target])
    }

    @Test
    fun edgeDropCreatesMissingPageAndMovesAppAtomically() {
        val moved = app("edge")
        val state = LayoutState(
            home = List(LayoutState.HOME_CELL_COUNT) { index ->
                if (index == 8) HomeItem.HomeApp(moved) else null
            },
        )

        val result = state.moveAppToHomePage(
            page = 1,
            source = AppMoveSource.Home(8),
        )

        assertEquals(2, result.homePageCount)
        assertNull(result.home[8])
        assertEquals(
            HomeItem.HomeApp(moved),
            result.home[LayoutState.HOME_CELL_COUNT],
        )
    }

    @Test
    fun edgeDropMovesWholeFolderWithoutFlatteningIt() {
        val folder = HomeItem.HomeFolder(
            name = "Folder",
            apps = listOf(app("one"), app("two")),
        )
        val state = LayoutState(
            home = List(LayoutState.HOME_CELL_COUNT) { index ->
                if (index == 4) folder else null
            },
        )

        val result = state.moveHomeItemToHomePage(page = 1, sourceIndex = 4)

        assertEquals(2, result.homePageCount)
        assertNull(result.home[4])
        assertEquals(folder, result.home[LayoutState.HOME_CELL_COUNT])
    }

    @Test
    fun canceledEdgeDragRemovesOnlyTrailingEmptyPages() {
        val occupied = app("occupied")
        val state = LayoutState(
            home = List(LayoutState.HOME_CELL_COUNT * 3) { index ->
                if (index == LayoutState.HOME_CELL_COUNT) HomeItem.HomeApp(occupied) else null
            },
        )

        val result = state.withoutTrailingEmptyHomePages()

        assertEquals(2, result.homePageCount)
        assertEquals(HomeItem.HomeApp(occupied), result.home[LayoutState.HOME_CELL_COUNT])
    }

    @Test
    fun occupiedLastPageIsRetained() {
        val occupied = app("last")
        val state = LayoutState(
            home = List(LayoutState.HOME_CELL_COUNT * 2) { index ->
                if (index == LayoutState.HOME_CELL_COUNT) HomeItem.HomeApp(occupied) else null
            },
        )

        assertEquals(2, state.withoutTrailingEmptyHomePages().homePageCount)
    }

    @Test
    fun widgetOrderCanMoveWithoutChangingWidgetIdentity() {
        val first = widget(1)
        val second = widget(2)
        val state = LayoutState(widgets = listOf(first, second))

        val result = state.withWidgetMoved(appWidgetId = 2, direction = -1)

        assertEquals(listOf(second, first), result.widgets)
    }

    private fun app(id: String) = AppRef(
        packageName = "example.$id",
        className = "example.$id.MainActivity",
    )

    private fun widget(id: Int) = WidgetPlacement(
        appWidgetId = id,
        providerPackage = "example.widget$id",
        providerClass = "example.widget$id.Provider",
    )
}
