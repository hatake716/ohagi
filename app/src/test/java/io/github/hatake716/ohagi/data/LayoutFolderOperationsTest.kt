package io.github.hatake716.ohagi.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutFolderOperationsTest {

    @Test
    fun stackingHomeAppOnAnotherCreatesFolderAndClearsSource() {
        val source = app("source")
        val target = app("target")
        val state = layout(
            home = mapOf(
                0 to HomeItem.HomeApp(source),
                1 to HomeItem.HomeApp(target),
            ),
        )

        val result = state.stackAppOnHome(
            targetIndex = 1,
            source = AppMoveSource.Home(0),
            folderName = "仕事",
        )

        assertNull(result.home[0])
        assertEquals(
            HomeItem.HomeFolder("仕事", listOf(target, source)),
            result.home[1],
        )
    }

    @Test
    fun movingDockAppIntoHomeFolderIsAtomic() {
        val existing = app("existing")
        val moved = app("moved")
        val state = layout(
            home = mapOf(2 to HomeItem.HomeFolder("Social", listOf(existing))),
            dock = mapOf(0 to DockItem.DockApp(moved)),
        )

        val result = state.stackAppOnHome(
            targetIndex = 2,
            source = AppMoveSource.Dock(0),
            folderName = "ignored",
        )

        assertNull(result.dock[0])
        assertEquals(
            HomeItem.HomeFolder("Social", listOf(existing, moved)),
            result.home[2],
        )
    }

    @Test
    fun movingFolderAppOutKeepsOneAppFolder() {
        val first = app("first")
        val second = app("second")
        val state = layout(
            home = mapOf(
                3 to HomeItem.HomeFolder("Folder", listOf(first, second)),
            ),
        )

        val result = state.moveAppToHome(
            targetIndex = 5,
            source = AppMoveSource.HomeFolder(3, 0, first),
        )

        assertEquals(HomeItem.HomeFolder("Folder", listOf(second)), result.home[3])
        assertEquals(HomeItem.HomeApp(first), result.home[5])
    }

    @Test
    fun movingLastFolderAppOutDeletesEmptyFolder() {
        val only = app("only")
        val state = layout(
            dock = mapOf(
                1 to DockItem.DockFolder("Folder", listOf(only)),
            ),
        )

        val result = state.moveAppToHome(
            targetIndex = 4,
            source = AppMoveSource.DockFolder(1, 0, only),
        )

        assertNull(result.dock[1])
        assertEquals(HomeItem.HomeApp(only), result.home[4])
    }

    @Test
    fun folderAppCanSwapWithTopLevelDockApp() {
        val inside = app("inside")
        val other = app("other")
        val state = layout(
            home = mapOf(
                0 to HomeItem.HomeFolder("Folder", listOf(inside)),
            ),
            dock = mapOf(
                2 to DockItem.DockApp(other),
            ),
        )

        val result = state.moveAppToDock(
            targetSlot = 2,
            source = AppMoveSource.HomeFolder(0, 0, inside),
        )

        assertEquals(HomeItem.HomeFolder("Folder", listOf(other)), result.home[0])
        assertEquals(DockItem.DockApp(inside), result.dock[2])
    }

    @Test
    fun wholeFolderMovesBetweenHomeAndDockAndSwapsTarget() {
        val folderApp = app("folder")
        val dockApp = app("dock")
        val state = layout(
            home = mapOf(
                7 to HomeItem.HomeFolder("Folder", listOf(folderApp)),
            ),
            dock = mapOf(
                3 to DockItem.DockApp(dockApp),
            ),
        )

        val result = state.moveHomeItemToDock(homeIndex = 7, dockSlot = 3)

        assertEquals(HomeItem.HomeApp(dockApp), result.home[7])
        assertEquals(DockItem.DockFolder("Folder", listOf(folderApp)), result.dock[3])
    }

    @Test
    fun folderAppsReorderWithinSameFolder() {
        val first = app("first")
        val second = app("second")
        val third = app("third")
        val state = layout(
            dock = mapOf(
                0 to DockItem.DockFolder("Folder", listOf(first, second, third)),
            ),
        )

        val result = state.reorderFolderApps(
            location = FolderLocation.Dock(0),
            fromIndex = 0,
            toIndex = 2,
        )

        assertEquals(
            DockItem.DockFolder("Folder", listOf(second, third, first)),
            result.dock[0],
        )
    }

    @Test
    fun pickerCreatesFolderOnlyAfterTwoDistinctAppsExist() {
        val existing = app("existing")
        val added = app("added")
        val state = layout(
            home = mapOf(0 to HomeItem.HomeApp(existing)),
        )

        val result = state.createOrAddFolder(
            location = FolderLocation.Home(0),
            name = "Utilities",
            addedApps = listOf(added, added),
        )

        val folder = result.home[0] as HomeItem.HomeFolder
        assertEquals("Utilities", folder.name)
        assertEquals(listOf(existing, added), folder.apps)
        assertTrue(folder.apps.distinct().size == folder.apps.size)
    }

    @Test
    fun folderAppCanMoveDirectlyIntoFolderOnOtherSurface() {
        val moved = app("moved")
        val stays = app("stays")
        val target = app("target")
        val state = layout(
            home = mapOf(
                6 to HomeItem.HomeFolder("Home", listOf(moved, stays)),
            ),
            dock = mapOf(
                1 to DockItem.DockFolder("Dock", listOf(target)),
            ),
        )

        val result = state.stackAppOnDock(
            targetSlot = 1,
            source = AppMoveSource.HomeFolder(6, 0, moved),
            folderName = "ignored",
        )

        assertEquals(HomeItem.HomeFolder("Home", listOf(stays)), result.home[6])
        assertEquals(DockItem.DockFolder("Dock", listOf(target, moved)), result.dock[1])
    }

    @Test
    fun externalAppAddsToExistingFolderWithoutChangingOtherPositions() {
        val existing = app("existing")
        val external = app("external")
        val untouched = app("untouched")
        val state = layout(
            home = mapOf(
                2 to HomeItem.HomeFolder("Folder", listOf(existing)),
                3 to HomeItem.HomeApp(untouched),
            ),
        )

        val result = state.stackAppOnHome(
            targetIndex = 2,
            source = AppMoveSource.External(external),
            folderName = "ignored",
        )

        assertEquals(
            HomeItem.HomeFolder("Folder", listOf(existing, external)),
            result.home[2],
        )
        assertEquals(HomeItem.HomeApp(untouched), result.home[3])
    }

    @Test
    fun removingLastAppFromFolderDeletesFolder() {
        val only = app("only")
        val state = layout(
            home = mapOf(4 to HomeItem.HomeFolder("Folder", listOf(only))),
        )

        val result = state.removeAppFromFolder(FolderLocation.Home(4), only)

        assertNull(result.home[4])
    }

    @Test
    fun legacyFourSlotDockMigrationKeepsSidesAndOpensCenter() {
        val oldDock = listOf(
            DockItem.DockApp(app("left-first")),
            DockItem.DockFolder("Left", listOf(app("left-second"))),
            DockItem.DockApp(app("right-first")),
            DockItem.DockFolder("Right", listOf(app("right-second"))),
        )

        val migrated = migrateLegacyFourSlotDock(oldDock)

        assertEquals(LayoutState.DOCK_SLOT_COUNT, migrated.size)
        assertEquals(oldDock[0], migrated[0])
        assertEquals(oldDock[1], migrated[1])
        assertNull(migrated[2])
        assertEquals(oldDock[2], migrated[3])
        assertEquals(oldDock[3], migrated[4])
    }

    @Test
    fun configurableDockAcceptsAppsAndFoldersInNewSlots() {
        val moved = app("center-slot")
        val appState = layout(home = mapOf(0 to HomeItem.HomeApp(moved)))

        val appResult = appState.moveHomeItemToDock(homeIndex = 0, dockSlot = 2)

        assertNull(appResult.home[0])
        assertEquals(DockItem.DockApp(moved), appResult.dock[2])

        val folder = HomeItem.HomeFolder("Fifth", listOf(app("inside")))
        val folderState = layout(home = mapOf(1 to folder))

        val folderResult = folderState.moveHomeItemToDock(homeIndex = 1, dockSlot = 2)

        assertNull(folderResult.home[1])
        assertEquals(DockItem.DockFolder(folder.name, folder.apps), folderResult.dock[2])

        val rightmost = app("rightmost-slot")
        val rightmostState = layout(home = mapOf(2 to HomeItem.HomeApp(rightmost)))

        val rightmostResult = rightmostState.moveHomeItemToDock(homeIndex = 2, dockSlot = 4)

        assertNull(rightmostResult.home[2])
        assertEquals(DockItem.DockApp(rightmost), rightmostResult.dock[4])
    }

    private fun layout(
        home: Map<Int, HomeItem> = emptyMap(),
        dock: Map<Int, DockItem> = emptyMap(),
    ): LayoutState = LayoutState(
        home = List(LayoutState.HOME_CELL_COUNT) { index -> home[index] },
        dock = List(LayoutState.DOCK_SLOT_COUNT) { slot -> dock[slot] },
    )

    private fun app(id: String) = AppRef(
        packageName = "example.$id",
        className = "example.$id.MainActivity",
    )
}
