package cz.hh.detektormapy.ui.map

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import cz.hh.detektormapy.map.LayerDef
import cz.hh.detektormapy.map.LayerKind
import cz.hh.detektormapy.map.LayerUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reorder mode swaps the visibility switches for arrows on purpose: a mis-tap while
 * arranging layers must never toggle one off (that would drop its source and refetch the
 * viewport). This drives the real sheet composition, so it also catches the arrows not
 * appearing at all.
 */
@RunWith(AndroidJUnit4::class)
class LayerPanelReorderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun overlay(id: String, title: String, order: Int) = LayerUiState(
        def = LayerDef(
            id = id,
            title = title,
            kind = LayerKind.XYZ,
            source = "https://example.test/$id/{z}/{x}/{y}",
            order = order,
        ),
        visible = true,
        opacity = 0.75f,
        available = true,
    )

    private val layers = listOf(
        overlay("vm2", "II. vojenské mapování", order = 10),
        overlay("dmr5g", "DMR 5G", order = 20),
    )

    private fun setPanel(onMoveLayer: (String, Int) -> Unit) {
        compose.setContent {
            LayerPanel(
                layers = layers,
                onDismiss = {},
                onToggle = { _, _ -> },
                onOpacity = { _, _ -> },
                onOpacityCommitted = {},
                onCalibrate = {},
                onManageCalibrations = {},
                onReload = {},
                onMoveLayer = onMoveLayer,
                showFinds = true,
                showPlaces = true,
                showAreas = true,
                onShowFinds = {},
                onShowPlaces = {},
                onShowAreas = {},
                onAddImageOverlay = {},
            )
        }
    }

    @Test
    fun reorderModeShowsArrowsAndReportsTheMove() {
        val moves = mutableListOf<Pair<String, Int>>()
        setPanel { id, delta -> moves += id to delta }

        compose.onNodeWithContentDescription("Uspořádat vrstvy").performClick()

        // Two overlays -> two rows of arrows; the first row can only move down.
        compose.onAllNodesWithContentDescription("Posunout výš").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Posunout výš")[0].assertIsNotEnabled()
        compose.onAllNodesWithContentDescription("Posunout níž")[1].assertIsNotEnabled()

        compose.onAllNodesWithContentDescription("Posunout níž")[0].performClick()
        assertThat(moves).containsExactly("vm2" to 1)

        compose.onAllNodesWithContentDescription("Posunout výš")[1].performClick()
        assertThat(moves).containsExactly("vm2" to 1, "dmr5g" to -1).inOrder()
    }

    @Test
    fun reorderModeHidesTheSwitchesSoAMisTapCannotToggleALayer() {
        setPanel { _, _ -> }
        // Before: two layer switches + three pin toggles of the "Na mapě" section.
        compose.onAllNodes(isToggleable()).assertCountEquals(5)

        compose.onNodeWithContentDescription("Uspořádat vrstvy").performClick()

        // After: the layer switches are gone, only the pin toggles remain.
        compose.onAllNodes(isToggleable()).assertCountEquals(3)
        compose.onAllNodesWithContentDescription("Posunout níž").assertCountEquals(2)
    }
}
