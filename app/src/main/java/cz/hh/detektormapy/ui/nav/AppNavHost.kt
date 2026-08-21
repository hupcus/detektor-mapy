package cz.hh.detektormapy.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import cz.hh.detektormapy.ui.calibration.GcpEditorScreen
import cz.hh.detektormapy.ui.calibration.ImageOverlayScreen
import cz.hh.detektormapy.ui.detector.DetectorAdvisorScreen
import cz.hh.detektormapy.ui.detector.DetectorProfilesScreen
import cz.hh.detektormapy.ui.finds.FindCaptureScreen
import cz.hh.detektormapy.ui.finds.FindDetailScreen
import cz.hh.detektormapy.ui.finds.FindsScreen
import cz.hh.detektormapy.ui.map.MapScreen
import cz.hh.detektormapy.ui.places.PlaceDetailScreen
import cz.hh.detektormapy.ui.places.PlacesScreen
import cz.hh.detektormapy.ui.settings.AboutScreen
import cz.hh.detektormapy.ui.settings.CalibrationListScreen
import cz.hh.detektormapy.ui.settings.PreflightScreen
import cz.hh.detektormapy.ui.settings.SettingsScreen
import cz.hh.detektormapy.ui.settings.StorageScreen
import cz.hh.detektormapy.ui.settings.TrackDetailScreen
import cz.hh.detektormapy.ui.settings.TracksScreen
import cz.hh.detektormapy.ui.settings.VersionScreen

@Composable
fun AppNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = TopDestination.Map.route) {
        composable(TopDestination.Map.route) {
            MapScreen(navController)
        }
        composable(TopDestination.Finds.route) {
            FindsScreen(navController)
        }
        composable(TopDestination.Places.route) {
            PlacesScreen(navController)
        }
        composable(TopDestination.Settings.route) {
            SettingsScreen(navController)
        }
        composable(Routes.FIND_CAPTURE) {
            FindCaptureScreen(navController)
        }
        composable(
            Routes.FIND_DETAIL,
            arguments = listOf(navArgument("findId") { type = NavType.LongType }),
        ) { entry ->
            FindDetailScreen(navController, entry.arguments?.getLong("findId") ?: -1L)
        }
        composable(
            Routes.PLACE_DETAIL,
            arguments = listOf(navArgument("placeId") { type = NavType.LongType }),
        ) { entry ->
            PlaceDetailScreen(navController, entry.arguments?.getLong("placeId") ?: -1L)
        }
        composable(
            Routes.CALIBRATIONS,
            arguments = listOf(navArgument("layerId") { type = NavType.StringType }),
        ) { entry ->
            CalibrationListScreen(navController, entry.arguments?.getString("layerId").orEmpty())
        }
        composable(
            Routes.GCP_EDITOR,
            arguments = listOf(navArgument("layerId") { type = NavType.StringType }),
        ) { entry ->
            GcpEditorScreen(navController, entry.arguments?.getString("layerId").orEmpty())
        }
        composable(Routes.IMAGE_OVERLAY) { ImageOverlayScreen(navController) }
        composable(Routes.TRACKS) { TracksScreen(navController) }
        composable(
            Routes.TRACK_DETAIL,
            arguments = listOf(navArgument("trackId") { type = NavType.LongType }),
        ) {
            TrackDetailScreen(navController)
        }
        composable(Routes.PREFLIGHT) { PreflightScreen(navController) }
        composable(Routes.ABOUT) { AboutScreen(navController) }
        composable(Routes.STORAGE) { StorageScreen(navController) }
        composable(Routes.VERSION) { VersionScreen(navController) }
        composable(Routes.DETECTOR_ADVISOR) { DetectorAdvisorScreen(navController) }
        composable(Routes.DETECTOR_PROFILES) { DetectorProfilesScreen(navController) }
    }
}
