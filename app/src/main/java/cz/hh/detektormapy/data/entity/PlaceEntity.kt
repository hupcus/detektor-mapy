package cz.hh.detektormapy.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import cz.hh.detektormapy.data.model.PlaceType

/** A waypoint: a planned spot, a point of interest, a no-go zone, a meeting point or parking. */
@Entity(
    tableName = "places",
    indices = [
        Index(value = ["lat", "lon"], name = "index_places_lat_lon"),
        Index(value = ["createdAt"], name = "index_places_createdAt"),
        Index(value = ["type"], name = "index_places_type"),
    ],
)
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val lat: Double,
    val lon: Double,
    val type: PlaceType = PlaceType.DEFAULT,
    val title: String = "",
    val note: String = "",
    val createdAt: Long,
    val visited: Boolean = false,
    val visitedAt: Long? = null,
) {
    /** Stable identity used by export / import to deduplicate across devices and re-imports. */
    val externalId: String get() = ExternalIds.place(createdAt, lat, lon)
}
