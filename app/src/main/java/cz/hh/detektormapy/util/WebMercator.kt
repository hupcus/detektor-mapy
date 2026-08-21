package cz.hh.detektormapy.util

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web Mercator (EPSG:3857) helpers. The whole app works in this projection; S-JTSK
 * only ever appears in the desktop pipeline under /tools.
 */
object WebMercator {

    const val EARTH_RADIUS = 6378137.0
    const val ORIGIN_SHIFT = PI * EARTH_RADIUS // 20037508.342789244
    const val TILE_SIZE = 256

    fun lonToMeters(lon: Double): Double = lon * ORIGIN_SHIFT / 180.0

    fun latToMeters(lat: Double): Double {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val y = ln(tan((90.0 + clamped) * PI / 360.0)) / (PI / 180.0)
        return y * ORIGIN_SHIFT / 180.0
    }

    fun metersToLon(x: Double): Double = x / ORIGIN_SHIFT * 180.0

    fun metersToLat(y: Double): Double {
        val lat = y / ORIGIN_SHIFT * 180.0
        return 180.0 / PI * atan(sinh(lat * PI / 180.0))
    }

    /** Metres covered by one pixel of a 256 px tile at [zoom]. */
    fun resolution(zoom: Int): Double = 2 * ORIGIN_SHIFT / (TILE_SIZE * (1 shl zoom).toDouble())

    /** Metres covered by one whole tile at [zoom]. */
    fun tileSpan(zoom: Int): Double = 2 * ORIGIN_SHIFT / (1 shl zoom).toDouble()

    fun lonToTileX(lon: Double, zoom: Int): Int =
        floor((lon + 180.0) / 360.0 * (1 shl zoom)).toInt().coerceIn(0, (1 shl zoom) - 1)

    fun latToTileY(lat: Double, zoom: Int): Int {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val latRad = clamped * PI / 180.0
        val n = (1 shl zoom).toDouble()
        val y = (1.0 - ln(tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / PI) / 2.0 * n
        return floor(y).toInt().coerceIn(0, (1 shl zoom) - 1)
    }

    /** XYZ tile bounds in 3857 metres: [minX, minY, maxX, maxY]. */
    fun tileBoundsMeters(x: Int, y: Int, zoom: Int): DoubleArray {
        val span = tileSpan(zoom)
        val minX = -ORIGIN_SHIFT + x * span
        val maxY = ORIGIN_SHIFT - y * span
        return doubleArrayOf(minX, maxY - span, minX + span, maxY)
    }

    /** Converts 3857 metres to fractional pixel coordinates in the global pixel grid at [zoom]. */
    fun metersToPixelX(x: Double, zoom: Int): Double = (x + ORIGIN_SHIFT) / resolution(zoom)

    fun metersToPixelY(y: Double, zoom: Int): Double = (ORIGIN_SHIFT - y) / resolution(zoom)

    fun pixelXToMeters(px: Double, zoom: Int): Double = px * resolution(zoom) - ORIGIN_SHIFT

    fun pixelYToMeters(py: Double, zoom: Int): Double = ORIGIN_SHIFT - py * resolution(zoom)
}

/** Simple WGS84 bounding box, the lingua franca between layers, calibrations and exports. */
data class BBox(val west: Double, val south: Double, val east: Double, val north: Double) {
    init {
        require(west <= east) { "BBox west ($west) must not exceed east ($east)" }
        require(south <= north) { "BBox south ($south) must not exceed north ($north)" }
    }

    val centerLon: Double get() = (west + east) / 2.0
    val centerLat: Double get() = (south + north) / 2.0
    val widthDeg: Double get() = east - west
    val heightDeg: Double get() = north - south

    fun contains(lat: Double, lon: Double): Boolean = lon in west..east && lat in south..north

    fun intersects(other: BBox): Boolean =
        west <= other.east && other.west <= east && south <= other.north && other.south <= north

    /** Rough area in square degrees; only used to rank overlapping calibrations. */
    val areaDeg: Double get() = widthDeg * heightDeg

    fun expand(factor: Double): BBox {
        val dx = widthDeg * (factor - 1.0) / 2.0
        val dy = heightDeg * (factor - 1.0) / 2.0
        return BBox(west - dx, south - dy, east + dx, north + dy)
    }

    /**
     * Grows the box around its centre until it spans at least [minDeg] each way.
     *
     * Guards the degenerate case: a box built from a single point has zero area, and zero area
     * multiplied by any expansion factor is still zero -- so it would contain nothing, match
     * nothing, and silently make whatever it describes unreachable.
     */
    fun atLeast(minDeg: Double): BBox {
        val dx = ((minDeg - widthDeg) / 2.0).coerceAtLeast(0.0)
        val dy = ((minDeg - heightDeg) / 2.0).coerceAtLeast(0.0)
        return BBox(west - dx, south - dy, east + dx, north + dy)
    }

    companion object {
        /** Whole Czech Republic, used as the default download / sanity bound. */
        val CZECHIA = BBox(11.9, 48.4, 19.0, 51.2)
    }
}
