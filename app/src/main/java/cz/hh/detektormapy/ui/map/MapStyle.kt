package cz.hh.detektormapy.ui.map

/**
 * Base MapLibre style.
 *
 * Deliberately minimal: the app composes every visible raster layer at runtime from
 * `layers.json`, so the style only has to provide a neutral background to draw on. A local
 * vector basemap (F1-5) is added the same way, as an extra source in the same style.
 */
object MapStyle {

    const val BACKGROUND_LAYER = "background"

    /** Layer ids reserved for app-owned overlays, so raster layers can be inserted below them. */
    const val LAYER_AREAS_FILL = "app-areas-fill"
    const val LAYER_AREAS_LINE = "app-areas-line"
    const val LAYER_UAN_FILL = "app-uan-fill"
    const val LAYER_UAN_LINE = "app-uan-line"
    const val LAYER_TRACK = "app-track"
    const val LAYER_PLACES = "app-places"
    const val LAYER_FINDS = "app-finds"

    const val SOURCE_AREAS = "app-areas-src"
    const val SOURCE_UAN = "app-uan-src"
    const val SOURCE_TRACK = "app-track-src"
    const val SOURCE_PLACES = "app-places-src"
    const val SOURCE_FINDS = "app-finds-src"

    fun rasterSourceId(layerId: String) = "raster-src-$layerId"

    fun rasterLayerId(layerId: String) = "raster-layer-$layerId"

    /** Empty style with a paper-coloured background, so a missing basemap still looks intentional. */
    fun emptyStyleJson(backgroundColor: String = "#EFEAE0"): String = """
    {
      "version": 8,
      "name": "DetektorMapy",
      "sources": {},
      "layers": [
        {
          "id": "$BACKGROUND_LAYER",
          "type": "background",
          "paint": { "background-color": "$backgroundColor" }
        }
      ]
    }
    """.trimIndent()
}
