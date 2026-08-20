package cz.hh.detektormapy.map

import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.util.WebMercator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One tile address in XYZ (Slippy) addressing. */
data class TileXY(val z: Int, val x: Int, val y: Int)

/**
 * Which source tiles have to be fetched to paint one calibrated output tile, and how they are
 * laid out in the intermediate mosaic bitmap.
 *
 * @param zoom zoom level the source tiles are taken from
 * @param originX tile column of the mosaic's left edge
 * @param originY tile row of the mosaic's top edge
 * @param cols mosaic width in tiles
 * @param rows mosaic height in tiles
 * @param tiles the addresses to fetch, row-major from the origin
 * @param clamped true when the 25-tile guard kicked in and the plan was reduced to the centre
 *   tile only -- the caller then knowingly produces a coarse result instead of an OOM
 */
data class SourceTilePlan(
    val zoom: Int,
    val originX: Int,
    val originY: Int,
    val cols: Int,
    val rows: Int,
    val tiles: List<TileXY>,
    val clamped: Boolean,
) {
    val isEmpty: Boolean get() = tiles.isEmpty()
}

/**
 * All the geometry behind runtime layer calibration, deliberately free of any Android import.
 *
 * Why it is split out of `CalibratedTileComposer`: the interesting part of warping a tile is
 * arithmetic -- which source tiles are covered, and the six numbers of the pixel-space matrix.
 * Keeping it here means it runs (and is unit tested) on a plain JVM, while the file that owns
 * `Bitmap`/`Canvas`/`Matrix` stays a thin, untested shell.
 *
 * Conventions used throughout:
 * - the calibration [Affine2D] maps **source** EPSG:3857 metres to **map** EPSG:3857 metres,
 * - tile pixel space is 256x256 with the origin at the tile's north-west corner, y downwards.
 */
object TileWarpGeometry {

    /** Hard ceiling on source tiles per output tile, to bound memory and IO. */
    const val MAX_SOURCE_TILES = 25

    private const val TILE_PX = WebMercator.TILE_SIZE

    /** Guards against a boundary landing exactly on a tile edge and pulling in a spurious tile. */
    private const val EDGE_EPS = 1e-9

    /**
     * Corners of the output tile expressed in **source** metres, as
     * `[x0,y0, x1,y1, x2,y2, x3,y3]` for NW, NE, SE, SW.
     */
    fun sourceCorners(z: Int, x: Int, y: Int, inverse: Affine2D): DoubleArray {
        val b = WebMercator.tileBoundsMeters(x, y, z)
        val minX = b[0]
        val minY = b[1]
        val maxX = b[2]
        val maxY = b[3]
        val cornersX = doubleArrayOf(minX, maxX, maxX, minX)
        val cornersY = doubleArrayOf(maxY, maxY, minY, minY)
        val out = DoubleArray(8)
        for (i in 0 until 4) {
            out[i * 2] = inverse.applyX(cornersX[i], cornersY[i])
            out[i * 2 + 1] = inverse.applyY(cornersX[i], cornersY[i])
        }
        return out
    }

    /**
     * Picks the source zoom whose pixel size best matches the output tile after the calibration
     * scale is applied, then clamps it to +/-1 around the requested zoom (anything further is a
     * calibration so extreme that it is not worth extra IO) and to what the archive holds.
     */
    fun chooseSourceZoom(outZ: Int, transform: Affine2D, archiveMinZoom: Int, archiveMaxZoom: Int): Int {
        val scale = transform.scale
        val delta = if (scale > 1e-9 && scale.isFinite()) {
            (ln(scale) / ln(2.0)).roundToInt()
        } else {
            0
        }
        val nudged = (outZ + delta).coerceIn(outZ - 1, outZ + 1)
        val lo = min(archiveMinZoom, archiveMaxZoom)
        val hi = max(archiveMinZoom, archiveMaxZoom)
        return nudged.coerceIn(lo, hi)
    }

