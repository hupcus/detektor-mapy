package cz.hh.detektormapy.ui.map

import cz.hh.detektormapy.data.entity.FindEntity
import cz.hh.detektormapy.data.entity.PlaceEntity
import cz.hh.detektormapy.data.entity.SearchedAreaEntity
import cz.hh.detektormapy.location.Fix
import cz.hh.detektormapy.location.FixQuality
import cz.hh.detektormapy.map.LayerUiState
import cz.hh.detektormapy.map.ProtectedAreaHit

/** Which two-finger gesture the map is currently interpreting. */
enum class MapMode {
    /** Normal navigation. */
    NAVIGATE,

    /** Režim A: gestures move the selected overlay, the basemap stays put. */
    CALIBRATE,

    /** Drawing a searched-area polygon by tapping/dragging. */
    DRAW_AREA,
}

data class MapUiState(
    val layers: List<LayerUiState> = emptyList(),
    val fix: Fix? = null,
    val fixQuality: FixQuality = FixQuality.NONE,
    val headingDeg: Float? = null,
    val followMode: Boolean = true,
    val rotateWithCompass: Boolean = false,
    val keepScreenOn: Boolean = true,
    val mode: MapMode = MapMode.NAVIGATE,
    val calibrationLayerId: String? = null,
    val calibrationDirty: Boolean = false,
    val activeCalibrationLabel: String? = null,
    val finds: List<FindEntity> = emptyList(),
    val places: List<PlaceEntity> = emptyList(),
    val areas: List<SearchedAreaEntity> = emptyList(),
    val trackPoints: List<Pair<Double, Double>> = emptyList(),
    val recording: Boolean = false,
    val navigateTarget: PlaceEntity? = null,
    val drawingPoints: List<Pair<Double, Double>> = emptyList(),
    val message: String? = null,
    val locationPermissionGranted: Boolean = false,
    /** Non-null while the current position sits inside an ÚAN polygon (issue F4-3). */
    val protectedArea: ProtectedAreaHit? = null,
    val geoJsonPayloads: Map<String, String> = emptyMap(),
    /** True while the user holds the layers button to peek at the modern map underneath. */
    val peeking: Boolean = false,
) {
    val overlayLayers: List<LayerUiState>
        get() = layers.filterNot { it.def.isBasemap }

    val basemapLayers: List<LayerUiState>
        get() = layers.filter { it.def.isBasemap }

    val calibrationLayer: LayerUiState?
        get() = calibrationLayerId?.let { id -> layers.firstOrNull { it.def.id == id } }
}
