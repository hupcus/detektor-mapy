package cz.hh.detektormapy.ui.settings

import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.hh.detektormapy.data.AppDirectories
import cz.hh.detektormapy.data.model.SoilCondition
import cz.hh.detektormapy.data.repository.CalibrationRepository
import cz.hh.detektormapy.detector.SoilEstimate
import cz.hh.detektormapy.detector.SoilReadingFetcher
import cz.hh.detektormapy.di.IoDispatcher
import cz.hh.detektormapy.location.Fix
import cz.hh.detektormapy.location.LocationProvider
import cz.hh.detektormapy.map.LayerManager
import cz.hh.detektormapy.util.Geo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import javax.inject.Inject

/** Current conditions from open-meteo. Every field is optional -- the API may omit any of them. */
data class WeatherSnapshot(val temperatureC: Double?, val precipitationMm: Double?, val windKmh: Double?)

/** One layer as the pre-flight checklist cares about it: can I use it without a signal? */
data class LayerReadiness(val title: String, val offline: Boolean, val available: Boolean, val problem: String?)

data class PreflightUiState(
    val fix: Fix? = null,
    val locationPermission: Boolean = true,
    val gpsEnabled: Boolean = true,
    val sunriseMillis: Long? = null,
    val sunsetMillis: Long? = null,
    val daylightLeftMs: Long? = null,
    val nearbyCalibrations: List<String> = emptyList(),
    val layers: List<LayerReadiness> = emptyList(),
    val freeStorageBytes: Long? = null,
    val storagePath: String = "",
    val batteryPercent: Int? = null,
    val weather: WeatherSnapshot? = null,
    val weatherLoading: Boolean = false,
    /** Model estimate of how wet the ground is; null when offline or the model has no data. */
    val soil: SoilCondition? = null,
    val refreshing: Boolean = false,
) {
    val offlineLayers: List<LayerReadiness> get() = layers.filter { it.offline }
    val onlineLayers: List<LayerReadiness> get() = layers.filter { !it.offline }
}

/**
 * State holder for the "one screen before driving out" check (PLAN.md F5-2).
 *
 * Everything here answers a question that is expensive to answer once you are already standing in
 * a field: how much daylight is left, is the map actually on the phone or only reachable online,
 * is there room for tiles, and how much battery is left for a full day of GPS.
 */
