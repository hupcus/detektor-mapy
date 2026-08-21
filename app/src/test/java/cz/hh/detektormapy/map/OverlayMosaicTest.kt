package cz.hh.detektormapy.map

import com.google.common.truth.Truth.assertThat
import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.util.WebMercator
import org.junit.Test

/**
 * Geometry behind the live calibration ghost. Everything here runs on a plain JVM on purpose:
 * the one thing that touches `android.graphics` is the stitching itself, and it is the part that
 * cannot be wrong in an interesting way.
 */
class OverlayMosaicTest {

    /** Roughly a phone screen over Úpice at z15, in EPSG:3857 metres. */
    private fun viewport(): DoubleArray = doubleArrayOf(
        WebMercator.lonToMeters(16.00),
        WebMercator.latToMeters(50.50),
        WebMercator.lonToMeters(16.03),
        WebMercator.latToMeters(50.52),
    )

    @Test
    fun `plan covers the requested rectangle`() {
        val rect = viewport()
        val plan = OverlayMosaic.plan(rect, preferredZoom = 15, minZoom = 6, maxZoom = 17)
        assertThat(plan).isNotNull()
        val bounds = plan!!.boundsMeters()
        // Whole tiles, so the mosaic is never smaller than what was asked for.
        assertThat(bounds[0]).isAtMost(rect[0])
        assertThat(bounds[1]).isAtMost(rect[1])
        assertThat(bounds[2]).isAtLeast(rect[2])
        assertThat(bounds[3]).isAtLeast(rect[3])
    }

    @Test
    fun `plan drops zoom until the tile budget is met`() {
        val rect = viewport()
        val generous = OverlayMosaic.plan(rect, 17, 6, 17, maxTiles = 400)
        val tight = OverlayMosaic.plan(rect, 17, 6, 17, maxTiles = 6)
        assertThat(generous!!.zoom).isGreaterThan(tight!!.zoom)
        assertThat(tight.tileCount).isAtMost(6)
    }

    @Test
    fun `plan never exceeds what the archive holds`() {
        val plan = OverlayMosaic.plan(viewport(), preferredZoom = 19, minZoom = 12, maxZoom = 14)
        assertThat(plan!!.zoom).isAtMost(14)
    }

    @Test
    fun `plan gives up rather than returning a zoom the archive lacks`() {
        // Half the country at once: even z12 needs far more than four tiles.
        val huge = doubleArrayOf(
            WebMercator.lonToMeters(13.0),
            WebMercator.latToMeters(49.0),
            WebMercator.lonToMeters(18.0),
            WebMercator.latToMeters(51.0),
        )
        assertThat(OverlayMosaic.plan(huge, 15, 12, 17, maxTiles = 4)).isNull()
    }

    @Test
    fun `plan rejects a degenerate rectangle`() {
        val x = WebMercator.lonToMeters(16.0)
        val y = WebMercator.latToMeters(50.5)
        assertThat(OverlayMosaic.plan(doubleArrayOf(x, y, x, y), 15, 6, 17)).isNull()
    }

    @Test
    fun `source rect equals the expanded viewport without a calibration`() {
        val rect = viewport()
        val source = OverlayMosaic.sourceRect(rect, null, expand = 1.0)
        for (i in 0..3) assertThat(source[i]).isWithin(1e-6).of(rect[i])
    }

    /**
     * The regression that motivates [OverlayMosaic.sourceRect]: an overlay already shifted a
     * kilometre east is *drawn* over the viewport, but the pixels behind it live a kilometre
     * west in the archive. Fetching the viewport directly would ghost the wrong countryside.
     */
    @Test
    fun `source rect is pulled back through an existing calibration`() {
        val rect = viewport()
        val shifted = OverlayMosaic.sourceRect(rect, Affine2D.translation(1000.0, 0.0), expand = 1.0)
        assertThat(shifted[0]).isWithin(1e-6).of(rect[0] - 1000.0)
        assertThat(shifted[2]).isWithin(1e-6).of(rect[2] - 1000.0)
        assertThat(shifted[1]).isWithin(1e-6).of(rect[1])
    }

    @Test
    fun `source rect expansion grows around the centre`() {
        val rect = viewport()
        val wide = OverlayMosaic.sourceRect(rect, null, expand = 2.0)
        val cx = (rect[0] + rect[2]) / 2.0
        assertThat((wide[0] + wide[2]) / 2.0).isWithin(1e-6).of(cx)
        assertThat(wide[2] - wide[0]).isWithin(1e-6).of((rect[2] - rect[0]) * 2.0)
    }

    @Test
    fun `quad corners come out in MapLibre order`() {
        val bounds = doubleArrayOf(
            WebMercator.lonToMeters(16.0),
            WebMercator.latToMeters(50.4),
            WebMercator.lonToMeters(16.1),
            WebMercator.latToMeters(50.6),
        )
        val q = OverlayMosaic.quadLatLon(bounds, Affine2D.IDENTITY)
        // topLeft, topRight, bottomRight, bottomLeft
        assertThat(q[0]).isWithin(1e-6).of(50.6)
        assertThat(q[1]).isWithin(1e-6).of(16.0)
        assertThat(q[3]).isWithin(1e-6).of(16.1)
        assertThat(q[4]).isWithin(1e-6).of(50.4)
        assertThat(q[7]).isWithin(1e-6).of(16.0)
    }

    @Test
    fun `quad follows a translation`() {
        val bounds = doubleArrayOf(
            WebMercator.lonToMeters(16.0),
            WebMercator.latToMeters(50.4),
            WebMercator.lonToMeters(16.1),
            WebMercator.latToMeters(50.6),
        )
        val moved = OverlayMosaic.quadLatLon(bounds, Affine2D.translation(5000.0, 0.0))
        assertThat(moved[1]).isGreaterThan(16.0)
        assertThat(moved[0]).isWithin(1e-9).of(50.6)
    }
}
