package cz.hh.detektormapy.location

import com.google.common.truth.Truth.assertThat
import cz.hh.detektormapy.data.entity.TrackPointEntity
import org.junit.Test

/**
 * Framing a saved walk. The interesting cases are the degenerate ones: a viewport cannot be
 * fitted to a box with no area, and MapLibre answers one with a nonsensical zoom rather than an
 * error, so the floor has to be applied here.
 */
class TrackGeometryTest {

    private fun point(lat: Double, lon: Double, t: Long = 0L) =
        TrackPointEntity(trackId = 1L, lat = lat, lon = lon, timestamp = t)

    @Test
    fun `no points cannot be framed`() {
        assertThat(TrackGeometry.boundsOf(emptyList())).isNull()
    }

    @Test
    fun `bounds span every point`() {
        val box = TrackGeometry.boundsOf(
            listOf(
                point(50.5100, 16.0100),
                point(50.5200, 16.0300),
                point(50.5150, 16.0050),
            ),
        )!!
        assertThat(box.south).isEqualTo(50.5100)
        assertThat(box.north).isEqualTo(50.5200)
        assertThat(box.west).isEqualTo(16.0050)
        assertThat(box.east).isEqualTo(16.0300)
    }

    @Test
    fun `a walk that never moved still gets a usable box`() {
        val box = TrackGeometry.boundsOf(listOf(point(50.51, 16.01), point(50.51, 16.01)))!!
        assertThat(box.widthDeg).isWithin(1e-9).of(TrackGeometry.MIN_SPAN_DEG)
        assertThat(box.heightDeg).isWithin(1e-9).of(TrackGeometry.MIN_SPAN_DEG)
        assertThat(box.centerLat).isWithin(1e-9).of(50.51)
        assertThat(box.centerLon).isWithin(1e-9).of(16.01)
    }

    @Test
    fun `a walk along a single line is widened only where it is flat`() {
        // Straight north-south: real height, zero width.
        val box = TrackGeometry.boundsOf(listOf(point(50.500, 16.01), point(50.530, 16.01)))!!
        assertThat(box.heightDeg).isWithin(1e-9).of(0.030)
        assertThat(box.widthDeg).isWithin(1e-9).of(TrackGeometry.MIN_SPAN_DEG)
    }

    @Test
    fun `a real walk is left alone`() {
        val box = TrackGeometry.boundsOf(listOf(point(50.500, 16.000), point(50.530, 16.040)))!!
        assertThat(box.widthDeg).isWithin(1e-9).of(0.040)
        assertThat(box.heightDeg).isWithin(1e-9).of(0.030)
    }
}
