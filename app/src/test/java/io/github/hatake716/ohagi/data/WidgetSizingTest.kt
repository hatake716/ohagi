package io.github.hatake716.ohagi.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class WidgetSizingTest {

    @Test
    fun legacyPlacementWithoutWidthRemainsResponsiveFullWidth() {
        val placement = Json.decodeFromString<WidgetPlacement>(
            """{"appWidgetId":7,"providerPackage":"example.widget","providerClass":"Provider","heightDp":180}""",
        )

        assertEquals(WidgetPlacement.MATCH_PARENT_WIDTH_DP, placement.widthDp)
        assertEquals(180, placement.heightDp)
    }

    @Test
    fun resizingChangesOnlyTheRequestedWidgetAndPreservesOrder() {
        val first = widget(1)
        val second = widget(2)
        val state = LayoutState(widgets = listOf(first, second))

        val result = state.withWidgetResized(
            appWidgetId = 2,
            widthDp = 220,
            heightDp = 300,
        )

        assertEquals(first, result.widgets[0])
        assertEquals(second.copy(widthDp = 220, heightDp = 300), result.widgets[1])
    }

    @Test
    fun resizingClampsCorruptDimensionsButKeepsFullWidthSentinel() {
        val widget = widget(1)
        val state = LayoutState(widgets = listOf(widget))

        val tooSmall = state.withWidgetResized(1, widthDp = 1, heightDp = 1)
        val tooLarge = state.withWidgetResized(1, widthDp = Int.MAX_VALUE, heightDp = Int.MAX_VALUE)
        val fullWidth = state.withWidgetResized(
            1,
            widthDp = WidgetPlacement.MATCH_PARENT_WIDTH_DP,
            heightDp = 240,
        )

        assertEquals(WidgetPlacement.MIN_WIDGET_WIDTH_DP, tooSmall.widgets.single().widthDp)
        assertEquals(WidgetPlacement.MIN_WIDGET_HEIGHT_DP, tooSmall.widgets.single().heightDp)
        assertEquals(WidgetPlacement.MAX_WIDGET_WIDTH_DP, tooLarge.widgets.single().widthDp)
        assertEquals(WidgetPlacement.MAX_WIDGET_HEIGHT_DP, tooLarge.widgets.single().heightDp)
        assertEquals(WidgetPlacement.MATCH_PARENT_WIDTH_DP, fullWidth.widgets.single().widthDp)
        assertEquals(240, fullWidth.widgets.single().heightDp)
    }

    @Test
    fun resizingUnknownWidgetIsNoOp() {
        val state = LayoutState(widgets = listOf(widget(1)))

        assertSame(state, state.withWidgetResized(99, widthDp = 220, heightDp = 300))
    }

    private fun widget(id: Int) = WidgetPlacement(
        appWidgetId = id,
        providerPackage = "example.widget$id",
        providerClass = "example.widget$id.Provider",
    )
}
