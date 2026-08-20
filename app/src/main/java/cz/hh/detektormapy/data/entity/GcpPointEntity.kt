package cz.hh.detektormapy.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One ground control point pair.
 *
 * [srcX] / [srcY] are source coordinates -- image pixels for a scan, Web Mercator metres for a
 * tiled layer. [dstX] / [dstY] are always Web Mercator metres (EPSG:3857) on the reference map.
 */
@Entity(
    tableName = "gcp_points",
    foreignKeys = [
        ForeignKey(
            entity = GcpSetEntity::class,
            parentColumns = ["id"],
            childColumns = ["setId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["setId"], name = "index_gcp_points_setId"),
    ],
)
data class GcpPointEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val setId: Long,
    val srcX: Double,
    val srcY: Double,
    val dstX: Double,
    val dstY: Double,
    val label: String = "",
)
