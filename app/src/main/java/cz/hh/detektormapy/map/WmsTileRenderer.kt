package cz.hh.detektormapy.map

import android.util.Log
import cz.hh.detektormapy.map.pmtiles.TileArchive
import cz.hh.detektormapy.util.BBox
import cz.hh.detektormapy.util.WebMercator
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Turns an XYZ tile request into a WMS `GetMap` call.
 *
 * Why it exists: several Czech sources (ČÚZK archival maps, NPÚ ÚAN) only publish WMS, which
 * MapLibre cannot consume directly. The local tile server therefore fronts them as ordinary XYZ
 * layers, so an online layer and an offline PMTiles layer are wired into the map identically.
 *
 * Axis order: WMS 1.3.0 famously flips BBOX to lat/lon for geographic CRSs such as EPSG:4326,
 * but **EPSG:3857 is a projected CRS whose declared axis order is easting, northing** -- so the
 * bbox stays `minX,minY,maxX,maxY` for every version we speak. That is the whole reason we only
 * ever request 3857 here.
 */
object WmsTileRenderer {

    private const val TAG = "WmsTileRenderer"

    /** Connect and read timeouts; in the field a slow WMS must not stall the tile pool. */
    const val TIMEOUT_MS = 8_000

    private const val USER_AGENT = "DetektorMapy/1.0 (Android)"

    /**
     * Builds the GetMap URL for one XYZ tile.
     *
     * @param endpoint the WMS base URL, with or without an existing query string
     * @param tileSize output size in pixels, 256 for standard tiles
     */
    fun buildGetMapUrl(
        endpoint: String,
        layers: String,
        z: Int,
        x: Int,
        y: Int,
        version: String = "1.3.0",
        format: String = "image/png",
        styles: String = "",
        transparent: Boolean = true,
        tileSize: Int = WebMercator.TILE_SIZE,
    ): String {
        val b = WebMercator.tileBoundsMeters(x, y, z)
        val bbox = "${b[0]},${b[1]},${b[2]},${b[3]}"
        // 1.3.0 renamed SRS to CRS; everything else is identical for our purposes.
        val crsParam = if (version.startsWith("1.3")) "CRS" else "SRS"
        val params = linkedMapOf(
            "SERVICE" to "WMS",
            "VERSION" to version,
            "REQUEST" to "GetMap",
            "LAYERS" to layers,
            "STYLES" to styles,
            crsParam to "EPSG:3857",
            "BBOX" to bbox,
            "WIDTH" to tileSize.toString(),
            "HEIGHT" to tileSize.toString(),
            "FORMAT" to format,
            "TRANSPARENT" to if (transparent) "TRUE" else "FALSE",
        )
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v, "UTF-8")
        }
        val separator = when {
            endpoint.contains('?') && endpoint.endsWith('?') -> ""
            endpoint.contains('?') -> "&"
            else -> "?"
        }
        return endpoint + separator + query
    }

    /**
     * Expands an XYZ template. Supports `{z}`, `{x}`, `{y}`, `{-y}` (TMS row) and `{s}`
     * (subdomain, picked deterministically from [subdomains] so the same tile always hits the
     * same host and stays cacheable).
     */
    fun expandXyzTemplate(template: String, z: Int, x: Int, y: Int, subdomains: String = "abc"): String {
        val flippedY = (1 shl z) - 1 - y
        val subdomain = if (subdomains.isEmpty()) "" else subdomains[(x + y) % subdomains.length].toString()
        return template
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
            .replace("{-y}", flippedY.toString())
            .replace("{s}", subdomain)
    }

    /**
     * Magic-byte sniff for the formats a tile service may legitimately return.
     * Servers lie about Content-Type often enough that the header alone is not proof.
     */
    internal fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        // PNG
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return true
        // JPEG
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return true
        // GIF
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return true
        // WEBP is "RIFF"...."WEBP"
        if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) return true
        return false
    }

    /**
     * Fetches [url] and returns the body, or **null on any failure** -- offline, DNS failure,
     * HTTP error, timeout, or a response that is not actually an image. Silent degradation is a
     * hard requirement: the app must stay usable without a signal, so an unreachable online
     * layer simply renders nothing.
     */
    fun fetch(url: String, timeoutMs: Int = TIMEOUT_MS): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "image/*,*/*;q=0.8")
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                Log.d(TAG, "HTTP $status for $url")
                return null
            }
            // A WMS ServiceExceptionReport and a captive-portal login page both arrive as
            // HTTP 200. Without this check they would be written into the on-disk tile cache
            // and then served to MapLibre as image/png forever, because that cache is exactly
            // what the app falls back to once there is no signal.
            val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (contentType != null && !contentType.startsWith("image/")) {
                Log.w(TAG, "Neobrázková odpověď ($contentType) z $url")
                return null
            }
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream(32 * 1024)
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    out.write(buf, 0, read)
                    if (out.size() > MAX_TILE_BYTES) {
                        Log.w(TAG, "Refusing oversized tile from $url")
                        return null
                    }
                }
                out.toByteArray()
                    .takeIf { it.isNotEmpty() && looksLikeImage(it) }
            }
        } catch (e: IOException) {
            Log.d(TAG, "Offline or unreachable: $url (${e.message})")
            null
        } catch (e: RuntimeException) {
            Log.w(TAG, "Malformed online layer URL: $url", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** 8 MB is far larger than any sane 256 px tile; anything bigger is a server error page. */
    const val MAX_TILE_BYTES = 8 * 1024 * 1024
}

/**
 * Small write-through disk cache for online tiles.
 *
 * Why: a layer the user looked at while they still had a signal must keep working once the signal
 * is gone -- that is the difference between a usable field tool and a blank screen in a forest.
 */
internal class OnlineTileCache(private val root: File) {

    fun read(layerId: String, z: Int, x: Int, y: Int): ByteArray? {
        val file = fileFor(layerId, z, x, y)
        return try {
            if (file.isFile && file.length() > 0) file.readBytes() else null
        } catch (e: IOException) {
            null
        }
    }

    fun write(layerId: String, z: Int, x: Int, y: Int, bytes: ByteArray) {
        val file = fileFor(layerId, z, x, y)
        try {
            file.parentFile?.mkdirs()
            // Write to a temp name first so a killed process never leaves a half tile behind.
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                temp.delete()
            }
        } catch (e: IOException) {
            // A full or read-only cache directory must not break rendering.
        }
    }

    private fun fileFor(layerId: String, z: Int, x: Int, y: Int): File =
        File(root, "${sanitize(layerId)}/$z/$x/$y.tile")

    private fun sanitize(id: String): String = id.map {
        if (it.isLetterOrDigit() || it == '-' ||
            it == '_'
        ) {
            it
        } else {
            '_'
        }
    }
        .joinToString("")
}

