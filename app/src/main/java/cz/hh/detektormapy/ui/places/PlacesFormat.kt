package cz.hh.detektormapy.ui.places

import androidx.navigation.NavHostController
import cz.hh.detektormapy.ui.nav.TopDestination
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small formatting and navigation helpers shared by the places list and the place detail.
 *
 * They live in one file so the two screens cannot drift apart in how they render a timestamp or
 * how they hand a navigation target back to the map.
 */
internal val CS_LOCALE: Locale = Locale.forLanguageTag("cs")

/** Key the map screen reads to pick up a waypoint the user asked to navigate to. */
internal const val NAVIGATE_TO_PLACE_KEY = "navigateToPlaceId"

internal fun formatDateTime(millis: Long?): String =
    if (millis == null) "—" else SimpleDateFormat("d. M. yyyy HH:mm", CS_LOCALE).format(Date(millis))

internal fun formatDate(millis: Long?): String =
    if (millis == null) "—" else SimpleDateFormat("d. M. yyyy", CS_LOCALE).format(Date(millis))

/** WGS84 pair with six decimals -- roughly 0,1 m, which is well below any GPS accuracy. */
internal fun formatCoordinates(lat: Double, lon: Double): String = String.format(CS_LOCALE, "%.6f, %.6f", lat, lon)

internal fun formatHectares(ha: Double): String = String.format(CS_LOCALE, "%.2f ha", ha)

/**
 * Hands [placeId] to the previous screen (the map) as a navigation target and goes back to it.
 *
 * The map owns the "navigate to" state, so the list only publishes an id into the previous back
 * stack entry instead of duplicating the arrow/bearing logic. Falls back to a plain navigation to
 * the map tab when there is nothing to pop, which happens on a deep link.
 */
internal fun navigateToPlaceOnMap(navController: NavHostController, placeId: Long) {
    val previous = navController.previousBackStackEntry
    if (previous == null) {
        navController.navigate(TopDestination.Map.route)
        return
    }
    previous.savedStateHandle.set(NAVIGATE_TO_PLACE_KEY, placeId)
    navController.popBackStack()
}
