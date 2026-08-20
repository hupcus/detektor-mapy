package cz.hh.detektormapy.data.relation

import androidx.room.Embedded
import androidx.room.Relation
import cz.hh.detektormapy.data.entity.TrackEntity
import cz.hh.detektormapy.data.entity.TrackPointEntity

/** A track together with its buffered GPS samples, loaded in one `@Transaction`. */
data class TrackWithPoints(
    @Embedded val track: TrackEntity,
    @Relation(parentColumn = "id", entityColumn = "trackId")
    val points: List<TrackPointEntity> = emptyList(),
) {
    /** Points in recording order; `@Relation` itself gives no ordering guarantee. */
    val orderedPoints: List<TrackPointEntity> get() = points.sortedBy { it.timestamp }
}
