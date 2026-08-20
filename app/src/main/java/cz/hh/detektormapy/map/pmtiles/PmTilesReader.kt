package cz.hh.detektormapy.map.pmtiles

import cz.hh.detektormapy.util.BBox
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/** Anything structurally wrong with a .pmtiles file ends up as this exception. */
class PmTilesException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Internal compression codec used for directories, metadata and tiles.
 * Only [NONE] and [GZIP] can be decoded on device -- Android has no brotli/zstd in the
 * platform, and adding a native dependency for a format we generate ourselves is not worth it.
 */
enum class PmTilesCompression(val value: Int) {
    UNKNOWN(0),
    NONE(1),
    GZIP(2),
    BROTLI(3),
    ZSTD(4),
    ;

    companion object {
        fun from(value: Int): PmTilesCompression = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
}

/** Payload type of the tiles inside the archive. Drives the HTTP `Content-Type` header. */
enum class PmTilesTileType(val value: Int, val mimeType: String) {
    UNKNOWN(0, "application/octet-stream"),
    MVT(1, "application/vnd.mapbox-vector-tile"),
    PNG(2, "image/png"),
    JPEG(3, "image/jpeg"),
    WEBP(4, "image/webp"),
    AVIF(5, "image/avif"),
    ;

    /** True for raster payloads that `BitmapFactory` can decode directly. */
    val isRaster: Boolean get() = this == PNG || this == JPEG || this == WEBP || this == AVIF

    companion object {
        fun from(value: Int): PmTilesTileType = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
}

/**
 * The fixed 127-byte PMTiles v3 header, parsed into something readable.
 *
 * Why a data class and not lazy accessors: the header is read exactly once at open time and
 * every field is cheap, so copying it out of the byte buffer removes any need to keep the
 * buffer alive or to re-seek while serving tiles.
 */
data class PmTilesHeader(
    val specVersion: Int,
    val rootDirOffset: Long,
    val rootDirLength: Long,
    val metadataOffset: Long,
    val metadataLength: Long,
    val leafDirsOffset: Long,
    val leafDirsLength: Long,
    val tileDataOffset: Long,
    val tileDataLength: Long,
    val addressedTilesCount: Long,
    val tileEntriesCount: Long,
    val tileContentsCount: Long,
    val clustered: Boolean,
    val internalCompression: PmTilesCompression,
    val tileCompression: PmTilesCompression,
    val tileType: PmTilesTileType,
    val minZoom: Int,
    val maxZoom: Int,
    val bounds: BBox?,
    val centerZoom: Int,
    val centerLon: Double,
    val centerLat: Double,
)

/** One decoded directory entry; either a tile run or a pointer to a leaf directory. */
internal data class PmTilesEntry(val tileId: Long, val offset: Long, val length: Int, val runLength: Long) {
    /** runLength == 0 marks a pointer into the leaf directory section. */
    val isLeafPointer: Boolean get() = runLength == 0L
}

/**
 * Pure-Kotlin reader for **PMTiles v3** archives, backed by a [RandomAccessFile].
 *
 * Why we roll our own: MapLibre Android cannot open PMTiles reliably across versions, and the
 * official Java reader is not published for Android. The format is small enough (a header, a
 * varint directory format and a blob of tiles) that a self-contained reader is far cheaper
 * than a dependency -- and it lets the local tile server hand the bytes straight to MapLibre.
 *
 * Thread safety: all file access is serialised on an internal lock, so a single reader can be
 * shared by every thread of the tile server's pool.
 *
 * @param file the .pmtiles archive
 * @param leafCacheEntries how many decoded leaf directories to keep hot (LRU)
 * @param decompressGzippedTiles when true (default) a tile whose bytes actually start with the
 *   gzip magic and whose archive declares gzip tile compression is inflated before being
 *   returned. Raster archives normally store tiles uncompressed, so this is a no-op for them;
 *   the check on the real magic bytes means a mislabelled archive still serves usable images.
 */
class PmTilesReader(
    file: File,
    private val leafCacheEntries: Int = 24,
    private val decompressGzippedTiles: Boolean = true,
) : TileArchive {

    private val lock = Any()
    private val raf = RandomAccessFile(file, "r")
    private val fileLength = raf.length()

    /** Parsed 127-byte header; everything else in the archive is reached through it. */
    val header: PmTilesHeader

    private val rootDir: List<PmTilesEntry>

    /**
     * LRU of decoded leaf directories keyed by their offset inside the leaf section.
     * Access-ordered so the tiles the user is currently panning over stay resident.
     */
    private val leafCache = object : LinkedHashMap<Long, List<PmTilesEntry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<PmTilesEntry>>): Boolean =
            size > leafCacheEntries
    }

