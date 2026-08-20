package cz.hh.detektormapy.location

import cz.hh.detektormapy.util.Geo

/** Why a fix was thrown away. Kept in the [Decision] so the service can log it, not guess. */
enum class FixRejection {
    /** Reported accuracy is worse than the recorder tolerates. */
    ACCURACY,

    /** Implied speed between two fixes is physically impossible -- a classic GPS teleport. */
    JUMP,

    /** The provider handed us a fix older than the previous one. */
    OUT_OF_ORDER,

    /** Same timestamp and position as the previous fix; carries no new information. */
    DUPLICATE,
}

/**
 * What the recorder decided about a single offered fix.
 *
 * [mode] is the cadence the caller should be subscribed with *from now on*; the service
 * compares it with the mode it is currently using and re-subscribes only when it differs.
 */
data class Decision(
    val accepted: Boolean,
    val shouldFlush: Boolean,
    val mode: LocationMode,
    val distanceM: Double,
    val pointCount: Int,
    val rejection: FixRejection? = null,
)

/**
 * All the track-recording logic that does not need Android.
 *
 * It lives apart from [TrackRecordingService] for two reasons: the junk-fix filter and the
 * moving/standing heuristic are the parts most likely to be wrong in the field, and they are
 * the only parts that can be tested on a plain JVM. The service keeps just the plumbing --
 * notifications, wake lock, Room, GPX.
 *
 * The class is deliberately **not** thread safe: the service touches it only from the single
 * coroutine that collects fixes.
 *
 * @param maxAccuracyM fixes with a worse reported accuracy are dropped (a 50 m "fix" under
 *   tree cover would smear the trail across a whole field).
 * @param maxSpeedMs implied-speed ceiling; 15 m/s is ~54 km/h, far above any walking pace, so
 *   anything above it is a provider glitch rather than the user.
 * @param idleRadiusM / [idleAfterMs] PLAN.md section 10: standing still for a minute inside a
 *   10 m circle drops GPS to the 30 s cadence, any real step back over 10 m restores 5 s.
 * @param flushEveryPoints / [flushEveryMs] how often the caller is told to write the buffer to
 *   the database -- a DB transaction per fix is both slow and a needless wakeup.
 */
class TrackRecorder(
    private val maxAccuracyM: Float = 50f,
    private val maxSpeedMs: Double = 15.0,
    private val idleRadiusM: Double = 10.0,
    private val idleAfterMs: Long = 60_000L,
    private val flushEveryPoints: Int = 10,
    private val flushEveryMs: Long = 30_000L,
) {

    private val pendingFixes = ArrayList<Fix>()

    /** Position the idle heuristic measures against; moves only when the user really moves. */
    private var anchor: Fix? = null
    private var anchorAt: Long = 0L
    private var lastFlushAt: Long = 0L

    /** Timestamp of the first accepted fix, null until the first one arrives. */
    var startedAt: Long? = null
        private set

    /** Last accepted fix -- the service uses it as the position of a quick find. */
    var lastFix: Fix? = null
        private set

    var totalDistanceM: Double = 0.0
        private set

    var pointCount: Int = 0
        private set

    var mode: LocationMode = LocationMode.TRACKING_MOVING
        private set

    /** Offers a fix to the recorder and reports what should happen because of it. */
    fun offer(fix: Fix): Decision {
        val accuracy = fix.accuracyM
        if (accuracy != null && accuracy > maxAccuracyM) return reject(FixRejection.ACCURACY)

        val previous = lastFix
        var step = 0.0
        if (previous != null) {
            val dtMs = fix.timestamp - previous.timestamp
            step = Geo.distanceM(previous.lat, previous.lon, fix.lat, fix.lon)
            when {
                dtMs < 0L -> return reject(FixRejection.OUT_OF_ORDER)

                dtMs == 0L && step <= 0.0 -> return reject(FixRejection.DUPLICATE)

                // A move with no elapsed time is an infinite speed, i.e. always a jump.
                dtMs == 0L -> return reject(FixRejection.JUMP)

                step / (dtMs / 1000.0) > maxSpeedMs -> return reject(FixRejection.JUMP)
            }
            // Only count movement that exceeds the fix's own uncertainty. Standing under trees
            // with 8 m accuracy produces an 8 m "step" every fix; over an hour that invents a
            // kilometre of walking in the notification and in the saved track statistics.
            val noiseFloorM = maxOf(MIN_STEP_M, (fix.accuracyM ?: 0f).toDouble())
            if (step > noiseFloorM) {
                totalDistanceM += step
            }
        } else {
            startedAt = fix.timestamp
            lastFlushAt = fix.timestamp
            anchor = fix
            anchorAt = fix.timestamp
        }

        lastFix = fix
        pointCount++
        pendingFixes += fix
        updateMode(fix)

        val flushDue = pendingFixes.size >= flushEveryPoints ||
            fix.timestamp - lastFlushAt >= flushEveryMs
        return Decision(
            accepted = true,
            shouldFlush = flushDue,
            mode = mode,
            distanceM = totalDistanceM,
            pointCount = pointCount,
        )
    }

    /** Buffered fixes that have not been handed to the database yet. */
    fun pending(): List<Fix> = pendingFixes.toList()

    /** Same as [pending], but empties the buffer and restarts the flush timer. */
    fun drainPending(): List<Fix> {
        val drained = pendingFixes.toList()
        pendingFixes.clear()
        lastFlushAt = lastFix?.timestamp ?: lastFlushAt
        return drained
    }

    /** Wall-clock length of the recording so far, measured from the first accepted fix. */
    fun durationMs(nowMs: Long): Long = startedAt?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L

    private fun updateMode(fix: Fix) {
        val current = anchor
        if (current == null) {
            anchor = fix
            anchorAt = fix.timestamp
            return
        }
        val fromAnchor = Geo.distanceM(current.lat, current.lon, fix.lat, fix.lon)
        if (fromAnchor >= idleRadiusM) {
            // Real movement: re-anchor here and go back to the fast cadence.
            anchor = fix
            anchorAt = fix.timestamp
            mode = LocationMode.TRACKING_MOVING
        } else if (fix.timestamp - anchorAt >= idleAfterMs) {
            // Still inside the circle after the grace period -- the user is standing.
            mode = LocationMode.TRACKING_IDLE
        }
    }

    private fun reject(reason: FixRejection) = Decision(
        accepted = false,
        shouldFlush = false,
        mode = mode,
        distanceM = totalDistanceM,
        pointCount = pointCount,
        rejection = reason,
    )

    private companion object {
        /**
         * Smallest movement counted towards the distance, regardless of reported accuracy.
         * Below this every fix is jitter, not walking.
         */
        private const val MIN_STEP_M = 3.0
    }
}
