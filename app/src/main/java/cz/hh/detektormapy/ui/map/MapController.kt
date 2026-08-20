package cz.hh.detektormapy.ui.map

import android.util.Log
import cz.hh.detektormapy.data.entity.FindEntity
import cz.hh.detektormapy.data.entity.PlaceEntity
import cz.hh.detektormapy.data.entity.SearchedAreaEntity
import cz.hh.detektormapy.location.Fix
import cz.hh.detektormapy.map.LayerKind
import cz.hh.detektormapy.map.LayerUiState
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * Keeps the MapLibre style in sync with [MapUiState].
 *
 * The style is mutated incrementally rather than rebuilt, because rebuilding a style drops the
 * raster tile cache and makes the map flash white -- unacceptable while walking a field.
 */
class MapController(private val map: MapLibreMap, private val urlTemplateProvider: (String) -> String?) {

    private val installedRasters = linkedSetOf<String>()
    private val installedGeoJson = linkedSetOf<String>()
    private var overlaysReady = false

    fun onStyleLoaded(style: Style) {
        installOverlaySources(style)
        overlaysReady = true
    }

    /** Adds, removes and reorders raster layers so that they match [layers] exactly. */
    fun syncRasterLayers(style: Style, layers: List<LayerUiState>) {
        val wanted = layers.filter { it.visible && it.available && it.def.isRaster }

        // Remove what is no longer wanted.
        installedRasters.toList().forEach { layerId ->
            if (wanted.none { it.def.id == layerId }) {
                runCatching { style.removeLayer(MapStyle.rasterLayerId(layerId)) }
                runCatching { style.removeSource(MapStyle.rasterSourceId(layerId)) }
                installedRasters.remove(layerId)
            }
        }

        // Add / update, bottom first, always below the app's own vector overlays.
        wanted.forEachIndexed { index, state ->
            val id = state.def.id
            val sourceId = MapStyle.rasterSourceId(id)
            val layerId = MapStyle.rasterLayerId(id)
            val template = urlTemplateProvider(id) ?: run {
                Log.d(TAG, "Vrstva $id zatím nemá URL, přeskakuji")
                return@forEachIndexed
            }

            if (id !in installedRasters) {
                runCatching {
                    val tileSet = TileSet("2.2.0", template).apply {
                        minZoom = state.def.minZoom.toFloat()
                        maxZoom = state.def.maxZoom.toFloat()
                        attribution = state.def.attribution
                    }
                    style.addSource(RasterSource(sourceId, tileSet, TILE_SIZE))
                    val rasterLayer = RasterLayer(layerId, sourceId).withProperties(
                        PropertyFactory.rasterOpacity(state.opacity),
                        PropertyFactory.rasterFadeDuration(0f),
                        PropertyFactory.rasterResampling(
                            if (state.def.kind == LayerKind.WMS) {
                                Property.RASTER_RESAMPLING_LINEAR
                            } else {
                                Property.RASTER_RESAMPLING_LINEAR
                            },
                        ),
                    )
                    // Insert below the first already-installed raster that ranks higher, so a
                    // layer toggled off and back on returns to its place instead of jumping to
                    // the top and hiding everything under it. Rank = position in `layers`,
                    // which LayerManager sorts by the user's order override with the catalog
                    // order as fallback — comparing `def.order` here ignored the override.
                    // Falling back to the app-overlay anchor keeps every raster below the
                    // finds/places/track layers.
                    val successorId = wanted
                        .drop(index + 1)
                        .firstOrNull { it.def.id in installedRasters }
                        ?.def?.id
                    val below = successorId?.let { MapStyle.rasterLayerId(it) }
                        ?: MapStyle.LAYER_AREAS_FILL.takeIf { style.getLayer(it) != null }
                    if (below != null) {
                        style.addLayerBelow(rasterLayer, below)
                    } else {
                        style.addLayer(rasterLayer)
                    }
                    installedRasters.add(id)
                }.onFailure { Log.w(TAG, "Vrstvu $id nelze přidat do stylu", it) }
            } else {
                (style.getLayer(layerId) as? RasterLayer)
                    ?.setProperties(PropertyFactory.rasterOpacity(state.opacity))
            }
        }
    }