    init {
        try {
            header = parseHeader(readAt(0L, HEADER_BYTES))
            rootDir = decodeDirectory(
                decompress(
                    readAt(header.rootDirOffset, header.rootDirLength.toInt()),
                    header.internalCompression,
                    "root directory",
                ),
            )
        } catch (t: Throwable) {
            // Never leak the file handle when the archive turns out to be unusable.
            runCatching { raf.close() }
            throw t
        }
    }

    override val minZoom: Int get() = header.minZoom
    override val maxZoom: Int get() = header.maxZoom
    override val bounds: BBox? get() = header.bounds
    override val contentType: String get() = header.tileType.mimeType

    /** Convenience: the payload type, so callers can refuse to warp vector tiles. */
    val tileType: PmTilesTileType get() = header.tileType

    /** The archive's TileJSON-ish metadata blob, decompressed, or null when empty. */
    fun metadataJson(): String? {
        if (header.metadataLength <= 0L) return null
        val raw = readAt(header.metadataOffset, header.metadataLength.toInt())
        return decompress(raw, header.internalCompression, "metadata").toString(Charsets.UTF_8)
    }

    override fun getTile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < header.minZoom || z > header.maxZoom) return null
        val n = 1L shl z
        if (x < 0 || y < 0 || x >= n || y >= n) return null

        val wanted = hilbertTileId(z, x, y)
        var dir = rootDir
        // A well-formed v3 archive needs at most root + one leaf level, but the spec permits
        // deeper nesting. Cap the walk so a corrupt archive can never spin forever.
        for (level in 0 until MAX_DIRECTORY_DEPTH) {
            val entry = findEntry(dir, wanted) ?: return null
            if (!entry.isLeafPointer) {
                return if (wanted < entry.tileId + entry.runLength) {
                    readTilePayload(entry)
                } else {
                    null
                }
            }
            dir = leafDirectory(entry.offset, entry.length)
        }
        throw PmTilesException("PMTiles directory nesting exceeded $MAX_DIRECTORY_DEPTH levels")
    }

    override fun close() {
        synchronized(lock) {
            leafCache.clear()
            runCatching { raf.close() }
        }
    }

    // ---------------------------------------------------------------- internals

    private fun readTilePayload(entry: PmTilesEntry): ByteArray {
        val raw = readAt(header.tileDataOffset + entry.offset, entry.length)
        if (!decompressGzippedTiles) return raw
        val gzipped = header.tileCompression == PmTilesCompression.GZIP && looksGzipped(raw)
        return if (gzipped) gunzip(raw) else raw
    }

    private fun leafDirectory(offset: Long, length: Int): List<PmTilesEntry> {
        synchronized(lock) { leafCache[offset] }?.let { return it }
        val bytes = decompress(
            readAt(header.leafDirsOffset + offset, length),
            header.internalCompression,
            "leaf directory",
        )
        val decoded = decodeDirectory(bytes)
        synchronized(lock) { leafCache[offset] = decoded }
        return decoded
    }

    private fun readAt(offset: Long, length: Int): ByteArray {
        if (length < 0) throw PmTilesException("Negative read length $length at offset $offset")
        // Every length here comes from the archive itself, i.e. from untrusted input. Without a
        // ceiling a crafted header declaring a 2 GB root directory turns into an immediate
        // OutOfMemoryError before a single useful byte is read.
        if (length > MAX_BLOB_BYTES) {
            throw PmTilesException("PMTiles blob of $length B at offset $offset exceeds the $MAX_BLOB_BYTES B limit")
        }
        if (offset < 0 || offset > fileLength) {
            throw PmTilesException("PMTiles blob offset $offset is outside the archive")
        }
        val buf = ByteArray(length)
        synchronized(lock) {
            raf.seek(offset)
            raf.readFully(buf)
        }
        return buf
    }

    private fun decompress(bytes: ByteArray, compression: PmTilesCompression, what: String): ByteArray =
        when (compression) {
            PmTilesCompression.NONE, PmTilesCompression.UNKNOWN -> bytes

            PmTilesCompression.GZIP -> gunzip(bytes)

            PmTilesCompression.BROTLI, PmTilesCompression.ZSTD ->
                throw PmTilesException(
                    "PMTiles $what uses unsupported compression '${compression.name.lowercase()}'; " +
                        "rebuild the archive with --compression=gzip or none",
                )
        }

    companion object {

        /** Size of the fixed PMTiles v3 header in bytes. */
        const val HEADER_BYTES = 127

        private const val MAX_DIRECTORY_DEPTH = 4

        /**
         * Upper bound for any single allocation driven by numbers read out of the archive.
         * Real PMTiles directories are a few hundred kB; 64 MB is generous for a legitimate
         * file and small enough that a hostile one fails fast instead of killing the process.
         */
        internal const val MAX_BLOB_BYTES = 64 * 1024 * 1024

        /** Minimum bytes a directory entry can occupy: four varints of one byte each. */
        private const val MIN_BYTES_PER_ENTRY = 4L

        private val MAGIC = byteArrayOf(0x50, 0x4D, 0x54, 0x69, 0x6C, 0x65, 0x73) // "PMTiles"

        /** Highest zoom whose tile ids still fit comfortably in a signed 64-bit integer. */
        const val MAX_ZOOM = 26

        // ------------------------------------------------------------ header

        internal fun parseHeader(bytes: ByteArray): PmTilesHeader {
            if (bytes.size < HEADER_BYTES) {
                throw PmTilesException("PMTiles header truncated: ${bytes.size} of $HEADER_BYTES bytes")
            }
            for (i in MAGIC.indices) {
                if (bytes[i] != MAGIC[i]) {
                    throw PmTilesException("Not a PMTiles file: bad magic bytes")
                }
            }
            val version = bytes[7].toInt() and 0xFF
            if (version != 3) {
                throw PmTilesException("Unsupported PMTiles spec version $version, only v3 is supported")
            }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val minLon = bb.getInt(102) / 1e7
            val minLat = bb.getInt(106) / 1e7
            val maxLon = bb.getInt(110) / 1e7
            val maxLat = bb.getInt(114) / 1e7
            val bbox = if (minLon <= maxLon && minLat <= maxLat) {
                BBox(minLon, minLat, maxLon, maxLat)
            } else {
                null
            }
            return PmTilesHeader(
                specVersion = version,
                rootDirOffset = bb.getLong(8),
                rootDirLength = bb.getLong(16),
                metadataOffset = bb.getLong(24),
                metadataLength = bb.getLong(32),
                leafDirsOffset = bb.getLong(40),
                leafDirsLength = bb.getLong(48),
                tileDataOffset = bb.getLong(56),
                tileDataLength = bb.getLong(64),
                addressedTilesCount = bb.getLong(72),
                tileEntriesCount = bb.getLong(80),
                tileContentsCount = bb.getLong(88),
                clustered = (bytes[96].toInt() and 0xFF) == 1,
                internalCompression = PmTilesCompression.from(bytes[97].toInt() and 0xFF),
                tileCompression = PmTilesCompression.from(bytes[98].toInt() and 0xFF),
                tileType = PmTilesTileType.from(bytes[99].toInt() and 0xFF),
                minZoom = bytes[100].toInt() and 0xFF,
                maxZoom = bytes[101].toInt() and 0xFF,
                bounds = bbox,
                centerZoom = bytes[118].toInt() and 0xFF,
                centerLon = bb.getInt(119) / 1e7,
                centerLat = bb.getInt(123) / 1e7,
            )
        }

        // ------------------------------------------------------------ directories

        /**
         * Decodes the columnar varint directory format: entry count, then the tile id deltas,
         * then all run lengths, then all lengths, then all offsets. An offset of 0 (for any
         * entry but the first) means "immediately after the previous entry", which is how a
         * clustered archive keeps the directory tiny.
         */
        internal fun decodeDirectory(bytes: ByteArray): List<PmTilesEntry> {
            val cursor = VarintCursor(bytes)
            val count = cursor.next().toInt()
            if (count < 0) throw PmTilesException("PMTiles directory declares $count entries")
            if (count == 0) return emptyList()
            // A directory entry costs at least four varints, so at least four bytes. Checking
            // that before allocating stops a 20-byte blob from claiming 2^31 entries and
            // asking for a 16 GB LongArray.
            if (count.toLong() * MIN_BYTES_PER_ENTRY > bytes.size) {
                throw PmTilesException(
                    "PMTiles directory declares $count entries but holds only ${bytes.size} B",
                )
            }

            val ids = LongArray(count)
            var lastId = 0L
            for (i in 0 until count) {
                lastId += cursor.next()
                ids[i] = lastId
            }
            val runLengths = LongArray(count) { cursor.next() }
            val lengths = IntArray(count) { cursor.next().toInt() }
            val offsets = LongArray(count)
            for (i in 0 until count) {
                val raw = cursor.next()
                offsets[i] = if (raw == 0L && i > 0) {
                    offsets[i - 1] + lengths[i - 1]
                } else {
                    raw - 1
                }
            }
            return List(count) { i -> PmTilesEntry(ids[i], offsets[i], lengths[i], runLengths[i]) }
        }

        /** Largest entry whose tileId is <= [tileId]; null when [tileId] precedes the directory. */
        internal fun findEntry(entries: List<PmTilesEntry>, tileId: Long): PmTilesEntry? {
            var lo = 0
            var hi = entries.size - 1
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val midId = entries[mid].tileId
                when {
                    midId == tileId -> return entries[mid]

                    midId < tileId -> {
                        found = mid
                        lo = mid + 1
                    }

                    else -> hi = mid - 1
                }
            }
            return if (found >= 0) entries[found] else null
        }

        // ------------------------------------------------------------ hilbert ids

        /**
         * Maps an XYZ tile address onto its position on the Hilbert curve, which is the id
         * PMTiles directories are sorted by. Exposed (and unit tested) because a single
         * off-by-one here silently serves the wrong part of the world.
         */
        fun hilbertTileId(z: Int, x: Int, y: Int): Long {
            require(z in 0..MAX_ZOOM) { "Zoom $z out of range 0..$MAX_ZOOM" }
            val n = 1L shl z
            require(x >= 0 && y >= 0 && x < n && y < n) {
                "Tile $z/$x/$y is outside the pyramid at that zoom"
            }
            var acc = 0L
            for (tz in 0 until z) acc += (1L shl tz) * (1L shl tz)
            var tx = x.toLong()
            var ty = y.toLong()
            var d = 0L
            var s = n / 2
            while (s > 0) {
                val rx = if ((tx and s) > 0) 1 else 0
                val ry = if ((ty and s) > 0) 1 else 0
                d += s * s * ((3 * rx) xor ry)
                // Rotate the quadrant so the curve stays continuous.
                if (ry == 0) {
                    if (rx == 1) {
                        tx = s - 1 - tx
                        ty = s - 1 - ty
                    }
                    val t = tx
                    tx = ty
                    ty = t
                }
                s /= 2
            }
            return acc + d
        }

        /** Exact inverse of [hilbertTileId]. */
        fun tileIdToZxy(id: Long): Triple<Int, Int, Int> {
            require(id >= 0) { "Tile id must not be negative, got $id" }
            var acc = 0L
            for (z in 0..MAX_ZOOM) {
                val tilesOnLevel = (1L shl z) * (1L shl z)
                if (acc + tilesOnLevel > id) return idOnLevel(z, id - acc)
                acc += tilesOnLevel
            }
            throw PmTilesException("Tile id $id exceeds zoom $MAX_ZOOM")
        }

        private fun idOnLevel(z: Int, pos: Long): Triple<Int, Int, Int> {
            val n = 1L shl z
            var t = pos
            var x = 0L
            var y = 0L
            var s = 1L
            while (s < n) {
                val rx = ((t / 2) and 1L).toInt()
                val ry = ((t xor rx.toLong()) and 1L).toInt()
                if (ry == 0) {
                    if (rx == 1) {
                        x = s - 1 - x
                        y = s - 1 - y
                    }
                    val tmp = x
                    x = y
                    y = tmp
                }
                x += s * rx
                y += s * ry
                t /= 4
                s *= 2
            }
            return Triple(z, x.toInt(), y.toInt())
        }

        // ------------------------------------------------------------ bytes

        internal fun looksGzipped(bytes: ByteArray): Boolean =
            bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B

        internal fun gunzip(bytes: ByteArray): ByteArray = try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                val out = ByteArrayOutputStream(minOf(bytes.size * 3, 1 shl 20))
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    out.write(buf, 0, read)
                    // Guard against a decompression bomb: a 1 kB payload must not be allowed to
                    // inflate into gigabytes just because the archive says so.
                    if (out.size() > MAX_BLOB_BYTES) {
                        throw PmTilesException("PMTiles payload inflates past $MAX_BLOB_BYTES B")
                    }
                }
                out.toByteArray()
            }
        } catch (e: IOException) {
            throw PmTilesException("Failed to gunzip PMTiles payload", e)
        }
    }
}

/** Minimal LEB128 varint reader over a byte array; the only parser the format needs. */
internal class VarintCursor(private val bytes: ByteArray, private var pos: Int = 0) {

    fun next(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= bytes.size) throw PmTilesException("PMTiles varint runs past end of directory")
            val b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw PmTilesException("PMTiles varint wider than 64 bits")
        }
    }
}
