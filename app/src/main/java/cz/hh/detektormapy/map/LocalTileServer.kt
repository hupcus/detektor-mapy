package cz.hh.detektormapy.map

import android.util.Log
import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.map.pmtiles.TileArchive
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A deliberately tiny HTTP/1.1 server bound to the loopback interface, which is how calibrated
 * offline tiles reach MapLibre.
 *
 * Why a server at all: MapLibre Android has no hook for "give me these bytes for this tile", and
 * no runtime transform for raster sources. It does, however, happily consume any XYZ template.
 * So the app registers a `RasterSource` pointing at `http://127.0.0.1:<port>/t/{layerId}/{z}/{x}/{y}`
 * and this class answers from a PMTiles/MBTiles archive, applying the layer's affine calibration
 * on the way out. That keeps calibration independent of the MapLibre version and unit testable.
 *
 * Why no third-party HTTP library: the surface is two routes and no concurrency beyond a small
 * pool. `ServerSocket` plus a bounded `ThreadPoolExecutor` is ~200 lines and adds zero APK weight.
 *
 * Security: the socket binds to the loopback address only, and any connection whose peer is not
 * a loopback address is dropped without reading a byte.
 */
class LocalTileServer(private val cacheBytes: Long = DEFAULT_CACHE_BYTES, private val workerThreads: Int = 4) :
    Closeable {

    private data class Layer(
        val archive: TileArchive,
        val calibration: Affine2D?,
        /**
         * Bumped whenever THIS layer's calibration or archive changes. Per layer rather than
         * global, so nudging the II. VM overlay does not throw away every cached ortophoto
         * tile as well.
         */
        val generation: Int,
    )

    private val layers = HashMap<String, Layer>()
    private val layersLock = Any()

    /** Source of monotonically increasing per-layer generation numbers. */
    private val generationSeq = AtomicInteger(0)

    private val cache = TileByteCache(cacheBytes)
    private val running = AtomicBoolean(false)

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var pool: ThreadPoolExecutor? = null

    /** Actual ephemeral port, valid only between [start] and [stop]; -1 otherwise. */
    @Volatile
    var port: Int = -1
        private set

    /** `http://127.0.0.1:<port>`; throws when the server is not running. */
    val baseUrl: String
        get() {
            val p = port
            check(p > 0) { "LocalTileServer is not running" }
            return "http://$LOOPBACK:$p"
        }

    val isRunning: Boolean get() = running.get()

    /**
     * XYZ template for a MapLibre `RasterSource`.
     *
     * The `?g=` suffix is the whole reason calibration is visible at all. MapLibre Android has
     * no way to refresh or invalidate a raster source (verified with javap over 11.11.0: neither
     * `Source` nor `RasterSource` exposes anything of the sort, and the style spec has no
     * `raster-translate`), so once it has drawn a tile it never asks for that URL again. Warping
     * the bytes server-side therefore changed nothing on screen. Folding the layer's generation
     * into the URL turns "the calibration changed" into "this is a different tile set", which is
     * something MapLibre *does* understand -- [MapController] compares templates and replaces
     * the source when they differ. The server itself ignores the query string.
     */
    fun urlTemplate(layerId: String): String = "$baseUrl/t/$layerId/{z}/{x}/{y}?g=${generationOf(layerId)}"

    /** Current cache generation of a layer; changes whenever its tiles would render differently. */
    fun generationOf(layerId: String): Int = synchronized(layersLock) { layers[layerId]?.generation ?: 0 }

    // ------------------------------------------------------------------ lifecycle

    /** Binds an ephemeral loopback port and starts accepting. Idempotent. */
    @Synchronized
    fun start(): Int {
        if (running.get()) return port
        val socket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
        socket.reuseAddress = true
        serverSocket = socket
        port = socket.localPort
        pool = ThreadPoolExecutor(
            1,
            workerThreads.coerceAtLeast(1),
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(QUEUE_CAPACITY),
        ).apply { allowCoreThreadTimeOut(true) }
        running.set(true)
        acceptThread = Thread({ acceptLoop(socket) }, "LocalTileServer-accept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Local tile server listening on $baseUrl")
        return port
    }

    /** Stops accepting, drops the pool and clears the cache. Archives are *not* closed. */
    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        pool?.shutdownNow()
        pool = null
        acceptThread = null
        port = -1
        cache.clear()
        Log.i(TAG, "Local tile server stopped")
    }

    override fun close() = stop()

    // ------------------------------------------------------------------ registry

    /** Makes [archive] available under `/t/{layerId}/...`. Replaces any previous registration. */
    fun register(layerId: String, archive: TileArchive) {
        synchronized(layersLock) {
            val previous = layers.put(
                layerId,
                Layer(archive, layers[layerId]?.calibration, generationSeq.incrementAndGet()),
            )
            if (previous != null) cache.evictLayer(layerId)
        }
    }

    fun unregister(layerId: String) {
        synchronized(layersLock) {
            layers.remove(layerId)
            cache.evictLayer(layerId)
        }
    }

    /**
     * Sets (or clears) a layer's calibration.
     *
     * A no-op call returns early instead of bumping the generation. That matters more than it
     * looks: the map re-applies stored calibrations on every camera-idle event, and bumping
     * unconditionally would re-warp and re-encode every visible tile each time the user pans —
     * a constant CPU drain on a device that has to last a full day in a field.
     */
    fun setCalibration(layerId: String, transform: Affine2D?) {
        synchronized(layersLock) {
            val current = layers[layerId] ?: return
            if (current.calibration == transform) return
            layers[layerId] = current.copy(
                calibration = transform,
                generation = generationSeq.incrementAndGet(),
            )
            cache.evictLayer(layerId)
        }
    }

    fun calibrationOf(layerId: String): Affine2D? = synchronized(layersLock) { layers[layerId]?.calibration }

    /** Ids of every registered layer. */
    fun registeredLayers(): Set<String> = synchronized(layersLock) { layers.keys.toSet() }

    /**
     * Forgets a layer's in-memory tiles without touching its registration or generation.
     *
     * Used when the persistent cache behind a layer is deleted: without it the map would keep
     * drawing from RAM the very tiles the user just asked to remove, and the freed space would
     * look like it had no effect.
     */
    fun dropCachedTiles(layerId: String) = cache.evictLayer(layerId)

    // ------------------------------------------------------------------ serving

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (running.get()) Log.w(TAG, "accept() failed", e)
                continue
            }
            // Refuse anything that is not the app itself talking to itself.
            if (client.inetAddress?.isLoopbackAddress != true) {
                Log.w(TAG, "Dropping non-loopback connection from ${client.inetAddress}")
                runCatching { client.close() }
                continue
            }
            try {
                pool?.execute { handleSafely(client) } ?: runCatching { client.close() }
            } catch (e: RejectedExecutionException) {
                // Overloaded: dropping the connection is far better than unbounded queueing.
                runCatching { client.close() }
            }
        }
    }

    private fun handleSafely(client: Socket) {
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            client.tcpNoDelay = true
            handle(client)
        } catch (e: SocketException) {
            // Client hung up mid-tile; MapLibre does this constantly while panning.
        } catch (t: Throwable) {
            // A single bad tile must never take the pool thread (or the server) down.
            Log.w(TAG, "Unhandled error while serving a tile", t)
        } finally {
            runCatching { client.close() }
        }
    }

    private fun handle(client: Socket) {
        val input = client.getInputStream()
        val requestLine = readLine(input) ?: return
        // Drain headers; we need none of them, but the client expects them consumed.
        //
        // The header COUNT is capped, not just each header's length: the worker pool has four
        // threads, and a client dribbling one header every few seconds would otherwise hold a
        // thread for as long as it liked. Four such connections would stall every layer on the
        // map, and on Android any local app can reach the loopback port.
        var headerCount = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            if (++headerCount > MAX_HEADERS) {
                respond(client, 431, "text/plain", "too many headers".toByteArray(), headOnly = false)
                return
            }
        }

        val parts = requestLine.split(' ')
        if (parts.size < 2) {
            respond(client, 400, "text/plain", "bad request".toByteArray(), headOnly = false)
            return
        }
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')

        if (method != "GET" && method != "HEAD") {
            respond(client, 405, "text/plain", "method not allowed".toByteArray(), headOnly = false)
            return
        }
        val headOnly = method == "HEAD"

        when {
            path == "/healthz" -> respond(client, 200, "text/plain", "ok".toByteArray(), headOnly)
            path.startsWith("/t/") -> serveTile(client, path, headOnly)
            else -> respond(client, 404, "text/plain", "not found".toByteArray(), headOnly)
        }
    }

    private fun serveTile(client: Socket, path: String, headOnly: Boolean) {
        val request = parseTilePath(path)
        if (request == null) {
            respond(client, 400, "text/plain", "bad tile path".toByteArray(), headOnly)
            return
        }
        val (layerId, z, x, y) = request
        // The layer and its generation must be read together. Reading them separately allows a
        // worker to compose a tile with calibration C1 and then store it under the key of the
        // newer generation belonging to C2 -- a permanently misaligned tile that survives until
        // the calibration changes again.
        val layer = synchronized(layersLock) { layers[layerId] }
        if (layer == null) {
            respond(client, 404, "text/plain", "unknown layer".toByteArray(), headOnly)
            return
        }

        val key = "$layerId/${layer.generation}/$z/$x/$y"
        val cached = cache.get(key)
        val calibrated = layer.calibration != null && !layer.calibration.isIdentity()
        val contentType = if (calibrated) "image/png" else layer.archive.contentType

        val bytes = cached ?: try {
            CalibratedTileComposer.compose(layer.archive, z, x, y, layer.calibration)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build tile $layerId/$z/$x/$y", e)
            null
        }

        if (bytes == null) {
            // 204 rather than 404: MapLibre treats an empty 2xx as "no data here", and does not
            // retry it, whereas a 404 storms the log while panning outside the coverage.
            respond(client, 204, contentType, ByteArray(0), headOnly)
            return
        }
        if (cached == null) cache.put(key, bytes)
        respond(client, 200, contentType, bytes, headOnly)
    }

    private fun respond(client: Socket, status: Int, contentType: String, body: ByteArray, headOnly: Boolean) {
        val reason = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            // No Access-Control-Allow-Origin on purpose: MapLibre's native HTTP stack does not
            // need CORS, while a wildcard would let any web page the user opens read tiles from
            // 127.0.0.1 and fingerprint the app.
            append("Connection: close\r\n\r\n")
        }
        val out = BufferedOutputStream(client.getOutputStream(), 16 * 1024)
        out.write(header.toByteArray(Charsets.US_ASCII))
        if (!headOnly && body.isNotEmpty()) out.write(body)
        out.flush()
    }

    companion object {
        private const val TAG = "LocalTileServer"

        /** Loopback literal; never a hostname, so no DNS and no accidental external bind. */
        const val LOOPBACK = "127.0.0.1"

        /** 64 MB of decoded tiles is roughly two screens of history at 256 px. */
        const val DEFAULT_CACHE_BYTES = 64L * 1024 * 1024

        private const val BACKLOG = 32
        private const val QUEUE_CAPACITY = 64

        /**
         * Loopback round trips are sub-millisecond, so a client that cannot get its request
         * out in three seconds is not MapLibre and does not deserve a worker thread.
         */
        private const val SOCKET_TIMEOUT_MS = 3_000

        /** Header lines accepted per request before the connection is refused. */
        private const val MAX_HEADERS = 64

        /** Parsed `/t/{layerId}/{z}/{x}/{y}` request. */
        internal data class TileRequest(val layerId: String, val z: Int, val x: Int, val y: Int)

        /** Splits a tile path, tolerating a trailing extension such as `.png`. Null when malformed. */
        internal fun parseTilePath(path: String): TileRequest? {
            val segments = path.trim('/').split('/')
            if (segments.size != 5 || segments[0] != "t") return null
            val layerId = segments[1]
            if (layerId.isEmpty() || layerId.length > 128) return null
            val z = segments[2].toIntOrNull() ?: return null
            val x = segments[3].toIntOrNull() ?: return null
            val y = segments[4].substringBefore('.').toIntOrNull() ?: return null
            if (z < 0 || z > 30) return null
            val n = 1 shl z
            if (x < 0 || y < 0 || x >= n || y >= n) return null
            return TileRequest(layerId, z, x, y)
        }

        /** Reads one CRLF-terminated request/header line without buffering past it. */
        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder(128)
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().removeSuffix("\r")
                sb.append(b.toChar())
                if (sb.length > 8 * 1024) return null // Refuse absurd request lines.
            }
        }
    }
}

