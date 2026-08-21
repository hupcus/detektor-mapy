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
    fun `distance is zero inside and grows with distance outside`() {
        val index = PolygonIndex.parse(collection(square(15.0, 49.0, 16.0, 50.0)))
        val polygon = index.polygons.single()

        assertThat(polygon.distanceMetersTo(49.5, 15.5)).isEqualTo(0.0)

        // 0.01 deg of latitude south of the southern edge is ~1111 m.
        val south = polygon.distanceMetersTo(48.99, 15.5)
        assertThat(south).isWithin(30.0).of(1111.0)

        // Twice as far away must read as roughly twice the distance.
        val further = polygon.distanceMetersTo(48.98, 15.5)
        assertThat(further).isGreaterThan(south * 1.9)
    }

    @Test
    fun `nearest reports an approaching area before it is entered`() {
        val index = PolygonIndex.parse(
            collection(square(15.0, 49.0, 16.0, 50.0, name = "Chráněno")),
        )
        // ~55 m south of the boundary.
        val hit = index.nearest(49.0 - 0.0005, 15.5, maxMeters = 120.0)
        assertThat(hit).isNotNull()
        assertThat(hit!!.second).isGreaterThan(0.0)
        assertThat(hit.second).isLessThan(120.0)
        assertThat(hit.first.properties["Nazev"]).isEqualTo("Chráněno")
    }

    @Test
    fun `nearest reports zero distance when standing inside`() {
        val index = PolygonIndex.parse(collection(square(15.0, 49.0, 16.0, 50.0)))
        val hit = index.nearest(49.5, 15.5, maxMeters = 120.0)
        assertThat(hit).isNotNull()
        assertThat(hit!!.second).isEqualTo(0.0)
    }

    @Test
    fun `nearest ignores areas beyond the radius`() {
        val index = PolygonIndex.parse(collection(square(15.0, 49.0, 16.0, 50.0)))
        // ~1.1 km south, well outside a 120 m warning radius.
        assertThat(index.nearest(48.99, 15.5, maxMeters = 120.0)).isNull()
        // …but a generous radius finds it.
        assertThat(index.nearest(48.99, 15.5, maxMeters = 2000.0)).isNotNull()
    }

    @Test
    fun `nearest prefers the closer of two areas`() {
        val index = PolygonIndex.parse(
            collection(
                square(15.0, 49.0, 15.2, 49.2, name = "Daleko"),
                square(15.0, 48.995, 15.2, 48.999, name = "Blizko"),
            ),
        )
        val hit = index.nearest(48.9945, 15.1, maxMeters = 500.0)
        assertThat(hit).isNotNull()
        assertThat(hit!!.first.properties["Nazev"]).isEqualTo("Blizko")
    }

    @Test
    fun `nearest on an empty index is null`() {
        assertThat(PolygonIndex.EMPTY.nearest(50.0, 15.0, maxMeters = 500.0)).isNull()
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

    @Test
    fun `grid answers match a brute-force scan over a lattice of polygons`() {
        // 10x10 small squares spread over ~0.5° so they land in many different grid cells,
        // including squares that straddle cell boundaries (cell size is 0.01°).
        val squares = buildList {
            for (i in 0 until 10) {
                for (j in 0 until 10) {
                    val west = 15.0 + i * 0.05 + 0.007
                    val south = 49.0 + j * 0.05 + 0.007
                    add(square(west, south, west + 0.012, south + 0.012, name = "sq_${i}_$j"))
                }
            }
        }
        val index = PolygonIndex.parse(collection(*squares.toTypedArray()))
        assertThat(index.polygons).hasSize(100)

        val random = java.util.Random(42)
        repeat(300) {
            val lat = 48.95 + random.nextDouble() * 0.6
            val lon = 14.95 + random.nextDouble() * 0.6
            val viaGrid = index.featureAt(lat, lon)?.properties?.get("Nazev")
            val bruteForce = index.polygons.firstOrNull { it.contains(lat, lon) }?.properties?.get("Nazev")
            assertThat(viaGrid).isEqualTo(bruteForce)
        }

        // nearest: the grid must find the same polygon (and distance) as walking everything.
        repeat(100) {
            val lat = 48.98 + random.nextDouble() * 0.55
            val lon = 14.98 + random.nextDouble() * 0.55
            val viaGrid = index.nearest(lat, lon, maxMeters = 900.0)
            val bruteForce = index.polygons
                .map { it to it.distanceMetersTo(lat, lon) }
                .filter { it.second <= 900.0 }
                .minByOrNull { it.second }
            if (bruteForce == null) {
                assertThat(viaGrid).isNull()
            } else {
                assertThat(viaGrid).isNotNull()
                assertThat(viaGrid!!.second).isWithin(1e-6).of(bruteForce.second)
            }
        }
    }

    @Test
    fun `a polygon spanning many grid cells is found from every cell it covers`() {
        // One big square (~0.2° across) covers hundreds of 0.01° cells.
        val index = PolygonIndex.parse(collection(square(15.0, 49.0, 15.2, 49.2, name = "Velky")))
        assertThat(index.featureAt(49.001, 15.001)?.properties?.get("Nazev")).isEqualTo("Velky")
        assertThat(index.featureAt(49.19, 15.19)?.properties?.get("Nazev")).isEqualTo("Velky")
        assertThat(index.featureAt(49.1, 15.1)?.properties?.get("Nazev")).isEqualTo("Velky")
        assertThat(index.featureAt(49.21, 15.1)).isNull()
    }
}
