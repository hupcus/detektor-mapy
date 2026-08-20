package cz.hh.detektormapy.ui.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.calibration.PointPair
import cz.hh.detektormapy.data.AppDirectories
import cz.hh.detektormapy.data.entity.GcpPointEntity
import cz.hh.detektormapy.data.repository.CalibrationRepository
import cz.hh.detektormapy.data.repository.GcpRepository
import cz.hh.detektormapy.map.LayerManager
import cz.hh.detektormapy.util.BBox
import cz.hh.detektormapy.util.WebMercator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/** One half-finished pair: the user tapped the old map but not the ortophoto yet. */
data class PendingGcp(val srcX: Double, val srcY: Double)

data class GcpEditorState(
    val layerId: String = "",
    val layerTitle: String = "",
    val setId: Long? = null,
    val points: List<GcpPointEntity> = emptyList(),
    val pending: PendingGcp? = null,
    val transform: Affine2D? = null,
    val rmseM: Double = 0.0,
    val residualsM: List<Double> = emptyList(),
    val useSimilarity: Boolean = true,
    val previewApplied: Boolean = false,
    val message: String? = null,
    val exportedPath: String? = null,
) {
    val canFit: Boolean get() = points.size >= if (useSimilarity) 2 else 3
    val tpsAdvisable: Boolean get() = points.size >= 6
}

/**
 * Režim B -- the ground control point editor (issue F3-3).
 *
 * The app deliberately only computes affine/similarity transforms. A thin-plate spline over a
 * whole map sheet has to resample the raster, which is a desktop job; here we merely *collect*
 * the points and export them for `tools/warp_scan.py` (issue F3-4).
 */
