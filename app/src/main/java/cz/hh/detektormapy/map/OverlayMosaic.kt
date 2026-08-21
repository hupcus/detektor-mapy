package cz.hh.detektormapy.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.map.pmtiles.TileArchive
import cz.hh.detektormapy.util.WebMercator
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A rectangle of tiles chosen to back the live calibration ghost, in EPSG:3857.
 *
 * [originX]/[originY] are tile indices at [zoom]; the covered ground is whole tiles, never the
 * exact viewport, because a tile-aligned mosaic is the only kind that can be stitched without
 * resampling.
 */
data class MosaicPlan(val zoom: Int, val originX: Int, val originY: Int, val cols: Int, val rows: Int) {

    val tileCount: Int get() = cols * rows

    /** Ground covered, as `[west, south, east, north]` in EPSG:3857 metres. */
    fun boundsMeters(): DoubleArray {
        val tile = WebMercator.TILE_SIZE.toDouble()
        return doubleArrayOf(
            WebMercator.pixelXToMeters(originX * tile, zoom),
            WebMercator.pixelYToMeters((originY + rows) * tile, zoom),
            WebMercator.pixelXToMeters((originX + cols) * tile, zoom),
            WebMercator.pixelYToMeters(originY * tile, zoom),
        )
    }
}

/**
 * One stitched overlay image plus the `[west, south, east, north]` EPSG:3857 rectangle it covers.
 */
class OverlaySnapshot(val bitmap: Bitmap, val boundsMeters: DoubleArray)

/**
 * Builds the bitmap that stands in for a raster overlay while the user is aligning it by hand.
 *
 * The reason this exists is a hard limit of MapLibre Android: a raster source cannot be moved,
 * and it cannot be refreshed. Warping tiles server-side is correct but only becomes visible
 * after the source is rebuilt, which is far too expensive to do per gesture frame. An
 * `ImageSource`, on the other hand, is *defined* by four corners and updating them is nearly
 * free -- so during Režim A the overlay is replaced by one stitched image whose corners follow
 * the fingers, and the real, properly warped tiles come back the moment the user is done.
 *
 * Everything here except [render] is pure arithmetic so it can be tested on a plain JVM.
 */
object OverlayMosaic {

    private const val TAG = "OverlayMosaic"

    /**
     * Tile budget for one ghost. 42 tiles is ~2.8 MB of source PNGs decoded into an 11 MB
     * ARGB_8888 bitmap at worst -- enough to cover a phone screen with room to drag, while
     * staying well clear of the heap limit on the sort of device that gets taken into a field.
     */
    const val MAX_TILES = 42

    /** Same budget for a layer whose tiles arrive over the network, where each one costs a request. */
    const val MAX_REMOTE_TILES = 12

    /** Largest side length accepted for a source tile. Real tiles are 256 or 512 px. */
    private const val MAX_TILE_PX = 2048

    /** How much larger than the viewport the ghost is, so dragging does not run off its edge. */
    const val VIEWPORT_EXPANSION = 1.4

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * The ground the mosaic has to cover, in *source* coordinates.
     *
     * The overlay is already displaced by [transform], so the pixels currently under the
     * viewport live somewhere else in the archive; the visible rectangle is therefore mapped
     * back through the inverse before anything is fetched. Without this a layer that is already
     * calibrated a kilometre off would produce a ghost of the wrong piece of countryside.
     *
     * @param visibleM `[west, south, east, north]` of the viewport in EPSG:3857 metres
     */
    fun sourceRect(visibleM: DoubleArray, transform: Affine2D?, expand: Double = VIEWPORT_EXPANSION): DoubleArray {
        require(visibleM.size == 4) { "Viewport rectangle needs 4 values, got ${visibleM.size}" }
        val halfW = (visibleM[2] - visibleM[0]) / 2.0 * expand
        val halfH = (visibleM[3] - visibleM[1]) / 2.0 * expand
        val cx = (visibleM[0] + visibleM[2]) / 2.0
        val cy = (visibleM[1] + visibleM[3]) / 2.0
        val corners = listOf(
            cx - halfW to cy - halfH,
            cx + halfW to cy - halfH,
            cx + halfW to cy + halfH,
            cx - halfW to cy + halfH,
        )
        val inverse = transform?.takeIf { !it.isIdentity() }?.runCatching { inverse() }?.getOrNull()
        val mapped = corners.map { (x, y) ->
            if (inverse == null) x to y else inverse.applyX(x, y) to inverse.applyY(x, y)
        }
        return doubleArrayOf(
            mapped.minOf { it.first },
            mapped.minOf { it.second },
            mapped.maxOf { it.first },
            mapped.maxOf { it.second },
        )
    }

