package cz.hh.detektormapy.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One machine the user actually owns.
 *
 * The app deliberately ships no catalogue of detectors and no factory settings. Every
 * manufacturer scales sensitivity, discrimination and ground balance differently, so a number
 * invented here would be worse than no number at all. What the app can honestly do is remember
 * what *this* user wrote down about *their* machine -- which is exactly what this table is.
 */
@Entity(
    tableName = "detectors",
    indices = [
        Index(value = ["createdAt"], name = "index_detectors_createdAt"),
        Index(value = ["isDefault"], name = "index_detectors_isDefault"),
    ],
)
data class DetectorEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** Free-text name the user recognises, e.g. "moje stará Garrett". */
    val name: String,
    val brand: String = "",
    val model: String = "",
    /** Coil currently mounted, free text: "11\" DD", "malá sniper". */
    val coil: String = "",
    val notes: String = "",
    val createdAt: Long,
    /** At most one row is the default; it is what a new preset is attached to without asking. */
    val isDefault: Boolean = false,
) {
    /** Stable identity used by export / import to deduplicate across devices and re-imports. */
    val externalId: String get() = ExternalIds.detector(createdAt)
}
