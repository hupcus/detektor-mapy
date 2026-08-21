package cz.hh.detektormapy.map

import com.google.common.truth.Truth.assertThat
import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.map.pmtiles.TileArchive
import cz.hh.detektormapy.util.BBox
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The bug this pins down cost the whole calibration feature: the tile server dutifully warped
 * its tiles, but the URL MapLibre had been given never changed, and MapLibre never re-requests a
 * URL it has already drawn. Every calibration was therefore invisible. The generation in the
 * query string is what turns "these tiles changed" into something MapLibre can act on, so if
 * these assertions ever go back to passing trivially, the map goes quietly stale again.
 */
class TileUrlGenerationTest {

    private class FakeArchive : TileArchive {
        override val minZoom = 0
        override val maxZoom = 19
        override val bounds: BBox? = null
        override val contentType = "image/png"
        override fun getTile(z: Int, x: Int, y: Int): ByteArray? = null
        override fun close() = Unit
    }

    private lateinit var server: LocalTileServer

    @Before
    fun setUp() {
        server = LocalTileServer()
        server.start()
        server.register(LAYER, FakeArchive())
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `template keeps the xyz placeholders`() {
        assertThat(server.urlTemplate(LAYER)).contains("/t/$LAYER/{z}/{x}/{y}")
    }

    @Test
    fun `applying a calibration changes the template`() {
        val before = server.urlTemplate(LAYER)
        server.setCalibration(LAYER, Affine2D.translation(120.0, -35.0))
        assertThat(server.urlTemplate(LAYER)).isNotEqualTo(before)
    }

    @Test
    fun `clearing a calibration changes the template again`() {
        server.setCalibration(LAYER, Affine2D.translation(120.0, -35.0))
        val calibrated = server.urlTemplate(LAYER)
        server.setCalibration(LAYER, null)
        assertThat(server.urlTemplate(LAYER)).isNotEqualTo(calibrated)
    }

    /**
     * The map re-applies stored calibrations on every camera-idle event. If that bumped the
     * generation the source would be rebuilt on every pan, which is exactly the tile storm the
     * local server exists to avoid.
     */
    @Test
    fun `re-applying the same calibration leaves the template alone`() {
        val transform = Affine2D.translation(120.0, -35.0)
        server.setCalibration(LAYER, transform)
        val first = server.urlTemplate(LAYER)
        server.setCalibration(LAYER, Affine2D.translation(120.0, -35.0))
        assertThat(server.urlTemplate(LAYER)).isEqualTo(first)
    }

    @Test
    fun `another layer is unaffected`() {
        server.register(OTHER, FakeArchive())
        val otherBefore = server.urlTemplate(OTHER)
        server.setCalibration(LAYER, Affine2D.translation(1.0, 1.0))
        assertThat(server.urlTemplate(OTHER)).isEqualTo(otherBefore)
    }

    @Test
    fun `the query string is not part of the served path`() {
        val request = LocalTileServer.parseTilePath("/t/$LAYER/12/2233/1401")
        assertThat(request).isNotNull()
        assertThat(request!!.layerId).isEqualTo(LAYER)
        assertThat(request.z).isEqualTo(12)
    }

    private companion object {
        const val LAYER = "vm2"
        const val OTHER = "ortofoto"
    }
}
