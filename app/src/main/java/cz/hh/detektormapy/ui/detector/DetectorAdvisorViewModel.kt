package cz.hh.detektormapy.ui.detector

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.hh.detektormapy.data.DetectorPreferences
import cz.hh.detektormapy.data.model.SoilCondition
import cz.hh.detektormapy.data.model.Terrain
import cz.hh.detektormapy.data.relation.DetectorWithPresets
import cz.hh.detektormapy.data.repository.DetectorRepository
import cz.hh.detektormapy.detector.DetectorAdvice
import cz.hh.detektormapy.detector.PresetMatch
import cz.hh.detektormapy.detector.PresetRanking
import cz.hh.detektormapy.detector.SoilEstimate
import cz.hh.detektormapy.di.IoDispatcher
import cz.hh.detektormapy.location.LocationProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
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

    val terrainTips: List<String> = DetectorAdvice.forTerrain(terrain)

    val soilTips: List<String> = soil?.let { DetectorAdvice.forSoil(it) } ?: emptyList()
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
    @param:IoDispatcher private val io: CoroutineDispatcher,
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
            val reading = fetchSoilReading(fix.lat, fix.lon)
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
    private data class SoilReading(val soilMoistureM3M3: Double?, val recentRainMm: Double?)

    /**
     * Soil moisture and recent rain from open-meteo.
     *
     * The hourly series is used rather than `current` because the soil layers are only published
     * hourly, and `past_days=3` is what turns "it rained" into "it rained recently". Times are
     * requested as unix seconds so nothing here has to parse a timezone.
     *
     * Fails silently on purpose -- airplane mode in a forest is the normal case, and the screen
     * then reads "počasí nedostupné" and stays fully usable with a manual override.
     */
    private suspend fun fetchSoilReading(lat: Double, lon: Double): SoilReading? = withContext(io) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$lat&longitude=$lon" +
                    "&hourly=soil_moisture_3_to_9cm,precipitation" +
                    "&past_days=$PAST_DAYS&forecast_days=1" +
                    "&timeformat=unixtime&timezone=UTC",
            )
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            if (connection.responseCode !in 200..299) return@withContext null
            val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            parseSoilReading(JSONObject(body))
        } catch (e: Exception) {
            Log.i(TAG, "Počasí není dostupné: ${e.message}")
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun parseSoilReading(root: JSONObject): SoilReading? {
        val hourly = root.optJSONObject("hourly") ?: return null
        val times = hourly.optJSONArray("time") ?: return null
        val nowSec = System.currentTimeMillis() / 1000L

        // The last hour that has already happened; the series runs into the forecast.
        var index = -1
        for (i in 0 until times.length()) {
            if (times.optLong(i, Long.MAX_VALUE) <= nowSec) index = i else break
        }
        if (index < 0) return null

        val moistureSeries = hourly.optJSONArray("soil_moisture_3_to_9cm")
        val moisture = moistureSeries?.let { series ->
            (index downTo 0).firstNotNullOfOrNull { series.optDoubleOrNull(it) }
        }

        val rainSeries = hourly.optJSONArray("precipitation")
        val rain = rainSeries?.let { series ->
            val since = nowSec - PAST_DAYS * SECONDS_PER_DAY
            (0..index).sumOf { i ->
                if (times.optLong(i, 0L) >= since) series.optDoubleOrNull(i) ?: 0.0 else 0.0
            }
        }

        return if (moisture == null && rain == null) null else SoilReading(moisture, rain)
    }

    private fun JSONArray.optDoubleOrNull(index: Int): Double? {
        if (isNull(index)) return null
        val value = optDouble(index, Double.NaN)
        return if (value.isNaN()) null else value
    }

    private companion object {
        const val TAG = "DetectorAdvisorVM"
        const val TIMEOUT_MS = 5_000
        const val FIX_TIMEOUT_MS = 10_000L
        const val PAST_DAYS = 3L
        const val SECONDS_PER_DAY = 86_400L
    }
}