/**
 * Adapter that makes a remote WMS endpoint look like any other [TileArchive], so
 * [LocalTileServer] (and therefore the calibration path) treats online and offline layers alike.
 */
class WmsTileArchive(
    private val layerId: String,
    private val endpoint: String,
    private val wmsLayers: String,
    override val minZoom: Int = 0,
    override val maxZoom: Int = 19,
    override val bounds: BBox? = null,
    private val version: String = "1.3.0",
    private val format: String = "image/png",
    private val styles: String = "",
    cacheDir: File? = null,
) : TileArchive {

    private val cache = cacheDir?.let { OnlineTileCache(it) }

    override val contentType: String = format

    override fun getTile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < minZoom || z > maxZoom) return null
        val url = WmsTileRenderer.buildGetMapUrl(
            endpoint = endpoint,
            layers = wmsLayers,
            z = z,
            x = x,
            y = y,
            version = version,
            format = format,
            styles = styles,
        )
        val fresh = WmsTileRenderer.fetch(url)
        if (fresh != null) {
            cache?.write(layerId, z, x, y, fresh)
            return fresh
        }
        return cache?.read(layerId, z, x, y)
    }

    /** Nothing to release: every request is a fresh connection. */
    override fun close() = Unit
}

/**
 * Adapter for plain XYZ / WMTS-RESTful templates, with the same offline fallback as
 * [WmsTileArchive].
 */
class XyzTileArchive(
    private val layerId: String,
    private val template: String,
    override val minZoom: Int = 0,
    override val maxZoom: Int = 19,
    override val bounds: BBox? = null,
    override val contentType: String = "image/png",
    private val subdomains: String = "abc",
    cacheDir: File? = null,
) : TileArchive {

    private val cache = cacheDir?.let { OnlineTileCache(it) }

    override fun getTile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < minZoom || z > maxZoom) return null
        val n = 1 shl z
        if (x < 0 || y < 0 || x >= n || y >= n) return null
        val url = WmsTileRenderer.expandXyzTemplate(template, z, x, y, subdomains)
        val fresh = WmsTileRenderer.fetch(url)
        if (fresh != null) {
            cache?.write(layerId, z, x, y, fresh)
            return fresh
        }
        return cache?.read(layerId, z, x, y)
    }

    override fun close() = Unit
}
