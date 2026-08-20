package cz.hh.detektormapy.data.repository

import cz.hh.detektormapy.data.dao.CategoryCount
import cz.hh.detektormapy.data.dao.FindDao
import cz.hh.detektormapy.data.dao.FindPhotoDao
import cz.hh.detektormapy.data.dao.FindStats
import cz.hh.detektormapy.data.entity.FindEntity
import cz.hh.detektormapy.data.entity.FindPhotoEntity
import cz.hh.detektormapy.data.model.FindCategory
import cz.hh.detektormapy.data.relation.FindWithPhotos
import cz.hh.detektormapy.util.BBox
import cz.hh.detektormapy.util.Geo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Filter state of the finds gallery (PLAN.md F2-3). */
data class FindFilter(
    /** Empty means "every category". */
    val categories: Set<FindCategory> = emptySet(),
    val fromMillis: Long = Long.MIN_VALUE,
    val toMillis: Long = Long.MAX_VALUE,
    val favoriteOnly: Boolean = false,
) {
    companion object {
        val ALL = FindFilter()
    }
}

/** Finds and their photos. The UI never sees Room types other than the entities themselves. */
class FindsRepository(private val findDao: FindDao, private val photoDao: FindPhotoDao) {

    fun observeAll(): Flow<List<FindEntity>> = findDao.observeAll()

    fun observeAllWithPhotos(): Flow<List<FindWithPhotos>> = findDao.observeAllWithPhotos()

    fun observeFind(id: Long): Flow<FindEntity?> = findDao.observeById(id)

    fun observeFindWithPhotos(id: Long): Flow<FindWithPhotos?> = findDao.observeWithPhotos(id)

    fun observeFiltered(filter: FindFilter): Flow<List<FindEntity>> = findDao.observeFiltered(
        categories = filter.categories.map { it.name },
        ignoreCategories = filter.categories.isEmpty(),
        fromMillis = filter.fromMillis,
        toMillis = filter.toMillis,
        favoriteOnly = filter.favoriteOnly,
    )

    fun observeInBBox(bbox: BBox): Flow<List<FindEntity>> =
        findDao.observeInBBox(bbox.west, bbox.south, bbox.east, bbox.north)

    fun observeStats(): Flow<FindStats> = findDao.observeStats()

    /** Histogram for the statistics screen, already mapped back to the enum. */
    fun observeCountsByCategory(): Flow<Map<FindCategory, Int>> =
        findDao.observeCountsByCategory().map { rows: List<CategoryCount> ->
            rows.associate { FindCategory.fromName(it.category) to it.count }
        }

    suspend fun getFind(id: Long): FindEntity? = findDao.getById(id)

    suspend fun getFindWithPhotos(id: Long): FindWithPhotos? = findDao.getWithPhotos(id)

    suspend fun getInBBox(bbox: BBox): List<FindEntity> =
        findDao.getInBBox(bbox.west, bbox.south, bbox.east, bbox.north)

    /**
     * How many finds already sit within [radiusM] of the given point — the capture screen's
     * "Tvůj lov: N. na tomto místě". The bbox pre-filter keeps the query cheap; the haversine
     * pass trims the bbox corners so the circle is honest.
     */
    suspend fun countNear(lat: Double, lon: Double, radiusM: Double = SAME_SPOT_RADIUS_M): Int {
        val dLat = radiusM / M_PER_DEG_LAT
        val dLon = radiusM / (M_PER_DEG_LAT * kotlin.math.cos(Math.toRadians(lat)))
        return findDao
            .getInBBox(west = lon - dLon, south = lat - dLat, east = lon + dLon, north = lat + dLat)
            .count { Geo.distanceM(lat, lon, it.lat, it.lon) <= radiusM }
    }

    suspend fun add(find: FindEntity): Long = findDao.insert(find)

    suspend fun update(find: FindEntity) = findDao.update(find)

    suspend fun delete(id: Long) = findDao.deleteById(id)

    suspend fun setFavorite(id: Long, favorite: Boolean) = findDao.setFavorite(id, favorite)

    suspend fun addPhoto(photo: FindPhotoEntity): Long = photoDao.insert(photo)

    suspend fun deletePhoto(id: Long) = photoDao.deleteById(id)

    suspend fun setPrimaryPhoto(findId: Long, photoId: Long) = photoDao.setPrimary(findId, photoId)

    fun observePhotos(findId: Long): Flow<List<FindPhotoEntity>> = photoDao.observeForFind(findId)

    suspend fun countFinds(): Int = findDao.count()

    suspend fun countPhotos(): Int = photoDao.count()

    companion object {
        /** Radius that still counts as "this spot" for [countNear]. Roughly one field edge. */
        const val SAME_SPOT_RADIUS_M = 150.0
        private const val M_PER_DEG_LAT = 111_320.0
    }
}
