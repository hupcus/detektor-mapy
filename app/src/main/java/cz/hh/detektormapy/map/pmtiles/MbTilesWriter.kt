package cz.hh.detektormapy.map.pmtiles

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import java.io.Closeable
import java.io.File

/**
 * Read/write MBTiles handle, the storage behind the app's offline tile cache.
 *
 * **Why MBTiles and not PMTiles**, given that the desktop pipeline produces the latter: PMTiles
 * is written once, in Hilbert order, with a complete directory at the front. That is ideal for a
 * finished archive and hopeless for a file that grows one tile at a time as the user pans, and
 * that must survive the process being killed mid-pan. SQLite gives incremental writes and
 * crash recovery for free.
 *
 * **Why it can also read**, when [MbTilesReader] exists: the cache is read on the tile server's
 * worker threads and written on a single background thread, and both would otherwise need their
 * own SQLite handle on the same file. One handle with WAL enabled already allows concurrent
 * readers alongside one writer, so a second one would only add bookkeeping.
 *
 * Addressing follows the format, not the app: MBTiles rows are **TMS** (y counted from the
 * south), so every method flips y exactly like [MbTilesReader] does on the way out.
 *
 * Durability: WAL is on, so a kill mid-transaction leaves a journal that SQLite rolls back on the
 * next open rather than a torn database. Losing the last few tiles is fine -- they get fetched
 * again the next time the user looks at that spot.
 */
