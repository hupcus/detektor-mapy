package cz.hh.detektormapy.map

import com.google.common.truth.Truth.assertThat
import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.util.WebMercator
import org.junit.Test

/**
 * Covers the arithmetic behind runtime calibration. Everything here is plain JVM maths -- the
 * `Bitmap`/`Canvas` side in `CalibratedTileComposer` is a thin shell over these results, so if
 * these numbers are right the rendered tile is right.
 */
class TileWarpGeometryTest {

    private val z = 12
    private val tileX = 2214
    private val tileY = 1391
    private val span = WebMercator.tileSpan(z)

    // ------------------------------------------------------------------ tile planning

    @Test
    fun `identity calibration needs exactly one source tile`() {
        val plan = TileWarpGeometry.planSourceTiles(z, tileX, tileY, Affine2D.IDENTITY, z)
        assertThat(plan.clamped).isFalse()
        assertThat(plan.tiles).containsExactly(TileXY(z, tileX, tileY))
        assertThat(plan.cols).isEqualTo(1)
        assertThat(plan.rows).isEqualTo(1)
        assertThat(plan.originX).isEqualTo(tileX)
        assertThat(plan.originY).isEqualTo(tileY)
    }

    @Test
    fun `half a tile of horizontal shift needs two source tiles`() {
        // The calibration moves the source east by half a tile, so the inverse pulls the output
        // tile back west across the tile boundary.
        val inverse = Affine2D.translation(span / 2.0, 0.0).inverse()
        val plan = TileWarpGeometry.planSourceTiles(z, tileX, tileY, inverse, z)
        assertThat(plan.clamped).isFalse()
        assertThat(plan.tiles).hasSize(2)
        assertThat(plan.cols).isEqualTo(2)
        assertThat(plan.rows).isEqualTo(1)
        assertThat(plan.tiles).containsExactly(
            TileXY(z, tileX - 1, tileY),
            TileXY(z, tileX, tileY),
        )
    }

    @Test
    fun `a diagonal half tile shift needs four source tiles`() {
        val inverse = Affine2D.translation(span / 2.0, span / 2.0).inverse()
        val plan = TileWarpGeometry.planSourceTiles(z, tileX, tileY, inverse, z)
        assertThat(plan.clamped).isFalse()
        assertThat(plan.tiles).hasSize(4)
        assertThat(plan.cols).isEqualTo(2)
        assertThat(plan.rows).isEqualTo(2)
    }

    @Test
    fun `a shift of exactly one tile still needs only one source tile`() {
        val inverse = Affine2D.translation(span, 0.0).inverse()
        val plan = TileWarpGeometry.planSourceTiles(z, tileX, tileY, inverse, z)
        assertThat(plan.tiles).containsExactly(TileXY(z, tileX - 1, tileY))
    }

    @Test
    fun `a plan outside the pyramid is empty rather than clamped`() {
        // Push the output tile far past the antimeridian in source space.
        val inverse = Affine2D.translation(4 * WebMercator.ORIGIN_SHIFT, 0.0)
        val plan = TileWarpGeometry.planSourceTiles(z, tileX, tileY, inverse, z)
        assertThat(plan.isEmpty).isTrue()
        assertThat(plan.clamped).isFalse()
    }

    // ------------------------------------------------------------------ the 25 tile guard

    @Test
    fun `the guard caps the tile count and falls back to the centre tile`() {
        // A calibration that shrinks the source tenfold makes the output tile cover ~121 source
        // tiles; the composer must not try to stitch that.
        val inverse = Affine2D(10.0, 0.0, 0.0, 0.0, 10.0, 0.0)
        val plan = TileWarpGeometry.planSourceTiles(10, 512, 512, inverse, 10)
        assertThat(plan.clamped).isTrue()
        assertThat(plan.tiles).hasSize(1)
        assertThat(plan.cols).isEqualTo(1)
        assertThat(plan.rows).isEqualTo(1)
        assertThat(plan.tiles.single().x).isEqualTo(plan.originX)
        assertThat(plan.tiles.single().y).isEqualTo(plan.originY)
    }

