package cz.hh.detektormapy.map.pmtiles

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import cz.hh.detektormapy.util.BBox
import java.io.File

/**
 * Minimal read-only MBTiles (SQLite) reader.
 *
 * Why it exists next to [PmTilesReader]: PMTiles is what our own pipeline produces, but a lot
 * of ready-made Czech map archives circulate as .mbtiles. Supporting them is ~100 lines because
 * the whole format is one `tiles(zoom_level, tile_column, tile_row, tile_data)` table plus a
 * `metadata(name, value)` key/value table.
 *
 * Note the addressing difference: **MBTiles stores TMS rows** (y counted from the south), while
 * the tile server and MapLibre speak XYZ (y from the north), so [getTile] flips y.
 *
 * Thread safety: `SQLiteDatabase` opened read-only is safe for concurrent readers, and the
 * cheap metadata is read once at open time.
 */
class MbTilesReader(file: File) : TileArchive {

    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY,
    )

    private val metadata: Map<String, String> = readMetadata()

    override val minZoom: Int = metadata["minzoom"]?.toIntOrNull() ?: queryZoom(min = true) ?: 0
    override val maxZoom: Int = metadata["maxzoom"]?.toIntOrNull() ?: queryZoom(min = false) ?: 19

    override val bounds: BBox? = metadata["bounds"]?.let(::parseBounds)

    override val contentType: String = when (metadata["format"]?.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "pbf", "mvt" -> "application/vnd.mapbox-vector-tile"
        else -> "image/png" // Overwhelmingly the case for the raster archives we consume.
    }

    /** Raw metadata table, exposed so the layer panel can show attribution/description. */
    fun metadata(): Map<String, String> = metadata

    override fun getTile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < 0 || z > 30) return null
        val n = 1 shl z
        if (x < 0 || y < 0 || x >= n || y >= n) return null
        val tmsY = n - 1 - y
        return try {
            db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
                arrayOf(z.toString(), x.toString(), tmsY.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getBlob(0) else null
            }
        } catch (e: SQLiteException) {
            // A corrupt page must degrade to "no tile", never crash the tile server thread.
            null
        }
    }

    override fun close() {
        runCatching { db.close() }
    }

    private fun readMetadata(): Map<String, String> = try {
        db.rawQuery("SELECT name, value FROM metadata", null).use { cursor ->
            val out = HashMap<String, String>()
            while (cursor.moveToNext()) {
                val key = cursor.getString(0) ?: continue
                out[key.lowercase()] = cursor.getString(1) ?: ""
            }
            out
        }
    } catch (e: SQLiteException) {
        emptyMap()
    }

    private fun queryZoom(min: Boolean): Int? = try {
        val fn = if (min) "MIN" else "MAX"
        db.rawQuery("SELECT $fn(zoom_level) FROM tiles", null).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null
        }
    } catch (e: SQLiteException) {
        null
    }

    private companion object {
        /** MBTiles `bounds` is "west,south,east,north" in WGS84. */
        fun parseBounds(raw: String): BBox? {
            val parts = raw.split(',').mapNotNull { it.trim().toDoubleOrNull() }
            if (parts.size != 4) return null
            val (west, south, east, north) = parts
            if (west > east || south > north) return null
            return BBox(west, south, east, north)
        }
    }
}
