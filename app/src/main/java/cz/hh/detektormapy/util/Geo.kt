package cz.hh.detektormapy.util

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle helpers for the "navigate to waypoint" feature (PLAN.md F2-4). */
object Geo {

    private const val R = 6371008.8 // mean Earth radius in metres

    /** Haversine distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing in degrees from north, 0..360. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Czech compass rose label for a bearing, e.g. "SV". */
    fun compassLabel(bearingDeg: Double): String {
        val names = listOf("S", "SV", "V", "JV", "J", "JZ", "Z", "SZ")
        val idx = (((bearingDeg % 360.0) + 360.0) % 360.0 / 45.0).roundToInt() % 8
        return names[idx]
    }

    /** Human readable distance: `35 m`, `1,2 km`. */
    fun formatDistance(meters: Double): String = when {
        meters < 1000 -> "${meters.roundToInt()} m"
        meters < 10_000 -> String.format(java.util.Locale.forLanguageTag("cs"), "%.1f km", meters / 1000.0)
        else -> "${(meters / 1000.0).roundToInt()} km"
    }

    /** Shoelace area of a WGS84 ring, in hectares. Good enough for "how much did I walk over". */
    fun polygonAreaHa(ring: List<Pair<Double, Double>>): Double {
        if (ring.size < 3) return 0.0
        val latRef = ring.sumOf { it.first } / ring.size
        val mPerDegLat = 111_132.92 - 559.82 * cos(2 * Math.toRadians(latRef))
        val mPerDegLon = 111_412.84 * cos(Math.toRadians(latRef))
        var sum = 0.0
        for (i in ring.indices) {
            val (lat1, lon1) = ring[i]
            val (lat2, lon2) = ring[(i + 1) % ring.size]
            val x1 = lon1 * mPerDegLon
            val y1 = lat1 * mPerDegLat
            val x2 = lon2 * mPerDegLon
            val y2 = lat2 * mPerDegLat
            sum += x1 * y2 - x2 * y1
        }
        return kotlin.math.abs(sum) / 2.0 / 10_000.0
    }

    /** Sunrise / sunset for the pre-flight screen (F5-2), NOAA approximation, in local millis. */
    fun sunTimes(lat: Double, lon: Double, epochDayUtc: Long): Pair<Long, Long>? {
        val n = epochDayUtc - 10957L // days since 2000-01-01
        val jStar = n - lon / 360.0
        val m = Math.toRadians((357.5291 + 0.98560028 * jStar) % 360.0)
        val c = 1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m)
        val lambda = Math.toRadians((Math.toDegrees(m) + c + 180.0 + 102.9372) % 360.0)
        val jTransit = 2451545.0 + jStar + 0.0053 * sin(m) - 0.0069 * sin(2 * lambda)
        val delta = kotlin.math.asin(sin(lambda) * sin(Math.toRadians(23.4397)))
        val cosOmega = (sin(Math.toRadians(-0.833)) - sin(Math.toRadians(lat)) * sin(delta)) /
            (cos(Math.toRadians(lat)) * cos(delta))
        if (cosOmega !in -1.0..1.0) return null // polar day / night
        val omega = Math.toDegrees(kotlin.math.acos(cosOmega))
        val jSet = jTransit + omega / 360.0
        val jRise = jTransit - omega / 360.0
        fun toMillis(jd: Double) = ((jd - 2440587.5) * 86_400_000.0).toLong()
        return toMillis(jRise) to toMillis(jSet)
    }

    private const val TWO_PI = 2 * PI
}
