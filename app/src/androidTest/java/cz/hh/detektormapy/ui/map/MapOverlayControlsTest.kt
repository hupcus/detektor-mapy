package cz.hh.detektormapy.ui.map

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import cz.hh.detektormapy.map.LayerDef
import cz.hh.detektormapy.map.LayerKind
import cz.hh.detektormapy.map.LayerUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the hold-to-peek gesture on the layers button.
 *
 * This needs an instrumented test rather than a unit test: the bug it guards against was the
 * layers button being a `SmallFloatingActionButton`, whose internal clickable swallowed the
 * pointer stream so a parent `pointerInput` never saw the long press. Only a real gesture
 * through the real composition can catch that class of mistake -- and `adb shell input` cannot,
 * because it compresses gesture timing and the press never crosses the long-press threshold.
 */
@RunWith(AndroidJUnit4::class)
class MapOverlayControlsTest {

    @get:Rule
    val compose = createComposeRule()

    private val overlay = LayerUiState(
        def = LayerDef(
            id = "vm2",
            title = "II. vojenské mapování",
            kind = LayerKind.PMTILES,
            source = "vm2.pmtiles",
        ),
        visible = true,
        opacity = 0.75f,
        available = true,
    )

    private fun state(peeking: Boolean = false, layers: List<LayerUiState> = listOf(overlay)) =
        MapUiState(layers = layers, peeking = peeking)

    @Test
    fun holdingTheLayersButtonPeeksAndReleasingRestores() {
        val events = mutableListOf<Boolean>()
        compose.setContent {
            MapOverlayControls(
                state = state(),
                onToggleLayers = {},
                onPeek = { events += it },
                onToggleFollow = {},
                onToggleCompass = {},
                onAddFind = {},
                onToggleRecording = {},
                onZoomIn = {},
                onZoomOut = {},
            )
        }

        compose.onNodeWithContentDescription("Vrstvy — podrž pro náhled reality")
            .performTouchInput { longClick() }

        // Peek on while held, off again once the finger lifts -- never left stuck on.
        assertThat(events).containsExactly(true, false).inOrder()
    }

    @Test
    fun tappingOpensTheLayerPanelWithoutPeeking() {
        var opened = 0
        val peeks = mutableListOf<Boolean>()
        compose.setContent {
            MapOverlayControls(
                state = state(),
                onToggleLayers = { opened++ },
                onPeek = { peeks += it },
                onToggleFollow = {},
                onToggleCompass = {},
                onAddFind = {},
                onToggleRecording = {},
                onZoomIn = {},
                onZoomOut = {},
            )
        }

        compose.onNodeWithContentDescription("Vrstvy — podrž pro náhled reality").performClick()

        assertThat(opened).isEqualTo(1)
        assertThat(peeks).doesNotContain(true)
    }

    @Test
    fun holdingDoesNothingWhenThereIsNoOverlayToHide() {
        val events = mutableListOf<Boolean>()
        compose.setContent {
            MapOverlayControls(
                state = state(layers = listOf(overlay.copy(visible = false))),
                onToggleLayers = {},
                onPeek = { events += it },
                onToggleFollow = {},
                onToggleCompass = {},
                onAddFind = {},
                onToggleRecording = {},
                onZoomIn = {},
                onZoomOut = {},
            )
        }

        compose.onNodeWithContentDescription("Vrstvy — podrž pro náhled reality")
            .performTouchInput { longClick() }

        assertThat(events).doesNotContain(true)
    }
}
