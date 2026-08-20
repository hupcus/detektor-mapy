package cz.hh.detektormapy.calibration

import com.google.common.truth.Truth.assertThat
import cz.hh.detektormapy.util.WebMercator
import org.junit.Test

/**
 * Regression test for the rotation direction of Režim A.
 *
 * The bug this pins down: Compose reports a clockwise two-finger twist as a POSITIVE angle in
 * screen space (y down), while [Affine2D] works in EPSG:3857 (y up). Feeding the raw angle
 * through rotated the overlay the wrong way, so a user correcting a 10° misalignment made it
 * 20° instead — the exact opposite of what the feature is for.
 *
 * `MapScreen` therefore negates the gesture angle; these assertions describe the behaviour the
 * negation has to produce.
 */
class GestureRotationTest {

    private val pivotLon = 15.0
    private val pivotLat = 50.0
    private val pivotX = WebMercator.lonToMeters(pivotLon)
    private val pivotY = WebMercator.latToMeters(pivotLat)

    /** What `MapScreen` hands to the view model for a gesture of [gestureDeg] degrees. */
    private fun transformForGesture(gestureDeg: Double): Affine2D = Affine2D.similarity(
        pivotX = pivotX,
        pivotY = pivotY,
        dx = 0.0,
        dy = 0.0,
        rotationRad = -Math.toRadians(gestureDeg),
        scale = 1.0,
    )

    @Test
    fun `clockwise gesture rotates the overlay clockwise on screen`() {
        // A point 1 km due east of the pivot. On screen it sits to the right.
        val eastX = pivotX + 1000.0
        val eastY = pivotY

        val rotated = transformForGesture(gestureDeg = 90.0)
        val x = rotated.applyX(eastX, eastY)
        val y = rotated.applyY(eastX, eastY)

        // Clockwise on screen moves "right" to "down"; down on screen is south, i.e. a SMALLER
        // Mercator y. Getting a larger y here means the overlay turned anticlockwise.
        assertThat(y).isLessThan(pivotY)
        assertThat(x).isWithin(1.0).of(pivotX)
    }

    @Test
    fun `anticlockwise gesture rotates the overlay anticlockwise on screen`() {
        val eastX = pivotX + 1000.0
        val eastY = pivotY

        val rotated = transformForGesture(gestureDeg = -90.0)
        assertThat(rotated.applyY(eastX, eastY)).isGreaterThan(pivotY)
    }

    @Test
    fun `a small correction moves the overlay by that same small angle`() {
        val transform = transformForGesture(gestureDeg = 10.0)
        // rotationRad is reported in the 3857 frame, where the sign is flipped back.
        assertThat(Math.toDegrees(transform.rotationRad)).isWithin(1e-6).of(-10.0)
        assertThat(transform.scale).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `the pivot never moves regardless of the gesture`() {
        listOf(-180.0, -37.0, 0.0, 5.0, 179.0).forEach { deg ->
            val t = transformForGesture(deg)
            assertThat(t.applyX(pivotX, pivotY)).isWithin(1e-6).of(pivotX)
            assertThat(t.applyY(pivotX, pivotY)).isWithin(1e-6).of(pivotY)
        }
    }

    @Test
    fun `two successive nudges compose into their sum`() {
        // MapViewModel composes step.concat(pending); repeating a gesture must add up, not drift.
        val first = transformForGesture(6.0)
        val second = transformForGesture(4.0)
        val combined = second.concat(first)
        assertThat(Math.toDegrees(combined.rotationRad)).isWithin(1e-6).of(-10.0)
        assertThat(combined.scale).isWithin(1e-9).of(1.0)
    }
}
