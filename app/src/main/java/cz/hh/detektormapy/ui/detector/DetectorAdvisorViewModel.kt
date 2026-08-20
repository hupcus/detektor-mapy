package cz.hh.detektormapy.ui.detector

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.hh.detektormapy.data.DetectorPreferences
import cz.hh.detektormapy.data.model.SoilCondition
import cz.hh.detektormapy.data.model.Terrain
import cz.hh.detektormapy.data.relation.DetectorWithPresets
import cz.hh.detektormapy.data.repository.DetectorRepository
import cz.hh.detektormapy.detector.PresetMatch
import cz.hh.detektormapy.detector.PresetRanking
import cz.hh.detektormapy.detector.SoilEstimate
import cz.hh.detektormapy.detector.SoilReadingFetcher
import cz.hh.detektormapy.location.LocationProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Whether the soil estimate has anything behind it yet. */
enum class WeatherStatus { LOADING, OK, UNAVAILABLE }

/**
 * Everything the advisor screen draws.
 *
 * [soil] deliberately prefers [soilOverride]: the model is an ~11 km grid cell and the user is
 * standing in the actual field, so their eyes beat it every time.
 */
data class DetectorAdvisorUiState(
    val terrain: Terrain = Terrain.DEFAULT,
    val estimatedSoil: SoilCondition? = null,
    val soilOverride: SoilCondition? = null,
    val soilMoistureM3M3: Double? = null,
    val recentRainMm: Double? = null,
    val weatherStatus: WeatherStatus = WeatherStatus.LOADING,
    val hasLocation: Boolean = false,
    val library: List<DetectorWithPresets> = emptyList(),
) {
    val soil: SoilCondition? = soilOverride ?: estimatedSoil

    /** The user's own presets, best match first. Empty when the library is empty. */
    val ranked: List<PresetMatch> = PresetRanking.rank(library, terrain, soil)

    val hasPresets: Boolean = library.any { it.presets.isNotEmpty() }
}

/**
 * State holder for the advisor (queue item "Nastavení detektoru + rozřazovací systém").
 *
 * Two inputs, one output. Terrain comes from the user -- the app has no offline land-cover data
 * and will not pretend to recognise a forest from coordinates, so it only remembers the last
 * answer. Soil comes from a weather model and is always overridable. The output is the user's own
 * presets, ranked, plus rules of thumb that are labelled as rules of thumb.
 *
 * Nothing here may fail loudly: no signal, no permission and an empty library are all normal
 * states in a field, and each of them has to render as a sentence rather than a crash.
 */
@HiltViewModel
class DetectorAdvisorViewModel @Inject constructor(
    private val repository: DetectorRepository,
    private val preferences: DetectorPreferences,
    private val locationProvider: LocationProvider,
    private val soilFetcher: SoilReadingFetcher,
) : ViewModel() {

    private val stateFlow = MutableStateFlow(DetectorAdvisorUiState())
    val state: StateFlow<DetectorAdvisorUiState> = stateFlow.asStateFlow()

    init {
        observeLibrary()
        restoreTerrain()
        refresh()
    }

    /** Re-reads the position and asks the weather model again. */
    fun refresh() {
        viewModelScope.launch {
            stateFlow.value = stateFlow.value.copy(weatherStatus = WeatherStatus.LOADING)
            // A cold start with no cached fix must not hang the screen: wait for one fix,
            // briefly, then carry on without it and let the user set the soil by hand.
            val fix = locationProvider.lastKnown() ?: runCatching {
                withTimeoutOrNull(FIX_TIMEOUT_MS) { locationProvider.fixes().catch { }.first() }
            }.getOrNull()
            if (fix == null) {
                stateFlow.value = stateFlow.value.copy(
                    hasLocation = false,
                    weatherStatus = WeatherStatus.UNAVAILABLE,
                )
                return@launch
            }
            val reading = soilFetcher.fetch(fix.lat, fix.lon)
            val estimate = reading?.let { SoilEstimate.estimate(it.soilMoistureM3M3, it.recentRainMm) }
            stateFlow.value = stateFlow.value.copy(
                hasLocation = true,
                soilMoistureM3M3 = reading?.soilMoistureM3M3,
                recentRainMm = reading?.recentRainMm,
                estimatedSoil = estimate,
                weatherStatus = if (estimate == null) WeatherStatus.UNAVAILABLE else WeatherStatus.OK,
            )
        }
    }

    fun selectTerrain(terrain: Terrain) {
        stateFlow.value = stateFlow.value.copy(terrain = terrain)
        viewModelScope.launch { runCatching { preferences.setLastTerrain(terrain) } }
    }

    /** Passing `null` hands the decision back to the weather model. */
    fun overrideSoil(soil: SoilCondition?) {
        stateFlow.value = stateFlow.value.copy(soilOverride = soil)
    }

    // --- internals -------------------------------------------------------------------

    private fun observeLibrary() {
        viewModelScope.launch {
            repository.observeLibrary()
                .catch { Log.w(TAG, "Knihovnu detektorů se nepodařilo načíst", it) }
                .collect { stateFlow.value = stateFlow.value.copy(library = it) }
        }
    }

    private fun restoreTerrain() {
        viewModelScope.launch {
            val remembered = runCatching { preferences.lastTerrain.first() }.getOrNull() ?: return@launch
            // Only if the user has not already tapped a chip while the store was being read.
            if (stateFlow.value.terrain == Terrain.DEFAULT) {
                stateFlow.value = stateFlow.value.copy(terrain = remembered)
            }
        }
    }

    /** Raw numbers behind the estimate; every field is optional because the model may omit it. */
    private companion object {
        const val TAG = "DetectorAdvisorVM"
        const val FIX_TIMEOUT_MS = 10_000L
    }
}
