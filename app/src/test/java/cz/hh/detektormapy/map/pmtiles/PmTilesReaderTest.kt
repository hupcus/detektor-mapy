package cz.hh.detektormapy.map.pmtiles

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/**
 * Exercises the PMTiles v3 reader against archives built here in the test, so the suite needs
 * neither a device nor a checked-in binary fixture (map data is deliberately kept out of the repo).
 */
class PmTilesReaderTest {

    // ------------------------------------------------------------------ header

    @Test
    fun `parses header of a plain archive`() {
        val tiles = sampleTiles()
        val file = writeArchive(buildArchive(tiles, gzipInternal = false))
        PmTilesReader(file).use { reader ->
            val header = reader.header
            assertThat(header.specVersion).isEqualTo(3)
            assertThat(header.tileType).isEqualTo(PmTilesTileType.PNG)
            assertThat(header.internalCompression).isEqualTo(PmTilesCompression.NONE)
            assertThat(header.tileCompression).isEqualTo(PmTilesCompression.NONE)
            assertThat(header.clustered).isTrue()
            assertThat(header.minZoom).isEqualTo(0)
            assertThat(header.maxZoom).isEqualTo(3)
            assertThat(header.addressedTilesCount).isEqualTo(tiles.size.toLong())
            assertThat(header.tileEntriesCount).isEqualTo(tiles.size.toLong())
            assertThat(reader.contentType).isEqualTo("image/png")
            val bounds = requireNonNull(header.bounds)
            assertThat(bounds.west).isWithin(1e-6).of(12.0)
            assertThat(bounds.south).isWithin(1e-6).of(48.5)
            assertThat(bounds.east).isWithin(1e-6).of(19.0)
            assertThat(bounds.north).isWithin(1e-6).of(51.0)
            assertThat(reader.metadataJson()).isEqualTo(METADATA)
        }
    }

    @Test
    fun `parses header of a gzipped archive`() {
        val file = writeArchive(buildArchive(sampleTiles(), gzipInternal = true))
        PmTilesReader(file).use { reader ->
            assertThat(reader.header.internalCompression).isEqualTo(PmTilesCompression.GZIP)
            assertThat(reader.metadataJson()).isEqualTo(METADATA)
        }
    }

    // ------------------------------------------------------------------ tiles

    @Test
    fun `returns exact bytes for present tiles and null for absent ones`() {
        val tiles = sampleTiles()
        for (gzip in listOf(false, true)) {
            val file = writeArchive(buildArchive(tiles, gzipInternal = gzip))
            PmTilesReader(file).use { reader ->
                for ((address, expected) in tiles) {
                    val (z, x, y) = address
                    assertThat(reader.getTile(z, x, y)).isEqualTo(expected)
                }
                // Present zoom, empty cell.
                assertThat(reader.getTile(3, 7, 7)).isNull()
                // Zoom outside the declared range.
                assertThat(reader.getTile(9, 1, 1)).isNull()
                // Out of the pyramid entirely.
                assertThat(reader.getTile(1, 5, 0)).isNull()
            }
        }
    }

    @Test
    fun `walks leaf directories`() {
        val tiles = sampleTiles()
        val file = writeArchive(buildArchive(tiles, gzipInternal = true, entriesPerLeaf = 2))
        PmTilesReader(file).use { reader ->
            for ((address, expected) in tiles) {
                val (z, x, y) = address
                assertThat(reader.getTile(z, x, y)).isEqualTo(expected)
            }
            assertThat(reader.getTile(3, 7, 7)).isNull()
        }
    }

    @Test
    fun `serves the same tile repeatedly from the leaf cache`() {
        val tiles = sampleTiles()
        val file = writeArchive(buildArchive(tiles, gzipInternal = false, entriesPerLeaf = 1))
        PmTilesReader(file, leafCacheEntries = 1).use { reader ->
            repeat(5) {
                for ((address, expected) in tiles) {
                    val (z, x, y) = address
                    assertThat(reader.getTile(z, x, y)).isEqualTo(expected)
                }
            }
        }
    }

    // ------------------------------------------------------------------ hilbert ids