    /**
     * Adds / removes GeoJSON overlays such as ÚAN (issue F4-3).
     *
     * ÚAN is drawn as a translucent fill plus a dashed outline rather than a true hatch:
     * MapLibre's `fill-pattern` needs a registered image, and a dashed border reads just as
     * clearly on a phone screen at arm's length while staying legible over a busy 1840s map.
     */
    fun syncGeoJsonLayers(style: Style, layers: List<LayerUiState>, payloads: Map<String, String>) {
        if (!overlaysReady) return
        val wanted = layers.filter { it.visible && it.available && it.def.kind == LayerKind.GEOJSON }

        installedGeoJson.toList().forEach { layerId ->
            if (wanted.none { it.def.id == layerId }) {
                runCatching { style.removeLayer(geoJsonLineId(layerId)) }
                runCatching { style.removeLayer(geoJsonFillId(layerId)) }
                runCatching { style.removeSource(geoJsonSourceId(layerId)) }
                installedGeoJson.remove(layerId)
            }
        }

        wanted.forEach { state ->
            val id = state.def.id
            val payload = payloads[id] ?: return@forEach
            if (id !in installedGeoJson) {
                runCatching {
                    style.addSource(GeoJsonSource(geoJsonSourceId(id), payload))
                    val protectedArea = state.def.isProtectedArea
                    // Below the app's own overlays: a translucent ÚAN fill drawn on top would
                    // wash out the find pins and the recorded track.
                    style.addLayerBelow(
                        FillLayer(geoJsonFillId(id), geoJsonSourceId(id)).withProperties(
                            PropertyFactory.fillColor(
                                if (protectedArea) UAN_FILL_COLOR else AREA_FILL_COLOR,
                            ),
                            PropertyFactory.fillOpacity(state.opacity),
                        ),
                        MapStyle.LAYER_AREAS_FILL,
                    )
                    style.addLayerBelow(
                        LineLayer(geoJsonLineId(id), geoJsonSourceId(id)).withProperties(
                            PropertyFactory.lineColor(
                                if (protectedArea) UAN_LINE_COLOR else AREA_LINE_COLOR,
                            ),
                            PropertyFactory.lineWidth(2f),
                            PropertyFactory.lineDasharray(arrayOf(3f, 2f)),
                        ),
                        MapStyle.LAYER_AREAS_FILL,
                    )
                    installedGeoJson.add(id)
                }.onFailure { Log.w(TAG, "GeoJSON vrstvu $id nelze přidat", it) }
            } else {
                (style.getLayer(geoJsonFillId(id)) as? FillLayer)
                    ?.setProperties(PropertyFactory.fillOpacity(state.opacity))
            }
        }
    }

    /**
     * Applies the momentary peek: historical overlays drop to zero opacity, the base map and
     * the app's own vector overlays stay.
     *
     * Only `rasterOpacity` is touched -- see the note in `syncRasterLayers`. Toggling layer
     * visibility would remove the source and force a full re-fetch on release, which is exactly
     * the sort of thing that makes an offline-first app feel broken on a bad signal.
     */
    fun applyPeek(style: Style, layers: List<LayerUiState>, peeking: Boolean) {
        if (!overlaysReady) return
        layers.filter { it.def.isRaster && !it.def.isBasemap }.forEach { state ->
            val layer = style.getLayer(MapStyle.rasterLayerId(state.def.id)) as? RasterLayer
                ?: return@forEach
            layer.setProperties(
                PropertyFactory.rasterOpacity(if (peeking) 0f else state.opacity),
            )
        }
    }

    fun updateFinds(style: Style, finds: List<FindEntity>) {
        if (!overlaysReady) return
        val features = finds.map { find ->
            Feature.fromGeometry(Point.fromLngLat(find.lon, find.lat)).apply {
                addStringProperty(PROP_ICON, MarkerIcons.findIconId(find.category))
                addNumberProperty(PROP_ID, find.id)
                addStringProperty(PROP_KIND, KIND_FIND)
                addStringProperty(PROP_TITLE, find.title.ifBlank { find.category.label })
            }
        }
        setSourceData(style, MapStyle.SOURCE_FINDS, features)
    }

    fun updatePlaces(style: Style, places: List<PlaceEntity>) {
        if (!overlaysReady) return
        val features = places.map { place ->
            Feature.fromGeometry(Point.fromLngLat(place.lon, place.lat)).apply {
                addStringProperty(PROP_ICON, MarkerIcons.placeIconId(place.type))
                addNumberProperty(PROP_ID, place.id)
                addStringProperty(PROP_KIND, KIND_PLACE)
                addStringProperty(PROP_TITLE, place.title.ifBlank { place.type.label })
            }
        }
        setSourceData(style, MapStyle.SOURCE_PLACES, features)
    }

