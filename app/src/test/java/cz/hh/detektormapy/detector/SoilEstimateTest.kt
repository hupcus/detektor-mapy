package cz.hh.detektormapy.detector

import com.google.common.truth.Truth.assertThat
import cz.hh.detektormapy.data.model.SoilCondition
import org.junit.Test

/**
 * Boundary tests for the soil-moisture mapping.
 *
 * The thresholds are a judgement call, so the point of these tests is not that 0.20 is the right
 * number -- it is that changing the number is a deliberate act with a visible diff, and that the
 * comparisons stay half-open (`<` on the way up, `>=` on the way in) instead of drifting into an
 * off-by-one bucket.
 */
class SoilEstimateTest {

    @Test
    fun `just below the dry threshold is dry`() {
        assertThat(SoilEstimate.fromSoilMoisture(0.199)).isEqualTo(SoilCondition.SUCHO)
    }

    @Test
    fun `exactly at the dry threshold is already damp`() {
        assertThat(SoilEstimate.fromSoilMoisture(SoilEstimate.DRY_BELOW_M3_M3))
            .isEqualTo(SoilCondition.VLHKO)
    }

    @Test
    fun `just below the wet threshold is still damp`() {
        assertThat(SoilEstimate.fromSoilMoisture(0.349)).isEqualTo(SoilCondition.VLHKO)
    }

    @Test
    fun `exactly at the wet threshold is soaked`() {
        assertThat(SoilEstimate.fromSoilMoisture(SoilEstimate.WET_FROM_M3_M3))
            .isEqualTo(SoilCondition.MOKRO)
    }

    @Test
    fun `extremes stay inside the scale`() {
        assertThat(SoilEstimate.fromSoilMoisture(0.0)).isEqualTo(SoilCondition.SUCHO)
        assertThat(SoilEstimate.fromSoilMoisture(1.0)).isEqualTo(SoilCondition.MOKRO)
    }

    @Test
    fun `rain fallback has the same half-open boundaries`() {
        assertThat(SoilEstimate.fromRecentRain(0.0)).isEqualTo(SoilCondition.SUCHO)
        assertThat(SoilEstimate.fromRecentRain(2.9)).isEqualTo(SoilCondition.SUCHO)
        assertThat(SoilEstimate.fromRecentRain(SoilEstimate.RAIN_DAMP_FROM_MM))
            .isEqualTo(SoilCondition.VLHKO)
        assertThat(SoilEstimate.fromRecentRain(14.9)).isEqualTo(SoilCondition.VLHKO)
        assertThat(SoilEstimate.fromRecentRain(SoilEstimate.RAIN_WET_FROM_MM))
            .isEqualTo(SoilCondition.MOKRO)
    }

    @Test
    fun `soil moisture wins over rain when both are available`() {
        // Three dry days but a wet profile: the soil layer is the better witness.
        assertThat(SoilEstimate.estimate(soilMoistureM3M3 = 0.40, recentRainMm = 0.0))
            .isEqualTo(SoilCondition.MOKRO)
    }

    @Test
    fun `rain is used when the model serves no soil layer`() {
        assertThat(SoilEstimate.estimate(soilMoistureM3M3 = null, recentRainMm = 20.0))
            .isEqualTo(SoilCondition.MOKRO)
    }

    @Test
    fun `no data at all is null rather than a guess`() {
        assertThat(SoilEstimate.estimate(null, null)).isNull()
    }

    @Test
    fun `nonsense values are treated as no data`() {
        assertThat(SoilEstimate.estimate(Double.NaN, null)).isNull()
        assertThat(SoilEstimate.estimate(-1.0, null)).isNull()
        assertThat(SoilEstimate.estimate(Double.NaN, 20.0)).isEqualTo(SoilCondition.MOKRO)
    }
}
