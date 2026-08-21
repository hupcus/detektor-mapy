package cz.hh.detektormapy.ui.calibration

import com.google.common.truth.Truth.assertThat
import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.data.entity.GcpPointEntity
import org.junit.Test

/**
 * A single ground control point used to leave every button disabled, so tapping the church on
 * the old map and again on the ortophoto produced no visible effect whatsoever -- the editor
 * looked broken when it was merely waiting for a second point it never announced needing.
 */
class GcpEditorStateTest {

    private fun point(id: Long) = GcpPointEntity(id = id, setId = 1, srcX = 0.0, srcY = 0.0, dstX = 10.0, dstY = 0.0)

    @Test
    fun `one point is enough for a similarity fit`() {
        val state = GcpEditorState(points = listOf(point(1)), transform = Affine2D.translation(10.0, 0.0))
        assertThat(state.canFit).isTrue()
    }

    @Test
    fun `an affine fit still demands three points`() {
        val state = GcpEditorState(points = listOf(point(1), point(2)), useSimilarity = false)
        assertThat(state.canFit).isFalse()
    }

    @Test
    fun `the label says a single point only shifts`() {
        val state = GcpEditorState(points = listOf(point(1)), transform = Affine2D.translation(10.0, 0.0))
        assertThat(state.fitLabel).contains("Jen posun")
    }

    @Test
    fun `the label promises rotation once a second point exists`() {
        val state = GcpEditorState(
            points = listOf(point(1), point(2)),
            transform = Affine2D.translation(10.0, 0.0),
        )
        assertThat(state.fitLabel).contains("otočení")
    }

    @Test
    fun `with no points the label asks for one`() {
        assertThat(GcpEditorState().fitLabel).isEqualTo("Zadej aspoň jeden bod")
    }
}
