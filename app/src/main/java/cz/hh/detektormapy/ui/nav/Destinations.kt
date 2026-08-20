package cz.hh.detektormapy.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stars
import androidx.compose.ui.graphics.vector.ImageVector
import cz.hh.detektormapy.R

/** Top-level tabs. Detail screens are pushed on top of the active tab. */
enum class TopDestination(val route: String, val labelRes: Int, val icon: ImageVector) {
    Map("map", R.string.nav_map, Icons.Filled.Map),
    Finds("finds", R.string.nav_finds, Icons.Filled.Stars),
    Places("places", R.string.nav_places, Icons.Filled.Place),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings),
}

object Routes {
    const val FIND_DETAIL = "find/{findId}"
    const val FIND_CAPTURE = "find/capture"
    const val PLACE_DETAIL = "place/{placeId}"
    const val CALIBRATIONS = "calibrations/{layerId}"
    const val GCP_EDITOR = "gcp/{layerId}"
    const val TRACKS = "tracks"
    const val PREFLIGHT = "preflight"
    const val ABOUT = "about"
    const val IMAGE_OVERLAY = "image-overlay"

    fun findDetail(id: Long) = "find/$id"
    fun placeDetail(id: Long) = "place/$id"
    fun calibrations(layerId: String) = "calibrations/$layerId"
    fun gcpEditor(layerId: String) = "gcp/$layerId"
}
