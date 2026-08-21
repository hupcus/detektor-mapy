package cz.hh.detektormapy.map.pmtiles

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The offline cache's storage layer. What matters here is not SQLite working -- it is that the
 * file we write stays a *standard* MBTiles archive (so the desktop tooling and [MbTilesReader]
 * can open it), that the TMS row flip matches the reader's, and that a process killed mid-write
 * leaves a usable file behind rather than a broken one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MbTilesWriterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun cacheFile(name: String = "test.cache.mbtiles"): File = File(temp.root, name)

    private fun png(marker: Int, size: Int = 64) = ByteArray(size) { (marker + it).toByte() }

    @Test
    fun `round trip through MbTilesReader`() {
        val file = cacheFile()
        MbTilesWriter(file, format = "png").use { writer ->
            writer.put(12, 2210, 1400, png(1))
            writer.put(13, 4420, 2800, png(2))
        }

        MbTilesReader(file).use { reader ->
            assertThat(reader.getTile(12, 2210, 1400)).isEqualTo(png(1))
            assertThat(reader.getTile(13, 4420, 2800)).isEqualTo(png(2))
            assertThat(reader.getTile(12, 0, 0)).isNull()
            assertThat(reader.contentType).isEqualTo("image/png")
            assertThat(reader.minZoom).isEqualTo(12)
            assertThat(reader.maxZoom).isEqualTo(13)
        }
    }

    @Test
    fun `stores rows in TMS order like the format demands`() {
        val file = cacheFile()
        // At z=2 the XYZ row 1 is TMS row 2; a reader that did not flip would miss it entirely.
        MbTilesWriter(file).use { it.put(2, 1, 1, png(7)) }

        MbTilesReader(file).use { reader ->
            assertThat(reader.getTile(2, 1, 1)).isEqualTo(png(7))
            assertThat(reader.getTile(2, 1, 2)).isNull()
        }
    }

    @Test
    fun `jpeg format survives reopening with an unspecified format`() {
        val file = cacheFile()
        MbTilesWriter(file, format = "jpg").use { it.put(10, 5, 5, png(3)) }
        // Reopening for a read must not rewrite the archive's declared format.
        MbTilesWriter(file).use { assertThat(it.getTile(10, 5, 5)).isEqualTo(png(3)) }

        MbTilesReader(file).use { assertThat(it.contentType).isEqualTo("image/jpeg") }
    }

    @Test
    fun `writing the same tile twice is idempotent and keeps the newest bytes`() {
        val file = cacheFile()
        MbTilesWriter(file).use { writer ->
            writer.put(14, 100, 200, png(1))
            writer.put(14, 100, 200, png(9))
            assertThat(writer.tileCount()).isEqualTo(1)
            assertThat(writer.getTile(14, 100, 200)).isEqualTo(png(9))
        }
    }

    @Test
    fun `batch write lands as one transaction`() {
        val file = cacheFile()
        val batch = (0 until 40).map { MbTilesWriter.Tile(15, 1000 + it, 2000, png(it)) }
        MbTilesWriter(file).use { writer ->
            assertThat(writer.putAll(batch)).isEqualTo(40)
            assertThat(writer.tileCount()).isEqualTo(40)
        }
    }

    @Test
    fun `out of range coordinates are refused, not stored`() {
        val file = cacheFile()
        MbTilesWriter(file).use { writer ->
            assertThat(writer.put(2, 9, 0, png(1))).isFalse()
            assertThat(writer.put(-1, 0, 0, png(1))).isFalse()
            assertThat(writer.put(5, 1, 1, ByteArray(0))).isFalse()
            assertThat(writer.tileCount()).isEqualTo(0)
        }
    }

    @Test
    fun `an abandoned handle leaves committed tiles readable`() {
        val file = cacheFile()
        // Deliberately no close(): this is what a process kill mid-session looks like on disk,
        // with data still sitting in the write-ahead log.
        val abandoned = MbTilesWriter(file)
        abandoned.put(11, 1100, 700, png(5))

        MbTilesWriter(file).use { reopened ->
            assertThat(reopened.getTile(11, 1100, 700)).isEqualTo(png(5))
            // And the file is still writable rather than stuck in recovery.
            assertThat(reopened.put(11, 1101, 700, png(6))).isTrue()
        }
    }

    @Test
    fun `deleteArchive removes the journal siblings too`() {
        val file = cacheFile()
        MbTilesWriter(file).use { it.put(9, 270, 170, png(4)) }
        assertThat(file.exists()).isTrue()

        assertThat(MbTilesWriter.deleteArchive(file)).isTrue()
        assertThat(file.exists()).isFalse()
        assertThat(File(temp.root, file.name + "-wal").exists()).isFalse()
        assertThat(File(temp.root, file.name + "-shm").exists()).isFalse()
    }

    @Test
    fun `sizeOnDisk counts the archive`() {
        val file = cacheFile()
        MbTilesWriter(file).use { writer ->
            writer.putAll((0 until 20).map { MbTilesWriter.Tile(12, 2000 + it, 1300, png(it, size = 4096)) })
        }
        assertThat(MbTilesWriter.sizeOnDisk(file)).isAtLeast(20 * 4096L)
    }

    @Test
    fun `cache hit is well under ten milliseconds`() {
        val file = cacheFile()
        MbTilesWriter(file).use { writer ->
            writer.putAll((0 until 200).map { MbTilesWriter.Tile(14, 8000 + it, 5400, png(it, size = 8192)) })
            // Warm up so the measurement is of a steady-state read, not of SQLite's first query.
            repeat(20) { writer.getTile(14, 8000 + it, 5400) }

            val started = System.nanoTime()
            repeat(100) { writer.getTile(14, 8000 + it, 5400) }
            val perReadMs = (System.nanoTime() - started) / 100.0 / 1_000_000.0
            assertThat(perReadMs).isLessThan(10.0)
        }
    }
}
