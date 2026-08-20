package cz.hh.detektormapy.data.repository

import cz.hh.detektormapy.data.dao.TrackDao
import cz.hh.detektormapy.data.dao.TrackPointDao
import cz.hh.detektormapy.data.dao.TrackStats
import cz.hh.detektormapy.data.entity.TrackEntity
import cz.hh.detektormapy.data.entity.TrackPointEntity
import cz.hh.detektormapy.data.relation.TrackWithPoints
import kotlinx.coroutines.flow.Flow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Walk recordings and their live GPS buffer (PLAN.md F4-1). */
class TracksRepository(private val trackDao: TrackDao, private val pointDao: TrackPointDao) {

    fun observeAll(): Flow<List<TrackEntity>> = trackDao.observeAll()

    fun observeActive(): Flow<TrackEntity?> = trackDao.observeActive()

    fun observeWithPoints(id: Long): Flow<TrackWithPoints?> = trackDao.observeWithPoints(id)

    fun observePoints(trackId: Long): Flow<List<TrackPointEntity>> = pointDao.observeForTrack(trackId)

    fun observeStats(): Flow<TrackStats> = trackDao.observeStats()

    suspend fun getTrack(id: Long): TrackEntity? = trackDao.getById(id)

    suspend fun getAll(): List<TrackEntity> = trackDao.getAll()

    suspend fun getWithPoints(id: Long): TrackWithPoints? = trackDao.getWithPoints(id)

    suspend fun getPoints(trackId: Long): List<TrackPointEntity> = pointDao.getForTrack(trackId)

    suspend fun getActive(): TrackEntity? = trackDao.getActive()

    /** Opens a new recording. Returns the id the foreground service should append points to. */
    suspend fun startTrack(startedAt: Long, name: String): Long =
        trackDao.insert(TrackEntity(startedAt = startedAt, name = name))

    suspend fun appendPoint(point: TrackPointEntity): Long = pointDao.insert(point)

    suspend fun appendPoints(points: List<TrackPointEntity>): List<Long> = pointDao.insertAll(points)

    /**
     * Closes a recording: recomputes distance and point count from the buffer and stores them
     * alongside [gpxPath]. Passing a null [gpxPath] is fine -- the buffer stays the source of
     * truth until the file is written.
     */
    suspend fun finishTrack(id: Long, endedAt: Long, gpxPath: String?): TrackEntity? {
        val track = trackDao.getById(id) ?: return null
        val points = pointDao.getForTrack(id)
        trackDao.finish(
            id = id,
            endedAt = endedAt,
            durationMs = (endedAt - track.startedAt).coerceAtLeast(0L),
            distanceM = totalDistanceM(points),
            pointCount = points.size,
            gpxPath = gpxPath,
        )
        return trackDao.getById(id)
    }

    suspend fun update(track: TrackEntity) = trackDao.update(track)

    suspend fun delete(id: Long) = trackDao.deleteById(id)

    suspend fun count(): Int = trackDao.count()

    suspend fun countPoints(): Int = pointDao.count()

    /** Great-circle length of a point list, in metres. */
    fun totalDistanceM(points: List<TrackPointEntity>): Double {
        if (points.size < 2) return 0.0
        val ordered = points.sortedBy { it.timestamp }
        var sum = 0.0
        for (i in 1 until ordered.size) {
            sum += haversineM(
                ordered[i - 1].lat,
                ordered[i - 1].lon,
                ordered[i].lat,
                ordered[i].lon,
            )
        }
        return sum
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371008.8
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
