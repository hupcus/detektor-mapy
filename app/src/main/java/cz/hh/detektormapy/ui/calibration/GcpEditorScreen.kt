package cz.hh.detektormapy.ui.calibration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import cz.hh.detektormapy.map.DefaultLayers
import cz.hh.detektormapy.ui.map.MapStyle
import cz.hh.detektormapy.ui.map.rememberMapViewWithLifecycle
import cz.hh.detektormapy.util.WebMercator
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Split-view GCP editor (issue F3-3).
 *
 * Top pane shows the historical layer, bottom pane the reference ortophoto. The user taps the
 * same feature -- a church, a crossroads, a pond dam -- in both panes; every completed pair
 * feeds a least-squares fit whose RMSE is shown live, so it is obvious when one point was
 * misplaced.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GcpEditorScreen(
    navController: NavHostController,
    layerId: String,
    viewModel: GcpEditorViewModel = hiltViewModel(),
) {
    LaunchedEffect(layerId) { viewModel.bind(layerId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var askLabel by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GCP: ${state.layerTitle}") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.clearPreview()
                        navController.popBackStack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PaneLabel(
                text = if (state.pending == null) {
                    "1) Klepni na bod ve staré mapě"
                } else {
                    "1) Bod zadán ✓"
                },
            )
            GcpMapPane(
                layerId = layerId,
                urlTemplate = viewModel.urlTemplateFor(layerId),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onTap = { lat, lon -> viewModel.tapSource(lat, lon) },
            )

            PaneLabel(text = "2) Klepni na stejné místo v ortofotu")
            GcpMapPane(
                layerId = ORTHO_LAYER_ID,
                urlTemplate = viewModel.urlTemplateFor(ORTHO_LAYER_ID)
                    ?: DefaultLayers.catalog.layers
                        .firstOrNull { it.id == ORTHO_LAYER_ID }?.source,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onTap = { lat, lon -> viewModel.tapTarget(lat, lon) },
            )

            GcpControls(
                state = state,
                onSimilarity = viewModel::setSimilarity,
                onPreview = viewModel::preview,
                onClearPreview = viewModel::clearPreview,
                onSave = { askLabel = true },
                onExport = { viewModel.exportGcpJson(null, 0, 0) },
                onCancelPending = viewModel::cancelPending,
                onRemovePoint = viewModel::removePoint,
            )
        }
    }

    if (askLabel) {
        var label by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askLabel = false },
            title = { Text("Uložit jako kalibraci") },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text("Název") },
                )
            },
            confirmButton = {
                Button(onClick = {
                    askLabel = false
                    viewModel.saveAsCalibration(label)
                }) { Text("Uložit") }
            },
            dismissButton = { TextButton(onClick = { askLabel = false }) { Text("Zrušit") } },
        )
    }
}

private const val ORTHO_LAYER_ID = "ortofoto"

@Composable
private fun PaneLabel(text: String) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelLarge,
    )
}

/** One map pane rendering a single raster layer, with a tap callback and a crosshair. */
@Composable
private fun GcpMapPane(
    layerId: String,
    urlTemplate: String?,
    modifier: Modifier = Modifier,
    onTap: (Double, Double) -> Unit,
) {
    val mapView = rememberMapViewWithLifecycle()
    var initialised by remember { mutableStateOf(false) }

    Box(modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                if (!initialised) {
                    initialised = true
                    view.getMapAsync { map: MapLibreMap ->
                        map.uiSettings.isRotateGesturesEnabled = false
                        map.uiSettings.isTiltGesturesEnabled = false
                        map.setStyle(Style.Builder().fromJson(MapStyle.emptyStyleJson())) { style ->
                            if (urlTemplate != null) {
                                runCatching {
                                    val sourceId = MapStyle.rasterSourceId(layerId)
                                    style.addSource(
                                        RasterSource(sourceId, TileSet("2.2.0", urlTemplate), 256),
                                    )
                                    style.addLayer(
                                        RasterLayer(MapStyle.rasterLayerId(layerId), sourceId)
                                            .withProperties(PropertyFactory.rasterOpacity(1f)),
                                    )
                                }
                            }
                        }
                        map.moveCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(LatLng(49.8, 15.5))
                                    .zoom(13.0)
                                    .build(),
                            ),
                        )
                        map.addOnMapClickListener { latLng ->
                            onTap(latLng.latitude, latLng.longitude)
                            true
                        }
                    }
                }
            },
        )
        // Crosshair so the tap target is unambiguous.
        Box(
            Modifier
                .align(Alignment.Center)
                .background(Color(0x88FF0000))
                .fillMaxWidth(0.002f)
                .heightIn(min = 24.dp),
        )
    }
}

@Composable
private fun GcpControls(
    state: GcpEditorState,
    onSimilarity: (Boolean) -> Unit,
    onPreview: () -> Unit,
    onClearPreview: () -> Unit,
    onSave: () -> Unit,
    onExport: () -> Unit,
    onCancelPending: () -> Unit,
    onRemovePoint: (Long) -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("${state.points.size} bodů", style = MaterialTheme.typography.titleSmall)
                if (state.transform != null) {
                    Text(
                        String.format(Locale.forLanguageTag("cs"), "RMSE %.1f m", state.rmseM),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (state.rmseM > 40) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                if (state.pending != null) {
                    TextButton(onClick = onCancelPending) { Text("Zrušit rozdělaný bod") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = state.useSimilarity,
                    onClick = { onSimilarity(true) },
                    label = { Text("Similarity (4 DOF)") },
                )
                FilterChip(
                    selected = !state.useSimilarity,
                    onClick = { onSimilarity(false) },
                    label = { Text("Afinní (6 DOF)") },
                )
            }

            if (state.tpsAdvisable) {
                Text(
                    "6+ bodů — pro přesný TPS warp exportuj GCP a spusť tools/warp_scan.py " +
                        "na desktopu. V telefonu se TPS nepočítá.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onPreview, enabled = state.canFit) { Text("Náhled") }
                TextButton(onClick = onClearPreview, enabled = state.previewApplied) {
                    Text("Zrušit náhled")
                }
                Button(onClick = onSave, enabled = state.canFit) { Text("Uložit") }
                TextButton(onClick = onExport, enabled = state.points.isNotEmpty()) {
                    Text("Export GCP")
                }
            }

            LazyColumn(Modifier.heightIn(max = 140.dp)) {
                items(state.points, key = { it.id }) { point ->
                    val index = state.points.indexOf(point)
                    val residual = state.residualsM.getOrNull(index)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            String.format(
                                Locale.forLanguageTag("cs"),
                                "%d. %.5f, %.5f%s",
                                index + 1,
                                WebMercator.metersToLat(point.dstY),
                                WebMercator.metersToLon(point.dstX),
                                residual?.let { "  •  odchylka ${it.roundToInt()} m" } ?: "",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onRemovePoint(point.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Smazat bod")
                        }
                    }
                }
            }
        }
    }
}
