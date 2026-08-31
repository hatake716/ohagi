package io.github.hatake716.ohagi.ui.common

import io.github.hatake716.ohagi.data.AppCategory
import io.github.hatake716.ohagi.data.AppInfo
import io.github.hatake716.ohagi.data.AppRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AppBrowserOrderingTest {

    @Test
    fun preferredAppsMoveToFrontWithoutChangingRemainingOrder() {
        val apps = listOf("alpha", "beta", "gamma", "delta").map(::app)

        val result = prioritizeAppsForPreview(
            apps = apps,
            preferredApps = listOf(apps[3].ref, apps[1].ref),
        )

        assertEquals(
            listOf(apps[3], apps[1], apps[0], apps[2]),
            result,
        )
    }

    @Test
    fun packageFallbackKeepsPreferredAppVisibleAfterLauncherActivityChanges() {
        val unrelated = app("unrelated")
        val updated = AppInfo(
            ref = AppRef("example.updated", "example.updated.NewActivity"),
            label = "updated",
            category = AppCategory.UTILITIES,
        )

        val result = prioritizeAppsForPreview(
            apps = listOf(unrelated, updated),
            preferredApps = listOf(
                AppRef("example.updated", "example.updated.OldActivity"),
            ),
        )

        assertEquals(listOf(updated, unrelated), result)
    }

    @Test
    fun packageFallbackPreservesItsPositionAmongExactPreferences() {
        val updated = AppInfo(
            ref = AppRef("example.updated", "example.updated.NewActivity"),
            label = "updated",
            category = AppCategory.UTILITIES,
        )
        val exact = app("exact")

        val result = prioritizeAppsForPreview(
            apps = listOf(exact, updated),
            preferredApps = listOf(
                AppRef("example.updated", "example.updated.OldActivity"),
                exact.ref,
            ),
        )

        assertEquals(listOf(updated, exact), result)
    }

    @Test
    fun emptyPreferenceKeepsOriginalListInstance() {
        val apps = listOf("alpha", "beta").map(::app)

        assertSame(
            apps,
            prioritizeAppsForPreview(apps, preferredApps = emptyList()),
        )
    }

    @Test
    fun overviewHasDedicatedFrequentSectionWithoutRemovingAppsFromCategories() {
        val apps = listOf("alpha", "beta", "gamma", "delta").map(::app)

        val overview = buildAppBrowserOverviewContent(
            apps = apps,
            frequentAppRefs = listOf(apps[3].ref, apps[1].ref),
            preferredApps = listOf(apps[2].ref),
        )

        assertEquals(listOf(apps[3], apps[1]), overview.frequentApps)
        assertEquals(
            listOf(apps[2], apps[0], apps[1], apps[3]),
            overview.categoryGroups.single().second,
        )
    }

    @Test
    fun frequentSectionIsLimitedToTwoRowsOfFourApps() {
        val apps = (0 until 10).map { app("app-$it") }

        val overview = buildAppBrowserOverviewContent(
            apps = apps,
            frequentAppRefs = apps.reversed().map(AppInfo::ref),
            preferredApps = emptyList(),
        )

        assertEquals(apps.reversed().take(FREQUENT_APP_LIMIT), overview.frequentApps)
    }

    @Test
    fun frequentSectionFallsBackToCurrentLauncherActivity() {
        val updated = AppInfo(
            ref = AppRef("example.updated", "example.updated.NewActivity"),
            label = "updated",
            category = AppCategory.UTILITIES,
        )

        val resolved = resolvePreferredApps(
            apps = listOf(updated),
            preferredApps = listOf(
                AppRef("example.updated", "example.updated.OldActivity"),
            ),
        )

        assertEquals(listOf(updated), resolved)
    }

    private fun app(id: String) = AppInfo(
        ref = AppRef(
            packageName = "example.$id",
            className = "example.$id.MainActivity",
        ),
        label = id,
        category = AppCategory.UTILITIES,
    )
}
