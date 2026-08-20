package cz.hh.detektormapy.map

import cz.hh.detektormapy.util.BBox
import org.json.JSONArray
import org.json.JSONObject

/**
 * One polygon feature reduced to what the map and the ÚAN warning actually need.
 *
 * @param rings outer ring first, holes after it, each as (lat, lon) pairs
 */
data class IndexedPolygon(
    val rings: List<List<Pair<Double, Double>>>,
    val properties: Map<String, String>,
    val bounds: BBox,
) {
    /** Ray-casting point-in-polygon, holes subtracted. */
    fun contains(lat: Double, lon: Double): Boolean {
        if (!bounds.contains(lat, lon)) return false
        if (rings.isEmpty()) return false
        if (!ringContains(rings[0], lat, lon)) return false
        for (i in 1 until rings.size) {
            if (ringContains(rings[i], lat, lon)) return false
        }
        return true
    }

    private fun ringContains(ring: List<Pair<Double, Double>>, lat: Double, lon: Double): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val (latI, lonI) = ring[i]
            val (latJ, lonJ) = ring[j]
            if ((lonI > lon) != (lonJ > lon)) {
                val slope = (latJ - latI) / (lonJ - lonI)
                if (lat < latI + slope * (lon - lonI)) inside = !inside
            }
            j = i
        }
        return inside
    }
}

/**
 * A tiny in-memory index over a GeoJSON `FeatureCollection`.
 *
 * Built for the ÚAN layer (issue F4-3): the app has to answer "am I standing inside a
 * protected archaeological area right now" on every GPS fix, and doing that against a parsed
 * structure with per-feature bounding boxes is orders of magnitude cheaper than re-reading the
 * file. It is deliberately simple -- a linear scan filtered by bbox is plenty for the few
 * thousand polygons a single region contains.
 */
class PolygonIndex(val polygons: List<IndexedPolygon>) {

    val isEmpty: Boolean get() = polygons.isEmpty()

    /** The first polygon containing the point, or null. */
    fun featureAt(lat: Double, lon: Double): IndexedPolygon? = polygons.firstOrNull { it.contains(lat, lon) }

    /** Value of [key] on the polygon containing the point; used to show "ÚAN II". */
    fun propertyAt(lat: Double, lon: Double, key: String): String? = featureAt(lat, lon)?.properties?.get(key)

    companion object {

        val EMPTY = PolygonIndex(emptyList())

        /**
         * Parses a GeoJSON FeatureCollection. Anything malformed is skipped rather than
         * thrown: a half-downloaded ÚAN export must not take the map down with it.
         */
        fun parse(geoJson: String): PolygonIndex = runCatching {
            val root = JSONObject(geoJson)
            val features = root.optJSONArray("features") ?: return@runCatching EMPTY
            val result = ArrayList<IndexedPolygon>(features.length())
            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                val geometry = feature.optJSONObject("geometry") ?: continue
                val properties = feature.optJSONObject("properties")?.toStringMap() ?: emptyMap()
                when (geometry.optString("type")) {
                    "Polygon" -> {
                        val rings = geometry.optJSONArray("coordinates")?.toRings() ?: continue
                        boundsOf(rings)?.let { result += IndexedPolygon(rings, properties, it) }
                    }

                    "MultiPolygon" -> {
                        val multi = geometry.optJSONArray("coordinates") ?: continue
                        for (p in 0 until multi.length()) {
                            val rings = multi.optJSONArray(p)?.toRings() ?: continue
                            boundsOf(rings)?.let { result += IndexedPolygon(rings, properties, it) }
                        }
                    }
                }
            }
            PolygonIndex(result)
        }.getOrDefault(EMPTY)

        private fun JSONObject.toStringMap(): Map<String, String> = buildMap {
            keys().forEach { key -> put(key, optString(key)) }
        }

        private fun JSONArray.toRings(): List<List<Pair<Double, Double>>>? {
            val rings = ArrayList<List<Pair<Double, Double>>>(length())
            for (r in 0 until length()) {
                val ringArray = optJSONArray(r) ?: continue
                val ring = ArrayList<Pair<Double, Double>>(ringArray.length())
                for (c in 0 until ringArray.length()) {
                    val pair = ringArray.optJSONArray(c) ?: continue
                    if (pair.length() < 2) continue
                    // GeoJSON is [lon, lat]; everything above this line works in (lat, lon).
                    ring += pair.optDouble(1) to pair.optDouble(0)
                }
                if (ring.size >= 3) rings += ring
            }
            return rings.takeIf { it.isNotEmpty() }
        }

        private fun boundsOf(rings: List<List<Pair<Double, Double>>>): BBox? {
            val outer = rings.firstOrNull() ?: return null
            var west = Double.MAX_VALUE
            var east = -Double.MAX_VALUE
            var south = Double.MAX_VALUE
            var north = -Double.MAX_VALUE
            outer.forEach { (lat, lon) ->
                if (lon < west) west = lon
                if (lon > east) east = lon
                if (lat < south) south = lat
                if (lat > north) north = lat
            }
            if (west > east || south > north) return null
            return BBox(west, south, east, north)
        }
    }
}