@HiltViewModel
class GcpEditorViewModel @Inject constructor(
    private val gcpRepository: GcpRepository,
    private val calibrationRepository: CalibrationRepository,
    private val layerManager: LayerManager,
    private val dirs: AppDirectories,
) : ViewModel() {

    private val layerIdState = MutableStateFlow("")
    private val setIdState = MutableStateFlow<Long?>(null)
    private val pendingState = MutableStateFlow<PendingGcp?>(null)
    private val similarityState = MutableStateFlow(true)
    private val previewState = MutableStateFlow(false)
    private val messageState = MutableStateFlow<String?>(null)
    private val exportState = MutableStateFlow<String?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val pointsFlow = setIdState.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else gcpRepository.observePoints(id)
    }

    val state: StateFlow<GcpEditorState> = combine(
        layerIdState,
        setIdState,
        pointsFlow,
        pendingState,
        combine(similarityState, previewState, messageState, exportState) { sim, preview, msg, exp ->
            listOf(sim, preview, msg, exp)
        },
    ) { layerId, setId, points, pending, extras ->
        val similarity = extras[0] as Boolean
        val preview = extras[1] as Boolean
        val message = extras[2] as String?
        val exported = extras[3] as String?

        val pairs = points.map { PointPair(it.srcX, it.srcY, it.dstX, it.dstY) }
        val transform = fitFor(pairs, similarity)

        GcpEditorState(
            layerId = layerId,
            layerTitle = layerManager.definitionOf(layerId)?.title ?: layerId,
            setId = setId,
            points = points,
            pending = pending,
            transform = transform,
            rmseM = transform?.let { Affine2D.rmse(it, pairs) } ?: 0.0,
            residualsM = transform?.let { Affine2D.residuals(it, pairs) } ?: emptyList(),
            useSimilarity = similarity,
            previewApplied = preview,
            message = message,
            exportedPath = exported,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GcpEditorState())

    /** Where the map was last pointing; both panes open here, at the same scale. */
    val camera get() = layerManager.lastCamera

    /** Zoom limits of a layer, so a pane can be clamped into the range that has data. */
    fun zoomRangeOf(id: String): IntRange = layerManager.definitionOf(id)?.let { it.minZoom..it.maxZoom } ?: 0..19

    fun bind(layerId: String) {
        if (layerIdState.value == layerId && setIdState.value != null) return
        layerIdState.value = layerId
        // The editor can be reached without visiting the map first, so make sure the tile
        // server is up before the panes ask it for a URL.
        layerManager.ensureStarted()
        viewModelScope.launch {
            val existing = gcpRepository.getAllSets().firstOrNull { it.layerId == layerId }
            setIdState.value = existing?.id ?: gcpRepository.createSet(
                layerId = layerId,
                name = "GCP $layerId",
                imagePath = null,
                createdAt = System.currentTimeMillis(),
            )
        }
    }

    /** Step 1: the user tapped a recognisable feature on the historical overlay. */
    fun tapSource(lat: Double, lon: Double) {
        pendingState.value = PendingGcp(WebMercator.lonToMeters(lon), WebMercator.latToMeters(lat))
    }

    /** Step 2: the same feature on the ortophoto -- completes the pair. */
    fun tapTarget(lat: Double, lon: Double) {
        val pending = pendingState.value ?: run {
            messageState.value = "Nejdřív klepni na starou mapu"
            return
        }
        val setId = setIdState.value ?: return
        viewModelScope.launch {
            gcpRepository.addPoint(
                GcpPointEntity(
                    setId = setId,
                    srcX = pending.srcX,
                    srcY = pending.srcY,
                    dstX = WebMercator.lonToMeters(lon),
                    dstY = WebMercator.latToMeters(lat),
                    label = "",
                ),
            )
            pendingState.value = null
        }
    }

    fun cancelPending() {
        pendingState.value = null
    }

    fun removePoint(id: Long) = viewModelScope.launch { gcpRepository.deletePoint(id) }

    fun setSimilarity(enabled: Boolean) {
        similarityState.value = enabled
    }

    /** Applies the fitted transform to the live map so the user can judge it visually. */
    fun preview() {
        val transform = state.value.transform ?: return
        layerManager.applyCalibration(layerIdState.value, transform, null)
        previewState.value = true
    }

    fun clearPreview() {
        layerManager.applyCalibration(layerIdState.value, null, null)
        previewState.value = false
    }

    /** Persists the fitted transform as a normal calibration covering the GCP extent. */
    fun saveAsCalibration(label: String, nowMillis: Long = System.currentTimeMillis()) {
        val current = state.value
        val transform = current.transform ?: return
        val bbox = extentOf(current.points) ?: BBox.CZECHIA
        viewModelScope.launch {
            val id = calibrationRepository.save(
                layerId = current.layerId,
                label = label.ifBlank { "GCP ${current.points.size} bodů" },
                bbox = bbox,
                transform = transform,
                nowMillis = nowMillis,
            )
            layerManager.applyCalibration(current.layerId, transform, id)
            messageState.value = "Kalibrace uložena"
        }
    }

    /**
     * Exports the point set in the JSON shape `tools/warp_scan.py` reads (issue F3-4).
     * `px`/`py` are pixel coordinates of the scan; for a tile layer without a scan we emit the
     * source position in EPSG:3857 metres and say so in the note, so gdal_translate can still
     * be driven manually.
     */
    fun exportGcpJson(imageName: String?, imageWidth: Int, imageHeight: Int) {
        val current = state.value
        if (current.points.isEmpty()) {
            messageState.value = "Není co exportovat"
            return
        }
        viewModelScope.launch {
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())

            val gcps = current.points.joinToString(",\n    ") { point ->
                val lon = WebMercator.metersToLon(point.dstX)
                val lat = WebMercator.metersToLat(point.dstY)
                """{"px": ${point.srcX}, "py": ${point.srcY}, "lon": $lon, "lat": $lat}"""
            }
            val json = """
            {
              "image": "${imageName ?: "${current.layerId}.tif"}",
              "width": ${if (imageWidth > 0) imageWidth else 1},
              "height": ${if (imageHeight > 0) imageHeight else 1},
              "note": "${if (imageName == null) "px/py jsou EPSG:3857 metry, ne pixely" else ""}",
              "gcps": [
                $gcps
              ],
              "created": "$iso"
            }
            """.trimIndent()

            val file = File(dirs.exportsDir, "gcp-${current.layerId}-${System.currentTimeMillis()}.json")
            runCatching { file.writeText(json) }
                .onSuccess {
                    exportState.value = file.absolutePath
                    messageState.value = "Exportováno: ${file.name}"
                }
                .onFailure { messageState.value = "Export selhal: ${it.message}" }
        }
    }

    fun consumeMessage() {
        messageState.value = null
    }

    /** Tile URL for a pane; goes through the local server so panes share the tile cache. */
    fun urlTemplateFor(id: String): String? = layerManager.urlTemplateFor(id)

    private fun fitFor(pairs: List<PointPair>, similarity: Boolean): Affine2D? = when {
        similarity && pairs.size >= 2 -> Affine2D.fitSimilarity(pairs)
        !similarity && pairs.size >= 3 -> Affine2D.fitAffine(pairs)
        else -> null
    }

    private fun extentOf(points: List<GcpPointEntity>): BBox? {
        if (points.isEmpty()) return null
        val lons = points.map { WebMercator.metersToLon(it.dstX) }
        val lats = points.map { WebMercator.metersToLat(it.dstY) }
        val raw = BBox(lons.min(), lats.min(), lons.max(), lats.max())
        // Pad by 20 % so the calibration also covers the ground just outside the GCP hull.
        return raw.expand(1.2)
    }
}
