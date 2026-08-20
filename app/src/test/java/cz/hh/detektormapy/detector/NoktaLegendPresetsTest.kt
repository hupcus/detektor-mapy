package cz.hh.detektormapy.detector

import com.google.common.truth.Truth.assertThat
import cz.hh.detektormapy.data.model.SoilCondition
import cz.hh.detektormapy.data.model.Terrain
import org.junit.Test

/**
 * Guards the transcription of `docs/nokta-legend-presety.md`.
 *
 * These presets are the reason the advisor is useful at all, and they were generated from a
 * document rather than typed by hand — so the risk is a silent transcription slip, not a logic
 * error. These assertions pin the shape and a few known values.
 */
class NoktaLegendPresetsTest {

    @Test
    fun `covers three terrains in dry and wet variants`() {
        assertThat(NoktaLegendPresets.presets).hasSize(6)
        val combos = NoktaLegendPresets.presets.map { it.terrain to it.soil }.toSet()
        assertThat(combos).containsExactly(
            Terrain.LES to SoilCondition.VLHKO,
            Terrain.LES to SoilCondition.MOKRO,
            Terrain.LOUKA to SoilCondition.VLHKO,
            Terrain.LOUKA to SoilCondition.MOKRO,
            Terrain.POLE to SoilCondition.VLHKO,
            Terrain.POLE to SoilCondition.MOKRO,
        )
    }

    @Test
    fun `every preset carries the fields the advisor shows`() {
        NoktaLegendPresets.presets.forEach { preset ->
            assertThat(preset.name).isNotEmpty()
            assertThat(preset.sensitivity).isNotEmpty()
            assertThat(preset.discrimination).isNotEmpty()
            assertThat(preset.groundBalance).contains("Ground Tracking")
            assertThat(preset.settings).contains("Režim")
            assertThat(preset.why).isNotEmpty()
            assertThat(preset.tuning).isNotEmpty()
        }
    }

    @Test
    fun `known values from the source document survived transcription`() {
        val forest = NoktaLegendPresets.presets
            .single { it.terrain == Terrain.LES && it.soil == SoilCondition.VLHKO }
        assertThat(forest.sensitivity).isEqualTo("25")
        assertThat(forest.discrimination).contains("All Metal")
        assertThat(forest.settings).contains("M1")
        assertThat(forest.settings).contains("Recovery Speed: 4")

        val wetField = NoktaLegendPresets.presets
            .single { it.terrain == Terrain.POLE && it.soil == SoilCondition.MOKRO }
        assertThat(wetField.settings).contains("M3")
    }

    @Test
    fun `notes bundle the sections the detail screen renders`() {
        val notes = NoktaLegendPresets.presets.first().toNotes()
        assertThat(notes).contains("NASTAVENÍ")
        assertThat(notes).contains("PROČ")
        assertThat(notes).contains("DOLADĚNÍ V TERÉNU")
    }

    @Test
    fun `startup routine begins with noise cancel and ground balance`() {
        val routine = NoktaLegendPresets.STARTUP_ROUTINE
        assertThat(routine).isNotEmpty()
        assertThat(routine.joinToString(" ")).contains("Noise Cancel")
        assertThat(routine.joinToString(" ")).contains("Ground Balance")
    }
}