@HiltViewModel
class PreflightViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val locationProvider: LocationProvider,
    private val layerManager: LayerManager,
    private val calibrationRepository: CalibrationRepository,
    private val directories: AppDirectories,
    private val soilFetcher: SoilReadingFetcher,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val stateFlow = MutableStateFlow(PreflightUiState())
    val state: StateFlow<PreflightUiState> = stateFlow.asStateFlow()

    init {
        layerManager.ensureStarted()
        observeLocation()
        refresh()
    }

    /** Recomputes everything, including a fresh weather request. */
    fun refresh() {
        viewModelScope.launch {
            stateFlow.value = stateFlow.value.copy(refreshing = true)
            applyDeviceInfo()
            applyLayers()
            applyCalibrations()
            stateFlow.value = stateFlow.value.copy(refreshing = false)
            loadWeather()
        }
    }

    // --- internals -------------------------------------------------------------------

    private fun observeLocation() {
        applyFix(locationProvider.lastKnown(), initial = true)
        viewModelScope.launch {
            locationProvider.fixes()
                .catch { /* No permission or provider: the screen says so and stays usable. */ }
                .collect { applyFix(it, initial = false) }
        }
    }

    private fun applyFix(fix: Fix?, initial: Boolean) {
        val previous = stateFlow.value.fix
        val sun = fix?.let { Geo.sunTimes(it.lat, it.lon, LocalDate.now().toEpochDay()) }
        val now = System.currentTimeMillis()
        stateFlow.value = stateFlow.value.copy(
            fix = fix ?: previous,
            locationPermission = locationProvider.hasPermission(),
            gpsEnabled = locationProvider.isGpsEnabled(),
            sunriseMillis = sun?.first ?: stateFlow.value.sunriseMillis,
            sunsetMillis = sun?.second ?: stateFlow.value.sunsetMillis,
            daylightLeftMs = sun?.second?.let { (it - now).coerceAtLeast(0L) },
        )
        // The first fix is what makes the position-dependent parts meaningful, so redo them once.
        if (fix != null && (previous == null || initial)) {
            viewModelScope.launch {
                applyCalibrations()
                loadWeather()
            }
        }
    }

    private fun applyDeviceInfo() {
        val layersDir = directories.layersDir
        val free = runCatching { StatFs(layersDir.absolutePath).availableBytes }.getOrNull()
        val battery = runCatching {
            val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()?.takeIf { it in 0..100 }

        stateFlow.value = stateFlow.value.copy(
            freeStorageBytes = free,
            storagePath = layersDir.absolutePath,
            batteryPercent = battery,
        )
    }

    private fun applyLayers() {
        val readiness = layerManager.layers.value.map { layer ->
            LayerReadiness(
                title = layer.def.title,
                offline = layer.def.isLocal,
                available = layer.available,
                problem = layer.unavailableReason,
            )
        }
        stateFlow.value = stateFlow.value.copy(layers = readiness)
    }

    private suspend fun applyCalibrations() {
        val fix = stateFlow.value.fix
        if (fix == null) {
            stateFlow.value = stateFlow.value.copy(nearbyCalibrations = emptyList())
            return
        }
        val labels = mutableListOf<String>()
        layerManager.layers.value
            .filter { it.def.isRaster && !it.def.isBasemap }
            .forEach { layer ->
                val best = runCatching {
                    calibrationRepository.getBestCalibrationFor(layer.def.id, fix.lat, fix.lon)
                }.getOrNull()
                if (best != null) labels += "${layer.def.title}: ${best.label}"
            }
        stateFlow.value = stateFlow.value.copy(nearbyCalibrations = labels)
    }

    private suspend fun loadWeather() {
        val fix = stateFlow.value.fix ?: return
        // The first fix and the initial refresh can race; one request is enough.
        if (stateFlow.value.weatherLoading) return
        stateFlow.value = stateFlow.value.copy(weatherLoading = true)
        val weather = fetchWeather(fix.lat, fix.lon)
        // Soil moisture from the queue item "vlhkost půdy do pre-flightu": an orientation,
        // not a depth prediction — the advisor explains the nuance, this screen just states it.
        val soil = soilFetcher.fetch(fix.lat, fix.lon)
            ?.let { SoilEstimate.estimate(it.soilMoistureM3M3, it.recentRainMm) }
        stateFlow.value = stateFlow.value.copy(weather = weather, soil = soil, weatherLoading = false)
    }

    /**
     * Current conditions from open-meteo. Fails silently on purpose: the pre-flight screen must
     * stay useful in airplane mode, where the weather block simply reads "počasí nedostupné".
     */
    private suspend fun fetchWeather(lat: Double, lon: Double): WeatherSnapshot? = withContext(io) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,precipitation,wind_speed_10m" +
                    "&timezone=Europe%2FPrague",
            )
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            if (connection.responseCode !in 200..299) return@withContext null
            val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            val current = JSONObject(body).optJSONObject("current") ?: return@withContext null
            WeatherSnapshot(
                temperatureC = current.optDoubleOrNull("temperature_2m"),
                precipitationMm = current.optDoubleOrNull("precipitation"),
                windKmh = current.optDoubleOrNull("wind_speed_10m"),
            )
        } catch (e: Exception) {
            Log.i(TAG, "Počasí není dostupné: ${e.message}")
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        val value = optDouble(key, Double.NaN)
        return if (value.isNaN()) null else value
    }

    private companion object {
        const val TAG = "PreflightViewModel"
        const val TIMEOUT_MS = 5_000
    }
}
