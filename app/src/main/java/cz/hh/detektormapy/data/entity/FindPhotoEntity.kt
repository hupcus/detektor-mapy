package cz.hh.detektormapy.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One photo attached to a [FindEntity]. Deleting the find cascades to its photos; the image file
 * itself is removed separately because the row may point at a `content://` uri we do not own.
 */
@Entity(
    tableName = "find_photos",
    foreignKeys = [
        ForeignKey(
            entity = FindEntity::class,
            parentColumns = ["id"],
            childColumns = ["findId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["findId"], name = "index_find_photos_findId"),
        Index(value = ["createdAt"], name = "index_find_photos_createdAt"),
    ],
)
data class FindPhotoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val findId: Long,
    /** Absolute file path or content uri of the image. */
    val uri: String,
    val createdAt: Long,
    val isPrimary: Boolean = false,
) {
    /** Stable identity used by export / import to deduplicate across devices and re-imports. */
    val externalId: String get() = ExternalIds.photo(createdAt, uri)
}
