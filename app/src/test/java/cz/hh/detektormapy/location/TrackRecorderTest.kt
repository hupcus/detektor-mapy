package cz.hh.detektormapy.location

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The recorder is the part of the recording pipeline that decides what ends up in the track,
 * so every rule it enforces is pinned down here. Plain JVM on purpose -- no Robolectric.
 */
class TrackRecorderTest {

    private val startMillis = 1_700_000_000_000L

    /** Roughly one metre of latitude, good enough to build a synthetic straight walk. */
    private val metreInDegreesLat = 1.0 / 111_132.0

    private fun fix(
        offsetMetres: Double = 0.0,
        atMillis: Long = startMillis,
        accuracyM: Float? = 5f,
        lon: Double = 15.0,
    ) = Fix(
        lat = 50.0 + offsetMetres * metreInDegreesLat,
        lon = lon,
        altitude = 300.0,
        accuracyM = accuracyM,
        speedMs = null,
        bearingDeg = null,
        timestamp = atMillis,
    )

    @Test
    fun `rejects a fix with poor accuracy`() {
        val recorder = TrackRecorder()

        val decision = recorder.offer(fix(accuracyM = 80f))

        assertThat(decision.accepted).isFalse()
        assertThat(decision.rejection).isEqualTo(FixRejection.ACCURACY)
        assertThat(recorder.pointCount).isEqualTo(0)
        assertThat(recorder.pending()).isEmpty()
    }

    @Test
    fun `accepts a fix without a reported accuracy`() {
        val recorder = TrackRecorder()

        assertThat(recorder.offer(fix(accuracyM = null)).accepted).isTrue()
    }

    @Test
    fun `rejects a two kilometre jump one second apart`() {
        val recorder = TrackRecorder()
        recorder.offer(fix())

        val jump = recorder.offer(fix(offsetMetres = 2_000.0, atMillis = startMillis + 1_000L))

        assertThat(jump.accepted).isFalse()
        assertThat(jump.rejection).isEqualTo(FixRejection.JUMP)
        assertThat(recorder.pointCount).isEqualTo(1)
        assertThat(recorder.totalDistanceM).isEqualTo(0.0)
    }

    @Test
    fun `rejects a fix that travelled back in time`() {
        val recorder = TrackRecorder()
        recorder.offer(fix(atMillis = startMillis + 10_000L))

        val stale = recorder.offer(fix(offsetMetres = 3.0, atMillis = startMillis))

        assertThat(stale.rejection).isEqualTo(FixRejection.OUT_OF_ORDER)
    }

    @Test
    fun `accumulates distance over a straight walk`() {
        val recorder = TrackRecorder()
        // 20 steps of 5 m, one second apart: 100 m of walking at a believable 5 m/s.
        repeat(21) { step ->
            val decision = recorder.offer(
                fix(offsetMetres = step * 5.0, atMillis = startMillis + step * 1_000L),
            )
            assertThat(decision.accepted).isTrue()
        }

        // Every fix is accepted, but only every tenth metre becomes a vertex of the trail.
        assertThat(recorder.pointCount).isEqualTo(10)
        assertThat(recorder.totalDistanceM).isWithin(1.0).of(100.0)
        assertThat(recorder.durationMs(startMillis + 20_000L)).isEqualTo(20_000L)
    }

    @Test
    fun `drops to idle after a minute of standing and recovers on a real step`() {
        val recorder = TrackRecorder()
        recorder.offer(fix())
        assertThat(recorder.mode).isEqualTo(LocationMode.TRACKING_MOVING)

        // Half a minute of GPS jitter inside a two metre circle: still "moving".
        recorder.offer(fix(offsetMetres = 1.5, atMillis = startMillis + 30_000L))
        assertThat(recorder.mode).isEqualTo(LocationMode.TRACKING_MOVING)

        // Past the 60 s grace period and still inside the 10 m circle: the user stands.
        val idle = recorder.offer(fix(offsetMetres = 2.0, atMillis = startMillis + 61_000L))
        assertThat(idle.mode).isEqualTo(LocationMode.TRACKING_IDLE)
        assertThat(recorder.mode).isEqualTo(LocationMode.TRACKING_IDLE)

        // A 15 m step is real movement, so the fast cadence comes back.
        val moving = recorder.offer(fix(offsetMetres = 17.0, atMillis = startMillis + 71_000L))
        assertThat(moving.mode).isEqualTo(LocationMode.TRACKING_MOVING)
    }

    @Test
    fun `signals a flush every few points, so the trail keeps up with the walk`() {
        val recorder = TrackRecorder()
        val flushed = mutableListOf<Int>()

        repeat(12) { step ->
            val decision = recorder.offer(
                fix(offsetMetres = step * 5.0, atMillis = startMillis + step * 1_000L),
            )
            if (decision.shouldFlush) {
                flushed += recorder.drainPending().size
            }
        }

        // Nothing is on the map until it is flushed, so the batch stays small on purpose: the
        // stretch you just walked is exactly the stretch you look down to check.
        assertThat(flushed).containsExactly(3, 3).inOrder()
        assertThat(recorder.pending()).isEmpty()
    }

