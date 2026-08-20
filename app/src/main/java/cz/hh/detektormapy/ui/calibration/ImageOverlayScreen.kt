package cz.hh.detektormapy.ui.calibration

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import cz.hh.detektormapy.ui.map.MapStyle
import cz.hh.detektormapy.ui.map.rememberMapViewWithLifecycle
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource
import kotlin.math.roundToInt

private const val IMAGE_SOURCE_ID = "manual-image-src"
private const val IMAGE_LAYER_ID = "manual-image-layer"

/**
 * Quick single-sheet overlay (issue F3-5).
 *
 * Sometimes there is no time for the full desktop warp: you downloaded one cadastral sheet
 * from ÚAZK, you are standing in the field, and you just want it roughly on the map. Four
 * draggable corners get you there in under a minute, at the cost of only ever being an
 * approximation -- exactly the trade-off PLAN.md section 6 calls the always-available fallback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageOverlayScreen(navController: NavHostController) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val mapView = rememberMapViewWithLifecycle()

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var opacity by remember { mutableStateOf(0.7f) }
    var corners by remember { mutableStateOf<List<LatLng>?>(null) }
    var cameraTick by remember { mutableStateOf(0) }
    var initialised by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        imageUri = uri
        val map = mapRef ?: return@rememberLauncherForActivityResult
        corners = defaultQuadFor(map)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Přiložit sken") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    if (initialised) return@AndroidView
                    initialised = true
                    view.getMapAsync { map ->
                        mapRef = map
                        map.setStyle(Style.Builder().fromJson(MapStyle.emptyStyleJson())) { style ->
                            styleRef = style
                        }
                        map.addOnCameraMoveListener { cameraTick++ }
                        map.addOnCameraIdleListener { cameraTick++ }
                    }
                },
            )

            // Push the current image + quad into the style whenever either changes.
            val style = styleRef
            val map = mapRef
            val quad = corners
            if (style != null && map != null && quad != null && imageUri != null) {
                remember(quad, imageUri, opacity) {
                    applyImageSource(context, style, imageUri, quad, opacity)
                    true
                }
            }

            // Draggable corner handles, positioned by projecting the quad to the screen.
            if (map != null && quad != null) {
                @Suppress("UNUSED_EXPRESSION")
                cameraTick // recompose the handles as the camera moves
                quad.forEachIndexed { index, corner ->
                    val screen = map.projection.toScreenLocation(corner)
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    (screen.x - with(density) { 16.dp.toPx() }).roundToInt(),
                                    (screen.y - with(density) { 16.dp.toPx() }).roundToInt(),
                                )
                            }
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xCCB3261E))
                            .pointerInput(index, quad) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val current = map.projection.toScreenLocation(quad[index])
                                    val moved = android.graphics.PointF(
                                        current.x + dragAmount.x,
                                        current.y + dragAmount.y,
                                    )
                                    val latLng = map.projection.fromScreenLocation(moved)
                                    corners = quad.toMutableList().also { it[index] = latLng }
                                }
                            },
                    )
                }
            }

            Card(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (imageUri == null) {
                            "Vyber sken (PNG/JPG), roztáhni ho čtyřmi rohy na správné místo."
                        } else {
                            "Táhni červené rohy. Průhlednost ${(opacity * 100).roundToInt()} %."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 0f..1f)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { picker.launch(arrayOf("image/*")) }) {
                            Text(if (imageUri == null) "Vybrat sken" else "Jiný sken")
                        }
                        TextButton(
                            onClick = { mapRef?.let { corners = defaultQuadFor(it) } },
                            enabled = imageUri != null,
                        ) { Text("Vycentrovat") }
                        TextButton(
                            onClick = {
                                imageUri = null
                                corners = null
                                styleRef?.let { s ->
                                    runCatching { s.removeLayer(IMAGE_LAYER_ID) }
                                    runCatching { s.removeSource(IMAGE_SOURCE_ID) }
                                }
                            },
                            enabled = imageUri != null,
                        ) { Text("Odebrat") }
                    }
                }
            }
        }
    }
}

/** Places the quad over the middle half of the current viewport. */
private fun defaultQuadFor(map: MapLibreMap): List<LatLng> {
    val region = map.projection.visibleRegion.latLngBounds
    val dLat = (region.latitudeNorth - region.latitudeSouth) / 4.0
    val dLon = (region.longitudeEast - region.longitudeWest) / 4.0
    val north = region.latitudeNorth - dLat
    val south = region.latitudeSouth + dLat
    val west = region.longitudeWest + dLon
    val east = region.longitudeEast - dLon
    return listOf(
        LatLng(north, west),
        LatLng(north, east),
        LatLng(south, east),
        LatLng(south, west),
    )
}

private fun applyImageSource(
    context: android.content.Context,
    style: Style,
    uri: Uri?,
    corners: List<LatLng>,
    opacity: Float,
) {
    if (uri == null || corners.size != 4) return
    val quad = LatLngQuad(corners[0], corners[1], corners[2], corners[3])
    val existing = style.getSourceAs<ImageSource>(IMAGE_SOURCE_ID)
    if (existing != null) {
        existing.setCoordinates(quad)
        (style.getLayer(IMAGE_LAYER_ID) as? RasterLayer)
            ?.setProperties(PropertyFactory.rasterOpacity(opacity))
        return
    }
    val bitmap = runCatching {
        context.contentResolver.openInputStream(uri).use { stream ->
            BitmapFactory.decodeStream(
                stream,
                null,
                BitmapFactory.Options().apply {
                    // Big cadastral scans routinely exceed 8000 px; downsample so we never OOM.
                    inSampleSize = 2
                },
            )
        }
    }.getOrNull() ?: return

    runCatching {
        style.addSource(ImageSource(IMAGE_SOURCE_ID, quad, bitmap))
        style.addLayer(
            RasterLayer(IMAGE_LAYER_ID, IMAGE_SOURCE_ID)
                .withProperties(PropertyFactory.rasterOpacity(opacity)),
        )
    }
}
