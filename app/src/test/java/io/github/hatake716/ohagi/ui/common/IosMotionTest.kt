package io.github.hatake716.ohagi.ui.common

import io.github.hatake716.ohagi.data.AppRef
import io.github.hatake716.ohagi.data.DockItem
import io.github.hatake716.ohagi.data.HomeItem
import kotlin.test.Test
import kotlin.test.assertEquals

class IosMotionTest {

    @Test
    fun homeAppKeepsMotionIdentityWhenItsSlotChanges() {
        val app = AppRef("example.app", "example.app.MainActivity")
        val before = homeMotionKeys(listOf(HomeItem.HomeApp(app), null), indexOffset = 24)
        val after = homeMotionKeys(listOf(null, HomeItem.HomeApp(app)), indexOffset = 24)

        assertEquals(before[0], after[1])
        assertEquals("home-empty-25", before[1])
        assertEquals("home-empty-24", after[0])
    }

    @Test
    fun dockFolderIdentitySurvivesRenameAndInternalReorder() {
        val first = AppRef("example.first", "example.first.MainActivity")
        val second = AppRef("example.second", "example.second.MainActivity")
        val before = dockMotionKeys(
            listOf(DockItem.DockFolder("Old", listOf(first, second)), null),
        )
        val after = dockMotionKeys(
            listOf(null, DockItem.DockFolder("New", listOf(second, first))),
        )

        assertEquals(before[0], after[1])
    }

    @Test
    fun folderAppKeepsMotionIdentityAcrossReorder() {
        val first = AppRef("example.first", "example.first.MainActivity")
        val second = AppRef("example.second", "example.second.MainActivity")
        val before = folderMotionKeys(listOf(first, second, null), indexOffset = 0)
        val after = folderMotionKeys(listOf(second, first, null), indexOffset = 0)

        assertEquals(before[0], after[1])
        assertEquals(before[1], after[0])
    }

    @Test
    fun dockVisibilityTracksGestureBetweenStaticAndHomePages() {
        assertEquals(0f, iosHomeSurfaceVisibility(0f, homePageCount = 2))
        assertEquals(0.5f, iosHomeSurfaceVisibility(0.5f, homePageCount = 2))
        assertEquals(1f, iosHomeSurfaceVisibility(1f, homePageCount = 2))
        assertEquals(1f, iosHomeSurfaceVisibility(2f, homePageCount = 2))
        assertEquals(0.5f, iosHomeSurfaceVisibility(2.5f, homePageCount = 2))
        assertEquals(0f, iosHomeSurfaceVisibility(3f, homePageCount = 2))
    }

    @Test
    fun pageDistanceIsContinuousOnBothSidesOfTheGesture() {
        assertEquals(0.25f, iosPageDistance(1, 0.25f, page = 1))
        assertEquals(0.75f, iosPageDistance(1, 0.25f, page = 2))
        assertEquals(1f, iosPageDistance(1, 0.25f, page = 3))
    }

    @Test
    fun dockVisibilityIsZeroWhenThereAreNoHomePages() {
        assertEquals(0f, iosHomeSurfaceVisibility(1f, homePageCount = 0))
    }
}
