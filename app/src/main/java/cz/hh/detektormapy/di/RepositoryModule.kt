package cz.hh.detektormapy.di

import cz.hh.detektormapy.data.AppDirectories
import cz.hh.detektormapy.data.dao.DetectorDao
import cz.hh.detektormapy.data.dao.FindDao
import cz.hh.detektormapy.data.dao.FindPhotoDao
import cz.hh.detektormapy.data.dao.GcpDao
import cz.hh.detektormapy.data.dao.LayerCalibrationDao
import cz.hh.detektormapy.data.dao.PlaceDao
import cz.hh.detektormapy.data.dao.SearchedAreaDao
import cz.hh.detektormapy.data.dao.TrackDao
import cz.hh.detektormapy.data.dao.TrackPointDao
import cz.hh.detektormapy.data.export.ProjectExporter
import cz.hh.detektormapy.data.export.ProjectImporter
import cz.hh.detektormapy.data.repository.AreasRepository
import cz.hh.detektormapy.data.repository.CalibrationRepository
import cz.hh.detektormapy.data.repository.DetectorRepository
import cz.hh.detektormapy.data.repository.FindsRepository
import cz.hh.detektormapy.data.repository.GcpRepository
import cz.hh.detektormapy.data.repository.PlacesRepository
import cz.hh.detektormapy.data.repository.TracksRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/**
 * Binds the repositories and the export / import services.
 *
 * The repository classes stay free of Dagger annotations so they can be built by hand in unit
 * tests against an in-memory database; the wiring lives here instead.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideFindsRepository(findDao: FindDao, photoDao: FindPhotoDao): FindsRepository =
        FindsRepository(findDao, photoDao)

    @Provides
    @Singleton
    fun providePlacesRepository(placeDao: PlaceDao): PlacesRepository = PlacesRepository(placeDao)

    @Provides
    @Singleton
    fun provideAreasRepository(areaDao: SearchedAreaDao): AreasRepository = AreasRepository(areaDao)

    @Provides
    @Singleton
    fun provideTracksRepository(trackDao: TrackDao, pointDao: TrackPointDao): TracksRepository =
        TracksRepository(trackDao, pointDao)

    @Provides
    @Singleton
    fun provideCalibrationRepository(dao: LayerCalibrationDao): CalibrationRepository = CalibrationRepository(dao)

    @Provides
    @Singleton
    fun provideGcpRepository(dao: GcpDao): GcpRepository = GcpRepository(dao)

    @Provides
    @Singleton
    fun provideDetectorRepository(dao: DetectorDao): DetectorRepository = DetectorRepository(dao)

    @Provides
    @Singleton
    fun provideProjectExporter(
        findDao: FindDao,
        placeDao: PlaceDao,
        areaDao: SearchedAreaDao,
        trackDao: TrackDao,
        trackPointDao: TrackPointDao,
        calibrationDao: LayerCalibrationDao,
        gcpDao: GcpDao,
        directories: AppDirectories,
        json: Json,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): ProjectExporter = ProjectExporter(
        findDao = findDao,
        placeDao = placeDao,
        areaDao = areaDao,
        trackDao = trackDao,
        trackPointDao = trackPointDao,
        calibrationDao = calibrationDao,
        gcpDao = gcpDao,
        directories = directories,
        json = json,
        ioDispatcher = ioDispatcher,
    )

    @Provides
    @Singleton
    fun provideProjectImporter(
        findDao: FindDao,
        photoDao: FindPhotoDao,
        placeDao: PlaceDao,
        areaDao: SearchedAreaDao,
        trackDao: TrackDao,
        trackPointDao: TrackPointDao,
        calibrationDao: LayerCalibrationDao,
        gcpDao: GcpDao,
        directories: AppDirectories,
        json: Json,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): ProjectImporter = ProjectImporter(
        findDao = findDao,
        photoDao = photoDao,
        placeDao = placeDao,
        areaDao = areaDao,
        trackDao = trackDao,
        trackPointDao = trackPointDao,
        calibrationDao = calibrationDao,
        gcpDao = gcpDao,
        directories = directories,
        json = json,
        ioDispatcher = ioDispatcher,
    )
}
