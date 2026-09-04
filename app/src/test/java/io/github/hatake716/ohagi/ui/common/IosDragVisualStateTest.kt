package io.github.hatake716.ohagi.ui.common

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IosDragVisualStateTest {
    @Test
    fun animationChangesInvalidateDrawingWithoutObservingHolderCreation() {
        val interaction = mutableStateOf(1f)
        val landing = mutableStateOf(1f)
        val opacity = mutableStateOf(1f)
        val invalidated = mutableSetOf<String>()
        val onChanged: (String) -> Unit = { invalidated += it }
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            lateinit var visual: IosDragVisualState
            observer.observeReads("composition", onChanged) {
                visual = IosDragVisualState(interaction, landing, opacity) {}
            }
            observer.observeReads("drawing", onChanged) {
                assertEquals(1f, visual.scale, 0f)
                assertEquals(1f, visual.alpha, 0f)
            }

            Snapshot.withMutableSnapshot {
                interaction.value = 0.90f
                landing.value = 1.065f
                opacity.value = 0.14f
            }

            assertEquals(setOf("drawing"), invalidated)
            // A retained holder must expose the latest frame, without being reconstructed.
            assertEquals(0.90f * 1.065f, visual.scale, 0.0001f)
            assertEquals(0.14f, visual.alpha, 0f)
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    @Test
    fun alphaOnlyDrawingDoesNotObserveScaleAnimations() {
        val interaction = mutableStateOf(1f)
        val landing = mutableStateOf(1f)
        val opacity = mutableStateOf(1f)
        val visual = IosDragVisualState(interaction, landing, opacity) {}
        val invalidated = mutableSetOf<String>()
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads("alpha", { scope: String -> invalidated += scope }) {
                assertEquals(1f, visual.alpha, 0f)
            }
            Snapshot.withMutableSnapshot {
                interaction.value = 0.94f
                landing.value = 1.02f
            }
            assertTrue(invalidated.isEmpty())

            Snapshot.withMutableSnapshot { opacity.value = 0.14f }
            assertEquals(setOf("alpha"), invalidated)
        } finally {
            observer.stop()
            observer.clear()
        }
    }
}
