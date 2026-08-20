package cz.hh.detektormapy.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A named collection of ground control points for the GCP editor (PLAN.md section 6, mode B).
 *
 * [imagePath] points at the scan being georeferenced when the set was built from a single image
 * rather than from an already tiled layer; it is null for tiled layers.
 */
@Entity(
    tableName = "gcp_sets",
    indices = [
        Index(value = ["layerId"], name = "index_gcp_sets_layerId"),
        Index(value = ["createdAt"], name = "index_gcp_sets_createdAt"),
    ],
)
data class GcpSetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val layerId: String,
    val name: String = "",
    /** Absolute path of the source scan, null when the set targets a tiled layer. */
    val imagePath: String? = null,
    val createdAt: Long,
) {
    /** Stable identity used by export / import to deduplicate across devices and re-imports. */
    val externalId: String get() = ExternalIds.gcpSet(createdAt, layerId)
}