/**
 * Byte-budgeted LRU for encoded tiles.
 *
 * Why not `LruCache`: the android one is fine, but keeping the cache a plain JVM class means the
 * server's caching logic can be exercised in a unit test without Robolectric.
 */
internal class TileByteCache(private val maxBytes: Long) {

    private var currentBytes = 0L

    /**
     * Access-ordered so the iterator hands back the least recently used entry first. Eviction is
     * driven entirely by [put] rather than `removeEldestEntry`, because one insertion can require
     * several evictions when tile sizes differ wildly.
     */
    private val map = LinkedHashMap<String, ByteArray>(64, 0.75f, true)

    @Synchronized
    fun get(key: String): ByteArray? = map[key]

    @Synchronized
    fun put(key: String, value: ByteArray) {
        if (value.size > maxBytes) return
        val previous = map.put(key, value)
        currentBytes += value.size.toLong() - (previous?.size?.toLong() ?: 0L)
        val iterator = map.entries.iterator()
        while (currentBytes > maxBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            iterator.remove()
            currentBytes -= eldest.value.size.toLong()
        }
    }

    /** Drops every entry belonging to one layer; used when its calibration changes. */
    @Synchronized
    fun evictLayer(layerId: String) {
        val prefix = "$layerId/"
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.startsWith(prefix)) {
                currentBytes -= entry.value.size.toLong()
                iterator.remove()
            }
        }
    }

    @Synchronized
    fun clear() {
        map.clear()
        currentBytes = 0L
    }

    @Synchronized
    fun sizeBytes(): Long = currentBytes
}