    @Test
    fun `hilbert ids match the specification for the first two zoom levels`() {
        assertThat(PmTilesReader.hilbertTileId(0, 0, 0)).isEqualTo(0L)
        assertThat(PmTilesReader.hilbertTileId(1, 0, 0)).isEqualTo(1L)
        assertThat(PmTilesReader.hilbertTileId(1, 0, 1)).isEqualTo(2L)
        assertThat(PmTilesReader.hilbertTileId(1, 1, 1)).isEqualTo(3L)
        assertThat(PmTilesReader.hilbertTileId(1, 1, 0)).isEqualTo(4L)
        assertThat(PmTilesReader.tileIdToZxy(0L)).isEqualTo(Triple(0, 0, 0))
        assertThat(PmTilesReader.tileIdToZxy(1L)).isEqualTo(Triple(1, 0, 0))
        assertThat(PmTilesReader.tileIdToZxy(2L)).isEqualTo(Triple(1, 0, 1))
        assertThat(PmTilesReader.tileIdToZxy(3L)).isEqualTo(Triple(1, 1, 1))
        assertThat(PmTilesReader.tileIdToZxy(4L)).isEqualTo(Triple(1, 1, 0))
        // The first id of zoom 2 is 5 = 1 + 4.
        assertThat(PmTilesReader.hilbertTileId(2, 0, 0)).isEqualTo(5L)
    }

    @Test
    fun `hilbert ids round trip for a deterministic random sweep`() {
        val random = Random(20260819)
        for (z in 0..16) {
            val n = 1 shl z
            val samples = if (n <= 8) n * n else 200
            repeat(samples) {
                val x = random.nextInt(n)
                val y = random.nextInt(n)
                val id = PmTilesReader.hilbertTileId(z, x, y)
                assertThat(PmTilesReader.tileIdToZxy(id)).isEqualTo(Triple(z, x, y))
            }
        }
    }

    @Test
    fun `hilbert ids are dense and unique inside a zoom level`() {
        for (z in 0..5) {
            val n = 1 shl z
            val base = (0 until z).sumOf { (1L shl it) * (1L shl it) }
            val seen = HashSet<Long>()
            for (x in 0 until n) {
                for (y in 0 until n) {
                    val id = PmTilesReader.hilbertTileId(z, x, y)
                    assertThat(id).isAtLeast(base)
                    assertThat(id).isLessThan(base + n.toLong() * n.toLong())
                    assertThat(seen.add(id)).isTrue()
                }
            }
            assertThat(seen).hasSize(n * n)
        }
    }

    // ------------------------------------------------------------------ failure modes

    @Test
    fun `unsupported internal compression is rejected with a clear message`() {
        val archive = buildArchive(sampleTiles(), gzipInternal = false, internalCompressionOverride = 3)
        val file = writeArchive(archive)
        val error = assertThrows(PmTilesException::class.java) { PmTilesReader(file) }
        assertThat(error).hasMessageThat().contains("brotli")
        assertThat(error).hasMessageThat().contains("root directory")
    }

    @Test
    fun `zstd directories are rejected too`() {
        val archive = buildArchive(sampleTiles(), gzipInternal = false, internalCompressionOverride = 4)
        val error = assertThrows(PmTilesException::class.java) { PmTilesReader(writeArchive(archive)) }
        assertThat(error).hasMessageThat().contains("zstd")
    }

    @Test
    fun `a file that is not pmtiles is rejected`() {
        val file = File.createTempFile("not-pmtiles", ".bin").apply {
            deleteOnExit()
            writeBytes(ByteArray(200) { 0x42 })
        }
        val error = assertThrows(PmTilesException::class.java) { PmTilesReader(file) }
        assertThat(error).hasMessageThat().contains("magic")
    }

    @Test
    fun `a v2 archive is rejected`() {
        val bytes = buildArchive(sampleTiles(), gzipInternal = false)
        bytes[7] = 2.toByte()
        val error = assertThrows(PmTilesException::class.java) { PmTilesReader(writeArchive(bytes)) }
        assertThat(error).hasMessageThat().contains("v3")
    }

    // ================================================================== fixture builder

    private fun <T> requireNonNull(value: T?): T {
        assertThat(value).isNotNull()
        return value!!
    }

    private fun sampleTiles(): Map<Triple<Int, Int, Int>, ByteArray> = linkedMapOf(
        Triple(0, 0, 0) to "zoom0".toByteArray(),
        Triple(1, 0, 0) to "z1-0-0".toByteArray(),
        Triple(1, 1, 1) to "z1-1-1".toByteArray(),
        Triple(2, 1, 2) to "z2-1-2".toByteArray(),
        Triple(3, 4, 5) to ByteArray(300) { (it % 251).toByte() },
        Triple(3, 5, 5) to ByteArray(17) { 0x7F },
    )

    private fun writeArchive(bytes: ByteArray): File = File.createTempFile("fixture", ".pmtiles").apply {
        deleteOnExit()
        writeBytes(bytes)
    }

