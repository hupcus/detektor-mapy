package cz.hh.detektormapy.detector

import cz.hh.detektormapy.data.entity.DetectorPresetEntity
import cz.hh.detektormapy.data.model.SoilCondition
import cz.hh.detektormapy.data.model.Terrain
import cz.hh.detektormapy.data.relation.DetectorWithPresets
import java.util.Locale

/**
 * One of the user's presets scored against the conditions, with the sentence that explains why.
 *
 * [detectorName] travels with the preset because "citlivost 18" is useless advice without the
 * machine it belongs to.
 */
data class PresetMatch(val preset: DetectorPresetEntity, val detectorName: String, val score: Int, val reason: String)

/**
 * Ranks the user's own presets against the conditions they are standing in.
 *
 * This is the whole "rozřazovací systém", and it is deliberately trivial: terrain and soil each
 * either match or they do not. The app knows nothing about the machine, so any cleverer scoring
 * would be scoring made-up data. What it can do honestly is sort what the user already wrote
 * down and say out loud which part matched.
 *
 * Pure and side-effect free on purpose -- it is the one piece of this feature that can be unit
 * tested to the letter.
 */
object PresetRanking {

    /** Terrain and soil both match -- the preset was recorded for exactly this situation. */
    const val SCORE_TERRAIN_AND_SOIL = 3

    /** Terrain matches. Ranked above a soil-only match: the ground type changes more. */
    const val SCORE_TERRAIN = 2

    /** Only the soil condition matches. */
    const val SCORE_SOIL = 1

    /** Nothing matches; still listed, because a starting point beats an empty screen. */
    const val SCORE_NONE = 0

    /**
     * Scores every preset in [library] and returns them best-first.
     *
     * [soil] may be `null` when the weather is unreachable; terrain then carries the ranking
     * alone and the reasons say the soil is unknown rather than pretending it is dry.
     *
     * Ordering is by score only, using a stable sort, so presets that score the same keep the
     * order the library gave them -- default machine first, then oldest first. Two runs on
     * unchanged data therefore produce the same list, which matters for a screen the user checks
     * repeatedly during one outing.
     */
    fun rank(library: List<DetectorWithPresets>, terrain: Terrain?, soil: SoilCondition?): List<PresetMatch> = library
        .flatMap { entry -> entry.presets.map { entry.detector.name to it } }
        .map { (detectorName, preset) -> match(preset, detectorName, terrain, soil) }
        .sortedByDescending { it.score }

    private fun match(
        preset: DetectorPresetEntity,
        detectorName: String,
        terrain: Terrain?,
        soil: SoilCondition?,
    ): PresetMatch {
        val terrainMatches = terrain != null && preset.terrain == terrain
        val soilMatches = soil != null && preset.soil == soil
        val score = when {
            terrainMatches && soilMatches -> SCORE_TERRAIN_AND_SOIL
            terrainMatches -> SCORE_TERRAIN
            soilMatches -> SCORE_SOIL
            else -> SCORE_NONE
        }
        return PresetMatch(
            preset = preset,
            detectorName = detectorName,
            score = score,
            reason = reason(preset, terrain, soil, terrainMatches, soilMatches),
        )
    }

    private fun label(terrain: Terrain): String = terrain.label.lowercase(Locale.ROOT)

    private fun label(soil: SoilCondition): String = soil.label.lowercase(Locale.ROOT)

    /** One line, in Czech, naming exactly what did and did not line up. */
    private fun reason(
        preset: DetectorPresetEntity,
        terrain: Terrain?,
        soil: SoilCondition?,
        terrainMatches: Boolean,
        soilMatches: Boolean,
    ): String = when {
        terrainMatches && soilMatches ->
            "Sedí terén i stav půdy: ${label(preset.terrain)}, ${label(preset.soil)}."

        terrainMatches && soil == null ->
            "Sedí terén (${label(preset.terrain)}); stav půdy neznáme."

        terrainMatches ->
            "Sedí terén (${label(preset.terrain)}), " +
                "ale uložený je na ${label(preset.soil)}."

        soilMatches && terrain == null ->
            "Sedí stav půdy (${label(preset.soil)}); terén jsi nevybral."

        soilMatches ->
            "Sedí stav půdy (${label(preset.soil)}), " +
                "ale uložený je na terén ${label(preset.terrain)}."

        else ->
            "Neodpovídá ani terénu, ani stavu půdy — uložený je na " +
                "${label(preset.terrain)}, ${label(preset.soil)}."
    }
}
