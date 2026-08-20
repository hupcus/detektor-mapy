package cz.hh.detektormapy.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One GPS sample of a [TrackEntity]. This table is the live buffer written by the foreground
 * recording service; it is what gets serialised into the track's GPX file.
 */
@Entity(
    tableName = "track_points",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["trackId", "timestamp"], name = "index_track_points_trackId_timestamp"),
    ],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val trackId: Long,
    val lat: Double,
    val lon: Double,
    val altitude: Double? = null,
    val timestamp: Long,
    val accuracyM: Float? = null,
    val speedMs: Float? = null,
)
