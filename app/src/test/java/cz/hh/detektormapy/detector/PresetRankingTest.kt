package cz.hh.detektormapy.detector

import com.google.common.truth.Truth.assertThat
import cz.hh.detektormapy.data.entity.DetectorEntity
import cz.hh.detektormapy.data.entity.DetectorPresetEntity
import cz.hh.detektormapy.data.model.SoilCondition
import cz.hh.detektormapy.data.model.Terrain
import cz.hh.detektormapy.data.relation.DetectorWithPresets
import org.junit.Test

/**
 * The ranking is the only part of the advisor that decides anything, so it is the part that has
 * to be provably boring: same input, same order, no invented winners.
 */
class PresetRankingTest {

    @Test
    fun `empty library ranks to an empty list`() {
        assertThat(PresetRanking.rank(emptyList(), Terrain.LES, SoilCondition.MOKRO)).isEmpty()
    }

    @Test
    fun `a detector with no presets contributes nothing`() {
        val library = listOf(DetectorWithPresets(detector(1, "Prázdný"), emptyList()))
        assertThat(PresetRanking.rank(library, Terrain.LES, SoilCondition.SUCHO)).isEmpty()
    }

    @Test
    fun `exact match beats terrain-only beats soil-only beats nothing`() {
        val library = listOf(
            DetectorWithPresets(
                detector(1, "Stroj"),
                listOf(
                    preset(1, "nic", Terrain.PLAZ, SoilCondition.SUCHO),
                    preset(2, "jen půda", Terrain.PLAZ, SoilCondition.MOKRO),
                    preset(3, "jen terén", Terrain.LES, SoilCondition.SUCHO),
                    preset(4, "přesně", Terrain.LES, SoilCondition.MOKRO),
                ),
            ),
        )

        val ranked = PresetRanking.rank(library, Terrain.LES, SoilCondition.MOKRO)

        assertThat(ranked.map { it.preset.name })
            .containsExactly("přesně", "jen terén", "jen půda", "nic")
            .inOrder()
        assertThat(ranked.map { it.score }).containsExactly(
            PresetRanking.SCORE_TERRAIN_AND_SOIL,
            PresetRanking.SCORE_TERRAIN,
            PresetRanking.SCORE_SOIL,
            PresetRanking.SCORE_NONE,
        ).inOrder()
    }

    @Test
    fun `ties keep the order the library gave them`() {
        val library = listOf(
            DetectorWithPresets(
                detector(1, "První"),
                listOf(
                    preset(1, "a", Terrain.LES, SoilCondition.MOKRO),
                    preset(2, "b", Terrain.LES, SoilCondition.MOKRO),
                ),
            ),
            DetectorWithPresets(
                detector(2, "Druhý"),
                listOf(preset(3, "c", Terrain.LES, SoilCondition.MOKRO)),
            ),
        )

        val ranked = PresetRanking.rank(library, Terrain.LES, SoilCondition.MOKRO)

        assertThat(ranked.map { it.preset.name }).containsExactly("a", "b", "c").inOrder()
        // And running it again on unchanged data must not shuffle anything.
        assertThat(PresetRanking.rank(library, Terrain.LES, SoilCondition.MOKRO).map { it.preset.name })
            .isEqualTo(ranked.map { it.preset.name })
    }

    @Test
    fun `unknown soil lets terrain carry the ranking alone`() {
        val library = listOf(
            DetectorWithPresets(
                detector(1, "Stroj"),
                listOf(
                    preset(1, "jiný terén", Terrain.POLE, SoilCondition.MOKRO),
                    preset(2, "správný terén", Terrain.LES, SoilCondition.SUCHO),
                ),
            ),
        )

        val ranked = PresetRanking.rank(library, Terrain.LES, soil = null)

        assertThat(ranked.first().preset.name).isEqualTo("správný terén")
        assertThat(ranked.first().score).isEqualTo(PresetRanking.SCORE_TERRAIN)
        assertThat(ranked.last().score).isEqualTo(PresetRanking.SCORE_NONE)
    }

    @Test
    fun `the machine travels with the preset because a value alone is useless`() {
        val library = listOf(
            DetectorWithPresets(
                detector(1, "Moje stará Garrett"),
                listOf(preset(1, "Les po dešti", Terrain.LES, SoilCondition.MOKRO)),
            ),
        )

        val match = PresetRanking.rank(library, Terrain.LES, SoilCondition.MOKRO).single()

        assertThat(match.detectorName).isEqualTo("Moje stará Garrett")
    }

    @Test
    fun `the reason names what matched and what did not`() {
        val library = listOf(
            DetectorWithPresets(
                detector(1, "Stroj"),
                listOf(
                    preset(1, "přesně", Terrain.LES, SoilCondition.MOKRO),
                    preset(2, "jen terén", Terrain.LES, SoilCondition.SUCHO),
                    preset(3, "nic", Terrain.PLAZ, SoilCondition.SUCHO),
                ),
            ),
        )

        val byName = PresetRanking.rank(library, Terrain.LES, SoilCondition.MOKRO)
            .associate { it.preset.name to it.reason }

        assertThat(byName.getValue("přesně")).contains("Sedí terén i stav půdy")
        assertThat(byName.getValue("jen terén")).contains("Sedí terén (les)")
        assertThat(byName.getValue("jen terén")).contains("sucho")
        assertThat(byName.getValue("nic")).contains("Neodpovídá")
    }

    @Test
    fun `reasons say the soil is unknown instead of pretending it is dry`() {
        val library = listOf(
            DetectorWithPresets(
                detector(1, "Stroj"),
                listOf(preset(1, "les", Terrain.LES, SoilCondition.SUCHO)),
            ),
        )

        val reason = PresetRanking.rank(library, Terrain.LES, soil = null).single().reason

        assertThat(reason).contains("stav půdy neznáme")
    }

    private fun detector(id: Long, name: String) = DetectorEntity(id = id, name = name, createdAt = T0 + id)

    private fun preset(id: Long, name: String, terrain: Terrain, soil: SoilCondition) = DetectorPresetEntity(
        id = id,
        detectorId = 1L,
        name = name,
        terrain = terrain,
        soil = soil,
        createdAt = T0 + id,
    )

    private companion object {
        /** 2024-06-01T10:00:00Z; nothing in these tests may depend on the wall clock. */
        const val T0 = 1_717_236_000_000L
    }
}
