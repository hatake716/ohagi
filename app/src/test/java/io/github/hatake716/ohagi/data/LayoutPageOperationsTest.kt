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
    fun emptyPrimaryPageIsAlwaysRetained() {
        val state = LayoutState(
            home = List(LayoutState.HOME_CELL_COUNT * 2) { null },
        )

        val result = state.withoutEmptyAdditionalHomePages()

        assertEquals(1, result.homePageCount)
        assertEquals(List(LayoutState.HOME_CELL_COUNT) { null }, result.home)
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
    fun emptyMiddlePageIsRemovedAndLaterPageKeepsItsCell() {
        val first = app("first")
        val later = app("later")
        val state = LayoutState(
            home = List(LayoutState.HOME_CELL_COUNT * 3) { index ->
                when (index) {
                    3 -> HomeItem.HomeApp(first)
                    LayoutState.HOME_CELL_COUNT * 2 + 7 -> HomeItem.HomeApp(later)
                    else -> null
                }
            },
        )

        val result = state.withoutEmptyAdditionalHomePages()

        assertEquals(2, result.homePageCount)
        assertEquals(HomeItem.HomeApp(first), result.home[3])
        assertEquals(
            HomeItem.HomeApp(later),
            result.home[LayoutState.HOME_CELL_COUNT + 7],
        )
    }

    @Test
    fun occupiedLastPageIsRetained() {
        val occupied = app("last")
        val state = LayoutState(
            home = List(LayoutState.HOME_CELL_COUNT * 2) { index ->
                if (index == LayoutState.HOME_CELL_COUNT) HomeItem.HomeApp(occupied) else null
            },
        )

        assertEquals(2, state.withoutEmptyAdditionalHomePages().homePageCount)
    }

    @Test
    fun folderIconKeepsAdditionalPage() {
        val folder = HomeItem.HomeFolder(
            name = "Folder",
            apps = listOf(app("one")),
        )
        val state = LayoutState(
            home = List(LayoutState.HOME_CELL_COUNT * 2) { index ->
                if (index == LayoutState.HOME_CELL_COUNT + 2) folder else null
            },
        )

        val result = state.withoutEmptyAdditionalHomePages()

        assertEquals(2, result.homePageCount)
        assertEquals(folder, result.home[LayoutState.HOME_CELL_COUNT + 2])
    }

    @Test
    fun movingLastItemOffAdditionalPageRemovesThatPage() {
        val moved = app("dock")
        val sourceIndex = LayoutState.HOME_CELL_COUNT + 4
        val state = LayoutState(
            home = List(LayoutState.HOME_CELL_COUNT * 2) { index ->
                if (index == sourceIndex) HomeItem.HomeApp(moved) else null
            },
        )

        val result = state
            .moveHomeItemToDock(sourceIndex, dockSlot = 0)
            .withoutEmptyAdditionalHomePages()

        assertEquals(1, result.homePageCount)
        assertEquals(DockItem.DockApp(moved), result.dock[0])
        assertEquals(List(LayoutState.HOME_CELL_COUNT) { null }, result.home)
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
