package cz.hh.detektormapy.data.relation

import androidx.room.Embedded
import androidx.room.Relation
import cz.hh.detektormapy.data.entity.DetectorEntity
import cz.hh.detektormapy.data.entity.DetectorPresetEntity

/**
 * A machine together with everything the user recorded about how to set it.
 *
 * The profiles screen and the advisor both need the pair at once -- a preset without the name of
 * the machine it belongs to is unusable advice -- so it is fetched as one relation rather than
 * two flows joined in the view model.
 */
data class DetectorWithPresets(
    @Embedded val detector: DetectorEntity,
    @Relation(parentColumn = "id", entityColumn = "detectorId")
    val presets: List<DetectorPresetEntity>,
)
