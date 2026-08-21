package cz.hh.detektormapy.map

import android.os.StatFs
import android.util.Log
import cz.hh.detektormapy.data.AppDirectories
import cz.hh.detektormapy.map.pmtiles.MbTilesWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The persistent half of the tile cache: "what I have once seen, I keep".
 *
 * One [MbTilesWriter] per layer, living next to the offline archives in the layers directory as
 * `<id>.cache.mbtiles`. The file sits there rather than in `cacheDir` on purpose -- Android is
 * free to wipe `cacheDir` whenever storage runs low, which is exactly the moment a detectorist in
 * a forest needs those tiles. It also means the cache can be pulled off the phone over USB and
 * opened with `sqlite3` or the desktop tooling like any other MBTiles archive.
 *
 * **Writes never happen on a request thread.** Tiles are queued and drained by one background
 * thread in batched transactions, so serving latency is unaffected by the disk. Dropping a queued
 * tile when the queue is full (or when the process dies) is harmless by construction: the tile is
 * simply fetched again next time.
 *
 * **What is cached is the source, not the picture on screen.** The cache sits underneath
 * [CalibratedTileComposer], so the key is a plain `(z, x, y)` with no calibration generation in
 * it. Caching the composed output would bake whatever alignment was active at the time into the
 * file, and re-calibrating a layer would then have to throw away every tile the user had
 * collected.
 */
@Singleton
class TileCacheStore @Inject constructor(private val dirs: AppDirectories) {

    private data class Pending(val layerId: String, val tile: MbTilesWriter.Tile, val format: String)

    private val writers = ConcurrentHashMap<String, MbTilesWriter>()
    private val queue = LinkedBlockingQueue<Pending>(QUEUE_CAPACITY)
    private val writerLock = Any()

    @Volatile
    private var writerThread: Thread? = null

    @Volatile
    private var globallyEnabled: Boolean = true

    @Volatile
    private var disabledLayers: Set<String> = emptySet()

    private val lowSpaceState = MutableStateFlow(false)

    /** True while free storage is below [MIN_FREE_BYTES]; the UI says so and writing stops. */
    val lowSpace: StateFlow<Boolean> = lowSpaceState

    /** Reflects the user's switches; called by [LayerManager] whenever preferences change. */
    fun applySettings(enabled: Boolean, disabledLayerIds: Set<String>) {
        globallyEnabled = enabled
        disabledLayers = disabledLayerIds
    }

    fun isEnabledFor(layerId: String): Boolean = globallyEnabled && layerId !in disabledLayers

    // ------------------------------------------------------------------ read / write

    /**
     * Cached bytes for a tile, or null. Reading is allowed even when caching is switched off:
     * the switch governs *filling* the cache, and throwing away access to tiles the user already
     * has would be a strange way to save space.
     */
    fun read(layerId: String, z: Int, x: Int, y: Int): ByteArray? {
        val writer = existingWriter(layerId) ?: return null
        return writer.getTile(z, x, y)
    }

    /** Queues a tile for the background writer. Returns false when it was not accepted. */
    fun write(layerId: String, z: Int, x: Int, y: Int, data: ByteArray, format: String): Boolean {
        if (!isEnabledFor(layerId)) return false
        if (data.isEmpty() || data.size > MAX_TILE_BYTES) return false
        if (lowSpaceState.value && !recheckFreeSpace()) return false
        ensureWriterThread()
        // offer(), not put(): a burst of panning must never block a tile-server worker on disk.
        val accepted = queue.offer(Pending(layerId, MbTilesWriter.Tile(z, x, y, data), format))
        if (!accepted) Log.d(TAG, "Fronta zápisu je plná, dlaždice $layerId/$z/$x/$y zahozena")
        return accepted
    }

    // ------------------------------------------------------------------ maintenance

    /** Bytes used by one layer's cache, journal files included. */
    fun sizeOf(layerId: String): Long = MbTilesWriter.sizeOnDisk(cacheFileFor(layerId))

    /** Every cache file present on disk, keyed by layer id, largest first. */
    fun sizes(): Map<String, Long> {
        val files = dirs.layersDir.listFiles() ?: return emptyMap()
        return files
            .filter { it.isFile && it.name.endsWith(CACHE_SUFFIX) }
            .associate { it.name.removeSuffix(CACHE_SUFFIX) to MbTilesWriter.sizeOnDisk(it) }
            .toList()
            .sortedByDescending { it.second }
            .toMap()
    }

    fun totalBytes(): Long = sizes().values.sum()

    /**
     * Free space on the volume that holds the layers directory.
     *
     * Two fallbacks, because measuring it is less reliable than it looks: `File.usableSpace`
     * answers 0 when the app is refused a `statfs` on the FUSE-mounted external path (seen on
     * API 36), and `StatFs` on the same path can fail for the same reason. Internal storage sits
     * on the same partition on every device this app supports, so it is the answer either way.
     */
    fun freeBytes(): Long = availableBytes(dirs.layersDir).takeIf { it > 0 } ?: availableBytes(dirs.internalRoot)

    private fun availableBytes(dir: File): Long = runCatching {
        val direct = dir.usableSpace
        if (direct > 0) direct else StatFs(dir.absolutePath).availableBytes
    }.getOrDefault(0L)

    /**
     * Drops one layer's cache immediately.
     *
     * Everything queued for that layer is discarded first; otherwise the drain loop would
     * recreate the file moments after it was deleted and the user would watch the size climb
     * back up on its own.
     */
    fun clear(layerId: String): Boolean {
        queue.removeAll { it.layerId == layerId }
        synchronized(writerLock) {
            writers.remove(layerId)?.let { runCatching { it.close() } }
        }
        val deleted = MbTilesWriter.deleteArchive(cacheFileFor(layerId))
        recheckFreeSpace()
        return deleted
    }

