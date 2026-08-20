package cz.hh.detektormapy.ui.map

import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Transparent gesture catcher used in Režim A.
 *
 * While it is composed the map itself receives no touches at all, so the OSM basemap stays
 * frozen and only the selected historical overlay moves -- exactly the mental model the field
 * workflow needs ("the old map is wrong, drag *it* onto the pond").
 */
@Composable
fun CalibrationGestureLayer(
    modifier: Modifier = Modifier,
    onTransform: (centroid: PointF, pan: PointF, rotationDeg: Float, zoom: Float) -> Unit,
) {
    Box(
        modifier
            .background(Color.Transparent)
            .pointerInput(Unit) {
                detectTransformGestures(panZoomLock = false) { centroid, pan, zoom, rotation ->
                    onTransform(
                        PointF(centroid.x, centroid.y),
                        PointF(pan.x, pan.y),
                        rotation,
                        zoom,
                    )
                }
            },
    )
}
