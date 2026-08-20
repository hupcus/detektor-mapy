package cz.hh.detektormapy.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.map.pmtiles.TileArchive
import cz.hh.detektormapy.util.WebMercator
import java.io.ByteArrayOutputStream

/**
 * Turns "source tiles + affine calibration" into a ready-to-serve PNG for one XYZ address.
 *
 * Why this exists: MapLibre Android cannot transform a raster source at runtime, so the only
 * place a per-layer calibration can be applied is *before* MapLibre ever sees the tile. The
 * local tile server therefore re-projects each requested tile out of the neighbouring source
 * tiles with a Canvas matrix -- exactly the plan recorded in `handoff.md`.
 *
 * This is the only file in the tile engine that touches `android.graphics`; all the arithmetic
 * lives in [TileWarpGeometry] so it can be unit tested on a plain JVM.
 */
object CalibratedTileComposer {

    private const val TAG = "TileComposer"
    private const val TILE_PX = WebMercator.TILE_SIZE
    private const val PNG_QUALITY = 100

    /** Largest side length accepted for a source tile. Real tiles are 256 or 512 px. */
    private const val MAX_TILE_PX = 2048

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * @param archive where the source tiles come from
     * @param transform calibration in EPSG:3857 metres, mapping *source* metres to *map* metres;
     *   null or identity takes the zero-cost fast path
     * @return PNG bytes for the tile, the untouched source bytes on the fast path, or null when
     *   there is nothing to draw
     */
    fun compose(archive: TileArchive, z: Int, x: Int, y: Int, transform: Affine2D?): ByteArray? {
        // Fast path: no calibration means the archive bytes are already correct. Re-encoding
        // them would cost a decode + encode per tile and lose nothing but quality.
        if (transform == null || transform.isIdentity()) return archive.getTile(z, x, y)

        // Vector tiles cannot be warped by a Canvas; serve them as-is rather than corrupt them.
        if (!archive.contentType.startsWith("image/")) return archive.getTile(z, x, y)

        val inverse = try {
            transform.inverse()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Singular calibration for $z/$x/$y, serving uncalibrated tile", e)
            return archive.getTile(z, x, y)
        }

        val srcZoom = TileWarpGeometry.chooseSourceZoom(z, transform, archive.minZoom, archive.maxZoom)
        val plan = TileWarpGeometry.planSourceTiles(z, x, y, inverse, srcZoom)
        if (plan.isEmpty) return null
        if (plan.clamped) {
            Log.d(TAG, "Calibration for $z/$x/$y is extreme; falling back to the centre tile")
        }

        val mosaic = buildMosaic(archive, plan) ?: return null
        return try {
            drawWarped(mosaic, plan, z, x, y, transform)
        } finally {
            mosaic.recycle()
        }
    }

    /** Decodes every planned source tile into one bitmap laid out row-major. */
    private fun buildMosaic(archive: TileArchive, plan: SourceTilePlan): Bitmap? {
        val width = plan.cols * TILE_PX
        val height = plan.rows * TILE_PX
        if (width <= 0 || height <= 0) return null

        var mosaic: Bitmap? = null
        var canvas: Canvas? = null
        var drewAnything = false

        for (tile in plan.tiles) {
            val bytes = try {
                archive.getTile(tile.z, tile.x, tile.y)
            } catch (e: Exception) {
                Log.w(TAG, "Source tile ${tile.z}/${tile.x}/${tile.y} unreadable", e)
                null
            } ?: continue
            val bitmap = decodeBoundedTile(bytes) ?: continue
            if (mosaic == null) {
                mosaic = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                canvas = Canvas(mosaic)
            }
            val left = ((tile.x - plan.originX) * TILE_PX).toFloat()
            val top = ((tile.y - plan.originY) * TILE_PX).toFloat()
            canvas?.drawBitmap(bitmap, left, top, paint)
            bitmap.recycle()
            drewAnything = true
        }
        if (drewAnything) return mosaic
        mosaic?.recycle()
        return null
    }

    /** Draws the mosaic into a fresh 256x256 tile through the calibration matrix. */
    private fun drawWarped(
        mosaic: Bitmap,
        plan: SourceTilePlan,
        z: Int,
        x: Int,
        y: Int,
        transform: Affine2D,
    ): ByteArray? {
        val coefficients = TileWarpGeometry.warpMatrix(
            outZ = z,
            outX = x,
            outY = y,
            transform = transform,
            srcZoom = plan.zoom,
            srcOriginX = plan.originX,
            srcOriginY = plan.originY,
        )
        val matrix = Matrix()
        matrix.setValues(
            floatArrayOf(
                coefficients[0].toFloat(), coefficients[1].toFloat(), coefficients[2].toFloat(),
                coefficients[3].toFloat(), coefficients[4].toFloat(), coefficients[5].toFloat(),
                0f, 0f, 1f,
            ),
        )

        val out = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(out)
            canvas.drawBitmap(mosaic, matrix, paint)
            val stream = ByteArrayOutputStream(32 * 1024)
            if (!out.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, stream)) {
                Log.w(TAG, "PNG encode failed for $z/$x/$y")
                null
            } else {
                stream.toByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Warp failed for $z/$x/$y", e)
            null
        } finally {
            out.recycle()
        }
    }

    /**
     * Decodes a tile only after checking how big it claims to be.
     *
     * Tile bytes are untrusted: they come out of a PMTiles archive the user copied onto the
     * phone, or off a public tile service. A 30000x30000 PNG would ask for gigabytes in one
     * allocation, so the header is read first and anything implausible for a map tile is
     * dropped instead of decoded.
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
}