    @Test
    fun `flushes on the time budget even when the buffer stays small`() {
        val recorder = TrackRecorder()
        recorder.offer(fix())

        // Two vertices, a slow half minute apart: the time budget gets them written even though
        // the batch never fills.
        recorder.offer(fix(offsetMetres = 15.0, atMillis = startMillis + 30_000L))
        val decision = recorder.offer(fix(offsetMetres = 30.0, atMillis = startMillis + 60_000L))

        assertThat(decision.shouldFlush).isTrue()
        assertThat(recorder.drainPending()).hasSize(2)
    }

    @Test
    fun `a buffer with nothing in it is never worth flushing`() {
        val recorder = TrackRecorder()
        recorder.offer(fix())
        recorder.drainPending()

        // Standing still for minutes: the time budget passes over and over, but there is
        // nothing to write and waking the database would be pure waste.
        repeat(5) { step ->
            val decision = recorder.offer(
                fix(offsetMetres = 2.0, atMillis = startMillis + (step + 1) * 30_000L),
            )
            assertThat(decision.shouldFlush).isFalse()
        }
    }

    @Test
    fun `drain empties the buffer without touching the totals`() {
        val recorder = TrackRecorder()
        // 0, 15, 30, 45 m: every step clears the threshold, so every step is a vertex once
        // the one behind it is confirmed.
        repeat(4) { step ->
            recorder.offer(fix(offsetMetres = step * 15.0, atMillis = startMillis + step * 3_000L))
        }

        val drained = recorder.drainPending()

        assertThat(drained).hasSize(3)
        assertThat(recorder.pending()).isEmpty()
        assertThat(recorder.pointCount).isEqualTo(3)
        assertThat(recorder.totalDistanceM).isWithin(1.0).of(45.0)
        assertThat(recorder.lastFix?.timestamp).isEqualTo(startMillis + 9_000L)
    }

    // --- standing still ------------------------------------------------------------

    @Test
    fun `digging in one spot adds no points to the trail`() {
        val recorder = TrackRecorder()
        recorder.offer(fix())
        assertThat(recorder.pointCount).isEqualTo(1)

        // Five minutes over a hole. The receiver reports a different position every time,
        // wandering by its own accuracy, but the user has not gone anywhere.
        val wander = listOf(2.0, 5.0, 1.0, 7.0, 3.0, 6.0, 2.0, 4.0)
        wander.forEachIndexed { index, offset ->
            val decision = recorder.offer(
                fix(offsetMetres = offset, atMillis = startMillis + (index + 1) * 30_000L),
            )
            assertThat(decision.accepted).isTrue()
            assertThat(decision.stored).isFalse()
        }

        assertThat(recorder.pointCount).isEqualTo(1)
        assertThat(recorder.pending()).hasSize(1)
    }

    @Test
    fun `walking away from the hole starts drawing again`() {
        val recorder = TrackRecorder()
        recorder.offer(fix())
        repeat(4) { recorder.offer(fix(offsetMetres = 3.0, atMillis = startMillis + (it + 1) * 30_000L)) }
        assertThat(recorder.pointCount).isEqualTo(1)

        // Off again: the first fix past the threshold waits for confirmation, the second
        // confirms it.
        val first = recorder.offer(fix(offsetMetres = 15.0, atMillis = startMillis + 160_000L))
        assertThat(first.stored).isFalse()
        val second = recorder.offer(fix(offsetMetres = 30.0, atMillis = startMillis + 170_000L))
        assertThat(second.stored).isTrue()
        assertThat(recorder.pointCount).isEqualTo(2)
    }

    @Test
    fun `a lone GPS outlier never reaches the trail`() {
        val recorder = TrackRecorder()
        recorder.offer(fix())

        // One wild position 40 m away, then straight back to where we were standing.
        val spike = recorder.offer(fix(offsetMetres = 40.0, atMillis = startMillis + 30_000L))
        assertThat(spike.stored).isFalse()
        val back = recorder.offer(fix(offsetMetres = 2.0, atMillis = startMillis + 60_000L))
        assertThat(back.stored).isFalse()

        assertThat(recorder.pointCount).isEqualTo(1)
    }

    @Test
    fun `poor accuracy raises the bar before a point is drawn`() {
        val recorder = TrackRecorder(minStoreDistanceM = 10.0)
        recorder.offer(fix(accuracyM = 25f))

        // 15 m of "movement" reported with 25 m of uncertainty is not movement.
        recorder.offer(fix(offsetMetres = 15.0, accuracyM = 25f, atMillis = startMillis + 30_000L))
        recorder.offer(fix(offsetMetres = 18.0, accuracyM = 25f, atMillis = startMillis + 60_000L))

        assertThat(recorder.pointCount).isEqualTo(1)
    }

    @Test
    fun `stopping the recording draws the last position walked to`() {
        val recorder = TrackRecorder()
        recorder.offer(fix())
        recorder.offer(fix(offsetMetres = 15.0, atMillis = startMillis + 10_000L))
        // One vertex so far: the 15 m fix is still an unconfirmed candidate.
        assertThat(recorder.pointCount).isEqualTo(1)

        assertThat(recorder.finalPoint()).isNotNull()

        assertThat(recorder.pointCount).isEqualTo(2)
        // Idempotent: a second call has nothing left to add.
        assertThat(recorder.finalPoint()).isNull()
    }
}
