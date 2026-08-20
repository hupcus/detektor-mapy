package cz.hh.detektormapy.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import cz.hh.detektormapy.data.model.SoilCondition
import cz.hh.detektormapy.data.model.Terrain

/**
 * A setting the user found works, tied to the conditions it was found in.
 *
 * [sensitivity], [groundBalance] and [discrimination] are **strings, not numbers**, on purpose.
 * One machine's sensitivity runs 1..10, another's 1..99, a third calls it "gain"; ground balance
 * is often "auto" or "tracking" rather than a value at all. A free-text "18/25" or "auto po
 * dešti" is what a person actually writes on a scrap of paper, and forcing it into an Int would
 * lose information the app cannot reconstruct.
 *
 * [terrain] and [soil] are what the advisor matches against -- they are the whole reason a preset
 * is more than a note.
 */
@Entity(
    tableName = "detector_presets",
    foreignKeys = [
        ForeignKey(
            entity = DetectorEntity::class,
            parentColumns = ["id"],
            childColumns = ["detectorId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["detectorId"], name = "index_detector_presets_detectorId"),
        Index(value = ["createdAt"], name = "index_detector_presets_createdAt"),
        Index(value = ["terrain"], name = "index_detector_presets_terrain"),
        Index(value = ["soil"], name = "index_detector_presets_soil"),
    ],
)
data class DetectorPresetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val detectorId: Long,
    val name: String,
    val terrain: Terrain = Terrain.DEFAULT,
    val soil: SoilCondition = SoilCondition.DEFAULT,
    val notes: String = "",
    /** Free text as written on the machine; null when the user did not record it. */
    val sensitivity: String? = null,
    val groundBalance: String? = null,
    val discrimination: String? = null,
    val createdAt: Long,
) {
    /** Stable identity used by export / import to deduplicate across devices and re-imports. */
    val externalId: String get() = ExternalIds.detectorPreset(createdAt)
}
