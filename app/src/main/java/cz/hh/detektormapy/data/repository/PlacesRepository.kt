package cz.hh.detektormapy.data.repository

import cz.hh.detektormapy.data.dao.PlaceDao
import cz.hh.detektormapy.data.entity.PlaceEntity
import cz.hh.detektormapy.data.model.PlaceType
import cz.hh.detektormapy.util.BBox
import kotlinx.coroutines.flow.Flow

/** Waypoints: planned spots, points of interest, no-go zones, meeting points, parking. */
class PlacesRepository(private val placeDao: PlaceDao) {

    fun observeAll(): Flow<List<PlaceEntity>> = placeDao.observeAll()

    fun observePlace(id: Long): Flow<PlaceEntity?> = placeDao.observeById(id)

    fun observeFiltered(types: Set<PlaceType> = emptySet(), unvisitedOnly: Boolean = false): Flow<List<PlaceEntity>> =
        placeDao.observeFiltered(
            types = types.map { it.name },
            ignoreTypes = types.isEmpty(),
            unvisitedOnly = unvisitedOnly,
        )

    fun observeInBBox(bbox: BBox): Flow<List<PlaceEntity>> =
        placeDao.observeInBBox(bbox.west, bbox.south, bbox.east, bbox.north)

    suspend fun getPlace(id: Long): PlaceEntity? = placeDao.getById(id)

    suspend fun getAll(): List<PlaceEntity> = placeDao.getAll()

    suspend fun add(place: PlaceEntity): Long = placeDao.insert(place)

    suspend fun update(place: PlaceEntity) = placeDao.update(place)

    suspend fun delete(id: Long) = placeDao.deleteById(id)

    suspend fun markVisited(id: Long, visited: Boolean, visitedAt: Long?) = placeDao.setVisited(id, visited, visitedAt)

    suspend fun count(): Int = placeDao.count()
}
