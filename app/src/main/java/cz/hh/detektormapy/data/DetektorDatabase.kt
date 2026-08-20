package cz.hh.detektormapy.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import cz.hh.detektormapy.data.dao.DetectorDao
import cz.hh.detektormapy.data.dao.FindDao
import cz.hh.detektormapy.data.dao.FindPhotoDao
import cz.hh.detektormapy.data.dao.GcpDao
import cz.hh.detektormapy.data.dao.LayerCalibrationDao
import cz.hh.detektormapy.data.dao.PlaceDao
import cz.hh.detektormapy.data.dao.SearchedAreaDao
import cz.hh.detektormapy.data.dao.TrackDao
import cz.hh.detektormapy.data.dao.TrackPointDao
import cz.hh.detektormapy.data.entity.DetectorEntity
import cz.hh.detektormapy.data.entity.DetectorPresetEntity
import cz.hh.detektormapy.data.entity.FindEntity
import cz.hh.detektormapy.data.entity.FindPhotoEntity
import cz.hh.detektormapy.data.entity.GcpPointEntity
import cz.hh.detektormapy.data.entity.GcpSetEntity
import cz.hh.detektormapy.data.entity.LayerCalibrationEntity
import cz.hh.detektormapy.data.entity.PlaceEntity
import cz.hh.detektormapy.data.entity.SearchedAreaEntity
import cz.hh.detektormapy.data.entity.TrackEntity
import cz.hh.detektormapy.data.entity.TrackPointEntity

/**
 * The single Room database of the app.
 *
 * Everything here is irreplaceable field data, so the schema is exported to `app/schemas` and
 * every future version ships an explicit migration -- see [ALL_MIGRATIONS].
 */
@Database(
    entities = [
        FindEntity::class,
        FindPhotoEntity::class,
        PlaceEntity::class,
        SearchedAreaEntity::class,
        TrackEntity::class,
        TrackPointEntity::class,
        LayerCalibrationEntity::class,
        GcpSetEntity::class,
        GcpPointEntity::class,
        DetectorEntity::class,
        DetectorPresetEntity::class,
    ],
    version = DetektorDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(DbConverters::class)
abstract class DetektorDatabase : RoomDatabase() {

    abstract fun findDao(): FindDao

    abstract fun findPhotoDao(): FindPhotoDao

    abstract fun placeDao(): PlaceDao

    abstract fun searchedAreaDao(): SearchedAreaDao

    abstract fun trackDao(): TrackDao

    abstract fun trackPointDao(): TrackPointDao

    abstract fun layerCalibrationDao(): LayerCalibrationDao

    abstract fun gcpDao(): GcpDao

    abstract fun detectorDao(): DetectorDao

    companion object {
        const val VERSION = 2
        const val NAME = "detektormapy.db"
    }
}
