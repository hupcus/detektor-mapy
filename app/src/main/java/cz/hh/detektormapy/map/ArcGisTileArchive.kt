package cz.hh.detektormapy.map

import cz.hh.detektormapy.map.pmtiles.TileArchive
import cz.hh.detektormapy.util.BBox
import cz.hh.detektormapy.util.WebMercator

/**
 * Tiles rendered on demand by an ArcGIS `MapServer/export` endpoint.
 *
 * Why this exists at all: every Czech WMTS worth having -- ČÚZK ortofoto, the regional
 * cadastral scans -- publishes a single tile matrix set in **EPSG:5514**, so its tiles cannot
 * be dropped straight into a Web Mercator map. The ArcGIS REST `export` operation, unlike its
 * WMTS front end, reprojects server-side: ask for a bbox with `bboxSR=3857&imageSR=3857` and
 * you get back a correctly warped PNG.
 *
 * This is the one place where a reprojection happens outside the desktop pipeline, and it is
 * acceptable precisely because *we* never do the maths -- the server does, once, and the result
 * is cached on disk like any other online tile ([CachedTileArchive] does the caching).
 */
class ArcGisTileArchive(
    private val endpoint: String,
    override val minZoom: Int = 0,
    override val maxZoom: Int = 19,
    override val bounds: BBox? = null,
    private val transparent: Boolean = true,
    private val format: String = "png",
    /** Comma separated ArcGIS layer ids, e.g. "show:0,1"; null means the service default. */
    private val visibleLayers: String? = null,
) : TileArchive {

    override val contentType: String = if (format.startsWith("jpg")) "image/jpeg" else "image/png"

    override fun getTile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < minZoom || z > maxZoom) return null
        val n = 1 shl z
        if (x < 0 || y < 0 || x >= n || y >= n) return null

        return WmsTileRenderer.fetch(buildExportUrl(z, x, y))
    }

    internal fun buildExportUrl(z: Int, x: Int, y: Int): String {
        val b = WebMercator.tileBoundsMeters(x, y, z)
        val size = WebMercator.TILE_SIZE
        val params = linkedMapOf(
            "bbox" to "${b[0]},${b[1]},${b[2]},${b[3]}",
            "bboxSR" to "3857",
            "imageSR" to "3857",
            "size" to "$size,$size",
            "format" to format,
            "transparent" to transparent.toString(),
            "f" to "image",
        )
        visibleLayers?.let { params["layers"] = it }
        val base = endpoint.trimEnd('/').removeSuffix("/export")
        return params.entries.joinToString("&", prefix = "$base/export?") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
    }

    override fun close() = Unit
}