    @Test
    fun `a custom cap is honoured`() {
        val inverse = Affine2D.translation(span / 2.0, span / 2.0).inverse()
        val plan = TileWarpGeometry.planSourceTiles(z, tileX, tileY, inverse, z, maxTiles = 3)
        assertThat(plan.clamped).isTrue()
        assertThat(plan.tiles).hasSize(1)
    }

    @Test
    fun `a plan never exceeds the documented maximum`() {
        for (scale in listOf(0.2, 0.5, 0.9, 1.0, 1.1, 2.0, 5.0)) {
            val transform = Affine2D(scale, 0.0, 1234.0, 0.0, scale, -987.0)
            val plan = TileWarpGeometry.planSourceTiles(z, tileX, tileY, transform.inverse(), z)
            assertThat(plan.tiles.size).isAtMost(TileWarpGeometry.MAX_SOURCE_TILES)
        }
    }

    // ------------------------------------------------------------------ corners

    @Test
    fun `identity corners are the tile bounds themselves`() {
        val corners = TileWarpGeometry.sourceCorners(z, tileX, tileY, Affine2D.IDENTITY)
        val b = WebMercator.tileBoundsMeters(tileX, tileY, z)
        assertThat(corners[0]).isWithin(1e-6).of(b[0]) // NW x
        assertThat(corners[1]).isWithin(1e-6).of(b[3]) // NW y
        assertThat(corners[2]).isWithin(1e-6).of(b[2]) // NE x
        assertThat(corners[3]).isWithin(1e-6).of(b[3])
        assertThat(corners[4]).isWithin(1e-6).of(b[2]) // SE
        assertThat(corners[5]).isWithin(1e-6).of(b[1])
        assertThat(corners[6]).isWithin(1e-6).of(b[0]) // SW
        assertThat(corners[7]).isWithin(1e-6).of(b[1])
    }

    @Test
    fun `corners survive a round trip through the calibration`() {
        val transform = Affine2D.similarity(
            pivotX = 1_600_000.0,
            pivotY = 6_400_000.0,
            dx = 137.0,
            dy = -42.0,
            rotationRad = 0.03,
            scale = 1.02,
        )
        val corners = TileWarpGeometry.sourceCorners(z, tileX, tileY, transform.inverse())
        val b = WebMercator.tileBoundsMeters(tileX, tileY, z)
        val expectedX = doubleArrayOf(b[0], b[2], b[2], b[0])
        val expectedY = doubleArrayOf(b[3], b[3], b[1], b[1])
        for (i in 0 until 4) {
            // Applying the calibration to the source corner must land back on the map corner.
            val backX = transform.applyX(corners[i * 2], corners[i * 2 + 1])
            val backY = transform.applyY(corners[i * 2], corners[i * 2 + 1])
            assertThat(backX).isWithin(1e-4).of(expectedX[i])
            assertThat(backY).isWithin(1e-4).of(expectedY[i])
        }
    }

    // ------------------------------------------------------------------ the pixel matrix

    @Test
    fun `identity produces the identity pixel matrix`() {
        val m = TileWarpGeometry.warpMatrix(z, tileX, tileY, Affine2D.IDENTITY, z, tileX, tileY)
        assertThat(m[0]).isWithin(1e-9).of(1.0)
        assertThat(m[1]).isWithin(1e-9).of(0.0)
        assertThat(m[2]).isWithin(1e-6).of(0.0)
        assertThat(m[3]).isWithin(1e-9).of(0.0)
        assertThat(m[4]).isWithin(1e-9).of(1.0)
        assertThat(m[5]).isWithin(1e-6).of(0.0)

        val (px, py) = TileWarpGeometry.applyMatrix(m, 256.0, 256.0)
        assertThat(px).isWithin(1e-6).of(256.0)
        assertThat(py).isWithin(1e-6).of(256.0)
    }

