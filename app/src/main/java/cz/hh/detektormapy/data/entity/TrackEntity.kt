package cz.hh.detektormapy.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A recorded walk. Live points land in [TrackPointEntity] and are flushed to a GPX file at
 * [gpxPath] once the recording stops; [gpxPath] stays null while the track is still running.
 */
@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["startedAt"], name = "index_tracks_startedAt"),
        Index(value = ["endedAt"], name = "index_tracks_endedAt"),
    ],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val startedAt: Long,
    /** Null while the recording is still in progress. */
    val endedAt: Long? = null,
    /** Absolute path of the flushed GPX file, null until the track is closed. */
    val gpxPath: String? = null,
    val distanceM: Double = 0.0,
    val durationMs: Long = 0L,
    val pointCount: Int = 0,
    val name: String = "",
) {
    val isRecording: Boolean get() = endedAt == null

    /** Stable identity used by export / import to deduplicate across devices and re-imports. */
    val externalId: String get() = ExternalIds.track(startedAt)
}