    /** Drops every cache file, including ones whose layer is no longer in the catalogue. */
    fun clearAll(): Int {
        queue.clear()
        synchronized(writerLock) {
            writers.values.forEach { runCatching { it.close() } }
            writers.clear()
        }
        val files = dirs.layersDir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(CACHE_SUFFIX) }
        val removed = files.count { MbTilesWriter.deleteArchive(it) }
        recheckFreeSpace()
        return removed
    }

    /** Flushes pending writes and closes every handle. Called when the map stack shuts down. */
    fun shutdown() {
        writerThread?.interrupt()
        writerThread = null
        synchronized(writerLock) {
            writers.values.forEach { runCatching { it.close() } }
            writers.clear()
        }
    }

    /**
     * Removes the pre-0.5 tile cache, a directory of one file per tile under `cacheDir`.
     *
     * It is dead weight now that tiles live in MBTiles archives, and it could be hundreds of
     * megabytes on a phone that has been used in the field. Safe to delete unconditionally:
     * that directory was always documented as scratch space.
     */
    fun purgeLegacyCache() {
        val legacy = dirs.tilesCacheDir
        if (!legacy.isDirectory) return
        val freed = runCatching { legacy.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
        if (runCatching { legacy.deleteRecursively() }.getOrDefault(false) && freed > 0) {
            Log.i(TAG, "Stará dlaždicová cache smazána (${freed / 1_000_000} MB)")
        }
    }

    /** Re-reads free space and returns true when there is room to keep caching. */
    fun recheckFreeSpace(): Boolean {
        val free = freeBytes()
        val low = free in 1 until MIN_FREE_BYTES
        if (lowSpaceState.value != low) {
            lowSpaceState.value = low
            if (low) Log.w(TAG, "Málo místa (${free / 1_000_000} MB), cache se přestane plnit")
        }
        return !low
    }

    // ------------------------------------------------------------------ internals

    internal fun cacheFileFor(layerId: String): File = File(dirs.layersDir, sanitize(layerId) + CACHE_SUFFIX)

    /** Opens a handle only if the file already exists, so reads never create empty archives. */
    private fun existingWriter(layerId: String): MbTilesWriter? {
        writers[layerId]?.let { return it }
        if (!cacheFileFor(layerId).isFile) return null
        // No format: the file already carries the one it was created with, and a reader that
        // opens a JPEG cache must not have its metadata rewritten to "png" on the way past.
        return openWriter(layerId, format = null)
    }

    private fun openWriter(layerId: String, format: String?): MbTilesWriter? = synchronized(writerLock) {
        writers[layerId]?.let { return it }
        val opened = runCatching { MbTilesWriter(cacheFileFor(layerId), format = format, name = layerId) }
            .onFailure { Log.w(TAG, "Cache vrstvy $layerId nelze otevřít", it) }
            .getOrNull()
        if (opened != null) writers[layerId] = opened
        opened
    }

    private fun ensureWriterThread() {
        if (writerThread?.isAlive == true) return
        synchronized(writerLock) {
            if (writerThread?.isAlive == true) return
            writerThread = Thread({ drainLoop() }, "TileCacheWriter").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }
    }

    private fun drainLoop() {
        var writesSinceSpaceCheck = 0
        while (true) {
            val first = try {
                queue.take()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            // A short pause before draining turns a pan gesture's burst of tiles into one
            // transaction instead of one fsync per tile.
            runCatching { TimeUnit.MILLISECONDS.sleep(COALESCE_MS) }
            val batch = ArrayList<Pending>(BATCH_SIZE).apply { add(first) }
            queue.drainTo(batch, BATCH_SIZE - 1)

            val touched = mutableListOf<MbTilesWriter>()
            batch.groupBy { it.layerId }.forEach { (layerId, pending) ->
                if (!isEnabledFor(layerId)) return@forEach
                val writer = openWriter(layerId, pending.first().format) ?: return@forEach
                val written = runCatching { writer.putAll(pending.map { it.tile }) }.getOrDefault(0)
                if (written > 0) touched += writer
                writesSinceSpaceCheck += written
            }

            // Once the burst is over, fold each write-ahead log back into its archive. Left
            // alone, the WALs are a large and permanent share of the cache's footprint (they
            // outweighed the data itself in testing), which makes "Správa úložiště" report
            // space that is not really being used for tiles.
            if (queue.isEmpty()) touched.forEach { runCatching { it.checkpoint() } }

            if (writesSinceSpaceCheck >= SPACE_CHECK_EVERY) {
                writesSinceSpaceCheck = 0
                // statfs is cheap but not free, and this runs on every pan; once per batch of
                // writes is often enough to notice a filling disk.
                recheckFreeSpace()
            }
        }
    }

    companion object {
        private const val TAG = "TileCacheStore"

        const val CACHE_SUFFIX = ".cache.mbtiles"

        /** Below this much free storage the app stops writing tiles and says so in Nastavení. */
        const val MIN_FREE_BYTES = 500L * 1000 * 1000

        /** Matches the tile-server ceiling; anything larger is a server error page, not a tile. */
        const val MAX_TILE_BYTES = 8 * 1024 * 1024

        private const val QUEUE_CAPACITY = 512
        private const val BATCH_SIZE = 64
        private const val SPACE_CHECK_EVERY = 200
        private const val COALESCE_MS = 120L

        /** Keeps a layer id usable as a file name; ids come from a hand-editable `layers.json`. */
        fun sanitize(id: String): String = id
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .ifEmpty { "layer" }
    }
}