    /**
     * Picks the highest zoom whose tile rectangle fits in [maxTiles], never above [preferredZoom]
     * and never outside what the archive holds. Returns null when even the archive's own minimum
     * zoom would need too many tiles, or when the rectangle is degenerate.
     */
    fun plan(
        rectM: DoubleArray,
        preferredZoom: Int,
        minZoom: Int,
        maxZoom: Int,
        maxTiles: Int = MAX_TILES,
    ): MosaicPlan? {
        require(rectM.size == 4) { "Rectangle needs 4 values, got ${rectM.size}" }
        if (rectM[2] <= rectM[0] || rectM[3] <= rectM[1]) return null
        if (minZoom > maxZoom) return null

        var zoom = preferredZoom.coerceIn(minZoom, maxZoom)
        while (zoom >= minZoom) {
            val plan = planAt(rectM, zoom)
            if (plan != null && plan.tileCount <= maxTiles) return plan
            zoom--
        }
        return null
    }

    private fun planAt(rectM: DoubleArray, zoom: Int): MosaicPlan? {
        val tile = WebMercator.TILE_SIZE.toDouble()
        val worldTiles = 1 shl zoom
        val x0 = floor(WebMercator.metersToPixelX(rectM[0], zoom) / tile).toInt()
        val x1 = ceil(WebMercator.metersToPixelX(rectM[2], zoom) / tile).toInt() - 1
        // Mercator y grows northwards, tile y grows southwards, so north gives the smaller index.
        val y0 = floor(WebMercator.metersToPixelY(rectM[3], zoom) / tile).toInt()
        val y1 = ceil(WebMercator.metersToPixelY(rectM[1], zoom) / tile).toInt() - 1

        val left = max(0, x0)
        val right = min(worldTiles - 1, max(x1, x0))
        val top = max(0, y0)
        val bottom = min(worldTiles - 1, max(y1, y0))
        if (right < left || bottom < top) return null
        return MosaicPlan(zoom, left, top, right - left + 1, bottom - top + 1)
    }

    /**
     * Stitches the planned tiles into one bitmap, or returns null when the archive has nothing
     * there -- the caller then leaves the real raster layer alone rather than blanking the map.
     */
    fun render(archive: TileArchive, plan: MosaicPlan): Bitmap? {
        val tilePx = WebMercator.TILE_SIZE
        var mosaic: Bitmap? = null
        var canvas: Canvas? = null

        for (row in 0 until plan.rows) {
            for (col in 0 until plan.cols) {
                val bytes = try {
                    archive.getTile(plan.zoom, plan.originX + col, plan.originY + row)
                } catch (e: Exception) {
                    Log.w(TAG, "Dlaždice ${plan.zoom}/${plan.originX + col}/${plan.originY + row} je nečitelná", e)
                    null
                } ?: continue
                val bitmap = decodeBoundedTile(bytes) ?: continue
                if (mosaic == null) {
                    mosaic = Bitmap.createBitmap(
                        plan.cols * tilePx,
                        plan.rows * tilePx,
                        Bitmap.Config.ARGB_8888,
                    )
                    canvas = Canvas(mosaic)
                }
                canvas?.drawBitmap(bitmap, (col * tilePx).toFloat(), (row * tilePx).toFloat(), paint)
                bitmap.recycle()
            }
        }
        return mosaic
    }

    /**
     * Reads a tile's declared size before decoding it.
     *
     * Tile bytes are untrusted -- they come out of a PMTiles archive the user copied onto the
     * phone, or off a public service. A 30000x30000 PNG would ask for gigabytes in a single
     * allocation, and unlike the tile server the mosaic decodes many tiles into one bitmap, so
     * it has more to lose. Same guard as [CalibratedTileComposer], for the same reason.
     */
    private fun decodeBoundedTile(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        if (w > MAX_TILE_PX || h > MAX_TILE_PX) {
            Log.w(TAG, "Odmítám dlaždici ${w}x$h px, limit je $MAX_TILE_PX px")
            return null
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /**
     * Corners of the mosaic after [transform], as `[nwLat, nwLon, neLat, neLon, seLat, seLon,
     * swLat, swLon]` -- the order MapLibre's `LatLngQuad` expects.
     */
    fun quadLatLon(boundsM: DoubleArray, transform: Affine2D): DoubleArray {
        require(boundsM.size == 4) { "Bounds need 4 values, got ${boundsM.size}" }
        val (west, south, east, north) = boundsM
        val corners = listOf(west to north, east to north, east to south, west to south)
        val out = DoubleArray(8)
        corners.forEachIndexed { index, (x, y) ->
            out[index * 2] = WebMercator.metersToLat(transform.applyY(x, y))
            out[index * 2 + 1] = WebMercator.metersToLon(transform.applyX(x, y))
        }
        return out
    }

    private operator fun DoubleArray.component1() = this[0]

    private operator fun DoubleArray.component2() = this[1]

    private operator fun DoubleArray.component3() = this[2]

    private operator fun DoubleArray.component4() = this[3]
}
