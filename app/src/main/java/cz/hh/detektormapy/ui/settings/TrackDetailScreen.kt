package cz.hh.detektormapy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import cz.hh.detektormapy.data.entity.TrackPointEntity
import cz.hh.detektormapy.ui.map.MapController
import cz.hh.detektormapy.ui.map.MapStyle
import cz.hh.detektormapy.ui.map.rememberMapViewWithLifecycle
import cz.hh.detektormapy.util.BBox
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * One saved walk drawn over the map — "kudy jsem šel".
 *
 * Read-only on purpose: no recording, no editing, no location follow. It answers a single
 * question after the fact, so everything that could change the map's state is left out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailScreen(navController: NavHostController) {
    val viewModel: TrackDetailViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.track?.name?.ifBlank { null }
                            ?: state.track?.let { "Pochůzka ${formatDateTime(it.startedAt)}" }
                            ?: "Pochůzka",
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    !state.hasRoute -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(
                            text = "Tahle pochůzka nemá dost bodů na vykreslení trasy.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }

                    else -> TrackMap(
                        points = state.points,
                        bounds = state.bounds,
                        basemapUrlTemplate = state.basemapUrlTemplate,
                    )
                }
            }

            TrackSummary(state)
        }
    }
}

@Composable
private fun TrackSummary(state: TrackDetailUiState) {
    val track = state.track ?: return
    Card(
        Modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SummaryRow("Kdy", formatDateTime(track.startedAt))
            SummaryRow("Nachozeno", formatKm(state.distanceM))
            track.endedAt?.let { SummaryRow("Trvání", formatDuration(it - track.startedAt)) }
            SummaryRow("Bodů trasy", state.points.size.toString())
            state.basemapTitle?.let { SummaryRow("Podklad", it) }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The map itself.
 *
 * Built by hand rather than through `MapController`: that class exists to keep a *live* map in
 * step with recording, calibration and layer edits, and none of that applies here. A static
 * route needs one raster, one line and one camera move.
 */
@Composable
private fun TrackMap(points: List<TrackPointEntity>, bounds: BBox?, basemapUrlTemplate: String?) {
    val mapView = rememberMapViewWithLifecycle()
    var styleRef by remember { mutableStateOf<Style?>(null) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var attachedUrl by remember { mutableStateOf<String?>(null) }
    var framed by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        DisposableEffect(mapView) {
            var disposed = false
            mapView.getMapAsync { map: MapLibreMap ->
                if (disposed) return@getMapAsync
                map.uiSettings.isRotateGesturesEnabled = false
                map.uiSettings.isTiltGesturesEnabled = false
                map.uiSettings.isLogoEnabled = false
                map.setStyle(Style.Builder().fromJson(MapStyle.emptyStyleJson())) { style ->
                    if (!disposed) styleRef = style
                }
                mapRef = map
            }
            onDispose { disposed = true }
        }

        // The basemap arrives late: the tile server binds an ephemeral port on a background
        // thread, so the URL is null for the first frames and changes if the server restarts.
        LaunchedEffect(styleRef, basemapUrlTemplate) {
            val style = styleRef ?: return@LaunchedEffect
            val template = basemapUrlTemplate ?: return@LaunchedEffect
            if (attachedUrl == template) return@LaunchedEffect
            runCatching {
                style.getLayer(BASEMAP_LAYER)?.let { style.removeLayer(it) }
                style.getSource(BASEMAP_SOURCE)?.let { style.removeSource(it) }
                style.addSource(RasterSource(BASEMAP_SOURCE, TileSet("2.2.0", template), 256))
                // Below the route, which was added when the style loaded.
                val below = style.getLayer(MapStyle.LAYER_TRACK_CASING)
                if (below != null) {
                    style.addLayerBelow(RasterLayer(BASEMAP_LAYER, BASEMAP_SOURCE), below.id)
                } else {
                    style.addLayer(RasterLayer(BASEMAP_LAYER, BASEMAP_SOURCE))
                }
                attachedUrl = template
            }
        }

        LaunchedEffect(styleRef, points) {
            val style = styleRef ?: return@LaunchedEffect
            drawRoute(style, points)
        }

        LaunchedEffect(mapRef, bounds) {
            val map = mapRef ?: return@LaunchedEffect
            val box = bounds ?: return@LaunchedEffect
            if (framed) return@LaunchedEffect
            runCatching {
                val latLngBounds = LatLngBounds.Builder()
                    .include(LatLng(box.north, box.east))
                    .include(LatLng(box.south, box.west))
                    .build()
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(latLngBounds, FRAME_PADDING_PX))
                framed = true
            }
        }
    }
}

/** Adds the casing, the line and the start/end dots. Idempotent: sources are reused. */
private fun drawRoute(style: Style, points: List<TrackPointEntity>) {
    if (points.size < 2) return
    runCatching {
        if (style.getSource(MapStyle.SOURCE_TRACK) == null) {
            style.addSource(GeoJsonSource(MapStyle.SOURCE_TRACK))
            style.addSource(GeoJsonSource(START_SOURCE))
            style.addSource(GeoJsonSource(END_SOURCE_ID))
            style.addLayer(
                LineLayer(MapStyle.LAYER_TRACK_CASING, MapStyle.SOURCE_TRACK).withProperties(
                    PropertyFactory.lineColor(MapController.TRACK_CASING_COLOR),
                    PropertyFactory.lineWidth(MapController.TRACK_WIDTH + 3f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineOpacity(0.75f),
                ),
            )
            style.addLayer(
                LineLayer(MapStyle.LAYER_TRACK, MapStyle.SOURCE_TRACK).withProperties(
                    PropertyFactory.lineColor(MapController.TRACK_COLOR),
                    PropertyFactory.lineWidth(MapController.TRACK_WIDTH),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineOpacity(0.95f),
                ),
            )
            // Two flat layers instead of one data-driven colour expression: there are exactly
            // two dots and they never change, so a match expression would be ceremony.
            style.addLayer(
                CircleLayer(START_LAYER, START_SOURCE).withProperties(
                    PropertyFactory.circleRadius(6f),
                    PropertyFactory.circleColor(START_COLOR),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeColor(MapController.TRACK_CASING_COLOR),
                ),
            )
            style.addLayer(
                CircleLayer(END_LAYER, END_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(6f),
                    PropertyFactory.circleColor(END_COLOR),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeColor(MapController.TRACK_CASING_COLOR),
                ),
            )
        }

        val line = LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })
        style.getSourceAs<GeoJsonSource>(MapStyle.SOURCE_TRACK)
            ?.setGeoJson(Feature.fromGeometry(line))

        val first = points.first()
        val last = points.last()
        style.getSourceAs<GeoJsonSource>(START_SOURCE)
            ?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(first.lon, first.lat)))
        style.getSourceAs<GeoJsonSource>(END_SOURCE_ID)
            ?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(last.lon, last.lat)))
    }
}

private const val BASEMAP_SOURCE = "track-basemap-src"
private const val BASEMAP_LAYER = "track-basemap-layer"
private const val START_SOURCE = "track-start-src"
private const val END_SOURCE_ID = "track-end-src"
private const val START_LAYER = "track-start-layer"
private const val END_LAYER = "track-end-layer"

/** Green where the walk began, near-black where it ended. */
private const val START_COLOR = 0xFF2E7D32.toInt()
private const val END_COLOR = 0xFF1A1A1A.toInt()

/** Breathing room so the route never touches the edge of the viewport. */
private const val FRAME_PADDING_PX = 96
