package cz.hh.detektormapy.di

import android.content.Context
import androidx.room.Room
import cz.hh.detektormapy.data.ALL_MIGRATIONS
import cz.hh.detektormapy.data.AppDirectories
import cz.hh.detektormapy.data.DetektorDatabase
import cz.hh.detektormapy.data.dao.FindDao
import cz.hh.detektormapy.data.dao.FindPhotoDao
import cz.hh.detektormapy.data.dao.GcpDao
import cz.hh.detektormapy.data.dao.LayerCalibrationDao
import cz.hh.detektormapy.data.dao.PlaceDao
import cz.hh.detektormapy.data.dao.SearchedAreaDao
import cz.hh.detektormapy.data.dao.TrackDao
import cz.hh.detektormapy.data.dao.TrackPointDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides the Room database, its DAOs and the on-disk directory layout. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Builds the database.
     *
     * Note the deliberate absence of `fallbackToDestructiveMigration`: this file holds finds,
     * photos, tracks and hand-tuned calibrations that cannot be re-collected, so a missing
     * migration must fail loudly rather than wipe the user's season.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DetektorDatabase =
        Room.databaseBuilder(context, DetektorDatabase::class.java, DetektorDatabase.NAME)
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    @Provides
    @Singleton
    fun provideAppDirectories(@ApplicationContext context: Context): AppDirectories = AppDirectories(context)

    @Provides
    fun provideFindDao(db: DetektorDatabase): FindDao = db.findDao()

    @Provides
    fun provideFindPhotoDao(db: DetektorDatabase): FindPhotoDao = db.findPhotoDao()

    @Provides
    fun providePlaceDao(db: DetektorDatabase): PlaceDao = db.placeDao()

    @Provides
    fun provideSearchedAreaDao(db: DetektorDatabase): SearchedAreaDao = db.searchedAreaDao()

    @Provides
    fun provideTrackDao(db: DetektorDatabase): TrackDao = db.trackDao()

    @Provides
    fun provideTrackPointDao(db: DetektorDatabase): TrackPointDao = db.trackPointDao()

    @Provides
    fun provideLayerCalibrationDao(db: DetektorDatabase): LayerCalibrationDao = db.layerCalibrationDao()

    @Provides
    fun provideGcpDao(db: DetektorDatabase): GcpDao = db.gcpDao()
}
