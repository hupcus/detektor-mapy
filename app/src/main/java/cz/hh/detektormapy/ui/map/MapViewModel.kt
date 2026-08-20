package cz.hh.detektormapy.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.data.entity.PlaceEntity
import cz.hh.detektormapy.data.entity.SearchedAreaEntity
import cz.hh.detektormapy.data.model.AreaStatus
import cz.hh.detektormapy.data.model.PlaceType
import cz.hh.detektormapy.data.repository.AreasRepository
import cz.hh.detektormapy.data.repository.CalibrationRepository
import cz.hh.detektormapy.data.repository.FindsRepository
import cz.hh.detektormapy.data.repository.PlacesRepository
import cz.hh.detektormapy.data.repository.TracksRepository
import cz.hh.detektormapy.location.CompassProvider
import cz.hh.detektormapy.location.Fix
import cz.hh.detektormapy.location.FixQuality
import cz.hh.detektormapy.location.LocationProvider
import cz.hh.detektormapy.location.TrackRecordingService
import cz.hh.detektormapy.map.LayerManager
import cz.hh.detektormapy.util.BBox
import cz.hh.detektormapy.util.Geo
import cz.hh.detektormapy.util.WebMercator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * State holder for the map screen.
 *
 * Everything the map draws funnels through here so the composable stays a pure renderer:
 * layer visibility, GPS, compass, the pins, the searched-area polygons, and the two
 * calibration modes from PLAN.md section 6.
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val layerManager: LayerManager,
    private val locationProvider: LocationProvider,
    private val compassProvider: CompassProvider,
    private val findsRepository: FindsRepository,
    private val placesRepository: PlacesRepository,
    private val areasRepository: AreasRepository,
    private val tracksRepository: TracksRepository,
    private val calibrationRepository: CalibrationRepository,
) : ViewModel() {

    private val fixState = MutableStateFlow<Fix?>(null)
    private val headingState = MutableStateFlow<Float?>(null)
    private val modeState = MutableStateFlow(MapMode.NAVIGATE)
    private val calibrationLayerState = MutableStateFlow<String?>(null)
    private val pendingTransform = MutableStateFlow(Affine2D.IDENTITY)
    private val drawingState = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    private val navigateTargetState = MutableStateFlow<PlaceEntity?>(null)
    private val messageState = MutableStateFlow<String?>(null)

    /**
     * Keyed by layer id: `refreshAllCalibrations` fans out one coroutine per visible overlay,
     * and a single shared slot would end up showing whichever of them happened to finish last.
     */
    private val calibrationLabels = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Hold-to-peek. Deliberately NOT persisted: it is a momentary look, and routing it through
     * DataStore would mean a disk write every time the user glances at the modern map.
     */
    private val peekState = MutableStateFlow(false)

    /**
     * Opacity while the user is dragging a slider.
     *
     * The persisted value lives in DataStore, which is a disk write — dragging a slider would
     * mean roughly sixty of them per second. The live value is applied to the map immediately
     * and written through only when the drag ends.
     */
    private val liveOpacity = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Which overlay the on-map opacity strip controls; null means the topmost visible one. */
    private val opacityTargetState = MutableStateFlow<String?>(null)

    /**
     * Flipped by the map screen once the runtime permission dialog comes back. The ViewModel is
     * constructed during composition, i.e. *before* that dialog is even shown, so without this
     * the location flow would complete immediately on a fresh install and the user would see no
     * position until they restarted the app.
     */
    private val permissionState = MutableStateFlow(locationProvider.hasPermission())

    /** Last viewport reported by the map; the anchor for "save calibration for this area". */
    @Volatile
    private var viewport: BBox = BBox.CZECHIA

    val state: StateFlow<MapUiState> = combineAll()

    init {
        layerManager.ensureStarted()
        observeLocation()
        observeCompass()
    }

    // --- camera / viewport -----------------------------------------------------------

    fun onViewportChanged(bbox: BBox, zoom: Double) {
        viewport = bbox
        layerManager.rememberCamera(bbox.centerLat, bbox.centerLon, zoom)
        val layerId = calibrationLayerState.value
        if (modeState.value != MapMode.CALIBRATE && layerId != null) {
            refreshCalibrationFor(layerId, bbox.centerLat, bbox.centerLon)
        }
    }

    fun currentViewport(): BBox = viewport

    fun setFollowMode(enabled: Boolean) = layerManager.setFollowMode(enabled)

    fun setRotateWithCompass(enabled: Boolean) = layerManager.setRotateWithCompass(enabled)

    fun setShowFinds(enabled: Boolean) = layerManager.setShowFinds(enabled)

    fun setShowPlaces(enabled: Boolean) = layerManager.setShowPlaces(enabled)

    fun setShowAreas(enabled: Boolean) = layerManager.setShowAreas(enabled)

    // --- layers ----------------------------------------------------------------------

    fun setLayerVisible(layerId: String, visible: Boolean) = layerManager.setVisible(layerId, visible)

    /** Live update while dragging: applied to the map, not written to disk. */
    fun setLayerOpacity(layerId: String, opacity: Float) {
        liveOpacity.value = liveOpacity.value + (layerId to opacity.coerceIn(0f, 1f))
    }

    /** Called when the drag ends; this is the only place the value reaches DataStore. */
    fun commitLayerOpacity(layerId: String) {
        val value = liveOpacity.value[layerId] ?: return
        layerManager.setOpacity(layerId, value)
    }

    /** Cycles the on-map strip through the visible overlays. */
    fun cycleOpacityTarget() {
        val candidates = state.value.overlayLayers
            .filter { it.visible && it.available && it.def.isRaster }
            .map { it.def.id }
        if (candidates.size < 2) return
        val current = opacityTargetState.value
        val index = candidates.indexOf(current)
        opacityTargetState.value = candidates[(index + 1) % candidates.size]
    }

    fun moveLayer(layerId: String, newOrder: Int) = layerManager.setOrder(layerId, newOrder)

    fun urlTemplateFor(layerId: String): String? = layerManager.urlTemplateFor(layerId)

    fun reloadLayers() = viewModelScope.launch { layerManager.reload() }

    // --- calibration, Režim A --------------------------------------------------------

    /**
     * Enters calibration mode for [layerId]. From now on two-finger gestures move only that
     * overlay; the basemap stays exactly where it is, which is the whole point -- the user
     * drags the 1840s map until the pond matches the pond they are standing next to.
     */
    fun startCalibration(layerId: String) {
        calibrationLayerState.value = layerId
        pendingTransform.value = layerManager.calibrationOf(layerId) ?: Affine2D.IDENTITY
        modeState.value = MapMode.CALIBRATE
    }

    fun cancelCalibration() {
        val layerId = calibrationLayerState.value
        modeState.value = MapMode.NAVIGATE
        if (layerId != null) {
            // Restore whatever was persisted for the current position.
            refreshCalibrationFor(layerId, viewport.centerLat, viewport.centerLon)
        }
        pendingTransform.value = Affine2D.IDENTITY
        calibrationLayerState.value = null
    }

    /**
     * Applies an incremental similarity nudge coming from the gesture detector. [dxMeters] and
     * [dyMeters] are already converted to EPSG:3857 metres by the caller, so the calibration is
     * zoom independent.
     */
    fun nudgeCalibration(
        pivotLat: Double,
        pivotLon: Double,
        dxMeters: Double,
        dyMeters: Double,
        rotationRad: Double,
        scale: Double,
    ) {
        val layerId = calibrationLayerState.value ?: return
        val pivotX = WebMercator.lonToMeters(pivotLon)
        val pivotY = WebMercator.latToMeters(pivotLat)
        val step = Affine2D.similarity(pivotX, pivotY, dxMeters, dyMeters, rotationRad, scale)
        val combined = step.concat(pendingTransform.value)
        pendingTransform.value = combined
        layerManager.applyCalibration(layerId, combined, null)
    }

    fun resetPendingCalibration() {
        val layerId = calibrationLayerState.value ?: return
        pendingTransform.value = Affine2D.IDENTITY
        layerManager.applyCalibration(layerId, Affine2D.IDENTITY, null)
    }

    /** Persists the current nudge for the current viewport (PLAN.md F3-1). */
    fun saveCalibration(label: String, nowMillis: Long = System.currentTimeMillis()) {
        val layerId = calibrationLayerState.value ?: return
        val transform = pendingTransform.value
        viewModelScope.launch {
            val id = calibrationRepository.save(
                layerId = layerId,
                label = label.ifBlank { defaultCalibrationLabel(nowMillis) },
                bbox = viewport,
                transform = transform,
                nowMillis = nowMillis,
            )
            layerManager.applyCalibration(layerId, transform, id)
            setCalibrationLabel(layerId, label.ifBlank { defaultCalibrationLabel(nowMillis) })
            modeState.value = MapMode.NAVIGATE
            calibrationLayerState.value = null
            // Reset the pending nudge too, otherwise the bar would still consider the layer
            // "dirty" after a successful save.
            pendingTransform.value = Affine2D.IDENTITY
            messageState.value = "Kalibrace uložena"
        }
    }

    /** Looks up and applies the tightest stored calibration containing the given position. */
    fun refreshCalibrationFor(layerId: String, lat: Double, lon: Double) {
        viewModelScope.launch {
            val best = calibrationRepository.getBestCalibrationFor(layerId, lat, lon)
            if (best == null) {
                layerManager.applyCalibration(layerId, null, null)
                setCalibrationLabel(layerId, null)
            } else {
                layerManager.applyCalibration(
                    layerId,
                    Affine2D(best.m0, best.m1, best.m2, best.m3, best.m4, best.m5),
                    best.id,
                )
                setCalibrationLabel(layerId, best.label)
            }
        }
    }

    /** Re-applies stored calibrations for every visible overlay after the camera settled. */
    fun refreshAllCalibrations(lat: Double, lon: Double) {
        if (modeState.value == MapMode.CALIBRATE) return
        layerManager.layers.value
            .filter { it.visible && it.def.isRaster && !it.def.isBasemap }
            .forEach { refreshCalibrationFor(it.def.id, lat, lon) }
    }

    // --- places, finds, areas --------------------------------------------------------

    fun addPlace(lat: Double, lon: Double, type: PlaceType, title: String, note: String) {
        viewModelScope.launch {
            placesRepository.add(
                PlaceEntity(
                    lat = lat,
                    lon = lon,
                    type = type,
                    title = title.ifBlank { type.label },
                    note = note,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun navigateTo(place: PlaceEntity?) {
        navigateTargetState.value = place
    }

    /** Distance and bearing to the active navigation target, or null. */
    fun navigationHint(): Triple<Double, Double, String>? {
        val target = navigateTargetState.value ?: return null
        val fix = fixState.value ?: return null
        val distance = Geo.distanceM(fix.lat, fix.lon, target.lat, target.lon)
        val bearing = Geo.bearingDeg(fix.lat, fix.lon, target.lat, target.lon)
        return Triple(distance, bearing, Geo.compassLabel(bearing))
    }

    fun startDrawingArea() {
        drawingState.value = emptyList()
        modeState.value = MapMode.DRAW_AREA
    }

    fun addDrawingPoint(lat: Double, lon: Double) {
        if (modeState.value != MapMode.DRAW_AREA) return
        drawingState.value = drawingState.value + (lat to lon)
    }

    fun undoDrawingPoint() {
        drawingState.value = drawingState.value.dropLast(1)
    }

    fun cancelDrawing() {
        drawingState.value = emptyList()
        modeState.value = MapMode.NAVIGATE
    }

    fun finishDrawingArea(name: String, nowMillis: Long = System.currentTimeMillis()) {
        val ring = drawingState.value
        if (ring.size < 3) {
            messageState.value = "Zóna potřebuje aspoň 3 body"
            return
        }
        viewModelScope.launch {
            areasRepository.add(
                SearchedAreaEntity(
                    name = name.ifBlank { "Zóna ${ring.size} bodů" },
                    polygonGeoJson = ringToGeoJson(ring),
                    createdAt = nowMillis,
                    status = AreaStatus.ROZPRACOVANO,
                    areaHa = Geo.polygonAreaHa(ring),
                ),
            )
            drawingState.value = emptyList()
            modeState.value = MapMode.NAVIGATE
            messageState.value = "Zóna uložena"
        }
    }

    /**
     * Momentarily hides the historical overlays so the user can check what is actually there.
     *
     * Implemented as opacity, never as visibility: turning a layer off removes its source from
     * the style, so restoring it would re-fetch the whole viewport. Opacity is a paint property
     * -- pure GPU, no tiles, no network.
     */
    fun setPeek(on: Boolean) {
        peekState.value = on
    }

    fun consumeMessage() {
        messageState.value = null
    }

    // --- internals -------------------------------------------------------------------

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeLocation() {
        viewModelScope.launch {
            permissionState
                .flatMapLatest { granted ->
                    if (granted) locationProvider.fixes() else kotlinx.coroutines.flow.emptyFlow()
                }
                .collect { fix -> fixState.value = fix }
        }
        refreshLastKnown()
    }

    /** Called by the map screen after the permission dialog resolves. */
    fun onLocationPermissionResult(granted: Boolean) {
        permissionState.value = granted
        if (granted) refreshLastKnown()
    }

    private fun refreshLastKnown() {
        locationProvider.lastKnown()?.let { fixState.value = it }
    }

    /** Opens the waypoint the user picked in the "Místa" list (deep link via savedStateHandle). */
    fun navigateToPlaceId(placeId: Long) {
        viewModelScope.launch {
            navigateTargetState.value = placesRepository.getPlace(placeId)
        }
    }

    fun startRecording(context: android.content.Context) = TrackRecordingService.start(context)

    fun stopRecording(context: android.content.Context) = TrackRecordingService.stop(context)

    fun markFindFromService(context: android.content.Context) = TrackRecordingService.markFind(context)

    private fun observeCompass() {
        viewModelScope.launch {
            compassProvider.headings { fixState.value }.collect { headingState.value = it }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun combineAll(): StateFlow<MapUiState> {
        val pins = combine(
            findsRepository.observeAll(),
            placesRepository.observeAll(),
            areasRepository.observeAll(),
        ) { finds, places, areas -> Triple(finds, places, areas) }

        val track = tracksRepository.observeActive().flatMapLatest { active ->
            if (active == null) {
                kotlinx.coroutines.flow.flowOf(emptyList<Pair<Double, Double>>() to false)
            } else {
                tracksRepository.observePoints(active.id)
                    .map { points -> points.map { it.lat to it.lon } to true }
            }
        }

        val calibration = combine(
            modeState,
            calibrationLayerState,
            pendingTransform,
            calibrationLabels,
        ) { mode, layerId, transform, labels ->
            CalibrationSnapshot(
                mode = mode,
                layerId = layerId,
                dirty = !transform.isIdentity(),
                // In calibration mode show the layer being adjusted; otherwise summarise.
                label = layerId?.let { labels[it] } ?: labels.values.firstOrNull(),
            )
        }

        val interaction = combine(
            drawingState,
            navigateTargetState,
            messageState,
            peekState,
            opacityTargetState,
        ) { drawing, target, message, peeking, opacityTarget ->
            InteractionSnapshot(drawing, target, message, peeking, opacityTarget)
        }

        val locationSnapshot = combine(fixState, headingState) { fix, heading ->
            LocationSnapshot(fix, FixQuality.of(fix?.accuracyM), heading)
        }

        return combine(
            layerManager.layers,
            layerManager.settings,
            pins,
            track,
            calibration,
            interaction,
            locationSnapshot,
            layerManager.geoJson,
            liveOpacity,
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val layers = values[0] as List<cz.hh.detektormapy.map.LayerUiState>
            val settings = values[1] as cz.hh.detektormapy.map.LayerPreferences.State

            @Suppress("UNCHECKED_CAST")
            val pinsValue = values[2] as Triple<
                List<cz.hh.detektormapy.data.entity.FindEntity>,
                List<PlaceEntity>,
                List<SearchedAreaEntity>,
                >

            @Suppress("UNCHECKED_CAST")
            val trackValue = values[3] as Pair<List<Pair<Double, Double>>, Boolean>
            val calibrationValue = values[4] as CalibrationSnapshot
            val interactionValue = values[5] as InteractionSnapshot
            val locationValue = values[6] as LocationSnapshot

            @Suppress("UNCHECKED_CAST")
            val geoJsonValue = values[7] as Map<String, String>

            @Suppress("UNCHECKED_CAST")
            val liveOpacityValue = values[8] as Map<String, Float>

            MapUiState(
                // The live drag value wins over the persisted one until the drag ends, so the
                // map and the slider never disagree mid-gesture.
                layers = layers.map { layer ->
                    liveOpacityValue[layer.def.id]
                        ?.let { layer.copy(opacity = it) }
                        ?: layer
                },
                fix = locationValue.fix,
                fixQuality = locationValue.quality,
                headingDeg = locationValue.heading,
                followMode = settings.followMode,
                rotateWithCompass = settings.rotateWithCompass,
                keepScreenOn = settings.keepScreenOn,
                showFinds = settings.showFinds,
                showPlaces = settings.showPlaces,
                showAreas = settings.showAreas,
                mode = calibrationValue.mode,
                calibrationLayerId = calibrationValue.layerId,
                calibrationDirty = calibrationValue.dirty,
                activeCalibrationLabel = calibrationValue.label,
                finds = pinsValue.first,
                places = pinsValue.second,
                areas = pinsValue.third,
                trackPoints = trackValue.first,
                recording = trackValue.second,
                navigateTarget = interactionValue.target,
                drawingPoints = interactionValue.drawing,
                message = interactionValue.message,
                locationPermissionGranted = locationProvider.hasPermission(),
                geoJsonPayloads = geoJsonValue,
                peeking = interactionValue.peeking,
                opacityTarget = interactionValue.opacityTarget,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUiState())
    }

    private fun setCalibrationLabel(layerId: String, label: String?) {
        calibrationLabels.value = calibrationLabels.value.toMutableMap().apply {
            if (label == null) remove(layerId) else put(layerId, label)
        }
    }

    private fun defaultCalibrationLabel(nowMillis: Long): String {
        val fmt = java.text.SimpleDateFormat("d.M. HH:mm", java.util.Locale.forLanguageTag("cs"))
        return "Kalibrace ${fmt.format(java.util.Date(nowMillis))}"
    }

    private data class CalibrationSnapshot(
        val mode: MapMode,
        val layerId: String?,
        val dirty: Boolean,
        val label: String?,
    )

    private data class InteractionSnapshot(
        val drawing: List<Pair<Double, Double>>,
        val target: PlaceEntity?,
        val message: String?,
        val peeking: Boolean,
        val opacityTarget: String?,
    )

    private data class LocationSnapshot(val fix: Fix?, val quality: FixQuality, val heading: Float?)

    companion object {
        /** Serialises a lat/lon ring into a GeoJSON Polygon string. */
        fun ringToGeoJson(ring: List<Pair<Double, Double>>): String {
            val closed = if (ring.first() == ring.last()) ring else ring + ring.first()
            val coords = closed.joinToString(",") { (lat, lon) -> "[$lon,$lat]" }
            return """{"type":"Polygon","coordinates":[[$coords]]}"""
        }

        /** Parses the ring back; tolerant of a missing or malformed polygon. */
        fun geoJsonToRing(geoJson: String): List<Pair<Double, Double>> = runCatching {
            val inner = geoJson.substringAfter("[[").substringBefore("]]")
            inner.split("],")
                .map { it.trim().removePrefix("[").removeSuffix("]") }
                .mapNotNull { pair ->
                    val parts = pair.split(",")
                    if (parts.size < 2) return@mapNotNull null
                    val lon = parts[0].trim().toDoubleOrNull() ?: return@mapNotNull null
                    val lat = parts[1].trim().toDoubleOrNull() ?: return@mapNotNull null
                    lat to lon
                }
        }.getOrDefault(emptyList())
    }
}
