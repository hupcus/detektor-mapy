package cz.hh.detektormapy.detector

import cz.hh.detektormapy.data.model.SoilCondition

/**
 * Turns a weather model's numbers into the three buckets the advisor reasons about.
 *
 * The primary input is open-meteo's `soil_moisture_3_to_9cm`, a volumetric water content in
 * m³/m³ -- roughly "what fraction of the soil volume is water". The thresholds below are the
 * usual soil-physics landmarks for a medium-textured soil: around 0.15 m³/m³ plants start to
 * struggle, and around 0.35 m³/m³ a loam is at field capacity and stops draining freely. They
 * are constants rather than magic numbers precisely so they can be argued with and tested.
 *
 * This is a **model estimate on an ~11 km grid**, not a measurement of the field being walked.
 * The UI says so, and the user can always override it.
 */
object SoilEstimate {

    /** Below this volumetric water content the ground reads as dry. */
    const val DRY_BELOW_M3_M3 = 0.20

    /** At or above this the ground reads as saturated. */
    const val WET_FROM_M3_M3 = 0.35

    /** Three-day rainfall total at or above which the ground reads as soaked. */
    const val RAIN_WET_FROM_MM = 15.0

    /** Three-day rainfall total at or above which the ground reads as damp. */
    const val RAIN_DAMP_FROM_MM = 3.0

    /** Maps volumetric water content (m³/m³) to a bucket. */
    fun fromSoilMoisture(m3m3: Double): SoilCondition = when {
        m3m3 < DRY_BELOW_M3_M3 -> SoilCondition.SUCHO
        m3m3 < WET_FROM_M3_M3 -> SoilCondition.VLHKO
        else -> SoilCondition.MOKRO
    }

    /**
     * Fallback for when the model serves no soil layer: the last three days of rain.
     *
     * Much cruder -- it knows nothing about what the soil did with the water -- so it is only
     * ever used when [fromSoilMoisture] has nothing to work with.
     */
    fun fromRecentRain(mm: Double): SoilCondition = when {
        mm < RAIN_DAMP_FROM_MM -> SoilCondition.SUCHO
        mm < RAIN_WET_FROM_MM -> SoilCondition.VLHKO
        else -> SoilCondition.MOKRO
    }

    /**
     * Best available estimate, or `null` when the weather could not be reached at all.
     *
     * `null` is a first-class answer here: offline is the normal case in a forest, and the screen
     * must then say "počasí nedostupné" rather than quietly guessing.
     */
    fun estimate(soilMoistureM3M3: Double?, recentRainMm: Double?): SoilCondition? {
        soilMoistureM3M3?.takeIf { it.isFinite() && it >= 0.0 }?.let { return fromSoilMoisture(it) }
        recentRainMm?.takeIf { it.isFinite() && it >= 0.0 }?.let { return fromRecentRain(it) }
        return null
    }
}