    fun updateAreas(style: Style, areas: List<SearchedAreaEntity>, draft: List<Pair<Double, Double>>) {
        if (!overlaysReady) return
        val features = areas.mapNotNull { area ->
            val ring = MapViewModel.geoJsonToRing(area.polygonGeoJson)
            if (ring.size < 3) return@mapNotNull null
            polygonFeature(ring).apply {
                addStringProperty(PROP_STATUS, area.status.name)
                addNumberProperty(PROP_ID, area.id)
            }
        }.toMutableList()

        if (draft.size >= 3) {
            features += polygonFeature(draft).apply { addStringProperty(PROP_STATUS, "DRAFT") }
        }
        setSourceData(style, MapStyle.SOURCE_AREAS, features)
    }

    /**
     * Draws where the user is standing: an accuracy circle, a dot, and a heading cone.
     *
     * The accuracy ring is a real polygon in metres rather than a `CircleLayer`, whose radius is
     * in screen pixels — a pixel radius would claim wildly different accuracy depending on zoom.
     * Getting this honest matters: the whole point is walking onto a spot marked on an old map,
     * and the ring is what tells you how much to trust the dot.
     */
    fun updateLocation(style: Style, fix: Fix?, headingDeg: Float?) {
        if (!overlaysReady) return
        if (fix == null) {
            setSourceData(style, MapStyle.SOURCE_ACCURACY, emptyList())
            setSourceData(style, MapStyle.SOURCE_LOCATION, emptyList())
            return
        }

        val accuracy = fix.accuracyM?.toDouble() ?: 0.0
        val ring = if (accuracy > 1.0) {
            listOf(circleFeature(fix.lat, fix.lon, accuracy))
        } else {
            emptyList()
        }
        setSourceData(style, MapStyle.SOURCE_ACCURACY, ring)

        val point = Feature.fromGeometry(Point.fromLngLat(fix.lon, fix.lat)).apply {
            addNumberProperty(PROP_HEADING, headingDeg ?: fix.bearingDeg ?: 0f)
            addStringProperty(PROP_ICON, MarkerIcons.ICON_HEADING)
            addStringProperty(PROP_HAS_HEADING, (headingDeg != null || fix.bearingDeg != null).toString())
        }
        setSourceData(style, MapStyle.SOURCE_LOCATION, listOf(point))

        (style.getLayer(MapStyle.LAYER_LOCATION_HEADING) as? SymbolLayer)?.setProperties(
            PropertyFactory.iconRotate((headingDeg ?: fix.bearingDeg ?: 0f)),
            PropertyFactory.iconOpacity(if (headingDeg != null || fix.bearingDeg != null) 1f else 0f),
        )
    }

    /** Polygon approximating a circle of [radiusMeters] around a position. */
    private fun circleFeature(lat: Double, lon: Double, radiusMeters: Double): Feature {
        val steps = 48
        val latPerM = 1.0 / 111_132.0
        val lonPerM = 1.0 / (111_320.0 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(1e-6))
        val points = (0..steps).map { i ->
            val a = 2.0 * Math.PI * i / steps
            Point.fromLngLat(
                lon + kotlin.math.cos(a) * radiusMeters * lonPerM,
                lat + kotlin.math.sin(a) * radiusMeters * latPerM,
            )
        }
        return Feature.fromGeometry(Polygon.fromLngLats(listOf(points)))
    }

    fun updateTrack(style: Style, points: List<Pair<Double, Double>>) {
        if (!overlaysReady) return
        val source = style.getSourceAs<GeoJsonSource>(MapStyle.SOURCE_TRACK) ?: return
        if (points.size < 2) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        val line = LineString.fromLngLats(points.map { Point.fromLngLat(it.second, it.first) })
        source.setGeoJson(Feature.fromGeometry(line))
    }

    // --- internals -------------------------------------------------------------------

    private fun polygonFeature(ring: List<Pair<Double, Double>>): Feature {
        val closed = if (ring.first() == ring.last()) ring else ring + ring.first()
        val points = closed.map { Point.fromLngLat(it.second, it.first) }
        return Feature.fromGeometry(Polygon.fromLngLats(listOf(points)))
    }

