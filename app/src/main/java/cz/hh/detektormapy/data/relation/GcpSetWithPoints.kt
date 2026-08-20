package cz.hh.detektormapy.data.relation

import androidx.room.Embedded
import androidx.room.Relation
import cz.hh.detektormapy.data.entity.GcpPointEntity
import cz.hh.detektormapy.data.entity.GcpSetEntity

/** A GCP set together with its point pairs, loaded in one `@Transaction`. */
data class GcpSetWithPoints(
    @Embedded val set: GcpSetEntity,
    @Relation(parentColumn = "id", entityColumn = "setId")
    val points: List<GcpPointEntity> = emptyList(),
)
