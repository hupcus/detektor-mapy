package cz.hh.detektormapy.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.data.AppDirectories
import cz.hh.detektormapy.map.pmtiles.MbTilesReader
import cz.hh.detektormapy.map.pmtiles.TileArchive
import cz.hh.detektormapy.util.BBox
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * The write-through cache as the app actually assembles it: a [CachedTileArchive] in front of a
 * remote archive, backed by [TileCacheStore].
 *
 * The behaviours worth pinning down are the ones that are easy to get subtly wrong and invisible
 * until a user is standing in a forest: cache before network (not network with a fallback),
 * blank "outside coverage" answers never becoming permanent, and the cache holding the
 * publisher's pixels rather than the calibrated ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TileCacheStoreTest {

    private lateinit var dirs: AppDirectories
    private lateinit var store: TileCacheStore

    /** Stand-in for a remote archive: counts requests and can be taken "offline". */
    private class FakeRemote(
        private val payload: (Int, Int, Int) -> ByteArray?,
        override val contentType: String = "image/png",
    ) : TileArchive {
        val requests = AtomicInteger(0)

        @Volatile
        var offline = false

        override val minZoom = 0
        override val maxZoom = 19
        override val bounds: BBox? = null

        override fun getTile(z: Int, x: Int, y: Int): ByteArray? {
            requests.incrementAndGet()
            return if (offline) null else payload(z, x, y)
        }

        override fun close() = Unit
    }

    /** Trailing-lambda factory; [FakeRemote]'s payload is not its last constructor parameter. */
    private fun remote(payload: (Int, Int, Int) -> ByteArray?) = FakeRemote(payload)

    private fun tile(marker: Int, size: Int = 1024) = ByteArray(size) { (marker + it).toByte() }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dirs = AppDirectories(context)
        dirs.layersDir.listFiles()?.forEach { it.delete() }
        store = TileCacheStore(dirs)
        store.applySettings(enabled = true, disabledLayerIds = emptySet())
    }

    @After
    fun tearDown() {
        store.shutdown()
        dirs.layersDir.listFiles()?.forEach { it.delete() }
    }

    /** Waits for the background writer to catch up; it batches, so a poll beats a fixed sleep. */
    private fun awaitCached(layerId: String, z: Int, x: Int, y: Int): ByteArray? {
        repeat(100) {
            store.read(layerId, z, x, y)?.let { return it }
            Thread.sleep(20)
        }
        return null
    }

    @Test
    fun `a tile seen online is served from disk afterwards`() {
        val source = remote { _, _, _ -> tile(1) }
        val archive = CachedTileArchive(source, "vm2_online", store)

        assertThat(archive.getTile(13, 4400, 2800)).isEqualTo(tile(1))
        assertThat(awaitCached("vm2_online", 13, 4400, 2800)).isEqualTo(tile(1))

        // Airplane mode: the remote answers null, but the layer keeps drawing.
        source.offline = true
        assertThat(archive.getTile(13, 4400, 2800)).isEqualTo(tile(1))
    }

    @Test
    fun `cache is consulted before the network, not after it fails`() {
        val source = remote { _, _, _ -> tile(2) }
        val archive = CachedTileArchive(source, "muller_cechy", store)

        archive.getTile(10, 550, 350)
        awaitCached("muller_cechy", 10, 550, 350)
        val afterFirst = source.requests.get()

        repeat(5) { archive.getTile(10, 550, 350) }
        // Every one of those five was answered from disk. The old behaviour would have made
        // five more requests and only used the cache when they failed.
        assertThat(source.requests.get()).isEqualTo(afterFirst)
    }

    @Test
    fun `empty coverage answers are never written to disk`() {
        // ~200-334 byte fully transparent PNG: what ArcGIS and chartae send outside coverage.
        val source = remote { _, _, _ -> tile(3, size = 250) }
        val archive = CachedTileArchive(source, "cisarske_kvk", store)

        assertThat(archive.getTile(14, 8800, 5500)).hasLength(250)
        Thread.sleep(300)
        // A cached blank would hide the data the day the service extends its coverage.
        assertThat(store.read("cisarske_kvk", 14, 8800, 5500)).isNull()
    }

    @Test
    fun `the cache holds source pixels, so calibration is applied on every serve`() {
        val source = remote { _, _, _ -> tile(4) }
        val archive = CachedTileArchive(source, "vm3_topo", store)
        val server = LocalTileServer()
        server.register("vm3_topo", archive)

        // Pull the tile once uncalibrated so it lands in the cache.
        assertThat(archive.getTile(12, 2200, 1400)).isEqualTo(tile(4))
        awaitCached("vm3_topo", 12, 2200, 1400)

        // Applying a calibration changes what the map is served, and the URL generation with it.
        server.setCalibration("vm3_topo", Affine2D.translation(120.0, -80.0))
        assertThat(server.generationOf("vm3_topo")).isGreaterThan(0)
        // ...but the bytes on disk are still the publisher's originals, untouched by the warp.
        assertThat(store.read("vm3_topo", 12, 2200, 1400)).isEqualTo(tile(4))
        server.close()
    }

    @Test
    fun `switching caching off stops writes but keeps what is already stored`() {
        val source = remote { _, _, _ -> tile(5) }
        val archive = CachedTileArchive(source, "ortofoto", store)

        archive.getTile(16, 35000, 22000)
        awaitCached("ortofoto", 16, 35000, 22000)

        store.applySettings(enabled = false, disabledLayerIds = emptySet())
        archive.getTile(16, 35001, 22000)
        Thread.sleep(300)

        assertThat(store.read("ortofoto", 16, 35001, 22000)).isNull()
        assertThat(store.read("ortofoto", 16, 35000, 22000)).isEqualTo(tile(5))
    }

    @Test
    fun `a per-layer opt out only silences that layer`() {
        store.applySettings(enabled = true, disabledLayerIds = setOf("ortofoto"))
        val ortho = CachedTileArchive(remote { _, _, _ -> tile(6) }, "ortofoto", store)
        val vm2 = CachedTileArchive(remote { _, _, _ -> tile(7) }, "vm2_online", store)

        ortho.getTile(15, 17000, 11000)
        vm2.getTile(15, 17000, 11000)
        awaitCached("vm2_online", 15, 17000, 11000)

        assertThat(store.read("ortofoto", 15, 17000, 11000)).isNull()
        assertThat(store.read("vm2_online", 15, 17000, 11000)).isEqualTo(tile(7))
    }

    @Test
    fun `clearing one layer frees its file and leaves the others alone`() {
        val a = CachedTileArchive(remote { _, _, _ -> tile(8) }, "vm2_online", store)
        val b = CachedTileArchive(remote { _, _, _ -> tile(9) }, "ztm", store)
        a.getTile(12, 2200, 1400)
        b.getTile(12, 2200, 1400)
        awaitCached("vm2_online", 12, 2200, 1400)
        awaitCached("ztm", 12, 2200, 1400)
        assertThat(store.sizes().keys).containsExactly("vm2_online", "ztm")

        assertThat(store.clear("vm2_online")).isTrue()

        assertThat(store.sizes().keys).containsExactly("ztm")
        assertThat(store.read("vm2_online", 12, 2200, 1400)).isNull()
        assertThat(store.read("ztm", 12, 2200, 1400)).isEqualTo(tile(9))
    }

    @Test
    fun `clearAll removes every cache file`() {
        val a = CachedTileArchive(remote { _, _, _ -> tile(1) }, "vm2_online", store)
        val b = CachedTileArchive(remote { _, _, _ -> tile(2) }, "vm1", store)
        a.getTile(11, 1100, 700)
        b.getTile(11, 1100, 700)
        awaitCached("vm2_online", 11, 1100, 700)
        awaitCached("vm1", 11, 1100, 700)

        assertThat(store.clearAll()).isEqualTo(2)
        assertThat(store.sizes()).isEmpty()
        assertThat(store.totalBytes()).isEqualTo(0)
    }

    @Test
    fun `cache files are named after the layer and stay next to the archives`() {
        val file = store.cacheFileFor("vm2_online")
        assertThat(file.parentFile?.absolutePath).isEqualTo(dirs.layersDir.absolutePath)
        assertThat(file.name).isEqualTo("vm2_online${TileCacheStore.CACHE_SUFFIX}")
        // A hand-edited layers.json can carry anything as an id; it must not escape the directory.
        assertThat(store.cacheFileFor("../../etc/passwd").parentFile?.absolutePath)
            .isEqualTo(dirs.layersDir.absolutePath)
    }

    @Test
    fun `the archive records the format the bytes actually are, not the declared one`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(1024)
        assertThat(CachedTileArchive.sniffFormat(jpeg)).isEqualTo("jpg")
        assertThat(CachedTileArchive.sniffFormat(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))).isEqualTo("png")

        // chartae-antiquae serves JPEG from a template with no extension, so the catalogue calls
        // it image/png. The cache must not repeat that guess in its metadata.
        val archive = CachedTileArchive(FakeRemote({ _, _, _ -> jpeg }, "image/png"), "vm2_online", store)
        archive.getTile(12, 2200, 1400)
        awaitCached("vm2_online", 12, 2200, 1400)

        MbTilesReader(store.cacheFileFor("vm2_online")).use {
            assertThat(it.contentType).isEqualTo("image/jpeg")
        }
    }

    @Test
    fun `sanitize keeps ids usable as file names`() {
        assertThat(TileCacheStore.sanitize("vm2_online")).isEqualTo("vm2_online")
        assertThat(TileCacheStore.sanitize("../secret")).isEqualTo("___secret")
        assertThat(TileCacheStore.sanitize("")).isEqualTo("layer")
    }
}