    private fun setSourceData(style: Style, sourceId: String, features: List<Feature>) {
        style.getSourceAs<GeoJsonSource>(sourceId)
            ?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun installOverlaySources(style: Style) {
        if (style.getSource(MapStyle.SOURCE_AREAS) != null) return

        style.addSource(GeoJsonSource(MapStyle.SOURCE_AREAS))
        style.addSource(GeoJsonSource(MapStyle.SOURCE_TRACK))
        style.addSource(GeoJsonSource(MapStyle.SOURCE_PLACES))
        style.addSource(GeoJsonSource(MapStyle.SOURCE_FINDS))
        style.addSource(GeoJsonSource(MapStyle.SOURCE_ACCURACY))
        style.addSource(GeoJsonSource(MapStyle.SOURCE_LOCATION))

        style.addLayer(
            FillLayer(MapStyle.LAYER_AREAS_FILL, MapStyle.SOURCE_AREAS).withProperties(
                PropertyFactory.fillColor(AREA_FILL_COLOR),
                PropertyFactory.fillOpacity(0.25f),
            ),
        )
        style.addLayer(
            LineLayer(MapStyle.LAYER_AREAS_LINE, MapStyle.SOURCE_AREAS).withProperties(
                PropertyFactory.lineColor(AREA_LINE_COLOR),
                PropertyFactory.lineWidth(2f),
            ),
        )
        style.addLayer(
            LineLayer(MapStyle.LAYER_TRACK, MapStyle.SOURCE_TRACK).withProperties(
                PropertyFactory.lineColor(TRACK_COLOR),
                PropertyFactory.lineWidth(4f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineOpacity(0.9f),
            ),
        )
        style.addLayer(
            CircleLayer(PLACES_HALO_LAYER, MapStyle.SOURCE_PLACES).withProperties(
                PropertyFactory.circleRadius(0f),
                PropertyFactory.circleOpacity(0f),
            ),
        )
        style.addLayer(
            SymbolLayer(MapStyle.LAYER_PLACES, MapStyle.SOURCE_PLACES).withProperties(
                PropertyFactory.iconImage("{$PROP_ICON}"),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconSize(1f),
            ),
        )
        // Accuracy ring sits under everything else; the dot and cone go on top of all pins so
        // the user can always see themselves.
        style.addLayer(
            FillLayer(MapStyle.LAYER_ACCURACY_FILL, MapStyle.SOURCE_ACCURACY).withProperties(
                PropertyFactory.fillColor(LOCATION_COLOR),
                PropertyFactory.fillOpacity(0.12f),
            ),
        )
        style.addLayer(
            LineLayer(MapStyle.LAYER_ACCURACY_LINE, MapStyle.SOURCE_ACCURACY).withProperties(
                PropertyFactory.lineColor(LOCATION_COLOR),
                PropertyFactory.lineWidth(1.5f),
                PropertyFactory.lineOpacity(0.5f),
            ),
        )
        style.addLayer(
            SymbolLayer(MapStyle.LAYER_FINDS, MapStyle.SOURCE_FINDS).withProperties(
                PropertyFactory.iconImage("{$PROP_ICON}"),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconSize(1f),
            ),
        )
        style.addLayer(
            SymbolLayer(MapStyle.LAYER_LOCATION_HEADING, MapStyle.SOURCE_LOCATION).withProperties(
                PropertyFactory.iconImage(MarkerIcons.ICON_HEADING),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
            ),
        )
        style.addLayer(
            CircleLayer(MapStyle.LAYER_LOCATION_DOT, MapStyle.SOURCE_LOCATION).withProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor(LOCATION_COLOR),
                PropertyFactory.circleStrokeColor(0xFFFFFFFF.toInt()),
                PropertyFactory.circleStrokeWidth(2.5f),
                PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
            ),
        )
    }

    companion object {
        const val TAG = "MapController"
        const val TILE_SIZE = 256
        const val PROP_ICON = "icon"
        const val PROP_ID = "entityId"
        const val PROP_KIND = "kind"
        const val PROP_TITLE = "title"
        const val PROP_STATUS = "status"
        const val KIND_FIND = "find"
        const val KIND_PLACE = "place"
        const val PLACES_HALO_LAYER = "app-places-halo"
        const val AREA_FILL_COLOR = 0xFF4A6B3F.toInt()
        const val AREA_LINE_COLOR = 0xFF2E3B2C.toInt()
        const val TRACK_COLOR = 0xFFB3261E.toInt()
        const val LOCATION_COLOR = 0xFF1E88E5.toInt()
        const val PROP_HEADING = "heading"
        const val PROP_HAS_HEADING = "hasHeading"
        const val UAN_FILL_COLOR = 0xFFB3261E.toInt()
        const val UAN_LINE_COLOR = 0xFF7F1D1D.toInt()

        fun geoJsonSourceId(layerId: String) = "geojson-src-$layerId"

        fun geoJsonFillId(layerId: String) = "geojson-fill-$layerId"

        fun geoJsonLineId(layerId: String) = "geojson-line-$layerId"
    }
}
