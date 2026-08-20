package cz.hh.detektormapy.location

/** A single position fix, stripped down to what the app actually stores and draws. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val altitude: Double?,
    val accuracyM: Float?,
    val speedMs: Float?,
    val bearingDeg: Float?,
    val timestamp: Long,
)

/** Whether the device currently has a usable fix, and how good it is. */
enum class FixQuality {
    NONE,
    POOR,
    OK,
    GOOD,
    ;

    companion object {
        fun of(accuracyM: Float?): FixQuality = when {
            accuracyM == null -> NONE
            accuracyM <= 6f -> GOOD
            accuracyM <= 15f -> OK
            else -> POOR
        }
    }
}

/** How aggressively we ask for fixes. Battery is the scarce resource on a full-day hunt. */
enum class LocationMode(val intervalMs: Long, val minDistanceM: Float) {
    /** Screen on, user is looking at the map. */
    INTERACTIVE(2_000L, 0f),

    /** Track recording while walking. */
    TRACKING_MOVING(5_000L, 3f),

    /** Track recording while standing still -- PLAN.md section 10 asks for 5 s / 30 s. */
    TRACKING_IDLE(30_000L, 5f),
}
