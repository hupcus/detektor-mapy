package cz.hh.detektormapy.data

import com.google.common.truth.Truth.assertThat
import cz.hh.detektormapy.data.dao.TrackDao
import cz.hh.detektormapy.data.dao.TrackPointDao
import cz.hh.detektormapy.data.entity.TrackEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackDaoTest {

    private lateinit var db: DetektorDatabase
    private lateinit var trackDao: TrackDao
    private lateinit var pointDao: TrackPointDao

    @Before
    fun setUp() {
        db = TestData.inMemoryDatabase()
        trackDao = db.trackDao()
        pointDao = db.trackPointDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun startTrack(name: String = "Pochůzka", startedAt: Long = TestData.T0): Long =
        trackDao.insert(TrackEntity(startedAt = startedAt, name = name))

    @Test
    fun `an unfinished track is the active one`() = runTest {
        val id = startTrack()
        val active = trackDao.getActive()
        assertThat(active?.id).isEqualTo(id)
        assertThat(active?.isRecording).isTrue()
    }

    @Test
    fun `finishing a track clears the active slot`() = runTest {
        val id = startTrack()
        trackDao.finish(
            id = id,
            endedAt = TestData.T0 + TestData.HOUR,
            durationMs = TestData.HOUR,
            distanceM = 1234.0,
            pointCount = 42,
            gpxPath = "tracks/1.gpx",
        )
        assertThat(trackDao.getActive()).isNull()
        val row = trackDao.getById(id)
        assertThat(row?.endedAt).isEqualTo(TestData.T0 + TestData.HOUR)
        assertThat(row?.gpxPath).isEqualTo("tracks/1.gpx")
        assertThat(row?.pointCount).isEqualTo(42)
        assertThat(row?.isRecording).isFalse()
    }

    @Test
    fun `points load through the relation in insertion order`() = runTest {
        val id = startTrack()
        pointDao.insertAll(
            listOf(
                TestData.trackPoint(id, 50.0, 15.0, TestData.T0),
                TestData.trackPoint(id, 50.001, 15.001, TestData.T0 + TestData.MINUTE),
                TestData.trackPoint(id, 50.002, 15.002, TestData.T0 + 2 * TestData.MINUTE),
            ),
        )
        val withPoints = trackDao.getWithPoints(id)
        assertThat(withPoints).isNotNull()
        assertThat(withPoints?.points).hasSize(3)
        assertThat(withPoints?.points?.map { it.timestamp })
            .isInOrder()
    }

    @Test
    fun `deleting a track cascades to its points`() = runTest {
        val id = startTrack()
        pointDao.insertAll(
            listOf(
                TestData.trackPoint(id, 50.0, 15.0, TestData.T0),
                TestData.trackPoint(id, 50.001, 15.001, TestData.T0 + TestData.MINUTE),
            ),
        )
        assertThat(pointDao.count()).isEqualTo(2)
        trackDao.deleteById(id)
        assertThat(pointDao.count()).isEqualTo(0)
    }

    @Test
    fun `observeAll sees a newly inserted track`() = runTest {
        startTrack(name = "První")
        val all = trackDao.observeAll().first()
        assertThat(all.map { it.name }).containsExactly("První")
    }

    @Test
    fun `points of one track never leak into another`() = runTest {
        val a = startTrack(name = "A")
        val b = startTrack(name = "B", startedAt = TestData.T0 + TestData.DAY)
        pointDao.insert(TestData.trackPoint(a, 50.0, 15.0, TestData.T0))
        pointDao.insert(TestData.trackPoint(b, 49.0, 14.0, TestData.T0 + TestData.DAY))
        assertThat(pointDao.getForTrack(a)).hasSize(1)
        assertThat(pointDao.getForTrack(b)).hasSize(1)
        assertThat(pointDao.getForTrack(a).first().lat).isEqualTo(50.0)
    }
}