class MbTilesWriter(
    val file: File,
    /**
     * `png`, `jpg`, ... written into `metadata`, where readers look to pick a MIME type. Null
     * means "whatever the file already says", which is what reopening an existing cache wants:
     * a JPEG archive must not have its format rewritten by a caller that happens to default
     * to PNG.
     */
    private val format: String? = null,
    private val name: String = file.nameWithoutExtension,
) : Closeable {

    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(prepare(file), null)

    /** Observed zoom range, mirrored into `metadata` on every flush. */
    private var minZoomSeen = Int.MAX_VALUE
    private var maxZoomSeen = Int.MIN_VALUE

    init {
        db.enableWriteAheadLogging()
        createSchema()
        readZoomRange()
        writeBaseMetadata()
        relaxPermissions()
    }

    /** One tile ready to be written, in XYZ addressing. */
    data class Tile(val z: Int, val x: Int, val y: Int, val data: ByteArray) {
        // Generated equals/hashCode would compare the ByteArray by identity, which silently
        // breaks any test that builds an expected tile. Comparing contents is the honest choice.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Tile) return false
            return z == other.z && x == other.x && y == other.y && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = z
            result = 31 * result + x
            result = 31 * result + y
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    /** Writes one tile. Repeating the same coordinates overwrites, so writes are idempotent. */
    fun put(z: Int, x: Int, y: Int, data: ByteArray): Boolean = putAll(listOf(Tile(z, x, y, data))) > 0

    /**
     * Writes a batch in a single transaction, returning how many tiles landed.
     *
     * Batching is the whole point of having a writer thread: one transaction per pan gesture
     * instead of one fsync per tile.
     */
    fun putAll(tiles: List<Tile>): Int {
        if (tiles.isEmpty()) return 0
        var written = 0
        try {
            db.beginTransaction()
            tiles.forEach { tile ->
                if (!isValid(tile.z, tile.x, tile.y) || tile.data.isEmpty()) return@forEach
                val values = ContentValues(4).apply {
                    put(COL_ZOOM, tile.z)
                    put(COL_COLUMN, tile.x)
                    put(COL_ROW, flipY(tile.z, tile.y))
                    put(COL_DATA, tile.data)
                }
                val id = db.insertWithOnConflict(TABLE_TILES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                if (id != -1L) {
                    written++
                    if (tile.z < minZoomSeen) minZoomSeen = tile.z
                    if (tile.z > maxZoomSeen) maxZoomSeen = tile.z
                }
            }
            db.setTransactionSuccessful()
        } catch (e: SQLiteException) {
            // A full disk or a locked database must degrade to "not cached", never to a crash
            // on the tile path.
            Log.w(TAG, "Zápis do ${file.name} selhal", e)
            return 0
        } finally {
            runCatching { db.endTransaction() }
        }
        if (written > 0) updateZoomMetadata()
        return written
    }

    /** Cached bytes for an **XYZ** tile, or null when the cache has never seen it. */
    fun getTile(z: Int, x: Int, y: Int): ByteArray? {
        if (!isValid(z, x, y)) return null
        return try {
            db.rawQuery(
                "SELECT $COL_DATA FROM $TABLE_TILES WHERE $COL_ZOOM=? AND $COL_COLUMN=? AND $COL_ROW=? LIMIT 1",
                arrayOf(z.toString(), x.toString(), flipY(z, y).toString()),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null }
        } catch (e: SQLiteException) {
            Log.w(TAG, "Čtení z ${file.name} selhalo", e)
            null
        }
    }

    fun tileCount(): Long = try {
        db.rawQuery("SELECT COUNT(*) FROM $TABLE_TILES", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    } catch (e: SQLiteException) {
        0L
    }

    fun setMetadata(key: String, value: String) {
        val values = ContentValues(2).apply {
            put("name", key)
            put("value", value)
        }
        runCatching { db.insertWithOnConflict(TABLE_METADATA, null, values, SQLiteDatabase.CONFLICT_REPLACE) }
    }

    /**
     * Folds the write-ahead log back into the database file.
     *
     * Worth doing when a cache goes idle: until a checkpoint runs, part of the data lives in
     * `-wal`, which makes [sizeOnDisk] jumpy and a copy of the bare `.mbtiles` file incomplete.
     */
    fun checkpoint() {
        runCatching { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }
    }

    /** Bytes the archive occupies, journal files included. */
    fun sizeOnDisk(): Long = sizeOnDisk(file)

    override fun close() {
        runCatching { checkpoint() }
        runCatching { db.close() }
        relaxPermissions()
    }

    /**
     * Makes the archive world-readable so it can be copied off the phone.
     *
     * SQLite creates its files 0600, which leaves the cache unreadable even to `adb pull` and to
     * the phone's own file manager, while everything else the app writes into the layers
     * directory is readable. This is not a widening of access: on API 30+ the OS gates
     * `Android/data/<pkg>` at the directory level regardless of the mode bits, so the only
     * parties this reaches are the ones that could already list the directory -- the user with a
     * cable, and the platform's own media stack.
     */
    private fun relaxPermissions() {
        listOf(file, walOf(file), shmOf(file)).forEach {
            if (it.exists()) runCatching { it.setReadable(true, false) }
        }
    }

    // --- internals ---------------------------------------------------------------------

    private fun createSchema() {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_TILES (" +
                "$COL_ZOOM INTEGER NOT NULL, $COL_COLUMN INTEGER NOT NULL, " +
                "$COL_ROW INTEGER NOT NULL, $COL_DATA BLOB NOT NULL)",
        )
        // The unique index is not decoration: CONFLICT_REPLACE needs it to turn a repeated write
        // of the same tile into an update instead of a duplicate row.
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON $TABLE_TILES " +
                "($COL_ZOOM, $COL_COLUMN, $COL_ROW)",
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_METADATA (name TEXT NOT NULL, value TEXT)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS metadata_index ON $TABLE_METADATA (name)")
    }

    private fun metadataValue(key: String): String? = try {
        db.rawQuery("SELECT value FROM $TABLE_METADATA WHERE name=? LIMIT 1", arrayOf(key)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: SQLiteException) {
        null
    }

    private fun readZoomRange() {
        runCatching {
            db.rawQuery("SELECT MIN($COL_ZOOM), MAX($COL_ZOOM) FROM $TABLE_TILES", null).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    minZoomSeen = cursor.getInt(0)
                    maxZoomSeen = cursor.getInt(1)
                }
            }
        }
    }

    private fun writeBaseMetadata() {
        setMetadata("name", name)
        setMetadata("format", format ?: metadataValue("format") ?: "png")
        setMetadata("type", "overlay")
        setMetadata("version", "1.0")
        setMetadata("description", "Dlaždice uložené aplikací DetektorMapy při prohlížení mapy")
        updateZoomMetadata()
    }

    private fun updateZoomMetadata() {
        if (minZoomSeen > maxZoomSeen) return
        setMetadata("minzoom", minZoomSeen.toString())
        setMetadata("maxzoom", maxZoomSeen.toString())
    }

    companion object {
        private const val TAG = "MbTilesWriter"

        private const val TABLE_TILES = "tiles"
        private const val TABLE_METADATA = "metadata"
        private const val COL_ZOOM = "zoom_level"
        private const val COL_COLUMN = "tile_column"
        private const val COL_ROW = "tile_row"
        private const val COL_DATA = "tile_data"

        /** Total footprint of an MBTiles file including its WAL and shared-memory siblings. */
        fun sizeOnDisk(file: File): Long = listOf(file, walOf(file), shmOf(file))
            .sumOf { if (it.isFile) it.length() else 0L }

        /**
         * Removes an archive and its journal files.
         *
         * Deleting only the `.mbtiles` would leave a `-wal` behind, and SQLite would happily
         * replay it into the fresh database the next time the layer is cached.
         */
        fun deleteArchive(file: File): Boolean {
            val results = listOf(file, walOf(file), shmOf(file)).map { !it.exists() || it.delete() }
            return results.all { it }
        }

        private fun walOf(file: File) = File(file.parentFile, file.name + "-wal")

        private fun shmOf(file: File) = File(file.parentFile, file.name + "-shm")

        private fun flipY(z: Int, y: Int): Int = (1 shl z) - 1 - y

        private fun isValid(z: Int, x: Int, y: Int): Boolean {
            if (z < 0 || z > 30) return false
            val n = 1 shl z
            return x in 0 until n && y in 0 until n
        }

        private fun prepare(file: File): String {
            file.parentFile?.mkdirs()
            return file.absolutePath
        }
    }
}
