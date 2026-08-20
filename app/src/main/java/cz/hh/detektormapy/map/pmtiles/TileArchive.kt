package cz.hh.detektormapy.map.pmtiles

import cz.hh.detektormapy.util.BBox
import java.io.Closeable

/**
 * The single abstraction the local tile server talks to.
 *
 * Why it exists: the server, the calibration composer and the layer manager must not care
 * whether a tile comes out of a PMTiles archive on the SD card, an MBTiles SQLite file, a
 * remote XYZ template or a WMS GetMap call. Everything is reduced to "give me the bytes for
 * z/x/y in XYZ (Slippy, y down from north) addressing, or null if there is nothing there".
 *
 * Implementations must be **thread safe**: the tile server serves several requests in
 * parallel from a small thread pool.
 */
interface TileArchive : Closeable {

    /** Lowest zoom the archive actually holds tiles for. */
    val minZoom: Int

    /** Highest zoom the archive actually holds tiles for. */
    val maxZoom: Int

    /** WGS84 extent of the data, when the archive declares one. Used for "mimo pokrytí" hints. */
    val bounds: BBox?

    /** MIME type of the bytes returned by [getTile], e.g. `image/png`. */
    val contentType: String

    /**
     * Returns the raw, ready-to-serve bytes of one tile in **XYZ** addressing
     * (y increasing southwards), or null when the archive has no tile there.
     * Must never throw for a simple miss; only genuinely broken archives may throw.
     */
    fun getTile(z: Int, x: Int, y: Int): ByteArray?
}