    @Test
    fun `a half tile shift moves the mosaic by 128 pixels`() {
        val transform = Affine2D.translation(span / 2.0, 0.0)
        val plan = TileWarpGeometry.planSourceTiles(z, tileX, tileY, transform.inverse(), z)
        val m = TileWarpGeometry.warpMatrix(z, tileX, tileY, transform, z, plan.originX, plan.originY)
        // The mosaic starts one tile to the west, and the calibration pushes it half a tile east,
        // so mosaic pixel 128 must land on output pixel 0.
        assertThat(m[0]).isWithin(1e-9).of(1.0)
        assertThat(m[2]).isWithin(1e-6).of(-128.0)
        assertThat(m[5]).isWithin(1e-6).of(0.0)
    }

    @Test
    fun `the pixel matrix agrees with the metre transform for every output corner`() {
        val transform = Affine2D.similarity(
            pivotX = 1_600_000.0,
            pivotY = 6_400_000.0,
            dx = 250.0,
            dy = 90.0,
            rotationRad = -0.02,
            scale = 0.98,
        )
        val inverse = transform.inverse()
        val plan = TileWarpGeometry.planSourceTiles(z, tileX, tileY, inverse, z)
        val m = TileWarpGeometry.warpMatrix(z, tileX, tileY, transform, plan.zoom, plan.originX, plan.originY)

        val outBounds = WebMercator.tileBoundsMeters(tileX, tileY, z)
        val srcSpan = WebMercator.tileSpan(plan.zoom)
        val srcRes = WebMercator.resolution(plan.zoom)
        val originMetersX = -WebMercator.ORIGIN_SHIFT + plan.originX * srcSpan
        val originMetersY = WebMercator.ORIGIN_SHIFT - plan.originY * srcSpan

        val expected = listOf(0.0 to 0.0, 256.0 to 0.0, 256.0 to 256.0, 0.0 to 256.0)
        val cornersX = doubleArrayOf(outBounds[0], outBounds[2], outBounds[2], outBounds[0])
        val cornersY = doubleArrayOf(outBounds[3], outBounds[3], outBounds[1], outBounds[1])
        for (i in 0 until 4) {
            val srcX = inverse.applyX(cornersX[i], cornersY[i])
            val srcY = inverse.applyY(cornersX[i], cornersY[i])
            val mosaicPx = (srcX - originMetersX) / srcRes
            val mosaicPy = (originMetersY - srcY) / srcRes
            val (outPx, outPy) = TileWarpGeometry.applyMatrix(m, mosaicPx, mosaicPy)
            assertThat(outPx).isWithin(1e-5).of(expected[i].first)
            assertThat(outPy).isWithin(1e-5).of(expected[i].second)
        }
    }

    // ------------------------------------------------------------------ zoom choice

    @Test
    fun `source zoom follows the calibration scale but stays within one level`() {
        assertThat(TileWarpGeometry.chooseSourceZoom(12, Affine2D.IDENTITY, 0, 18)).isEqualTo(12)
        // A calibration that doubles the source needs one zoom level deeper.
        val doubled = Affine2D(2.0, 0.0, 0.0, 0.0, 2.0, 0.0)
        assertThat(TileWarpGeometry.chooseSourceZoom(12, doubled, 0, 18)).isEqualTo(13)
        val halved = Affine2D(0.5, 0.0, 0.0, 0.0, 0.5, 0.0)
        assertThat(TileWarpGeometry.chooseSourceZoom(12, halved, 0, 18)).isEqualTo(11)
        // Even an absurd scale never wanders more than one level away.
        val absurd = Affine2D(64.0, 0.0, 0.0, 0.0, 64.0, 0.0)
        assertThat(TileWarpGeometry.chooseSourceZoom(12, absurd, 0, 18)).isEqualTo(13)
    }

    @Test
    fun `source zoom is clamped to what the archive actually holds`() {
        assertThat(TileWarpGeometry.chooseSourceZoom(19, Affine2D.IDENTITY, 8, 14)).isEqualTo(14)
        assertThat(TileWarpGeometry.chooseSourceZoom(2, Affine2D.IDENTITY, 8, 14)).isEqualTo(8)
    }

    @Test
    fun `identity is reported as negligible`() {
        assertThat(TileWarpGeometry.isNegligible(Affine2D.IDENTITY, 12)).isTrue()
        assertThat(TileWarpGeometry.isNegligible(Affine2D.translation(500.0, 0.0), 12)).isFalse()
    }
}
