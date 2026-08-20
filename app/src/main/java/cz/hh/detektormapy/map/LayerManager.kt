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
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    private val catalog = MutableStateFlow(LayerCatalog())
    private val availability = MutableStateFlow<Map<String, String?>>(emptyMap())
    private val activeCalibrations = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Everything the layer panel needs, already sorted bottom-to-top. */
    val layers: StateFlow<List<LayerUiState>> =
        combine(catalog, prefs.state, availability, activeCalibrations) { cat, p, avail, calibs ->
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
    }

    fun calibrationOf(layerId: String): Affine2D? = server.calibrationOf(layerId)

    fun shutdown() {
        openArchives.values.forEach { runCatching { it.close() } }
        openArchives.clear()
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

    private fun openArchive(def: LayerDef, onProblem: (String) -> Unit): TileArchive? = try {
        when (def.kind) {
            LayerKind.PMTILES -> localFile(def, onProblem)?.let { PmTilesReader(it) }

            LayerKind.MBTILES -> localFile(def, onProblem)?.let { MbTilesReader(it) }

            LayerKind.XYZ -> XyzTileArchive(
                layerId = def.id,
                template = def.source,
                minZoom = def.minZoom,
                maxZoom = def.maxZoom,
                bounds = def.boundsBox(),
                contentType = if (def.source.endsWith(".jpeg") || def.source.endsWith(".jpg")) {
                    "image/jpeg"
                } else {
                    "image/png"
                },
                cacheDir = File(dirs.tilesCacheDir, def.id),
            )

            LayerKind.WMS -> WmsTileArchive(
                layerId = def.id,
                endpoint = def.source,
                wmsLayers = def.wmsLayers.orEmpty(),
                styles = def.wmsStyle.orEmpty(),
                format = def.wmsFormat,
                version = def.wmsVersion,
                minZoom = def.minZoom,
                maxZoom = def.maxZoom,
                bounds = def.boundsBox(),
                cacheDir = File(dirs.tilesCacheDir, def.id),
            )

            LayerKind.ARCGIS -> ArcGisTileArchive(
                layerId = def.id,
                endpoint = def.source,
                minZoom = def.minZoom,
                maxZoom = def.maxZoom,
                bounds = def.boundsBox(),
                transparent = def.arcgisFormat != "jpg",
                format = def.arcgisFormat,
                visibleLayers = def.arcgisLayers,
                cacheDir = File(dirs.tilesCacheDir, def.id),
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
