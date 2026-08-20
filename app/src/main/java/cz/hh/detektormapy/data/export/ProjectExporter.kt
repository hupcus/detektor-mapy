package cz.hh.detektormapy.data.export

import cz.hh.detektormapy.data.AppDirectories
import cz.hh.detektormapy.data.DetektorDatabase
import cz.hh.detektormapy.data.dao.FindDao
import cz.hh.detektormapy.data.dao.GcpDao
import cz.hh.detektormapy.data.dao.LayerCalibrationDao
import cz.hh.detektormapy.data.dao.PlaceDao
import cz.hh.detektormapy.data.dao.SearchedAreaDao
import cz.hh.detektormapy.data.dao.TrackDao
import cz.hh.detektormapy.data.dao.TrackPointDao
import cz.hh.detektormapy.data.entity.FindPhotoEntity
import cz.hh.detektormapy.data.entity.PlaceEntity
import cz.hh.detektormapy.data.entity.SearchedAreaEntity
import cz.hh.detektormapy.data.relation.FindWithPhotos
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the whole project to a single zip in [AppDirectories.exportsDir] (PLAN.md F2-5).
 *
 * Layout: `finds.geojson`, `places.geojson`, `areas.geojson`, `calibrations.json`, `gcp.json`,
 * `tracks/<id>.gpx`, `photos/<file>` and `manifest.json`.
 *
 * A photo whose file has been deleted from under us is *not* an error: the row is still exported
 * with its original uri, only the binary is missing, and the count lands in
 * [ExportResult.missingPhotoFiles].
 */
