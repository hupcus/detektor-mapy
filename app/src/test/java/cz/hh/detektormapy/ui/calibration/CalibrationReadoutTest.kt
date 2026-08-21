package cz.hh.detektormapy.ui.calibration

import com.google.common.truth.Truth.assertThat
import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.util.WebMercator
import org.junit.Test

class CalibrationReadoutTest {

    private val pivotX = WebMercator.lonToMeters(16.02)
    private val pivotY = WebMercator.latToMeters(50.51)

    @Test
    fun `identity says nothing has changed`() {
        assertThat(CalibrationReadout.describe(Affine2D.IDENTITY, pivotX, pivotY))
            .isEqualTo("Zatím beze změny")
    }

    /**
     * Web Mercator metres are inflated by 1/cos(latitude). At 50.5 N that is ~1.57x, so a
     * 100 m nudge used to be reported as 157 m -- enough to send someone digging in the wrong
     * field, or to make them "correct" a calibration that was already right.
     */
    @Test
    fun `distance is reported in ground metres, not Mercator metres`() {
        val mercatorMetres = 100.0 / Math.cos(Math.toRadians(50.51))
        val text = CalibrationReadout.describe(
            Affine2D.translation(mercatorMetres, 0.0),
            pivotX,
            pivotY,
        )
        assertThat(text).isEqualTo("posun 100 m")
    }

    /**
     * The regression that made the calibration list unreadable: with any rotation the raw `tx`
     * of a similarity transform is measured from the Mercator origin off West Africa, so a
     * modest local nudge printed as hundreds of kilometres.
     */
    @Test
    fun `a rotation about the pivot does not invent a huge shift`() {
        val rotated = Affine2D.similarity(pivotX, pivotY, 0.0, 0.0, Math.toRadians(2.0), 1.0)
        assertThat(rotated.tx).isGreaterThan(100_000.0)
        val text = CalibrationReadout.describe(rotated, pivotX, pivotY)
        assertThat(text).startsWith("posun 0 m")
        assertThat(text).contains("otočení 2,0°")
    }

    @Test
    fun `scale is reported only when it actually differs`() {
        val plain = CalibrationReadout.describe(Affine2D.translation(500.0, 0.0), pivotX, pivotY)
        assertThat(plain).doesNotContain("měřítko")

        val scaled = Affine2D.similarity(pivotX, pivotY, 0.0, 0.0, 0.0, 1.05)
        assertThat(CalibrationReadout.describe(scaled, pivotX, pivotY)).contains("měřítko 1,050×")
    }

    @Test
    fun `describeAt takes degrees and agrees with the metric form`() {
        val transform = Affine2D.translation(300.0, 0.0)
        assertThat(CalibrationReadout.describeAt(transform, 50.51, 16.02))
            .isEqualTo(CalibrationReadout.describe(transform, pivotX, pivotY))
    }
}
