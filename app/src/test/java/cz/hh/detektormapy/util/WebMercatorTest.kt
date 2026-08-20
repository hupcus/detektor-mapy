package cz.hh.detektormapy.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class WebMercatorTest {

    @Test
    fun `origin maps to zero metres`() {
        assertThat(WebMercator.lonToMeters(0.0)).isWithin(1e-9).of(0.0)
        assertThat(WebMercator.latToMeters(0.0)).isWithin(1e-6).of(0.0)
    }

    @Test
    fun `antimeridian maps to the origin shift`() {
        assertThat(WebMercator.lonToMeters(180.0)).isWithin(1e-6).of(WebMercator.ORIGIN_SHIFT)
        assertThat(WebMercator.lonToMeters(-180.0)).isWithin(1e-6).of(-WebMercator.ORIGIN_SHIFT)
    }

    @Test
    fun `lat lon to metres round-trips over Czechia`() {
        val rnd = Random(7)
        repeat(500) {
            val lon = rnd.nextDouble(BBox.CZECHIA.west, BBox.CZECHIA.east)
            val lat = rnd.nextDouble(BBox.CZECHIA.south, BBox.CZECHIA.north)
            val x = WebMercator.lonToMeters(lon)
            val y = WebMercator.latToMeters(lat)
            assertThat(WebMercator.metersToLon(x)).isWithin(1e-9).of(lon)
            assertThat(WebMercator.metersToLat(y)).isWithin(1e-9).of(lat)
        }
    }

    @Test
    fun `known reference point matches gdal`() {
        // Prague, Staromestske namesti. Reference values recomputed from the closed-form
        // spherical Mercator equations (x = R*lambda, y = R*ln(tan(pi/4 + phi/2))), which is
        // what EPSG:3857 is defined as -- so this test pins the implementation to the spec
        // rather than to itself.
        val x = WebMercator.lonToMeters(14.4205)
        val y = WebMercator.latToMeters(50.0870)
        assertThat(x).isWithin(1.0).of(1_605_282.7)
        assertThat(y).isWithin(1.0).of(6_461_356.4)
    }

    @Test
    fun `tile bounds tile the plane without gaps at zoom 2`() {
        val span = WebMercator.tileSpan(2)
        assertThat(span).isWithin(1e-6).of(2 * WebMercator.ORIGIN_SHIFT / 4.0)
        for (x in 0 until 4) {
            for (y in 0 until 4) {
                val b = WebMercator.tileBoundsMeters(x, y, 2)
                assertThat(b[2] - b[0]).isWithin(1e-6).of(span)
                assertThat(b[3] - b[1]).isWithin(1e-6).of(span)
                if (x > 0) {
                    assertThat(WebMercator.tileBoundsMeters(x - 1, y, 2)[2]).isWithin(1e-6).of(b[0])
                }
                if (y > 0) {
                    assertThat(WebMercator.tileBoundsMeters(x, y - 1, 2)[1]).isWithin(1e-6).of(b[3])
                }
            }
        }
    }

    @Test
    fun `zoom zero is a single tile covering the world`() {
        val b = WebMercator.tileBoundsMeters(0, 0, 0)
        assertThat(b[0]).isWithin(1e-6).of(-WebMercator.ORIGIN_SHIFT)
        assertThat(b[3]).isWithin(1e-6).of(WebMercator.ORIGIN_SHIFT)
        assertThat(WebMercator.lonToTileX(14.42, 0)).isEqualTo(0)
        assertThat(WebMercator.latToTileY(50.08, 0)).isEqualTo(0)
    }

    @Test
    fun `prague lands in the expected tile at zoom 14`() {
        assertThat(WebMercator.lonToTileX(14.4205, 14)).isEqualTo(8848)
        assertThat(WebMercator.latToTileY(50.0870, 14)).isEqualTo(5550)
    }

    @Test
    fun `tile index stays inside the grid for extreme inputs`() {
        assertThat(WebMercator.lonToTileX(180.0, 5)).isAtMost(31)
        assertThat(WebMercator.lonToTileX(-180.0, 5)).isAtLeast(0)
        assertThat(WebMercator.latToTileY(89.9, 5)).isAtLeast(0)
        assertThat(WebMercator.latToTileY(-89.9, 5)).isAtMost(31)
    }

    @Test
    fun `pixel and metre conversions are inverse`() {
        val rnd = Random(11)
        repeat(200) {
            val z = rnd.nextInt(0, 20)
            val x = rnd.nextDouble(-WebMercator.ORIGIN_SHIFT, WebMercator.ORIGIN_SHIFT)
            val px = WebMercator.metersToPixelX(x, z)
            assertThat(WebMercator.pixelXToMeters(px, z)).isWithin(1e-3).of(x)
            val py = WebMercator.metersToPixelY(x, z)
            assertThat(WebMercator.pixelYToMeters(py, z)).isWithin(1e-3).of(x)
        }
    }

    @Test
    fun `resolution halves with every zoom level`() {
        for (z in 0 until 19) {
            assertThat(WebMercator.resolution(z) / WebMercator.resolution(z + 1)).isWithin(1e-9).of(2.0)
        }
    }
}

class BBoxTest {

    @Test
    fun `contains respects all four edges`() {
        val b = BBox(14.0, 50.0, 15.0, 51.0)
        assertThat(b.contains(50.5, 14.5)).isTrue()
        assertThat(b.contains(50.0, 14.0)).isTrue()
        assertThat(b.contains(51.0, 15.0)).isTrue()
        assertThat(b.contains(49.9, 14.5)).isFalse()
        assertThat(b.contains(50.5, 15.1)).isFalse()
    }

    @Test
    fun `intersects is symmetric and excludes disjoint boxes`() {
        val a = BBox(14.0, 50.0, 15.0, 51.0)
        val b = BBox(14.5, 50.5, 16.0, 52.0)
        val c = BBox(16.5, 50.0, 17.0, 51.0)
        assertThat(a.intersects(b)).isTrue()
        assertThat(b.intersects(a)).isTrue()
        assertThat(a.intersects(c)).isFalse()
        assertThat(c.intersects(a)).isFalse()
    }

    @Test
    fun `area ranks nested boxes so the tightest calibration wins`() {
        val wide = BBox(14.0, 50.0, 16.0, 52.0)
        val tight = BBox(14.4, 50.4, 14.6, 50.6)
        assertThat(tight.areaDeg).isLessThan(wide.areaDeg)
    }

    @Test
    fun `expand grows symmetrically around the centre`() {
        val b = BBox(14.0, 50.0, 15.0, 51.0)
        val e = b.expand(2.0)
        assertThat(e.centerLon).isWithin(1e-9).of(b.centerLon)
        assertThat(e.centerLat).isWithin(1e-9).of(b.centerLat)
        assertThat(e.widthDeg).isWithin(1e-9).of(2.0)
    }

    @Test
    fun `inverted bounds are rejected`() {
        try {
            BBox(15.0, 50.0, 14.0, 51.0)
            throw AssertionError("Expected an IllegalArgumentException for inverted bounds")
        } catch (expected: IllegalArgumentException) {
            assertThat(expected).hasMessageThat().contains("west")
        }
    }
}