class ProjectExporter(
    private val findDao: FindDao,
    private val placeDao: PlaceDao,
    private val areaDao: SearchedAreaDao,
    private val trackDao: TrackDao,
    private val trackPointDao: TrackPointDao,
    private val calibrationDao: LayerCalibrationDao,
    private val gcpDao: GcpDao,
    private val directories: AppDirectories,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Exports everything into a zip.
     *
     * @param nowMillis timestamp stamped into the manifest and, when [fileName] is null, into the
     *   generated file name. Injected rather than read from the clock so exports are reproducible.
     * @param fileName explicit archive name; defaults to `detektormapy-export-<nowMillis>.zip`.
     * @param targetDir explicit output directory; defaults to [AppDirectories.exportsDir].
     */
    suspend fun export(nowMillis: Long, fileName: String? = null, targetDir: File? = null): ExportResult =
        withContext(ioDispatcher) {
            val findsWithPhotos = findDao.getAllWithPhotos()
            val places = placeDao.getAll()
            val areas = areaDao.getAll()
            val tracks = trackDao.getAll()
            val calibrations = calibrationDao.getAll()
            val gcpSets = gcpDao.getAllSets()

            val dir = (targetDir ?: directories.exportsDir).also { it.mkdirs() }
            val archive = File(dir, fileName ?: "detektormapy-export-$nowMillis.zip")

            var missingPhotos = 0
            var photoCount = 0
            var trackPointCount = 0
            var gcpPointCount = 0

            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                // Photo binaries first so the geojson can reference the entry names it actually wrote.
                val photoEntries = mutableMapOf<Long, String>()
                for (fwp in findsWithPhotos) {
                    for (photo in fwp.photos) {
                        photoCount++
                        val entryName = photoEntryName(photo)
                        if (copyPhoto(zip, photo, entryName)) {
                            photoEntries[photo.id] = entryName
                        } else {
                            missingPhotos++
                        }
                    }
                }

                zip.writeText(ExportLayout.FINDS, json.encodeToString(findsCollection(findsWithPhotos, photoEntries)))
                zip.writeText(ExportLayout.PLACES, json.encodeToString(placesCollection(places)))
                zip.writeText(ExportLayout.AREAS, json.encodeToString(areasCollection(areas)))

                val calibrationDtos = calibrations.map { c ->
                    CalibrationDto(
                        id = c.id,
                        externalId = c.externalId,
                        layerId = c.layerId,
                        label = c.label,
                        west = c.west,
                        south = c.south,
                        east = c.east,
                        north = c.north,
                        matrix = listOf(c.m0, c.m1, c.m2, c.m3, c.m4, c.m5),
                        createdAt = c.createdAt,
                        updatedAt = c.updatedAt,
                        active = c.active,
                    )
                }
                zip.writeText(ExportLayout.CALIBRATIONS, json.encodeToString(calibrationDtos))

                val gcpDtos = gcpSets.map { set ->
                    val points = gcpDao.getPointsForSet(set.id)
                    gcpPointCount += points.size
                    GcpSetDto(
                        id = set.id,
                        externalId = set.externalId,
                        layerId = set.layerId,
                        name = set.name,
                        imagePath = set.imagePath,
                        createdAt = set.createdAt,
                        points = points.map {
                            GcpPointDto(it.id, it.srcX, it.srcY, it.dstX, it.dstY, it.label)
                        },
                    )
                }
                zip.writeText(ExportLayout.GCP, json.encodeToString(gcpDtos))

                for (track in tracks) {
                    val points = trackPointDao.getForTrack(track.id)
                    trackPointCount += points.size
                    zip.writeText("${ExportLayout.TRACKS_DIR}/track-${track.id}.gpx", Gpx.write(track, points))
                }

                val counts = ExportCounts(
                    finds = findsWithPhotos.size,
                    photos = photoCount,
                    places = places.size,
                    areas = areas.size,
                    tracks = tracks.size,
                    trackPoints = trackPointCount,
                    calibrations = calibrations.size,
                    gcpSets = gcpSets.size,
                    gcpPoints = gcpPointCount,
                )
                val manifest = ExportManifest(
                    schemaVersion = DetektorDatabase.VERSION,
                    exportedAt = nowMillis,
                    counts = counts,
                    missingPhotoFiles = missingPhotos,
                )
                zip.writeText(ExportLayout.MANIFEST, json.encodeToString(manifest))

                return@withContext ExportResult(archive, counts, missingPhotos)
            }
        }

    private fun findsCollection(finds: List<FindWithPhotos>, photoEntries: Map<Long, String>): JsonObject =
        GeoJson.featureCollection(
            finds.map { fwp ->
                val f = fwp.find
                val properties = buildJsonObject {
                    put("externalId", f.externalId)
                    put("id", f.id)
                    put("title", f.title)
                    put("category", f.category.name)
                    put("depthCm", f.depthCm)
                    put("note", f.note)
                    put("favorite", f.favorite)
                    put("createdAt", f.createdAt)
                    put("accuracyM", f.accuracyM)
                    put("altitude", f.altitude)
                    put("layerContextId", f.layerContextId)
                    put("trackId", f.trackId)
                    put(
                        "photos",
                        buildJsonArray {
                            for (photo in fwp.photos) {
                                add(
                                    buildJsonObject {
                                        put("externalId", photo.externalId)
                                        put("id", photo.id)
                                        put("uri", photo.uri)
                                        put("createdAt", photo.createdAt)
                                        put("isPrimary", photo.isPrimary)
                                        put("file", photoEntries[photo.id])
                                    },
                                )
                            }
                        },
                    )
                }
                GeoJson.pointFeature(f.lon, f.lat, f.altitude, properties)
            },
        )

    private fun placesCollection(places: List<PlaceEntity>): JsonObject = GeoJson.featureCollection(
        places.map { p ->
            val properties = buildJsonObject {
                put("externalId", p.externalId)
                put("id", p.id)
                put("type", p.type.name)
                put("title", p.title)
                put("note", p.note)
                put("createdAt", p.createdAt)
                put("visited", p.visited)
                put("visitedAt", p.visitedAt)
            }
            GeoJson.pointFeature(p.lon, p.lat, null, properties)
        },
    )

    private fun areasCollection(areas: List<SearchedAreaEntity>): JsonObject = GeoJson.featureCollection(
        areas.map { a ->
            val geometry = runCatching { json.parseToJsonElement(a.polygonGeoJson) }
                .getOrNull() as? JsonObject
            val properties = buildJsonObject {
                put("externalId", a.externalId)
                put("id", a.id)
                put("name", a.name)
                put("status", a.status.name)
                put("areaHa", a.areaHa)
                put("createdAt", a.createdAt)
                // Kept verbatim so a polygon we could not parse still survives a round trip.
                if (geometry == null) put("polygonRaw", a.polygonGeoJson)
            }
            GeoJson.feature(geometry ?: JsonNull, properties)
        },
    )

    private fun photoEntryName(photo: FindPhotoEntity): String {
        val base = photo.uri.substringAfterLast('/').ifBlank { "photo" }
        val safe = base.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        return "${ExportLayout.PHOTOS_DIR}/${photo.findId}-${photo.id}-$safe"
    }

    /** Returns false when the source file is gone or unreadable; never throws. */
    private fun copyPhoto(zip: ZipOutputStream, photo: FindPhotoEntity, entryName: String): Boolean {
        val source = resolvePhotoFile(photo.uri) ?: return false
        return try {
            zip.putNextEntry(ZipEntry(entryName))
            FileInputStream(source).use { it.copyTo(zip) }
            zip.closeEntry()
            true
        } catch (_: IOException) {
            runCatching { zip.closeEntry() }
            false
        }
    }

    /** Only `file://` uris and bare paths can be read without a ContentResolver. */
    private fun resolvePhotoFile(uri: String): File? {
        val path = when {
            uri.startsWith("file://") -> uri.removePrefix("file://")
            uri.startsWith("content://") -> return null
            else -> uri
        }
        val file = File(path)
        return if (file.isFile && file.canRead()) file else null
    }

    private fun ZipOutputStream.writeText(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
