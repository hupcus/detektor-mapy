package cz.hh.detektormapy.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import cz.hh.detektormapy.data.model.AreaStatus

/**
 * A manually drawn polygon marking ground that has already been searched.
 *
 * [polygonGeoJson] holds a bare GeoJSON *geometry* object (`Polygon` or `MultiPolygon`), not a
 * feature, so it can be dropped into an export unchanged.
 */
@Entity(
    tableName = "searched_areas",
    indices = [
        Index(value = ["createdAt"], name = "index_searched_areas_createdAt"),
        Index(value = ["status"], name = "index_searched_areas_status"),
    ],
)
data class SearchedAreaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String = "",
    val polygonGeoJson: String,
    val createdAt: Long,
    val status: AreaStatus = AreaStatus.DEFAULT,
    /** Pre-computed area in hectares so the list does not have to re-parse the polygon. */
    val areaHa: Double = 0.0,
) {
    /** Stable identity used by export / import to deduplicate across devices and re-imports. */
    val externalId: String get() = ExternalIds.area(createdAt, name)
}
