package io.github.hatake716.ohagi.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageRepositoryTest {

    @Test
    fun existingVersionOneFileIsDecodedAndRanked() {
        val decoded = Json.decodeFromString<UsageState>(
            """
            {
              "entries": {
                "example.first/example.first.MainActivity": {
                  "count": 2,
                  "lastLaunchMs": 100
                },
                "example.frequent/example.frequent.MainActivity": {
                  "count": 5,
                  "lastLaunchMs": 50
                }
              },
              "version": 1
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf(app("frequent"), app("first")),
            decoded.rankedAppRefs(),
        )
    }

    @Test
    fun rankingUsesRecencyForEqualCountsAndIgnoresMalformedKeys() {
        val state = UsageState(
            entries = mapOf(
                "example.older/example.older.MainActivity" to
                    AppUsageEntry(count = 3, lastLaunchMs = 100),
                "example.newer/example.newer.MainActivity" to
                    AppUsageEntry(count = 3, lastLaunchMs = 200),
                "missing-separator" to AppUsageEntry(count = 99, lastLaunchMs = 999),
            ),
        )

        assertEquals(
            listOf(app("newer"), app("older")),
            state.rankedAppRefs(),
        )
    }

    @Test
    fun recordingLaunchIncrementsOnlyTheSelectedApp() {
        val selected = app("selected")
        val untouched = app("untouched")
        val initial = UsageState()
            .withRecordedLaunch(selected, launchTimeMs = 100)
            .withRecordedLaunch(untouched, launchTimeMs = 200)

        val result = initial.withRecordedLaunch(selected, launchTimeMs = 300)

        assertEquals(AppUsageEntry(count = 2, lastLaunchMs = 300), result.entries[key(selected)])
        assertEquals(AppUsageEntry(count = 1, lastLaunchMs = 200), result.entries[key(untouched)])
    }

    private fun app(id: String) = AppRef(
        packageName = "example.$id",
        className = "example.$id.MainActivity",
    )

    private fun key(app: AppRef): String = "${app.packageName}/${app.className}"
}
