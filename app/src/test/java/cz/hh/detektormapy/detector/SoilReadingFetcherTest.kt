package cz.hh.detektormapy.detector

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The open-meteo hourly series runs from three days ago into tomorrow's forecast, and the soil
 * layers arrive with holes. Picking the wrong hour would quietly feed the advisor tomorrow's
 * weather, so the "last hour that already happened" logic is pinned here with a fixed clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoilReadingFetcherTest {

    private val nowSec = 1_700_000_000L
    private val hour = 3_600L
    private val day = SoilReadingFetcher.SECONDS_PER_DAY

    private fun payload(times: List<Long>, moisture: List<Double?>?, rain: List<Double?>?): JSONObject {
        fun series(values: List<Double?>) = JSONArray().apply {
            values.forEach { put(it ?: JSONObject.NULL) }
        }
        val hourly = JSONObject().put("time", JSONArray().apply { times.forEach { put(it) } })
        moisture?.let { hourly.put("soil_moisture_3_to_9cm", series(it)) }
        rain?.let { hourly.put("precipitation", series(it)) }
        return JSONObject().put("hourly", hourly)
    }

    @Test
    fun `takes the last published hour and skips holes in the soil series`() {
        val root = payload(
            times = listOf(nowSec - 4 * day, nowSec - 2 * hour, nowSec - 1 * hour, nowSec + 1 * hour),
            moisture = listOf(0.10, 0.30, null, 0.50),
            rain = listOf(100.0, 1.0, 2.0, 9.0),
        )
        val reading = SoilReadingFetcher.parse(root, nowSec)

        // Hour at now-1h is the latest past one; its soil value is a hole, so now-2h wins.
        assertThat(reading?.soilMoistureM3M3).isEqualTo(0.30)
        // Rain sums only the 3-day window: the 100 mm from four days ago must not count,
        // and neither may tomorrow's forecast.
        assertThat(reading?.recentRainMm).isEqualTo(3.0)
    }

    @Test
    fun `forecast-only series yields nothing instead of tomorrow's weather`() {
        val root = payload(
            times = listOf(nowSec + hour, nowSec + 2 * hour),
            moisture = listOf(0.4, 0.4),
            rain = listOf(5.0, 5.0),
        )
        assertThat(SoilReadingFetcher.parse(root, nowSec)).isNull()
    }

    @Test
    fun `missing hourly block or both series missing yields null`() {
        assertThat(SoilReadingFetcher.parse(JSONObject(), nowSec)).isNull()
        val noSeries = payload(times = listOf(nowSec - hour), moisture = null, rain = null)
        assertThat(SoilReadingFetcher.parse(noSeries, nowSec)).isNull()
    }
}