    /**
     * Works out which tiles at [sourceZoom] cover the output tile once it is pulled back into
     * source space by [inverse].
     *
     * When the covered rectangle needs more than [maxTiles] tiles the plan collapses to the single
     * tile under the centre of the output tile and reports `clamped = true`; that is the
     * nearest-neighbour fallback for pathological calibrations.
     */
    fun planSourceTiles(
        outZ: Int,
        outX: Int,
        outY: Int,
        inverse: Affine2D,
        sourceZoom: Int,
        maxTiles: Int = MAX_SOURCE_TILES,
    ): SourceTilePlan {
        val corners = sourceCorners(outZ, outX, outY, inverse)
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (i in 0 until 4) {
            minX = min(minX, corners[i * 2])
            maxX = max(maxX, corners[i * 2])
            minY = min(minY, corners[i * 2 + 1])
            maxY = max(maxY, corners[i * 2 + 1])
        }

        val span = WebMercator.tileSpan(sourceZoom)
        val n = 1 shl sourceZoom

        // Fractional tile coordinates; y grows southwards, hence the flip.
        val fx0 = (minX + WebMercator.ORIGIN_SHIFT) / span
        val fx1 = (maxX + WebMercator.ORIGIN_SHIFT) / span
        val fy0 = (WebMercator.ORIGIN_SHIFT - maxY) / span
        val fy1 = (WebMercator.ORIGIN_SHIFT - minY) / span

        var tx0 = floor(fx0 + EDGE_EPS).toLong()
        var tx1 = ceil(fx1 - EDGE_EPS).toLong() - 1
        var ty0 = floor(fy0 + EDGE_EPS).toLong()
        var ty1 = ceil(fy1 - EDGE_EPS).toLong() - 1
        // A degenerate rectangle (zero width after epsilon) still needs its one tile.
        if (tx1 < tx0) tx1 = tx0
        if (ty1 < ty0) ty1 = ty0

        // Nothing of the source pyramid is visible at all.
        if (tx1 < 0 || ty1 < 0 || tx0 > n - 1 || ty0 > n - 1) {
            return SourceTilePlan(sourceZoom, 0, 0, 0, 0, emptyList(), clamped = false)
        }
        tx0 = tx0.coerceIn(0L, (n - 1).toLong())
        tx1 = tx1.coerceIn(0L, (n - 1).toLong())
        ty0 = ty0.coerceIn(0L, (n - 1).toLong())
        ty1 = ty1.coerceIn(0L, (n - 1).toLong())

        val cols = (tx1 - tx0 + 1).toInt()
        val rows = (ty1 - ty0 + 1).toInt()

        if (cols.toLong() * rows.toLong() > maxTiles) {
            val cx = (minX + maxX) / 2.0
            val cy = (minY + maxY) / 2.0
            val centreX = floor((cx + WebMercator.ORIGIN_SHIFT) / span).toLong()
                .coerceIn(0L, (n - 1).toLong()).toInt()
            val centreY = floor((WebMercator.ORIGIN_SHIFT - cy) / span).toLong()
                .coerceIn(0L, (n - 1).toLong()).toInt()
            return SourceTilePlan(
                zoom = sourceZoom,
                originX = centreX,
                originY = centreY,
                cols = 1,
                rows = 1,
                tiles = listOf(TileXY(sourceZoom, centreX, centreY)),
                clamped = true,
            )
        }

        val tiles = ArrayList<TileXY>(cols * rows)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                tiles.add(TileXY(sourceZoom, (tx0 + col).toInt(), (ty0 + row).toInt()))
            }
        }
        return SourceTilePlan(
            zoom = sourceZoom,
            originX = tx0.toInt(),
            originY = ty0.toInt(),
            cols = cols,
            rows = rows,
            tiles = tiles,
            clamped = false,
        )
    }

    /**
     * The six coefficients of the affine matrix that maps **mosaic pixel space** (origin at the
     * north-west corner of the tile at [srcOriginX]/[srcOriginY] on [srcZoom]) into **output tile
     * pixel space**, ready to be fed to `android.graphics.Matrix.setValues`.
     *
     * Returned as `[m00, m01, m02, m10, m11, m12]`, i.e.
     * `outX = m00*srcPx + m01*srcPy + m02`, `outY = m10*srcPx + m11*srcPy + m12`.
     *
     * Derivation: mosaic pixel -> source metres -> (calibration) -> map metres -> output pixel.
     * Doing the composition analytically instead of chaining three `Matrix` objects keeps the
     * result in double precision, which matters because 3857 metres are 8-digit numbers.
     */
    fun warpMatrix(
        outZ: Int,
        outX: Int,
        outY: Int,
        transform: Affine2D,
        srcZoom: Int,
        srcOriginX: Int,
        srcOriginY: Int,
    ): DoubleArray {
        val outBounds = WebMercator.tileBoundsMeters(outX, outY, outZ)
        val outMinX = outBounds[0]
        val outMaxY = outBounds[3]
        val outRes = WebMercator.resolution(outZ)
        val srcRes = WebMercator.resolution(srcZoom)
        val srcSpan = WebMercator.tileSpan(srcZoom)
        val srcOriginMetersX = -WebMercator.ORIGIN_SHIFT + srcOriginX * srcSpan
        val srcOriginMetersY = WebMercator.ORIGIN_SHIFT - srcOriginY * srcSpan

        val k = srcRes / outRes
        return doubleArrayOf(
            transform.a * k,
            -transform.b * k,
            (transform.a * srcOriginMetersX + transform.b * srcOriginMetersY + transform.tx - outMinX) / outRes,
            -transform.c * k,
            transform.d * k,
            (outMaxY - transform.c * srcOriginMetersX - transform.d * srcOriginMetersY - transform.ty) / outRes,
        )
    }

    /** Convenience for tests and sanity checks: applies a [warpMatrix] result to one point. */
    fun applyMatrix(m: DoubleArray, px: Double, py: Double): Pair<Double, Double> {
        require(m.size == 6) { "Warp matrix needs 6 coefficients, got ${m.size}" }
        return (m[0] * px + m[1] * py + m[2]) to (m[3] * px + m[4] * py + m[5])
    }

    /** True when the transform is so close to identity that warping is a waste of CPU. */
    fun isNegligible(transform: Affine2D, outZ: Int, toleranceP: Double = 0.25): Boolean {
        if (transform.isIdentity()) return true
        val res = WebMercator.resolution(outZ)
        val shift = kotlin.math.hypot(transform.tx, transform.ty)
        val scaleOff = abs(transform.scale - 1.0) * TILE_PX * res
        return shift + scaleOff < toleranceP * res
    }
}
