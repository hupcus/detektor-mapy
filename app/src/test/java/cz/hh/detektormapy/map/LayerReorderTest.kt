package cz.hh.detektormapy.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Reordering writes order values for *every* overlay, because default layers often share the
 * same catalog `order` — a single-value write could tie and change nothing visible. These tests
 * pin the normalisation and the no-op edges.
 */
class LayerReorderTest {

    private val ids = listOf("ortofoto", "vm2", "dmr5g")

    @Test
    fun `moving down swaps neighbours and renumbers everything`() {
        val orders = reorderedOverlayOrders(ids, "vm2", +1)
        assertThat(orders).containsExactly("ortofoto", 0, "dmr5g", 10, "vm2", 20)
    }

    @Test
    fun `moving up swaps the other way`() {
        val orders = reorderedOverlayOrders(ids, "vm2", -1)
        assertThat(orders).containsExactly("vm2", 0, "ortofoto", 10, "dmr5g", 20)
    }

    @Test
    fun `edges and unknown ids are no-ops`() {
        assertThat(reorderedOverlayOrders(ids, "ortofoto", -1)).isNull()
        assertThat(reorderedOverlayOrders(ids, "dmr5g", +1)).isNull()
        assertThat(reorderedOverlayOrders(ids, "neexistuje", +1)).isNull()
        assertThat(reorderedOverlayOrders(ids, "vm2", 0)).isNull()
        assertThat(reorderedOverlayOrders(emptyList(), "vm2", 1)).isNull()
    }

    @Test
    fun `ties in default catalog orders are broken by the normalisation`() {
        // Two layers with the same def.order sort stably; after one move every overlay has a
        // distinct persisted value, so the next sort has nothing left to tie on.
        val orders = reorderedOverlayOrders(listOf("a", "b", "c", "d"), "d", -2)
        assertThat(orders).containsExactly("a", 0, "d", 10, "b", 20, "c", 30)
        assertThat(orders!!.values.toSet()).hasSize(4)
    }
}
