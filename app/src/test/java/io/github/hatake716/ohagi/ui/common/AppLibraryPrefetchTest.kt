package io.github.hatake716.ohagi.ui.common

import io.github.hatake716.ohagi.data.AppCategory
import io.github.hatake716.ohagi.data.AppIconRequest
import io.github.hatake716.ohagi.data.AppInfo
import io.github.hatake716.ohagi.data.AppRef
import kotlin.test.Test
import kotlin.test.assertEquals

class AppLibraryPrefetchTest {

    @Test
    fun requestsOnlyVisibleCategoryCardsAtTheirActualSizes() {
        val social = (0..7).map { app("social-$it", AppCategory.SOCIAL) }
        val productivity = (0..3).map { app("work-$it", AppCategory.PRODUCTIVITY_FINANCE) }
        val photo = listOf(app("photo", AppCategory.PHOTO_VIDEO))

        val requests = buildAppLibraryIconPrefetchRequests(
            apps = social + productivity + photo,
            previewIconSizePx = 192,
            miniIconSizePx = 96,
            categoryLimit = 2,
        )

        assertEquals(
            social.take(3).map { AppIconRequest(it.ref, 192) } +
                social.drop(3).take(4).map { AppIconRequest(it.ref, 96) } +
                productivity.take(3).map { AppIconRequest(it.ref, 192) } +
                productivity.drop(3).take(4).map { AppIconRequest(it.ref, 96) },
            requests,
        )
    }

    @Test
    fun categoryLimitCountsOnlyNonEmptyCategories() {
        val photo = app("photo", AppCategory.PHOTO_VIDEO)

        val requests = buildAppLibraryIconPrefetchRequests(
            apps = listOf(photo),
            previewIconSizePx = 192,
            miniIconSizePx = 96,
            categoryLimit = 1,
        )

        assertEquals(listOf(AppIconRequest(photo.ref, 192)), requests)
    }

    @Test
    fun partialCategoryRequestsOnlyItsVisibleTopRow() {
        val social = (0..7).map { app("social-$it", AppCategory.SOCIAL) }
        val productivity = (0..7).map { app("work-$it", AppCategory.PRODUCTIVITY_FINANCE) }

        val requests = buildAppLibraryIconPrefetchRequests(
            apps = social + productivity,
            previewIconSizePx = 192,
            miniIconSizePx = 96,
            categoryLimit = 1,
            partialCategoryLimit = 1,
        )

        assertEquals(
            social.take(3).map { AppIconRequest(it.ref, 192) } +
                social.drop(3).take(4).map { AppIconRequest(it.ref, 96) } +
                productivity.take(2).map { AppIconRequest(it.ref, 192) },
            requests,
        )
    }

    @Test
    fun categoryBudgetUsesSmallerViewportOnLowRamDevices() {
        assertEquals(AppLibraryPrefetchBudget(6, 2), appLibraryPrefetchBudget(false))
        assertEquals(AppLibraryPrefetchBudget(4, 0), appLibraryPrefetchBudget(true))
    }

    private fun app(id: String, category: AppCategory) = AppInfo(
        ref = AppRef(
            packageName = "example.$id",
            className = "example.$id.MainActivity",
        ),
        label = id,
        category = category,
    )
}
