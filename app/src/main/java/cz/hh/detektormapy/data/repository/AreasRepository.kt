package cz.hh.detektormapy.data.repository

import cz.hh.detektormapy.data.dao.SearchedAreaDao
import cz.hh.detektormapy.data.entity.SearchedAreaEntity
import cz.hh.detektormapy.data.model.AreaStatus
import kotlinx.coroutines.flow.Flow

/** Manually drawn polygons of already searched ground (PLAN.md F4-2). */
class AreasRepository(private val areaDao: SearchedAreaDao) {

    fun observeAll(): Flow<List<SearchedAreaEntity>> = areaDao.observeAll()

    fun observeByStatus(status: AreaStatus): Flow<List<SearchedAreaEntity>> = areaDao.observeByStatus(status.name)

    /** Total hectares in a given state, for the statistics screen. */
    fun observeTotalAreaHa(status: AreaStatus): Flow<Double> = areaDao.observeTotalAreaHa(status.name)

    suspend fun getArea(id: Long): SearchedAreaEntity? = areaDao.getById(id)

    suspend fun getAll(): List<SearchedAreaEntity> = areaDao.getAll()

    suspend fun add(area: SearchedAreaEntity): Long = areaDao.insert(area)

    suspend fun update(area: SearchedAreaEntity) = areaDao.update(area)

    suspend fun setStatus(id: Long, status: AreaStatus) = areaDao.setStatus(id, status.name)

    suspend fun delete(id: Long) = areaDao.deleteById(id)

    suspend fun count(): Int = areaDao.count()
}
