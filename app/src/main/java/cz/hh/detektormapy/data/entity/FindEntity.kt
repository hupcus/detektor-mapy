package cz.hh.detektormapy.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import cz.hh.detektormapy.data.model.FindCategory

/**
 * A single find logged in the field.
 *
 * Coordinates are WGS84 degrees; [altitude] and [accuracyM] come straight from the fix and are
 * null when the provider did not supply them. [layerContextId] records which historical map was
 * active at the moment of logging (PLAN.md F2-6).
 */
@Entity(
    tableName = "finds",
    indices = [
        Index(value = ["lat", "lon"], name = "index_finds_lat_lon"),
        Index(value = ["createdAt"], name = "index_finds_createdAt"),
        Index(value = ["category"], name = "index_finds_category"),
        Index(value = ["favorite"], name = "index_finds_favorite"),
        Index(value = ["trackId"], name = "index_finds_trackId"),
    ],
)
data class FindEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    val lat: Double,
    val lon: Double,
    val altitude: Double? = null,
    val accuracyM: Float? = null,
    val createdAt: Long,
    val title: String = "",
    val category: FindCategory = FindCategory.DEFAULT,
    val depthCm: Int? = null,
    val note: String = "",
    val favorite: Boolean = false,
    /** Id of the [cz.hh.detektormapy.map.LayerDef] that was on screen when the find was logged. */
    val layerContextId: String? = null,
    /** Track this find was logged during, if any. Deliberately not a foreign key: deleting a
     * track must never delete the finds made on it. */
    val trackId: Long? = null,
) {
    /** Stable identity used by export / import to deduplicate across devices and re-imports. */
    val externalId: String get() = ExternalIds.find(createdAt, lat, lon)
}
