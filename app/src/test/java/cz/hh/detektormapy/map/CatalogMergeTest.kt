package cz.hh.detektormapy.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The catalogue on disk wins once written, so new built-in layers reach an existing install
 * only through [mergeCatalogs]. These tests pin down the contract: user edits survive, new
 * defaults are appended, and an up-to-date file is returned untouched (no pointless writes).
 */
class CatalogMergeTest {

    private fun layer(id: String, title: String = id) = LayerDef(
        id = id,
        title = title,
        kind = LayerKind.XYZ,
        source = "https://example.test/$id/{z}/{x}/{y}",
    )

    @Test
    fun `up-to-date file is returned as the same instance`() {
        val onDisk = LayerCatalog(version = DefaultLayers.catalog.version, layers = listOf(layer("osm")))
        assertThat(mergeCatalogs(onDisk, DefaultLayers.catalog)).isSameInstanceAs(onDisk)
    }

    @Test
    fun `newer file than seed is never downgraded`() {
        val onDisk = LayerCatalog(version = 99, layers = listOf(layer("osm")))
        assertThat(mergeCatalogs(onDisk, DefaultLayers.catalog)).isSameInstanceAs(onDisk)
    }

    @Test
    fun `new default layers are appended after the user's entries`() {
        val edited = layer("vm2", title = "moje přejmenovaná vrstva")
        val onDisk = LayerCatalog(version = 1, layers = listOf(layer("osm"), edited))

        val merged = mergeCatalogs(onDisk, DefaultLayers.catalog)

        assertThat(merged.version).isEqualTo(DefaultLayers.catalog.version)
        // User's hand edit survives even though vm2 exists in the seed too.
        assertThat(merged.layers.first { it.id == "vm2" }.title)
            .isEqualTo("moje přejmenovaná vrstva")
        // The v2 additions arrive exactly once each.
        val additions = listOf(
            "muller_cechy",
            "muller_morava",
            "vm1",
            "cisarske_kvk",
            "vm2_online",
            "vm3_topo",
            "ztm",
        )
        for (id in additions) {
            assertThat(merged.layers.count { it.id == id }).isEqualTo(1)
        }
        // Existing entries keep their position at the front.
        assertThat(merged.layers.take(2).map { it.id }).containsExactly("osm", "vm2").inOrder()
    }

    @Test
    fun `layer deliberately deleted by the user is re-offered only on a version bump`() {
        val withoutUan = LayerCatalog(
            version = DefaultLayers.catalog.version,
            layers = DefaultLayers.catalog.layers.filterNot { it.id == "uan" },
        )
        assertThat(mergeCatalogs(withoutUan, DefaultLayers.catalog)).isSameInstanceAs(withoutUan)
    }

    @Test
    fun `seed catalogue contains the verified additions`() {
        val byId = DefaultLayers.catalog.layers.associateBy { it.id }

        val vm1 = requireNotNull(byId["vm1"])
        assertThat(vm1.manualAlignment).isTrue()
        assertThat(vm1.kind).isEqualTo(LayerKind.XYZ)
        assertThat(vm1.source).contains("chartae-antiquae.cz/TMS/Military1")

        val kvk = requireNotNull(byId["cisarske_kvk"])
        assertThat(kvk.kind).isEqualTo(LayerKind.ARCGIS)
        assertThat(kvk.source).contains("geo-ags.kr-karlovarsky.cz")

        assertThat(requireNotNull(byId["muller_cechy"]).source).contains("/TMS/MullerC/")
        assertThat(requireNotNull(byId["muller_morava"]).source).contains("/TMS/MullerM/")
        assertThat(requireNotNull(byId["vm2_online"]).source).contains("/TMS/Military2/")
        assertThat(requireNotNull(byId["vm3_topo"]).source).contains("/TMS/Military3/")

        // ArcGIS caches are row-before-column; a swapped template renders garbage.
        assertThat(requireNotNull(byId["ortofoto"]).source).contains("ORTOFOTO_WM")
        assertThat(requireNotNull(byId["ortofoto"]).source).contains("/tile/{z}/{y}/{x}")
        val ztm = requireNotNull(byId["ztm"])
        assertThat(ztm.isBasemap).isTrue()
        assertThat(ztm.source).contains("ZTM_WM/MapServer/tile/{z}/{y}/{x}")

        // Orders must stay unique, otherwise the panel sort is unstable.
        val orders = DefaultLayers.catalog.layers.filterNot { it.isBasemap }.map { it.order }
        assertThat(orders).containsNoDuplicates()
    }
}
