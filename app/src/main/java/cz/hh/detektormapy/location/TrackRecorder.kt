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
    /**
     * True when this fix (or the one it confirmed) became a vertex of the drawn trail.
     * An accepted fix that is not stored is a real position that simply did not move far
     * enough to be worth a point -- standing over a hole, most of the time.
     */
    val stored: Boolean,
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
 * @param minStoreDistanceM how far the user must actually get from the last drawn point before
 *   another one is drawn. This is what stops a stop from becoming a scribble: digging a find
 *   keeps the receiver in one place for several minutes while its reported position wanders by
 *   its own accuracy, and every one of those wanders used to become a vertex. The trail is an
 *   orientation aid -- "have I swept here?" -- so a metre of precision buys nothing and a ball
 *   of wool over the spot you dug costs real legibility.
 * @param flushEveryPoints / [flushEveryMs] how often the caller is told to write the buffer to
 *   the database. The buffer is not just a write optimisation -- nothing on the map exists until
 *   it is flushed, because the map reads the track from Room. Batching too hard therefore hides
 *   the newest stretch of the walk, which is the one stretch the user is asking about when they
 *   look down at the phone: "have I already swept here?" A few rows every few seconds costs
 *   SQLite nothing next to the GPS wakeup that produced them.
 */
class TrackRecorder(
    private val maxAccuracyM: Float = 50f,
    private val maxSpeedMs: Double = 15.0,
    private val idleRadiusM: Double = 10.0,
    private val idleAfterMs: Long = 60_000L,
    private val flushEveryPoints: Int = 3,
    private val flushEveryMs: Long = 6_000L,
    private val minStoreDistanceM: Double = MIN_STORE_M,
) {

    private val pendingFixes = ArrayList<Fix>()

    /** Last fix that actually became a vertex of the trail; the yardstick for the next one. */
    private var lastStoredFix: Fix? = null

    /**
     * A fix that cleared the movement threshold but has not been confirmed by a second one yet.
     *
     * GPS does not only wander in small circles, it occasionally throws a single position tens
     * of metres away and comes straight back. Storing on the first sighting would spike the
     * trail out and back for no reason, so a candidate waits for the next fix to agree that we
     * really did leave. The trail therefore trails the walker by one fix -- a few seconds, which
     * costs nothing on a tool for deciding where you have already been.
     */
    private var candidate: Fix? = null

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
        updateMode(fix)
        val stored = considerForTrail(fix)

        val flushDue = pendingFixes.isNotEmpty() &&
            (
                pendingFixes.size >= flushEveryPoints ||
                    fix.timestamp - lastFlushAt >= flushEveryMs
                )
        return Decision(
            accepted = true,
            stored = stored,
            shouldFlush = flushDue,
            mode = mode,
            distanceM = totalDistanceM,
            pointCount = pointCount,
        )
    }

    /**
     * Decides whether this fix moves the trail on, and returns true when a vertex was added.
     *
     * Three states, in the order they are checked:
     * - **No trail yet** -- the very first accepted fix anchors it, unconditionally.
     * - **Far enough from the last vertex** -- this fix becomes a candidate, and whatever
     *   candidate was already waiting is confirmed and stored. Confirmation is the whole point:
     *   a lone outlier is followed by a fix back at the old spot, which lands in the third case
     *   and throws the outlier away before it ever reaches the trail.
     * - **Still around the last vertex** -- nothing is stored and any waiting candidate is
     *   dropped. This is the case that runs while you dig.
     */
    private fun considerForTrail(fix: Fix): Boolean {
        val anchorFix = lastStoredFix
        if (anchorFix == null) {
            commit(fix)
            return true
        }
        val moved = Geo.distanceM(anchorFix.lat, anchorFix.lon, fix.lat, fix.lon)
        // Scaled by the fix's own uncertainty: under tree cover a "10 m step" may be pure noise,
        // and a fixed threshold would let the canopy draw the scribble the threshold exists to
        // prevent.
        val threshold = maxOf(minStoreDistanceM, (fix.accuracyM ?: 0f).toDouble())
        if (moved < threshold) {
            candidate = null
            return false
        }
        val confirmed = candidate
        if (confirmed == null) {
            candidate = fix
            return false
        }
        commit(confirmed)
        // The current fix is now measured against the vertex just laid down, not the old one.
        // Without this re-check the candidate is always one step behind the anchor and the
        // trail ends up spaced by the sampling interval rather than by the threshold -- which
        // is the scribble this method exists to prevent, only stretched into a line.
        candidate = fix.takeIf {
            Geo.distanceM(confirmed.lat, confirmed.lon, it.lat, it.lon) >= threshold
        }
        return true
    }

    private fun commit(fix: Fix) {
        lastStoredFix = fix
        pointCount++
        pendingFixes += fix
    }

    /**
     * The last position worth drawing, handed over when the recording stops.
     *
     * Without it the trail ends at the last confirmed vertex, which can be a threshold short of
     * where the walk actually finished -- and the end of the line is exactly the bit someone
     * looks at to see where they left off.
     */
    fun finalPoint(): Fix? {
        val tail = candidate ?: lastFix ?: return null
        val anchorFix = lastStoredFix
        if (anchorFix != null && anchorFix.lat == tail.lat && anchorFix.lon == tail.lon) return null
        commit(tail)
        candidate = null
        return tail
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
        stored = false,
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

        /**
         * Default movement needed before the trail gains another point.
         *
         * Chosen against the job, not against the GPS: a detector sweep covers roughly a metre,
         * so ten metres is "a different patch of ground" while still being wider than the
         * wander of a stationary consumer receiver in the open.
         */
        const val MIN_STORE_M = 10.0
    }
}
