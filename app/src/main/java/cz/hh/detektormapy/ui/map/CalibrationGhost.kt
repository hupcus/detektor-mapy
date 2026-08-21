package cz.hh.detektormapy.ui.map

import android.util.Log
import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.map.OverlayMosaic
import cz.hh.detektormapy.map.OverlaySnapshot
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource

/**
 * The overlay stand-in that actually moves under the user's fingers in Režim A.
 *
 * MapLibre Android cannot translate a raster layer and cannot refresh a raster source, so
 * "drag the old map onto the pond" had nothing to drag: the tile server was warping bytes that
 * MapLibre never asked for again. An `ImageSource` is the one thing in the style that *is*
 * defined by four corners, and moving those corners costs a single native call, so the whole
 * alignment gesture runs on one stitched snapshot at full frame rate. The real, tile-based
 * layer is restored -- properly warped this time -- as soon as the user saves or cancels.
 */
class CalibrationGhost private constructor(private val style: Style, private val boundsM: DoubleArray) {

    /** Moves the ghost to where [transform] puts it. Cheap enough to call on every gesture frame. */
    fun apply(transform: Affine2D) {
        val source = style.getSourceAs<ImageSource>(SOURCE_ID) ?: return
        runCatching { source.setCoordinates(quadOf(transform)) }
            .onFailure { Log.w(TAG, "Náhled kalibrace nelze posunout", it) }
    }

    fun remove() {
        runCatching { style.removeLayer(LAYER_ID) }
        runCatching { style.removeSource(SOURCE_ID) }
    }

    private fun quadOf(transform: Affine2D): LatLngQuad {
        val q = OverlayMosaic.quadLatLon(boundsM, transform)
        return LatLngQuad(
            LatLng(q[0], q[1]),
            LatLng(q[2], q[3]),
            LatLng(q[4], q[5]),
            LatLng(q[6], q[7]),
        )
    }

    companion object {
        private const val TAG = "CalibrationGhost"
        private const val SOURCE_ID = "calibration-ghost-src"
        private const val LAYER_ID = "calibration-ghost-layer"

        /**
         * Installs the ghost directly above the layer it replaces, so it keeps that layer's place
         * in the stack instead of jumping on top of everything.
         */
        fun attach(
            style: Style,
            layerId: String,
            snapshot: OverlaySnapshot,
            transform: Affine2D,
            opacity: Float,
        ): CalibrationGhost? {
            val ghost = CalibrationGhost(style, snapshot.boundsMeters)
            // A ghost left over from an interrupted session would make addSource throw.
            ghost.remove()
            return runCatching {
                style.addSource(ImageSource(SOURCE_ID, ghost.quadOf(transform), snapshot.bitmap))
                val layer = RasterLayer(LAYER_ID, SOURCE_ID)
                    .withProperties(
                        PropertyFactory.rasterOpacity(opacity),
                        PropertyFactory.rasterFadeDuration(0f),
                    )
                val anchor = MapStyle.rasterLayerId(layerId).takeIf { style.getLayer(it) != null }
                if (anchor != null) style.addLayerAbove(layer, anchor) else style.addLayer(layer)
                ghost
            }.onFailure { Log.w(TAG, "Náhled kalibrace nelze zobrazit", it) }.getOrNull()
        }
    }
}
