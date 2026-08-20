package cz.hh.detektormapy.ui.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView

/**
 * A [MapView] wired to the composition's lifecycle.
 *
 * MapLibre's native view keeps a GL surface and a tile worker pool alive; forgetting a single
 * `onPause`/`onDestroy` leaks both. Doing it once here means every screen that shows a map
 * gets it right.
 */
@Composable
fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { createMapView(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, mapView) {
        // MapLibre's onDestroy is not documented as idempotent, and the observer plus onDispose
        // would otherwise both call it on a normal teardown.
        var destroyed = false
        fun destroyOnce() {
            if (!destroyed) {
                destroyed = true
                mapView.onDestroy()
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> destroyOnce()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            destroyOnce()
        }
    }
    return mapView
}

private fun createMapView(context: Context): MapView {
    // No API key: every source we use is either local or a plain public tile URL.
    MapLibre.getInstance(context)
    return MapView(context)
}
