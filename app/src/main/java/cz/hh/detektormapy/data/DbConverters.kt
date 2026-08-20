package cz.hh.detektormapy.data

import androidx.room.TypeConverter
import cz.hh.detektormapy.data.model.AreaStatus
import cz.hh.detektormapy.data.model.FindCategory
import cz.hh.detektormapy.data.model.PlaceType
import cz.hh.detektormapy.data.model.SoilCondition
import cz.hh.detektormapy.data.model.Terrain

/**
 * Room type converters.
 *
 * Every enum is stored by its `name`, never by ordinal, so declarations can be reordered or
 * extended without a migration. Reading is deliberately lenient: an unknown name coming out of a
 * database written by a newer build degrades to the enum's default instead of crashing in the
 * field, which is the one place where a crash costs real data.
 */
class DbConverters {

    @TypeConverter
    fun fromFindCategory(value: FindCategory): String = value.name

    @TypeConverter
    fun toFindCategory(value: String): FindCategory = FindCategory.fromName(value)

    @TypeConverter
    fun fromPlaceType(value: PlaceType): String = value.name

    @TypeConverter
    fun toPlaceType(value: String): PlaceType = PlaceType.fromName(value)

    @TypeConverter
    fun fromAreaStatus(value: AreaStatus): String = value.name

    @TypeConverter
    fun toAreaStatus(value: String): AreaStatus = AreaStatus.fromName(value)

    @TypeConverter
    fun fromTerrain(value: Terrain): String = value.name

    @TypeConverter
    fun toTerrain(value: String): Terrain = Terrain.fromName(value)

    @TypeConverter
    fun fromSoilCondition(value: SoilCondition): String = value.name

    @TypeConverter
    fun toSoilCondition(value: String): SoilCondition = SoilCondition.fromName(value)
}
