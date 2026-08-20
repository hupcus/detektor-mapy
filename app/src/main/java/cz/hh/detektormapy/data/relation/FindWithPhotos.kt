package cz.hh.detektormapy.data.relation

import androidx.room.Embedded
import androidx.room.Relation
import cz.hh.detektormapy.data.entity.FindEntity
import cz.hh.detektormapy.data.entity.FindPhotoEntity

/** A find together with its photos, loaded in one `@Transaction`. */
data class FindWithPhotos(
    @Embedded val find: FindEntity,
    @Relation(parentColumn = "id", entityColumn = "findId")
    val photos: List<FindPhotoEntity> = emptyList(),
) {
    /** The photo flagged primary, falling back to the oldest one, or null when there are none. */
    val primaryPhoto: FindPhotoEntity?
        get() = photos.firstOrNull { it.isPrimary } ?: photos.minByOrNull { it.createdAt }
}
