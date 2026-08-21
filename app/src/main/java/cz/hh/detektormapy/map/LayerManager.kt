package cz.hh.detektormapy.map

import android.util.Log
import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.data.AppDirectories
import cz.hh.detektormapy.di.ApplicationScope
import cz.hh.detektormapy.di.IoDispatcher
import cz.hh.detektormapy.map.pmtiles.MbTilesReader
import cz.hh.detektormapy.map.pmtiles.PmTilesReader
import cz.hh.detektormapy.map.pmtiles.TileArchive
import cz.hh.detektormapy.util.BBox
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the layer catalogue and the running [LocalTileServer].
 *
 * The whole map stack hangs off this class: `layers.json` is the only place a new historical
 * map has to be declared, and every raster the map draws is fetched from the local server, so
 * calibration and offline access are handled uniformly for local archives and online services.
 */
@Singleton
class LayerManager @Inject constructor(
    private val dirs: AppDirectories,
    private val prefs: LayerPreferences,
    private val json: Json,
    private val server: LocalTileServer,
    private val tileCache: TileCacheStore,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    private val catalog = MutableStateFlow(LayerCatalog())
    private val availability = MutableStateFlow<Map<String, String?>>(emptyMap())
    private val activeCalibrations = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * Mirror of the tile server's per-layer generation counters.
     *
     * The server already bumps a generation on every calibration change, but nothing downstream
     * could see it, so the map never learned that its tiles had gone stale. Mirroring it into a
     * flow makes the change part of [layers], which is what the map actually observes.
     */
    private val tileRevisions = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Everything the layer panel needs, already sorted bottom-to-top. */
    val layers: StateFlow<List<LayerUiState>> =
        combine(catalog, prefs.state, availability, activeCalibrations, tileRevisions) { cat, p, avail, calibs, revs ->
            cat.layers
                .map { def ->
                    val reason = avail[def.id]
                    LayerUiState(
                        def = def,
                        visible = p.visible[def.id] ?: def.enabledByDefault,
                        opacity = p.opacity[def.id] ?: def.defaultOpacity,
                        available = reason == null,
                        unavailableReason = reason,
                        activeCalibrationId = calibs[def.id],
                        tileRevision = revs[def.id] ?: 0,
                    )
                }
                .sortedBy { p.order[it.def.id] ?: it.def.order }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val settings: StateFlow<LayerPreferences.State> =
        prefs.state.stateIn(scope, SharingStarted.Eagerly, LayerPreferences.State())

    /**
     * Concurrent, not plain HashMaps: [protectedAreaAt] is called from the GPS fix collector on
     * the main thread while [reload] rebuilds these from the IO dispatcher.
     */
    private val openArchives = java.util.concurrent.ConcurrentHashMap<String, TileArchive>()

    /** Raw GeoJSON text per layer, for the kinds MapLibre renders directly. */
    private val geoJsonLayers = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Parsed polygons per layer, so "am I inside ÚAN" is a cheap lookup on every fix. */
    private val polygonIndexes = java.util.concurrent.ConcurrentHashMap<String, PolygonIndex>()

    /** Serialises [reload]; two concurrent runs would close each other's archives. */
    private val reloadMutex = kotlinx.coroutines.sync.Mutex()

    /** GeoJSON payloads currently loaded, keyed by layer id. */
    val geoJson: StateFlow<Map<String, String>> = geoJsonLayers

    private val lastCameraState = MutableStateFlow(MapCamera.CZECHIA)

    /**
     * Where the user last had the map, as centre + zoom rather than a bounding box.
     *
     * Calibration screens open here instead of at a fixed point in the middle of the country --
     * you calibrate the area you are standing in. Centre+zoom rather than bounds because the
     * calibration panes are half-height: fitting the main map's bounds into them drops roughly
     * two zoom levels, which is enough to fall below an offline layer's minimum zoom and show
     * an empty pane.
     */
    val lastCamera: StateFlow<MapCamera> = lastCameraState

    fun rememberCamera(lat: Double, lon: Double, zoom: Double) {
        lastCameraState.value = MapCamera(lat, lon, zoom)
    }

    @Volatile
    private var started = false

    init {
        // The cache is consulted on tile-server worker threads, which cannot suspend to read a
        // DataStore, so the two switches are mirrored into plain fields as they change.
        scope.launch {
            prefs.state
                .map { it.cacheTiles to it.cacheExcluded }
                .collect { (enabled, excluded) -> tileCache.applySettings(enabled, excluded) }
        }
    }

    /** Idempotent. Safe to call from every `MapScreen` composition. */
    fun ensureStarted() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch(io) {
            runCatching { server.start() }
                .onFailure { Log.e(TAG, "Lokální dlaždicový server se nepodařilo spustit", it) }
            tileCache.recheckFreeSpace()
            tileCache.purgeLegacyCache()
            reload()
        }
    }

    /** Re-reads `layers.json` from disk and (re)registers every archive with the server. */
    suspend fun reload() = withContext(io) {
        reloadMutex.withLock {
            reloadLocked()
        }
    }

    private fun reloadLocked() {
        val loaded = readCatalog()
        catalog.value = loaded

        val problems = mutableMapOf<String, String?>()
        // The previous archives stay open and registered until the replacements are in place,
        // so there is never a window where the server hands out a URL backed by a closed file.
        val previousArchives = openArchives.toMap()
        val freshArchives = mutableMapOf<String, TileArchive>()
        polygonIndexes.clear()
        val geo = mutableMapOf<String, String>()

        loaded.layers.forEach { def ->
            if (def.kind == LayerKind.GEOJSON) {
                val file = runCatching { dirs.layerFile(def.source) }.getOrNull()
                if (file == null) {
                    problems[def.id] = "neplatná cesta"
                    return@forEach
                }
                if (file.exists()) {
                    val text = runCatching { file.readText() }.getOrNull()
                    if (text != null) {
                        geo[def.id] = text
                        polygonIndexes[def.id] = PolygonIndex.parse(text)
                        problems[def.id] = null
                    } else {
                        problems[def.id] = "soubor nelze přečíst"
                    }
                } else {
                    problems[def.id] = "soubor chybí"
                }
                return@forEach
            }

            val archive = openArchive(def) { problems[def.id] = it }
            if (archive != null) {
                freshArchives[def.id] = archive
                server.register(def.id, archive)
                problems[def.id] = null
            }
        }

        // Layers that vanished from layers.json must stop being served, otherwise the server
        // keeps answering for an archive nobody owns any more.
        (previousArchives.keys - freshArchives.keys).forEach { server.unregister(it) }

        openArchives.clear()
        openArchives.putAll(freshArchives)
        previousArchives.values.forEach { runCatching { it.close() } }

        geoJsonLayers.value = geo
        availability.value = problems
        // Re-registering an archive bumps its generation too, so the map has to be told; a
        // reload that swapped a .pmtiles file would otherwise keep serving the old pixels.
        tileRevisions.value = freshArchives.keys.associateWith { server.generationOf(it) }
    }

    /** Base URL template for MapLibre, or null when the layer cannot currently be served. */
    fun urlTemplateFor(layerId: String): String? = if (server.isRunning && layerId in server.registeredLayers()) {
        server.urlTemplate(layerId)
    } else {
        null
    }

    fun definitionOf(layerId: String): LayerDef? = catalog.value.layers.firstOrNull { it.id == layerId }

    fun setVisible(layerId: String, visible: Boolean) = scope.launch { prefs.setVisible(layerId, visible) }

    fun setOpacity(layerId: String, opacity: Float) = scope.launch { prefs.setOpacity(layerId, opacity) }

    fun setOrder(layerId: String, order: Int) = scope.launch { prefs.setOrder(layerId, order) }

    fun setOrders(orders: Map<String, Int>) = scope.launch { prefs.setOrders(orders) }

    fun setRotateWithCompass(enabled: Boolean) = scope.launch { prefs.setRotateWithCompass(enabled) }

    fun setFollowMode(enabled: Boolean) = scope.launch { prefs.setFollowMode(enabled) }

    fun setKeepScreenOn(enabled: Boolean) = scope.launch { prefs.setKeepScreenOn(enabled) }

    fun setShowFinds(enabled: Boolean) = scope.launch { prefs.setShowFinds(enabled) }

    fun setShowPlaces(enabled: Boolean) = scope.launch { prefs.setShowPlaces(enabled) }

    fun setShowAreas(enabled: Boolean) = scope.launch { prefs.setShowAreas(enabled) }

    /**
     * Applies (or clears) the runtime calibration of a layer. The server bumps its cache
     * generation internally, so the next MapLibre tile request already gets warped pixels.
     */
    fun applyCalibration(layerId: String, transform: Affine2D?, calibrationId: Long?) {
        server.setCalibration(layerId, transform)
        activeCalibrations.value = activeCalibrations.value.toMutableMap().apply {
            if (calibrationId == null) remove(layerId) else put(layerId, calibrationId)
        }
        publishTileRevision(layerId)
    }

    /**
     * Republishes a layer's tile generation. A no-op calibration leaves the generation alone, so
     * the resulting map compares equal and the flow stays silent -- which matters, because the
     * map re-applies stored calibrations on every camera-idle event.
     */
    private fun publishTileRevision(layerId: String) {
        val generation = server.generationOf(layerId)
        val current = tileRevisions.value
        if (current[layerId] == generation) return
        tileRevisions.value = current + (layerId to generation)
    }

    fun calibrationOf(layerId: String): Affine2D? = server.calibrationOf(layerId)

    /**
     * Stitches the pixels behind the live calibration ghost (Režim A).
     *
     * Reads the archive directly rather than the tile server on purpose: the ghost is positioned
     * by its four corners, so it needs the *uncalibrated* image and applies the whole pending
     * transform geometrically. Going through the server would bake the stored calibration into
     * the pixels and then transform them a second time.
     *
     * @param visibleM viewport as `[west, south, east, north]` in EPSG:3857 metres
     */
    suspend fun renderOverlaySnapshot(layerId: String, visibleM: DoubleArray, zoom: Int): OverlaySnapshot? =
        withContext(io) {
            val archive = openArchives[layerId] ?: return@withContext null
            val rect = OverlayMosaic.sourceRect(visibleM, server.calibrationOf(layerId))
            // A remote layer fetches each of these tiles over the network, one at a time, so it
            // gets a smaller budget -- waiting half a minute for the ghost to appear would be
            // worse than the slightly tighter margin it can be dragged within.
            val local = definitionOf(layerId)?.kind.let { it == LayerKind.PMTILES || it == LayerKind.MBTILES }
            val budget = if (local) OverlayMosaic.MAX_TILES else OverlayMosaic.MAX_REMOTE_TILES
            val plan = OverlayMosaic.plan(rect, zoom, archive.minZoom, archive.maxZoom, budget)
                ?: return@withContext null
            val bitmap = OverlayMosaic.render(archive, plan) ?: return@withContext null
            OverlaySnapshot(bitmap, plan.boundsMeters())
        }

    // --- offline tile cache ---------------------------------------------------------

    fun setCacheTiles(enabled: Boolean) = scope.launch { prefs.setCacheTiles(enabled) }

    fun setCacheLayer(layerId: String, enabled: Boolean) = scope.launch { prefs.setCacheLayer(layerId, enabled) }

    /**
     * Deletes one layer's cache without a restart.
     *
     * No unregister/re-register dance is needed: the archive registered with the server is a
     * [CachedTileArchive] that asks [TileCacheStore] for a handle on each tile, so dropping the
     * file underneath it is enough. The server's in-memory tiles are dropped too, otherwise the
     * map would keep drawing from RAM what the user just asked to delete.
     */
    suspend fun clearCache(layerId: String): Boolean = withContext(io) {
        val deleted = tileCache.clear(layerId)
        server.dropCachedTiles(layerId)
        deleted
    }

    /** Deletes every layer's cache. Returns how many files were removed. */
    suspend fun clearAllCaches(): Int = withContext(io) {
        val removed = tileCache.clearAll()
        openArchives.keys.forEach { server.dropCachedTiles(it) }
        removed
    }

    /** Cache sizes per layer id, in bytes, largest first. */
    suspend fun cacheSizes(): Map<String, Long> = withContext(io) { tileCache.sizes() }

    /** Size of a layer's own offline archive (PMTiles/MBTiles/GeoJSON), 0 for online-only layers. */
    suspend fun archiveSize(def: LayerDef): Long = withContext(io) {
        if (!def.isLocal) return@withContext 0L
        runCatching { dirs.layerFile(def.source) }.getOrNull()?.takeIf { it.isFile }?.length() ?: 0L
    }

    suspend fun freeSpaceBytes(): Long = withContext(io) { tileCache.freeBytes() }

    val cacheLowSpace: StateFlow<Boolean> get() = tileCache.lowSpace

    fun shutdown() {
        openArchives.values.forEach { runCatching { it.close() } }
        openArchives.clear()
        tileCache.shutdown()
        runCatching { server.stop() }
        started = false
    }

    // --- internals -----------------------------------------------------------------

    private fun readCatalog(): LayerCatalog {
        val file = dirs.layersCatalogFile
        if (!file.exists()) {
            writeDefaultCatalog(file)
            return DefaultLayers.catalog
        }
        val onDisk = try {
            json.decodeFromString(LayerCatalog.serializer(), file.readText())
        } catch (e: Exception) {
            // A hand-edited catalogue with a typo must not brick the map.
            Log.e(TAG, "layers.json je poškozený, používám výchozí katalog", e)
            return DefaultLayers.catalog
        }
        // A version bump in DefaultLayers means new built-in layers; without this merge they
        // would only ever reach fresh installs, because the file on disk wins once written.
        val merged = mergeCatalogs(onDisk, DefaultLayers.catalog)
        if (merged !== onDisk) {
            try {
                file.writeText(json.encodeToString(LayerCatalog.serializer(), merged))
            } catch (e: IOException) {
                Log.w(TAG, "Nelze zapsat sloučený layers.json", e)
            }
        }
        return merged
    }

    private fun writeDefaultCatalog(file: File) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(LayerCatalog.serializer(), DefaultLayers.catalog))
        } catch (e: IOException) {
            Log.w(TAG, "Nelze zapsat výchozí layers.json", e)
        }
    }

    /**
     * Puts the persistent tile cache in front of a remote archive.
     *
     * Every online layer gets one, unconditionally: the switches are honoured inside
     * [TileCacheStore] instead, so flipping "Ukládat mapy" does not have to rebuild the whole
     * layer stack and tiles already on disk keep working.
     */
    private fun cached(def: LayerDef, remote: TileArchive): TileArchive = CachedTileArchive(remote, def.id, tileCache)

    private fun openArchive(def: LayerDef, onProblem: (String) -> Unit): TileArchive? = try {
        when (def.kind) {
            LayerKind.PMTILES -> localFile(def, onProblem)?.let { PmTilesReader(it) }

            LayerKind.MBTILES -> localFile(def, onProblem)?.let { MbTilesReader(it) }

            LayerKind.XYZ -> cached(
                def,
                XyzTileArchive(
                    template = def.source,
                    minZoom = def.minZoom,
                    maxZoom = def.maxZoom,
                    bounds = def.boundsBox(),
                    contentType = if (def.source.endsWith(".jpeg") || def.source.endsWith(".jpg")) {
                        "image/jpeg"
                    } else {
                        "image/png"
                    },
                ),
            )

            LayerKind.WMS -> cached(
                def,
                WmsTileArchive(
                    endpoint = def.source,
                    wmsLayers = def.wmsLayers.orEmpty(),
                    styles = def.wmsStyle.orEmpty(),
                    format = def.wmsFormat,
                    version = def.wmsVersion,
                    minZoom = def.minZoom,
                    maxZoom = def.maxZoom,
                    bounds = def.boundsBox(),
                ),
            )

            LayerKind.ARCGIS -> cached(
                def,
                ArcGisTileArchive(
                    endpoint = def.source,
                    minZoom = def.minZoom,
                    maxZoom = def.maxZoom,
                    bounds = def.boundsBox(),
                    transparent = def.arcgisFormat != "jpg",
                    format = def.arcgisFormat,
                    visibleLayers = def.arcgisLayers,
                ),
            )

            // Vector, GeoJSON and single-image layers are wired straight into the MapLibre
            // style instead of going through the tile server.
            LayerKind.VECTOR, LayerKind.GEOJSON, LayerKind.IMAGE -> null
        }
    } catch (t: Throwable) {
        // Throwable, not Exception: a crafted PMTiles header can provoke an OutOfMemoryError,
        // which is an Error. Letting it escape would take the whole process down every time
        // the map screen opens, leaving the app unusable until the file is deleted over USB.
        Log.w(TAG, "Vrstvu ${def.id} nelze otevřít", t)
        onProblem(t.message ?: "nelze otevřít")
        null
    }

    /**
     * Resolves a local layer file, reporting *why* it is unusable.
     *
     * Reporting matters: without it a declared-but-missing PMTiles archive looks perfectly
     * available in the layer panel, the user flips the switch, and nothing happens with no
     * explanation. Now the row says "soubor chybí" and the switch is disabled.
     */
    private fun localFile(def: LayerDef, onProblem: (String) -> Unit): File? {
        val file = runCatching { dirs.layerFile(def.source) }.getOrNull() ?: run {
            Log.w(TAG, "Vrstva ${def.id} má cestu mimo adresář layers: ${def.source}")
            onProblem("neplatná cesta")
            return null
        }
        return if (file.exists()) {
            file
        } else {
            Log.i(TAG, "Soubor vrstvy ${def.id} chybí: ${file.absolutePath}")
            onProblem("soubor chybí — vytvoř přes tools/")
            null
        }
    }

    private companion object {
        const val TAG = "LayerManager"
    }
}

/** Convenience conversion of the raw `bounds` list in `layers.json` to a [BBox]. */
fun LayerDef.boundsBox(): BBox? {
    val b = bounds ?: return null
    if (b.size != 4) return null
    return runCatching { BBox(b[0], b[1], b[2], b[3]) }.getOrNull()
}

/** Camera position remembered across screens. */
data class MapCamera(val lat: Double, val lon: Double, val zoom: Double) {
    companion object {
        val CZECHIA = MapCamera(BBox.CZECHIA.centerLat, BBox.CZECHIA.centerLon, 7.0)
    }
}
