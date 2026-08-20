package cz.hh.detektormapy.data.repository

import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.calibration.PointPair
import cz.hh.detektormapy.data.dao.GcpDao
import cz.hh.detektormapy.data.entity.GcpPointEntity
import cz.hh.detektormapy.data.entity.GcpSetEntity
import cz.hh.detektormapy.data.relation.GcpSetWithPoints
import kotlinx.coroutines.flow.Flow

/** Result of fitting a GCP set, shown live in the editor (PLAN.md F3-3). */
data class GcpFit(
    val transform: Affine2D?,
    /** RMSE in metres; 0.0 when there is nothing to fit. */
    val rmseM: Double,
    /** Per-point residuals in metres, aligned with the point list order. */
    val residualsM: List<Double>,
)

/** Ground control point sets for the GCP editor (PLAN.md section 6, mode B). */
class GcpRepository(private val dao: GcpDao) {

    fun observeSets(): Flow<List<GcpSetEntity>> = dao.observeAllSets()

    fun observeSetsForLayer(layerId: String): Flow<List<GcpSetEntity>> = dao.observeSetsForLayer(layerId)

    fun observeSetWithPoints(setId: Long): Flow<GcpSetWithPoints?> = dao.observeSetWithPoints(setId)

    fun observePoints(setId: Long): Flow<List<GcpPointEntity>> = dao.observePointsForSet(setId)

    suspend fun getSet(id: Long): GcpSetEntity? = dao.getSetById(id)

    suspend fun getSetWithPoints(id: Long): GcpSetWithPoints? = dao.getSetWithPoints(id)

    suspend fun getAllSets(): List<GcpSetEntity> = dao.getAllSets()

    suspend fun getPoints(setId: Long): List<GcpPointEntity> = dao.getPointsForSet(setId)

    suspend fun createSet(layerId: String, name: String, imagePath: String?, createdAt: Long): Long = dao.insertSet(
        GcpSetEntity(layerId = layerId, name = name, imagePath = imagePath, createdAt = createdAt),
    )

    suspend fun updateSet(set: GcpSetEntity) = dao.updateSet(set)

    suspend fun deleteSet(id: Long) = dao.deleteSetById(id)

    suspend fun addPoint(point: GcpPointEntity): Long = dao.insertPoint(point)

    suspend fun updatePoint(point: GcpPointEntity) = dao.updatePoint(point)

    suspend fun deletePoint(id: Long) = dao.deletePointById(id)

    suspend fun countSets(): Int = dao.countSets()

    suspend fun countPoints(): Int = dao.countPoints()

    /**
     * Fits the set's points. Fewer than three pairs (or a degenerate configuration) falls back to
     * a 4 DOF similarity fit, because a 6 DOF affine fit on three points happily shears a map into
     * nonsense. Returns a [GcpFit] with a null transform when nothing can be fitted at all.
     */
    suspend fun fit(setId: Long): GcpFit {
        val pairs = dao.getPointsForSet(setId).map { PointPair(it.srcX, it.srcY, it.dstX, it.dstY) }
        val transform = when {
            pairs.size >= 3 -> Affine2D.fitAffine(pairs) ?: Affine2D.fitSimilarity(pairs)
            else -> Affine2D.fitSimilarity(pairs)
        }
        if (transform == null) return GcpFit(null, 0.0, emptyList())
        return GcpFit(
            transform = transform,
            rmseM = Affine2D.rmse(transform, pairs),
            residualsM = Affine2D.residuals(transform, pairs),
        )
    }
}
