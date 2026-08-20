package cz.hh.detektormapy.ui.map

import android.Manifest
import android.graphics.PointF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import cz.hh.detektormapy.data.model.PlaceType
import cz.hh.detektormapy.ui.nav.Routes
import cz.hh.detektormapy.util.BBox
import cz.hh.detektormapy.util.WebMercator
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * The map. Everything else in the app is a side quest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(navController: NavHostController, viewModel: MapViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val snackbarHost = remember { SnackbarHostState() }

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }
    var controller by remember { mutableStateOf<MapController?>(null) }
    var showLayerPanel by remember { mutableStateOf(false) }
    var pendingLongPress by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    val currentState = rememberUpdatedState(state)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        viewModel.onLocationPermissionResult(granted.values.any { it })
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    // "Navigovat" from the Místa list hands the id over through the back stack entry.
    LaunchedEffect(navController) {
        val handle = navController.currentBackStackEntry?.savedStateHandle
        handle?.getStateFlow<Long?>("navigateToPlaceId", null)?.collect { placeId ->
            if (placeId != null) {
                viewModel.navigateToPlaceId(placeId)
                handle["navigateToPlaceId"] = null
            }
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val mapView = rememberMapViewWithLifecycle()

    // "Nechat displej rozsvícený" from Nastavení. Walking a field with the screen blanking
    // every 30 s makes the map useless, so the flag is bound to the map screen only and is
    // released automatically when the user navigates away.
    val view = LocalView.current
    DisposableEffect(state.keepScreenOn, view) {
        view.keepScreenOn = state.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { /* state is pushed through the effects below */ },
        )

        DisposableEffect(mapView) {
            mapView.getMapAsync { map ->
                mapRef = map
                map.uiSettings.apply {
                    isAttributionEnabled = true
                    isLogoEnabled = false
                    isRotateGesturesEnabled = true
                    isTiltGesturesEnabled = false
                    isCompassEnabled = true
                }
                map.setPrefetchesTiles(true)
                map.setStyle(Style.Builder().fromJson(MapStyle.emptyStyleJson())) { style ->
                    MarkerIcons.all(density).forEach { (id, bitmap) -> style.addImage(id, bitmap) }
                    val ctrl = MapController(map) { layerId -> viewModel.urlTemplateFor(layerId) }
                    ctrl.onStyleLoaded(style)
                    styleRef = style
                    controller = ctrl
                }

                map.addOnMapClickListener { latLng ->
                    when (currentState.value.mode) {
                        MapMode.DRAW_AREA -> {
                            viewModel.addDrawingPoint(latLng.latitude, latLng.longitude)
                            true
                        }

                        else -> {
                            val hit = hitTest(map, latLng)
                            if (hit != null) {
                                val (kind, id) = hit
                                if (kind == MapController.KIND_FIND) {
                                    navController.navigate(Routes.findDetail(id))
                                } else {
                                    navController.navigate(Routes.placeDetail(id))
                                }
                                true
                            } else {
                                false
                            }
                        }
                    }
                }

                map.addOnMapLongClickListener { latLng ->
                    pendingLongPress = latLng.latitude to latLng.longitude
                    true
                }

                map.addOnCameraIdleListener {
                    val bbox = map.visibleBBox()
                    viewModel.onViewportChanged(bbox, map.cameraPosition.zoom)
                    viewModel.refreshAllCalibrations(bbox.centerLat, bbox.centerLon)
                }

                // Start over Czechia so a first launch without GPS is not lost at sea.
                map.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(BBox.CZECHIA.centerLat, BBox.CZECHIA.centerLon))
                            .zoom(7.0)
                            .build(),
                    ),
                )
            }
            onDispose { }
        }

        // Push state into the style whenever it changes.
        LaunchedEffect(state.layers, styleRef) {
            val style = styleRef ?: return@LaunchedEffect
            controller?.syncRasterLayers(style, state.layers)
        }
        LaunchedEffect(state.layers, state.geoJsonPayloads, styleRef) {
            val style = styleRef ?: return@LaunchedEffect
            controller?.syncGeoJsonLayers(style, state.layers, state.geoJsonPayloads)
        }
        LaunchedEffect(state.peeking, state.layers, styleRef) {
            val style = styleRef ?: return@LaunchedEffect
            controller?.applyPeek(style, state.layers, state.peeking)
        }
        LaunchedEffect(state.finds, state.showFinds, styleRef) {
            styleRef?.let {
                controller?.updateFinds(it, if (state.showFinds) state.finds else emptyList())
            }
        }
        LaunchedEffect(state.places, state.showPlaces, styleRef) {
            styleRef?.let {
                controller?.updatePlaces(it, if (state.showPlaces) state.places else emptyList())
            }
        }
        LaunchedEffect(state.areas, state.showAreas, state.drawingPoints, styleRef) {
            styleRef?.let {
                controller?.updateAreas(
                    it,
                    if (state.showAreas) state.areas else emptyList(),
                    state.drawingPoints,
                )
            }
        }
        LaunchedEffect(state.fix, state.headingDeg, styleRef) {
            styleRef?.let { controller?.updateLocation(it, state.fix, state.headingDeg) }
        }
        LaunchedEffect(state.trackPoints, styleRef) {
            styleRef?.let { controller?.updateTrack(it, state.trackPoints) }
        }
        LaunchedEffect(state.fix, state.followMode) {
            val fix = state.fix ?: return@LaunchedEffect
            if (!state.followMode) return@LaunchedEffect
            mapRef?.animateCamera(
                CameraUpdateFactory.newLatLng(LatLng(fix.lat, fix.lon)),
                600,
            )
        }
        LaunchedEffect(state.headingDeg, state.rotateWithCompass) {
            val heading = state.headingDeg ?: return@LaunchedEffect
            if (!state.rotateWithCompass) return@LaunchedEffect
            val map = mapRef ?: return@LaunchedEffect
            val pos = CameraPosition.Builder(map.cameraPosition).bearing(heading.toDouble()).build()
            map.easeCamera(CameraUpdateFactory.newCameraPosition(pos), 300)
        }

        // Calibration gesture layer: swallows gestures and moves only the overlay.
        if (state.mode == MapMode.CALIBRATE) {
            CalibrationGestureLayer(
                modifier = Modifier.fillMaxSize(),
                onTransform = { centroid, pan, rotationDeg, zoom ->
                    val map = mapRef ?: return@CalibrationGestureLayer
                    val delta = map.screenDeltaToMercator(centroid, pan)
                    val anchor = map.projection.fromScreenLocation(centroid)
                    viewModel.nudgeCalibration(
                        pivotLat = anchor.latitude,
                        pivotLon = anchor.longitude,
                        dxMeters = delta.first,
                        dyMeters = delta.second,
                        // Negated on purpose. Compose reports a clockwise two-finger twist as a
                        // POSITIVE angle in screen space, where y grows downwards. Affine2D works
                        // in EPSG:3857, where y grows northwards, so the same number would rotate
                        // the overlay anticlockwise -- the user would nudge 10 deg and watch the
                        // old map swing 10 deg the wrong way, doubling the error with every try.
                        rotationRad = -Math.toRadians(rotationDeg.toDouble()),
                        scale = zoom.toDouble(),
                    )
                },
            )
        }

        MapOverlayControls(
            state = state,
            onToggleLayers = { showLayerPanel = true },
            onPeek = viewModel::setPeek,
            onToggleFollow = { viewModel.setFollowMode(!state.followMode) },
            onToggleCompass = { viewModel.setRotateWithCompass(!state.rotateWithCompass) },
            onAddFind = { navController.navigate(Routes.FIND_CAPTURE) },
            onToggleRecording = {
                if (state.recording) {
                    viewModel.stopRecording(context)
                } else {
                    viewModel.startRecording(context)
                }
            },
            onZoomIn = { mapRef?.animateCamera(CameraUpdateFactory.zoomIn()) },
            onZoomOut = { mapRef?.animateCamera(CameraUpdateFactory.zoomOut()) },
            modifier = Modifier.fillMaxSize(),
        )

        if (state.mode == MapMode.CALIBRATE) {
            CalibrationBar(
                state = state,
                onSave = { label -> viewModel.saveCalibration(label) },
                onReset = { viewModel.resetPendingCalibration() },
                onCancel = { viewModel.cancelCalibration() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (state.mode == MapMode.DRAW_AREA) {
            DrawAreaBar(
                pointCount = state.drawingPoints.size,
                onUndo = { viewModel.undoDrawingPoint() },
                onFinish = { name -> viewModel.finishDrawingArea(name) },
                onCancel = { viewModel.cancelDrawing() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        state.navigateTarget?.let { target ->
            NavigationHintCard(
                target = target,
                hint = viewModel.navigationHint(),
                onDismiss = { viewModel.navigateTo(null) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
            )
        }

        // Opacity control on the map, not behind a dimming sheet.
        state.opacityLayer?.let { layer ->
            if (state.mode == MapMode.NAVIGATE && !showLayerPanel) {
                OpacityStrip(
                    layer = layer,
                    canCycle = state.overlayLayers.count {
                        it.visible && it.available && it.def.isRaster
                    } > 1,
                    onOpacityChange = { viewModel.setLayerOpacity(layer.def.id, it) },
                    onOpacityCommitted = { viewModel.commitLayerOpacity(layer.def.id) },
                    onCycleLayer = { viewModel.cycleOpacityTarget() },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = 12.dp),
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp),
        )
    }

    if (showLayerPanel) {
        LayerPanel(
            layers = state.layers,
            onDismiss = { showLayerPanel = false },
            onToggle = viewModel::setLayerVisible,
            onOpacity = viewModel::setLayerOpacity,
            onOpacityCommitted = viewModel::commitLayerOpacity,
            onCalibrate = { layerId ->
                showLayerPanel = false
                viewModel.startCalibration(layerId)
            },
            onManageCalibrations = { layerId ->
                showLayerPanel = false
                navController.navigate(Routes.calibrations(layerId))
            },
            onReload = { viewModel.reloadLayers() },
            showFinds = state.showFinds,
            showPlaces = state.showPlaces,
            showAreas = state.showAreas,
            onShowFinds = viewModel::setShowFinds,
            onShowPlaces = viewModel::setShowPlaces,
            onShowAreas = viewModel::setShowAreas,
            onAddImageOverlay = {
                showLayerPanel = false
                navController.navigate(Routes.IMAGE_OVERLAY)
            },
        )
    }

    pendingLongPress?.let { (lat, lon) ->
        NewPlaceDialog(
            lat = lat,
            lon = lon,
            onDismiss = { pendingLongPress = null },
            onConfirm = { type: PlaceType, title: String, note: String ->
                viewModel.addPlace(lat, lon, type, title, note)
                pendingLongPress = null
            },
            onDrawArea = {
                pendingLongPress = null
                viewModel.startDrawingArea()
            },
        )
    }
}

/** Which pin, if any, sits under the tap. */
private fun hitTest(map: MapLibreMap, latLng: LatLng): Pair<String, Long>? {
    val screen = map.projection.toScreenLocation(latLng)
    val rect = android.graphics.RectF(screen.x - 28f, screen.y - 40f, screen.x + 28f, screen.y + 8f)
    val features = map.queryRenderedFeatures(rect, MapStyle.LAYER_FINDS, MapStyle.LAYER_PLACES)
    val feature = features.firstOrNull() ?: return null
    val kind = feature.getStringProperty(MapController.PROP_KIND) ?: return null
    val id = feature.getNumberProperty(MapController.PROP_ID)?.toLong() ?: return null
    return kind to id
}

/** Current viewport as a WGS84 [BBox]. */
fun MapLibreMap.visibleBBox(): BBox {
    val bounds = projection.visibleRegion.latLngBounds
    return BBox(
        west = bounds.longitudeWest.coerceIn(-180.0, 180.0),
        south = bounds.latitudeSouth.coerceIn(-85.0, 85.0),
        east = bounds.longitudeEast.coerceIn(-180.0, 180.0),
        north = bounds.latitudeNorth.coerceIn(-85.0, 85.0),
    )
}

/**
 * Converts a screen-space drag into an EPSG:3857 offset in metres.
 *
 * Doing it by unprojecting both endpoints (rather than multiplying by a metres-per-pixel
 * constant) keeps the offset exact under rotation and at any latitude, which matters because a
 * calibration saved in one place gets re-applied in another.
 */
fun MapLibreMap.screenDeltaToMercator(from: PointF, pan: PointF): Pair<Double, Double> {
    val start = projection.fromScreenLocation(from)
    val end = projection.fromScreenLocation(PointF(from.x + pan.x, from.y + pan.y))
    val dx = WebMercator.lonToMeters(end.longitude) - WebMercator.lonToMeters(start.longitude)
    val dy = WebMercator.latToMeters(end.latitude) - WebMercator.latToMeters(start.latitude)
    return dx to dy
}
