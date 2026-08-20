package cz.hh.detektormapy.map

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Layer catalogue, loaded from `layers.json` in app storage. Adding a new historical map
 * means dropping a .pmtiles file next to it and adding one entry here -- no app release.
 */
@Serializable
data class LayerCatalog(val version: Int = 1, val layers: List<LayerDef> = emptyList())

@Serializable
enum class LayerKind {
    /** Local PMTiles archive served through the built-in tile server. */
    @SerialName("pmtiles")
    PMTILES,

    /** Local MBTiles archive (SQLite), same server, fallback format. */
    @SerialName("mbtiles")
    MBTILES,

    /** Remote XYZ / WMTS RESTful template, only usable with a signal. */
    @SerialName("xyz")
    XYZ,

    /** Remote WMS endpoint; the tile server renders GetMap requests into XYZ tiles. */
    @SerialName("wms")
    WMS,

    /**
     * ArcGIS `MapServer/export`. The only online way to get the Czech EPSG:5514 services into
     * a Web Mercator map, because the server reprojects for us.
     */
    @SerialName("arcgis")
    ARCGIS,

    /** Local vector PMTiles used as the base map, rendered by a MapLibre style. */
    @SerialName("vector")
    VECTOR,

    /** A single georeferenced image pinned by four draggable corners. */
    @SerialName("image")
    IMAGE,

    /** Local GeoJSON polygons (UAN, searched areas imported from the pipeline). */
    @SerialName("geojson")
    GEOJSON,
}

@Serializable
data class LayerDef(
    val id: String,
    val title: String,
    val kind: LayerKind,
    /** Relative to the layers directory for local kinds, absolute URL for remote ones. */
    val source: String,
    val attribution: String = "",
    val defaultOpacity: Float = 0.7f,
    val minZoom: Int = 0,
    val maxZoom: Int = 19,
    /** Overlays with a higher order render on top of lower ones. */
    val order: Int = 0,
    val enabledByDefault: Boolean = false,
    /** Extent hint in WGS84 (west, south, east, north); used to warn "mimo pokrytí". */
    val bounds: List<Double>? = null,
    /** Marks the entry as the base map rather than an overlay. */
    val isBasemap: Boolean = false,
    /** WMS only. */
    val wmsLayers: String? = null,
    val wmsStyle: String? = null,
    val wmsFormat: String = "image/png",
    val wmsVersion: String = "1.3.0",
    /** ArcGIS only: which sub-layers to draw, e.g. `show:0,1`. */
    val arcgisLayers: String? = null,
    /** ArcGIS only: `png` (default, transparent) or `jpg` for opaque imagery. */
    val arcgisFormat: String = "png",
    /** Free-form note shown in the layer panel. */
    val note: String? = null,
    /**
     * Marks a GeoJSON layer as a legally protected area (ÚAN). Entering one triggers the
     * warning from PLAN.md F4-3, so it must be opt-in per layer rather than guessed.
     */
    val isProtectedArea: Boolean = false,
    /**
     * The map was surveyed without a trigonometric network, so any global georeference is
     * only approximate. The layer panel warns the user up front that precise work needs the
     * manual overlay flow ("Přiložit sken…") instead of trusting this layer's placement.
     */
    val manualAlignment: Boolean = false,
) {
    val isLocal: Boolean
        get() = kind == LayerKind.PMTILES || kind == LayerKind.MBTILES ||
            kind == LayerKind.VECTOR || kind == LayerKind.GEOJSON || kind == LayerKind.IMAGE

    val isOnline: Boolean
        get() = kind == LayerKind.XYZ || kind == LayerKind.WMS || kind == LayerKind.ARCGIS

    val isRaster: Boolean
        get() = kind == LayerKind.PMTILES || kind == LayerKind.MBTILES ||
            kind == LayerKind.XYZ || kind == LayerKind.WMS || kind == LayerKind.ARCGIS
}

/**
 * Merges a `layers.json` read from disk with the current [DefaultLayers] seed.
 *
 * The file on disk is the user's — hand edits and removals of *existing* entries survive.
 * Only layers whose id the file has never seen are appended, and only when the seed catalogue
 * is newer than the file, so a user who deliberately deleted a default layer is asked again
 * at most once per catalogue version bump.
 *
 * Returns the input catalogue unchanged (same instance) when the file is already at the
 * seed's version, which is the caller's signal that no write-back is needed.
 */
fun mergeCatalogs(onDisk: LayerCatalog, defaults: LayerCatalog): LayerCatalog {
    if (onDisk.version >= defaults.version) return onDisk
    val known = onDisk.layers.map { it.id }.toSet()
    val added = defaults.layers.filter { it.id !in known }
    return LayerCatalog(version = defaults.version, layers = onDisk.layers + added)
}

/** Runtime, user-controlled state of a layer (persisted in DataStore, not in layers.json). */
data class LayerUiState(
    val def: LayerDef,
    val visible: Boolean,
    val opacity: Float,
    /** False when a local file is declared but missing on disk. */
    val available: Boolean = true,
    /** Human readable reason why the layer cannot be shown right now. */
    val unavailableReason: String? = null,
    /** Id of the calibration currently applied, if any. */
    val activeCalibrationId: Long? = null,
)
