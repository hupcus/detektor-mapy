package cz.hh.detektormapy.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ÚAN warning is a legal-consequence feature: a false negative means the user digs where
 * they must not. These tests pin the geometry down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PolygonIndexTest {

    private fun square(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        category: String = "kategorie II",
        name: String = "Testovací ÚAN",
    ) = """
    {"type":"Feature",
     "properties":{"Kategorie":"$category","Nazev":"$name"},
     "geometry":{"type":"Polygon","coordinates":[[
        [$west,$south],[$east,$south],[$east,$north],[$west,$north],[$west,$south]]]}}
    """.trimIndent()

    private fun collection(vararg features: String) =
        """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    @Test
    fun `a point inside the square is found`() {
        val index = PolygonIndex.parse(collection(square(15.0, 49.0, 16.0, 50.0)))
        assertThat(index.isEmpty).isFalse()
        assertThat(index.featureAt(49.5, 15.5)).isNotNull()
        assertThat(index.propertyAt(49.5, 15.5, "Kategorie")).isEqualTo("kategorie II")
        assertThat(index.propertyAt(49.5, 15.5, "Nazev")).isEqualTo("Testovací ÚAN")
    }

    @Test
    fun `points outside are not found`() {
        val index = PolygonIndex.parse(collection(square(15.0, 49.0, 16.0, 50.0)))
        assertThat(index.featureAt(48.5, 15.5)).isNull()
        assertThat(index.featureAt(49.5, 14.5)).isNull()
        assertThat(index.featureAt(50.5, 15.5)).isNull()
        assertThat(index.featureAt(49.5, 16.5)).isNull()
    }

    @Test
    fun `a hole in the polygon is excluded`() {
        val withHole = """
        {"type":"Feature","properties":{"Kategorie":"kategorie I"},
         "geometry":{"type":"Polygon","coordinates":[
            [[15.0,49.0],[16.0,49.0],[16.0,50.0],[15.0,50.0],[15.0,49.0]],
            [[15.4,49.4],[15.6,49.4],[15.6,49.6],[15.4,49.6],[15.4,49.4]]
         ]}}
        """.trimIndent()
        val index = PolygonIndex.parse(collection(withHole))
        assertThat(index.featureAt(49.2, 15.2)).isNotNull()
        assertThat(index.featureAt(49.5, 15.5)).isNull()
    }

    @Test
    fun `multipolygon yields one entry per part`() {
        val multi = """
        {"type":"Feature","properties":{"Kategorie":"kategorie II"},
         "geometry":{"type":"MultiPolygon","coordinates":[
            [[[15.0,49.0],[15.5,49.0],[15.5,49.5],[15.0,49.5],[15.0,49.0]]],
            [[[16.0,50.0],[16.5,50.0],[16.5,50.5],[16.0,50.5],[16.0,50.0]]]
         ]}}
        """.trimIndent()
        val index = PolygonIndex.parse(collection(multi))
        assertThat(index.polygons).hasSize(2)
        assertThat(index.featureAt(49.2, 15.2)).isNotNull()
        assertThat(index.featureAt(50.2, 16.2)).isNotNull()
        assertThat(index.featureAt(49.8, 15.8)).isNull()
    }

    @Test
    fun `overlapping categories return the first match deterministically`() {
        val index = PolygonIndex.parse(
            collection(
                square(15.0, 49.0, 16.0, 50.0, category = "kategorie I", name = "A"),
                square(15.4, 49.4, 15.6, 49.6, category = "kategorie II", name = "B"),
            ),
        )
        assertThat(index.propertyAt(49.5, 15.5, "Nazev")).isEqualTo("A")
    }

    @Test
    fun `malformed input degrades to an empty index instead of throwing`() {
        assertThat(PolygonIndex.parse("").isEmpty).isTrue()
        assertThat(PolygonIndex.parse("{").isEmpty).isTrue()
        assertThat(PolygonIndex.parse("""{"type":"FeatureCollection"}""").isEmpty).isTrue()
        assertThat(PolygonIndex.parse("""{"features":[{"geometry":null}]}""").isEmpty).isTrue()
    }

    @Test
    fun `a ring with fewer than three points is skipped`() {
        val degenerate = """
        {"type":"Feature","properties":{},
         "geometry":{"type":"Polygon","coordinates":[[[15.0,49.0],[16.0,49.0]]]}}
        """.trimIndent()
        assertThat(PolygonIndex.parse(collection(degenerate)).isEmpty).isTrue()
    }

    @Test
    fun `lookups outside every bounding box are cheap and null`() {
        val index = PolygonIndex.parse(
            collection(
                square(15.0, 49.0, 15.1, 49.1),
                square(16.0, 50.0, 16.1, 50.1),
            ),
        )
        assertThat(index.featureAt(0.0, 0.0)).isNull()
        assertThat(index.propertyAt(0.0, 0.0, "Kategorie")).isNull()
    }
}
