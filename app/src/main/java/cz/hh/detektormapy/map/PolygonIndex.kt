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
    /**
     * Metres from the point to the nearest edge of this polygon, 0 when inside.
     *
     * Distances are computed in a local equirectangular frame scaled at the query latitude.
     * Over the few hundred metres that matter for a warning the error is far below GPS noise,
     * and it avoids a haversine per segment over hundreds of polygons on every fix.
     */
    fun distanceMetersTo(lat: Double, lon: Double): Double {
        if (contains(lat, lon)) return 0.0
        val mPerDegLat = 111_132.0
        val mPerDegLon = 111_320.0 * kotlin.math.cos(Math.toRadians(lat))
        val px = lon * mPerDegLon
        val py = lat * mPerDegLat
        var best = Double.MAX_VALUE
        for (ring in rings) {
            for (i in ring.indices) {
                val (aLat, aLon) = ring[i]
                val (bLat, bLon) = ring[(i + 1) % ring.size]
                val d = pointToSegment(
                    px,
                    py,
                    aLon * mPerDegLon,
                    aLat * mPerDegLat,
                    bLon * mPerDegLon,
                    bLat * mPerDegLat,
                )
                if (d < best) best = d
            }
        }
        return best
    }

    private fun pointToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq <= 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return kotlin.math.hypot(px - cx, py - cy)
    }

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
 * protected archaeological area right now" on every GPS fix. Polygons are bucketed into a
 * uniform ~0.01° grid at parse time, so a query touches only the handful of features whose
 * bounding box crosses its cell — a nationwide ÚAN export stays O(cell), not O(n), at the
 * adaptive fix cadence of 12 queries a minute.
 */
class PolygonIndex(val polygons: List<IndexedPolygon>) {

    /** Cell key -> indices into [polygons] whose bbox intersects the cell. */
    private val grid: Map<Long, IntArray> = buildMap<Long, MutableList<Int>> {
        polygons.forEachIndexed { index, polygon ->
            val b = polygon.bounds
            for (cx in cellOf(b.west)..cellOf(b.east)) {
                for (cy in cellOf(b.south)..cellOf(b.north)) {
                    getOrPut(cellKey(cx, cy)) { mutableListOf() } += index
                }
            }
        }
    }.mapValues { (_, indices) -> indices.toIntArray() }

    val isEmpty: Boolean get() = polygons.isEmpty()

    /** The first polygon containing the point, or null. */
    fun featureAt(lat: Double, lon: Double): IndexedPolygon? {
        val cell = grid[cellKey(cellOf(lon), cellOf(lat))] ?: return null
        // First by parse order, matching the old linear scan when polygons overlap.
        for (index in cell) {
            val polygon = polygons[index]
            if (polygon.contains(lat, lon)) return polygon
        }
        return null
    }

    /**
     * Nearest polygon within [maxMeters], with its distance -- 0 when the point is inside one.
     *
     * Being told "you are standing in a protected area" is already too late; the useful warning
     * is the one that arrives while you are still walking towards the boundary. Candidates come
     * from the grid cells covered by the query bbox grown by [maxMeters], so the expensive edge
     * walk only runs for polygons that could plausibly be in range.
     */
    fun nearest(lat: Double, lon: Double, maxMeters: Double): Pair<IndexedPolygon, Double>? {
        if (polygons.isEmpty()) return null
        val padLat = maxMeters / 111_132.0
        val cosLat = kotlin.math.cos(Math.toRadians(lat))
        val padLon = if (cosLat < 1e-6) 180.0 else maxMeters / (111_320.0 * cosLat)

        val seen = HashSet<Int>()
        var bestPolygon: IndexedPolygon? = null
        var bestDistance = Double.MAX_VALUE
        outer@ for (cx in cellOf(lon - padLon)..cellOf(lon + padLon)) {
            for (cy in cellOf(lat - padLat)..cellOf(lat + padLat)) {
                val cell = grid[cellKey(cx, cy)] ?: continue
                for (index in cell) {
                    if (!seen.add(index)) continue
                    val polygon = polygons[index]
                    val b = polygon.bounds
                    if (lat < b.south - padLat || lat > b.north + padLat) continue
                    if (lon < b.west - padLon || lon > b.east + padLon) continue
                    val d = polygon.distanceMetersTo(lat, lon)
                    if (d < bestDistance) {
                        bestDistance = d
                        bestPolygon = polygon
                        if (d == 0.0) break@outer
                    }
                }
            }
        }
        val polygon = bestPolygon ?: return null
        return if (bestDistance <= maxMeters) polygon to bestDistance else null
    }

    /** Value of [key] on the polygon containing the point; used to show "ÚAN II". */
    fun propertyAt(lat: Double, lon: Double, key: String): String? = featureAt(lat, lon)?.properties?.get(key)

    companion object {

        val EMPTY = PolygonIndex(emptyList())

        /**
         * ~1.1 km of latitude per cell. ÚAN polygons are village-sized, so most span a cell
         * or two; a nationwide export lands at a few entries per occupied cell.
         */
        private const val CELL_DEG = 0.01

        private fun cellOf(deg: Double): Int = kotlin.math.floor(deg / CELL_DEG).toInt()

        private fun cellKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFF_FFFFL)

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
