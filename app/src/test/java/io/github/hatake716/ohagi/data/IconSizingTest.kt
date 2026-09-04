package io.github.hatake716.ohagi.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IconSizingTest {
    @Test
    fun previewTiersNeverUndersampleTheirDisplaySize() {
        for (requested in 1..192) {
            assertTrue(iconRenderSize(requested) >= requested, "request=$requested")
        }
        assertEquals(192, iconRenderSize(Int.MAX_VALUE))
        assertEquals(32, iconRenderSize(0))
    }

    @Test
    fun folderPreviewsUseAQuarterOfTheFormerPixelMemoryAtThreeTimesDensity() {
        val requested = (60 * 0.205f * 3).toInt()
        val actual = iconRenderSize(requested)
        assertEquals(48, actual)
        assertEquals(96 * 96 / 4, actual * actual)
    }

    @Test
    fun homeAndLibraryIconsKeepTheirExistingResolution() {
        assertEquals(96, iconRenderSize(90))
        assertEquals(144, iconRenderSize(120))
        assertEquals(192, iconRenderSize(180))
    }
}
