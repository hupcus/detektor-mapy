package cz.hh.detektormapy.detector

import android.util.Log
import cz.hh.detektormapy.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** What the weather model says about the ground; either field may be missing. */
data class SoilReading(val soilMoistureM3M3: Double?, val recentRainMm: Double?)

/**
 * Soil moisture and recent rain from open-meteo, shared by the detector advisor and the
 * pre-flight screen so the request, the parsing and the honesty caveats live in one place.
 *
 * The hourly series is used rather than `current` because the soil layers are only published
 * hourly, and `past_days=3` is what turns "it rained" into "it rained recently". Times are
 * requested as unix seconds so nothing here has to parse a timezone.
 *
 * Fails silently on purpose -- airplane mode in a forest is the normal case, and both screens
 * degrade to "počasí nedostupné" while staying fully usable.
 */
@Singleton
class SoilReadingFetcher @Inject constructor(@param:IoDispatcher private val io: CoroutineDispatcher) {

    suspend fun fetch(lat: Double, lon: Double): SoilReading? = withContext(io) {
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
            parse(JSONObject(body), System.currentTimeMillis() / 1000L)
        } catch (e: Exception) {
            Log.i(TAG, "Počasí není dostupné: ${e.message}")
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    companion object {
        private const val TAG = "SoilReadingFetcher"
        const val TIMEOUT_MS = 5_000
        const val PAST_DAYS = 3L
        const val SECONDS_PER_DAY = 86_400L

        /**
         * Pulls the reading out of an open-meteo hourly payload. [nowSec] is a parameter so a
         * test can pin "now" instead of racing the clock.
         */
        fun parse(root: JSONObject, nowSec: Long): SoilReading? {
            val hourly = root.optJSONObject("hourly") ?: return null
            val times = hourly.optJSONArray("time") ?: return null

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
    }
}
