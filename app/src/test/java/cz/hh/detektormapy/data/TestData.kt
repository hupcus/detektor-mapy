package cz.hh.detektormapy.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.hh.detektormapy.data.entity.FindEntity
import cz.hh.detektormapy.data.entity.FindPhotoEntity
import cz.hh.detektormapy.data.entity.PlaceEntity
import cz.hh.detektormapy.data.entity.TrackPointEntity
import cz.hh.detektormapy.data.model.FindCategory
import cz.hh.detektormapy.data.model.PlaceType
import kotlinx.serialization.json.Json

/**
 * Shared fixtures for the data-layer tests.
 *
 * Every timestamp is a constant: nothing in these tests may depend on the wall clock, otherwise a
 * failure two months from now is impossible to reproduce.
 */
internal object TestData {

    /** 2024-06-01T10:00:00Z, the anchor every other timestamp is derived from. */
    const val T0 = 1_717_236_000_000L
    const val MINUTE = 60_000L
    const val HOUR = 3_600_000L
    const val DAY = 86_400_000L

    fun inMemoryDatabase(): DetektorDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, DetektorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    fun json(): Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        isLenient = true
    }

    fun find(
        lat: Double = 50.0,
        lon: Double = 14.0,
        createdAt: Long = T0,
        title: String = "Nález",
        category: FindCategory = FindCategory.MINCE,
        depthCm: Int? = 12,
        favorite: Boolean = false,
        note: String = "",
        altitude: Double? = 310.5,
        accuracyM: Float? = 4.5f,
        layerContextId: String? = "ii-vm",
        trackId: Long? = null,
    ) = FindEntity(
        lat = lat,
        lon = lon,
        altitude = altitude,
        accuracyM = accuracyM,
        createdAt = createdAt,
        title = title,
        category = category,
        depthCm = depthCm,
        note = note,
        favorite = favorite,
        layerContextId = layerContextId,
        trackId = trackId,
    )

    fun photo(findId: Long, uri: String, createdAt: Long = T0, isPrimary: Boolean = true) =
        FindPhotoEntity(findId = findId, uri = uri, createdAt = createdAt, isPrimary = isPrimary)

    fun place(
        lat: Double = 49.5,
        lon: Double = 15.5,
        type: PlaceType = PlaceType.PLAN,
        title: String = "Louka u lesa",
        note: String = "Zkusit po orbě",
        createdAt: Long = T0,
        visited: Boolean = false,
        visitedAt: Long? = null,
    ) = PlaceEntity(
        lat = lat,
        lon = lon,
        type = type,
        title = title,
        note = note,
        createdAt = createdAt,
        visited = visited,
        visitedAt = visitedAt,
    )

    fun trackPoint(
        trackId: Long,
        lat: Double,
        lon: Double,
        timestamp: Long,
        altitude: Double? = 300.0,
        accuracyM: Float? = 5.0f,
        speedMs: Float? = 1.2f,
    ) = TrackPointEntity(
        trackId = trackId,
        lat = lat,
        lon = lon,
        altitude = altitude,
        timestamp = timestamp,
        accuracyM = accuracyM,
        speedMs = speedMs,
    )
}