    /**
     * Serialises a complete, spec-shaped PMTiles v3 archive.
     *
     * @param entriesPerLeaf 0 keeps every entry in the root directory; a positive value chops the
     *   entries into leaf directories so the reader's leaf walk is exercised too
     * @param internalCompressionOverride writes a codec byte without actually compressing, which
     *   is exactly how an archive produced by another tool would look to us
     */
    private fun buildArchive(
        tiles: Map<Triple<Int, Int, Int>, ByteArray>,
        gzipInternal: Boolean,
        entriesPerLeaf: Int = 0,
        internalCompressionOverride: Int? = null,
    ): ByteArray {
        data class Built(val tileId: Long, val offset: Long, val length: Int)

        val sorted = tiles.entries
            .map { (address, payload) ->
                PmTilesReader.hilbertTileId(address.first, address.second, address.third) to payload
            }
            .sortedBy { it.first }

        val tileData = ByteArrayOutputStream()
        val built = ArrayList<Built>(sorted.size)
        for ((tileId, payload) in sorted) {
            built.add(Built(tileId, tileData.size().toLong(), payload.size))
            tileData.write(payload)
        }

        val rootBytes: ByteArray
        val leafSection: ByteArray
        if (entriesPerLeaf <= 0) {
            rootBytes =
                maybeGzip(
                    serializeDirectory(
                        built.map {
                            arrayOf(it.tileId, it.offset, it.length.toLong(), 1L)
                        },
                    ),
                    gzipInternal,
                )
            leafSection = ByteArray(0)
        } else {
            val leafBlob = ByteArrayOutputStream()
            val rootEntries = ArrayList<Array<Long>>()
            for (chunk in built.chunked(entriesPerLeaf)) {
                val leaf = maybeGzip(
                    serializeDirectory(chunk.map { arrayOf(it.tileId, it.offset, it.length.toLong(), 1L) }),
                    gzipInternal,
                )
                rootEntries.add(arrayOf(chunk.first().tileId, leafBlob.size().toLong(), leaf.size.toLong(), 0L))
                leafBlob.write(leaf)
            }
            rootBytes = maybeGzip(serializeDirectory(rootEntries), gzipInternal)
            leafSection = leafBlob.toByteArray()
        }

        val metadataBytes = maybeGzip(METADATA.toByteArray(Charsets.UTF_8), gzipInternal)
        val tileBytes = tileData.toByteArray()

        val rootOffset = PmTilesReader.HEADER_BYTES.toLong()
        val metadataOffset = rootOffset + rootBytes.size
        val leafOffset = metadataOffset + metadataBytes.size
        val tileOffset = leafOffset + leafSection.size

        val header = ByteBuffer.allocate(PmTilesReader.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("PMTiles".toByteArray(Charsets.US_ASCII))
        header.put(3.toByte())
        header.putLong(rootOffset)
        header.putLong(rootBytes.size.toLong())
        header.putLong(metadataOffset)
        header.putLong(metadataBytes.size.toLong())
        header.putLong(leafOffset)
        header.putLong(leafSection.size.toLong())
        header.putLong(tileOffset)
        header.putLong(tileBytes.size.toLong())
        header.putLong(tiles.size.toLong())
        header.putLong(tiles.size.toLong())
        header.putLong(tiles.size.toLong())
        header.put(1.toByte()) // clustered
        header.put((internalCompressionOverride ?: if (gzipInternal) 2 else 1).toByte())
        header.put(1.toByte()) // tile compression: none
        header.put(2.toByte()) // tile type: PNG
        header.put(tiles.keys.minOf { it.first }.toByte())
        header.put(tiles.keys.maxOf { it.first }.toByte())
        header.putInt(120_000_000)
        header.putInt(485_000_000)
        header.putInt(190_000_000)
        header.putInt(510_000_000)
        header.put(8.toByte())
        header.putInt(155_000_000)
        header.putInt(497_500_000)

        val out = ByteArrayOutputStream()
        out.write(header.array())
        out.write(rootBytes)
        out.write(metadataBytes)
        out.write(leafSection)
        out.write(tileBytes)
        return out.toByteArray()
    }

    /** Columnar varint directory: count, tileId deltas, run lengths, lengths, offsets. */
    private fun serializeDirectory(entries: List<Array<Long>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varint(entries.size.toLong()))
        var last = 0L
        for (e in entries) {
            out.write(varint(e[0] - last))
            last = e[0]
        }
        for (e in entries) out.write(varint(e[3]))
        for (e in entries) out.write(varint(e[2]))
        for ((index, e) in entries.withIndex()) {
            val contiguous = index > 0 && e[1] == entries[index - 1][1] + entries[index - 1][2]
            out.write(varint(if (contiguous) 0L else e[1] + 1L))
        }
        return out.toByteArray()
    }

    private fun varint(value: Long): ByteArray {
        require(value >= 0)
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                return out.toByteArray()
            }
            out.write(b or 0x80)
        }
    }

    private fun maybeGzip(bytes: ByteArray, gzip: Boolean): ByteArray {
        if (!gzip) return bytes
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private companion object {
        const val METADATA = "{\"name\":\"fixture\",\"attribution\":\"test\"}"
    }
}
