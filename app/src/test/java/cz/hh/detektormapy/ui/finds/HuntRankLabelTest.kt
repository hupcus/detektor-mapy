package cz.hh.detektormapy.ui.finds

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * "Tvůj lov: N. na tomto místě" is ordinal arithmetic over the count of *existing* finds:
 * the find being captured is the (count+1)-th. Off-by-one here would tell the user their
 * second find is their first, which is exactly the kind of small lie that erodes trust.
 */
class HuntRankLabelTest {

    private fun state(nearbyCount: Int?) = FindCaptureUiState(nearbyCount = nearbyCount)

    @Test
    fun `unknown count shows nothing rather than a guess`() {
        assertThat(state(nearbyCount = null).huntRankLabel).isNull()
    }

    @Test
    fun `zero existing finds celebrates the first one`() {
        assertThat(state(nearbyCount = 0).huntRankLabel).isEqualTo("První nález na tomto místě")
    }

    @Test
    fun `existing finds shift the rank by one`() {
        assertThat(state(nearbyCount = 1).huntRankLabel)
            .isEqualTo("Tvůj lov: 2. nález na tomto místě")
        assertThat(state(nearbyCount = 4).huntRankLabel)
            .isEqualTo("Tvůj lov: 5. nález na tomto místě")
    }
}
