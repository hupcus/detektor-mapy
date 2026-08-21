package cz.hh.detektormapy.location

import cz.hh.detektormapy.data.entity.TrackPointEntity
import cz.hh.detektormapy.util.BBox

/**
 * Geometry of a finished walk, kept apart from anything Android so it can be tested on a plain
 * JVM -- the same split as [TrackRecorder].
 */
object TrackGeometry {

    /**
     * Smallest box containing every point, or null when there is nothing to frame.
     *
     * Degenerate walks are the interesting case: a recording that was started and stopped
     * without moving collapses to a single coordinate, and a box with zero width cannot be
     * fitted to a viewport -- MapLibre answers a zero-sized bounds with an absurd zoom. Every
     * box therefore spans at least [MIN_SPAN_DEG], which at Czech latitudes is roughly 100 m,
     * a sane "you stood here" view.
     */
    fun boundsOf(points: List<TrackPointEntity>): BBox? {
        if (points.isEmpty()) return null
        var west = Double.MAX_VALUE
        var east = -Double.MAX_VALUE
        var south = Double.MAX_VALUE
        var north = -Double.MAX_VALUE
        points.forEach { point ->
            if (point.lon < west) west = point.lon
            if (point.lon > east) east = point.lon
            if (point.lat < south) south = point.lat
            if (point.lat > north) north = point.lat
        }
        return BBox(west, south, east, north).atLeast(MIN_SPAN_DEG)
    }

    /** ~100 m at Czech latitudes; the floor for framing a walk that barely moved. */
    const val MIN_SPAN_DEG = 0.001
}
