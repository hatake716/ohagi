package io.github.hatake716.ohagi.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppLaunchRequestTest {

    @Test
    fun floatingBoundsRoundOutwardAroundTheWholeIcon() {
        assertEquals(
            LaunchBounds(left = 10, top = 20, right = 71, bottom = 82),
            LaunchBounds.fromEdges(10.8f, 20.2f, 70.1f, 81.01f),
        )
    }

    @Test
    fun launchBoundsAreClippedToTheComposeRoot() {
        assertEquals(
            LaunchBounds(left = 0, top = 12, right = 1080, bottom = 120),
            LaunchBounds(-20, 12, 1120, 120).clippedTo(1080, 2424),
        )
    }

    @Test
    fun emptyOrNonFiniteBoundsAreRejected() {
        assertNull(LaunchBounds.fromEdges(Float.NaN, 0f, 10f, 10f))
        assertNull(LaunchBounds(20, 20, 20, 40).clippedTo(1080, 2424))
        assertNull(LaunchBounds(-20, -20, -10, -10).clippedTo(1080, 2424))
    }
}
