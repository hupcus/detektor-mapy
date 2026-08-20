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

        assertThat(recorder.pointCount).isEqualTo(21)
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
    fun `signals a flush every ten points`() {
        val recorder = TrackRecorder()
        val flushed = mutableListOf<Int>()

        repeat(20) { step ->
            val decision = recorder.offer(
                fix(offsetMetres = step * 5.0, atMillis = startMillis + step * 1_000L),
            )
            if (decision.shouldFlush) {
                flushed += recorder.drainPending().size
            }
        }

        assertThat(flushed).containsExactly(10, 10).inOrder()
        assertThat(recorder.pending()).isEmpty()
    }

    @Test
    fun `flushes on the time budget even when the buffer stays small`() {
        val recorder = TrackRecorder()
        recorder.offer(fix())

        // Standing still at the 30 s idle cadence: only two points, but half a minute of them.
        val decision = recorder.offer(fix(offsetMetres = 1.0, atMillis = startMillis + 30_000L))

        assertThat(decision.shouldFlush).isTrue()
        assertThat(recorder.drainPending()).hasSize(2)
    }

    @Test
    fun `drain empties the buffer without touching the totals`() {
        val recorder = TrackRecorder()
        repeat(3) { step ->
            recorder.offer(fix(offsetMetres = step * 5.0, atMillis = startMillis + step * 1_000L))
        }

        val drained = recorder.drainPending()

        assertThat(drained).hasSize(3)
        assertThat(recorder.pending()).isEmpty()
        assertThat(recorder.pointCount).isEqualTo(3)
        assertThat(recorder.totalDistanceM).isWithin(1.0).of(10.0)
        assertThat(recorder.lastFix?.timestamp).isEqualTo(startMillis + 2_000L)
    }
}
