package cz.hh.detektormapy.data.export

import cz.hh.detektormapy.data.AppDirectories
import cz.hh.detektormapy.data.dao.FindDao
import cz.hh.detektormapy.data.dao.FindPhotoDao
import cz.hh.detektormapy.data.dao.GcpDao
import cz.hh.detektormapy.data.dao.LayerCalibrationDao
import cz.hh.detektormapy.data.dao.PlaceDao
import cz.hh.detektormapy.data.dao.SearchedAreaDao
import cz.hh.detektormapy.data.dao.TrackDao
import cz.hh.detektormapy.data.dao.TrackPointDao
import cz.hh.detektormapy.data.entity.ExternalIds
import cz.hh.detektormapy.data.entity.FindEntity
import cz.hh.detektormapy.data.entity.FindPhotoEntity
import cz.hh.detektormapy.data.entity.GcpPointEntity
import cz.hh.detektormapy.data.entity.GcpSetEntity
import cz.hh.detektormapy.data.entity.LayerCalibrationEntity
import cz.hh.detektormapy.data.entity.PlaceEntity
import cz.hh.detektormapy.data.entity.SearchedAreaEntity
import cz.hh.detektormapy.data.entity.TrackEntity
import cz.hh.detektormapy.data.entity.TrackPointEntity
import cz.hh.detektormapy.data.model.AreaStatus
import cz.hh.detektormapy.data.model.FindCategory
import cz.hh.detektormapy.data.model.PlaceType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Reads an archive written by [ProjectExporter] back into the database (PLAN.md F2-5).
 *
 * The import is **idempotent**. Every row carries a stable `externalId` derived from its
 * content (see [ExternalIds]) rather than from its
 * primary key plus its creation timestamp, and rows are inserted with their original ids whenever
 * those ids are still free. Re-importing the same archive therefore recognises everything as
 * already present and inserts nothing.
 *
 * Nothing here throws on bad input: unreadable entries, malformed GeoJSON, broken GPX and missing
 * photo binaries all end up as strings in [ImportResult.warnings] while the rest still lands.
 */
class ProjectImporter(
    private val findDao: FindDao,
    private val photoDao: FindPhotoDao,
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

    /** Inserted / skipped counters of one section. */
    private data class Section(
        val inserted: Int = 0,
        val skipped: Int = 0,
        val childrenInserted: Int = 0,
        val childrenSkipped: Int = 0,
    )

    /**
     * Imports [archive]. Returns what was inserted, what was recognised as already present, and
     * any non-fatal problems encountered on the way.
     */
    suspend fun import(archive: File): ImportResult = withContext(ioDispatcher) {
        if (!archive.isFile || !archive.canRead()) {
            return@withContext failed("Archiv nelze číst: ${archive.absolutePath}")
        }
        val zip = try {
            ZipFile(archive)
        } catch (e: IOException) {
            return@withContext failed("Poškozený archiv: ${e.message}")
        }

        val warnings = mutableListOf<String>()
        zip.use { z ->
            val finds = importFinds(z, warnings)
            val places = importPlaces(z, warnings)
            val areas = importAreas(z, warnings)
            val tracks = importTracks(z, warnings)
            val calibrations = importCalibrations(z, warnings)
            val gcp = importGcp(z, warnings)

            ImportResult(
                imported = ExportCounts(
                    finds = finds.inserted,
                    photos = finds.childrenInserted,
                    places = places.inserted,
                    areas = areas.inserted,
                    tracks = tracks.inserted,
                    trackPoints = tracks.childrenInserted,
                    calibrations = calibrations.inserted,
                    gcpSets = gcp.inserted,
                    gcpPoints = gcp.childrenInserted,
                ),
                skipped = ExportCounts(
                    finds = finds.skipped,
                    photos = finds.childrenSkipped,
                    places = places.skipped,
                    areas = areas.skipped,
                    tracks = tracks.skipped,
                    trackPoints = tracks.childrenSkipped,
                    calibrations = calibrations.skipped,
                    gcpSets = gcp.skipped,
                    gcpPoints = gcp.childrenSkipped,
                ),
                warnings = warnings,
            )
        }
    }

    private fun failed(message: String) = ImportResult(ExportCounts(), ExportCounts(), listOf(message))

    private suspend fun importFinds(zip: ZipFile, warnings: MutableList<String>): Section {
        val features = GeoJson.features(readJson(zip, ExportLayout.FINDS, warnings))
        if (features.isEmpty()) return Section()

        val existing = findDao.getAll()
        val existingByExternalId = existing.associateBy { it.externalId }
        val usedFindIds = existing.mapTo(mutableSetOf()) { it.id }
        val existingPhotos = photoDao.getAll()
        val existingPhotoExternalIds = existingPhotos.mapTo(mutableSetOf()) { it.externalId }
        val usedPhotoIds = existingPhotos.mapTo(mutableSetOf()) { it.id }

        var inserted = 0
        var skipped = 0
        var photosInserted = 0
        var photosSkipped = 0

        for (feature in features) {
            val props = GeoJson.properties(feature)
            val coords = GeoJson.pointCoordinates(feature)
            if (coords == null) {
                warnings += "Nález bez souřadnic přeskočen."
                continue
            }
            val originalId = props.longOr("id", 0L)
            val createdAt = props.longOr("createdAt", 0L)
            val externalId = ExternalIds.find(createdAt, coords.second, coords.first)

            val alreadyThere = existingByExternalId[externalId]
            val findId: Long
            if (alreadyThere != null) {
                skipped++
                findId = alreadyThere.id
            } else {
                val entity = FindEntity(
                    id = freeId(originalId, usedFindIds),
                    lat = coords.second,
                    lon = coords.first,
                    altitude = props.doubleOrNull("altitude") ?: coords.third,
                    accuracyM = props.floatOrNull("accuracyM"),
                    createdAt = createdAt,
                    title = props.stringOr("title", ""),
                    category = FindCategory.fromName(props.stringOrNull("category")),
                    depthCm = props.intOrNull("depthCm"),
                    note = props.stringOr("note", ""),
                    favorite = props.boolOr("favorite", false),
                    layerContextId = props.stringOrNull("layerContextId"),
                    trackId = props.longOrNull("trackId"),
                )
                val newId = runCatching { findDao.insert(entity) }.getOrNull()
                if (newId == null) {
                    warnings += "Nález $externalId se nepodařilo uložit."
                    continue
                }
                usedFindIds += newId
                inserted++
                findId = newId
            }

            for (element in props["photos"] as? JsonArray ?: JsonArray(emptyList())) {
                val photo = element as? JsonObject ?: continue
                val photoId = photo.longOr("id", 0L)
                val photoCreatedAt = photo.longOr("createdAt", createdAt)
                val photoUri = photo.stringOr("file", photo.stringOr("uri", ""))
                val photoExternalId = ExternalIds.photo(photoCreatedAt, photoUri)
                if (photoExternalId in existingPhotoExternalIds) {
                    photosSkipped++
                    continue
                }
                val restoredUri = restorePhoto(zip, photo, warnings) ?: photo.stringOr("uri", "")
                val entity = FindPhotoEntity(
                    id = freeId(photoId, usedPhotoIds),
                    findId = findId,
                    uri = restoredUri,
                    createdAt = photoCreatedAt,
                    isPrimary = photo.boolOr("isPrimary", false),
                )
                val newPhotoId = runCatching { photoDao.insert(entity) }.getOrNull()
                if (newPhotoId == null) {
                    warnings += "Fotku $photoExternalId se nepodařilo uložit."
                    continue
                }
                usedPhotoIds += newPhotoId
                existingPhotoExternalIds += photoExternalId
                photosInserted++
            }
        }
        return Section(inserted, skipped, photosInserted, photosSkipped)
    }

    private suspend fun importPlaces(zip: ZipFile, warnings: MutableList<String>): Section {
        val features = GeoJson.features(readJson(zip, ExportLayout.PLACES, warnings))
        if (features.isEmpty()) return Section()

        val existing = placeDao.getAll()
        val existingExternalIds = existing.mapTo(mutableSetOf()) { it.externalId }
        val usedIds = existing.mapTo(mutableSetOf()) { it.id }
        var inserted = 0
        var skipped = 0

        for (feature in features) {
            val props = GeoJson.properties(feature)
            val coords = GeoJson.pointCoordinates(feature)
            if (coords == null) {
                warnings += "Místo bez souřadnic přeskočeno."
                continue
            }
            val originalId = props.longOr("id", 0L)
            val createdAt = props.longOr("createdAt", 0L)
            val externalId = ExternalIds.place(createdAt, coords.second, coords.first)
            if (externalId in existingExternalIds) {
                skipped++
                continue
            }
            val entity = PlaceEntity(
                id = freeId(originalId, usedIds),
                lat = coords.second,
                lon = coords.first,
                type = PlaceType.fromName(props.stringOrNull("type")),
                title = props.stringOr("title", ""),
                note = props.stringOr("note", ""),
                createdAt = createdAt,
                visited = props.boolOr("visited", false),
                visitedAt = props.longOrNull("visitedAt"),
            )
            val id = runCatching { placeDao.insert(entity) }.getOrNull()
            if (id == null) {
                warnings += "Místo $externalId se nepodařilo uložit."
                continue
            }
            usedIds += id
            existingExternalIds += externalId
            inserted++
        }
        return Section(inserted, skipped)
    }

    private suspend fun importAreas(zip: ZipFile, warnings: MutableList<String>): Section {
        val features = GeoJson.features(readJson(zip, ExportLayout.AREAS, warnings))
        if (features.isEmpty()) return Section()

        val existing = areaDao.getAll()
        val existingExternalIds = existing.mapTo(mutableSetOf()) { it.externalId }
        val usedIds = existing.mapTo(mutableSetOf()) { it.id }
        var inserted = 0
        var skipped = 0

        for (feature in features) {
            val props = GeoJson.properties(feature)
            val originalId = props.longOr("id", 0L)
            val createdAt = props.longOr("createdAt", 0L)
            val externalId = ExternalIds.area(createdAt, props.stringOr("name", ""))
            if (externalId in existingExternalIds) {
                skipped++
                continue
            }
            val geometry = GeoJson.geometry(feature)
            val polygon = if (geometry != null) {
                json.encodeToString(JsonObject.serializer(), geometry)
            } else {
                props.stringOr("polygonRaw", "")
            }
            if (polygon.isBlank()) {
                warnings += "Zóna $externalId nemá geometrii, přeskočena."
                continue
            }
            val entity = SearchedAreaEntity(
                id = freeId(originalId, usedIds),
                name = props.stringOr("name", ""),
                polygonGeoJson = polygon,
                createdAt = createdAt,
                status = AreaStatus.fromName(props.stringOrNull("status")),
                areaHa = props.doubleOr("areaHa", 0.0),
            )
            val id = runCatching { areaDao.insert(entity) }.getOrNull()
            if (id == null) {
                warnings += "Zónu $externalId se nepodařilo uložit."
                continue
            }
            usedIds += id
            existingExternalIds += externalId
            inserted++
        }
        return Section(inserted, skipped)
    }

    private suspend fun importTracks(zip: ZipFile, warnings: MutableList<String>): Section {
        val entries = zip.entries().toList().filter {
            !it.isDirectory &&
                it.name.startsWith("${ExportLayout.TRACKS_DIR}/") &&
                it.name.endsWith(".gpx")
        }
        if (entries.isEmpty()) return Section()

        val existing = trackDao.getAll()
        val existingExternalIds = existing.mapTo(mutableSetOf()) { it.externalId }
        val usedIds = existing.mapTo(mutableSetOf()) { it.id }
        var inserted = 0
        var skipped = 0
        var pointsInserted = 0
        var pointsSkipped = 0

        for (entry in entries.sortedBy { it.name }) {
            val xml = readText(zip, entry, warnings) ?: continue
            val parsed = Gpx.read(xml)
            if (parsed == null) {
                warnings += "GPX ${entry.name} nelze přečíst, přeskočeno."
                continue
            }
            val externalId = ExternalIds.track(parsed.startedAt)
            if (externalId in existingExternalIds) {
                skipped++
                pointsSkipped += parsed.points.size
                continue
            }
            val track = TrackEntity(
                id = freeId(parsed.id, usedIds),
                startedAt = parsed.startedAt,
                endedAt = parsed.endedAt,
                gpxPath = null,
                distanceM = parsed.distanceM,
                durationMs = parsed.durationMs,
                pointCount = parsed.points.size,
                name = parsed.name,
            )
            val trackId = runCatching { trackDao.insert(track) }.getOrNull()
            if (trackId == null) {
                warnings += "Track $externalId se nepodařilo uložit."
                continue
            }
            usedIds += trackId
            existingExternalIds += externalId
            inserted++

            val points = parsed.points.map {
                TrackPointEntity(
                    trackId = trackId,
                    lat = it.lat,
                    lon = it.lon,
                    altitude = it.altitude,
                    timestamp = it.timestamp,
                    accuracyM = it.accuracyM,
                    speedMs = it.speedMs,
                )
            }
            if (points.isNotEmpty()) {
                val ok = runCatching { trackPointDao.insertAll(points) }.isSuccess
                if (ok) {
                    pointsInserted += points.size
                } else {
                    warnings += "Body tracku $externalId se nepodařilo uložit."
                }
            }

            // Re-materialise the GPX next to the other tracks so the file survives the import too.
            val target = File(directories.tracksDir, "track-$trackId.gpx")
            val written = runCatching { target.writeText(xml) }.isSuccess
            if (written) {
                runCatching { trackDao.update(track.copy(id = trackId, gpxPath = target.absolutePath)) }
            } else {
                warnings += "GPX soubor tracku $externalId nelze zapsat."
            }
        }
        return Section(inserted, skipped, pointsInserted, pointsSkipped)
    }

    private suspend fun importCalibrations(zip: ZipFile, warnings: MutableList<String>): Section {
        val text = readText(zip, ExportLayout.CALIBRATIONS, warnings) ?: return Section()
        val dtos = runCatching { json.decodeFromString<List<CalibrationDto>>(text) }.getOrNull()
        if (dtos == null) {
            warnings += "calibrations.json nelze přečíst."
            return Section()
        }
        val existing = calibrationDao.getAll()
        val existingExternalIds = existing.mapTo(mutableSetOf()) { it.externalId }
        val usedIds = existing.mapTo(mutableSetOf()) { it.id }
        var inserted = 0
        var skipped = 0

        for (dto in dtos) {
            // Recompute rather than trusting the archive: an id-derived value written by an
            // older export would never match what the entity reports today.
            val externalId = ExternalIds.calibration(dto.createdAt, dto.layerId)
            if (externalId in existingExternalIds) {
                skipped++
                continue
            }
            val m = dto.matrix
            if (m.size != 6) {
                warnings += "Kalibrace $externalId má vadnou matici, přeskočena."
                continue
            }
            val entity = LayerCalibrationEntity(
                id = freeId(dto.id, usedIds),
                layerId = dto.layerId,
                label = dto.label,
                west = dto.west,
                south = dto.south,
                east = dto.east,
                north = dto.north,
                m0 = m[0],
                m1 = m[1],
                m2 = m[2],
                m3 = m[3],
                m4 = m[4],
                m5 = m[5],
                createdAt = dto.createdAt,
                updatedAt = dto.updatedAt,
                active = dto.active,
            )
            val id = runCatching { calibrationDao.insert(entity) }.getOrNull()
            if (id == null) {
                warnings += "Kalibraci $externalId se nepodařilo uložit."
                continue
            }
            usedIds += id
            existingExternalIds += externalId
            inserted++
        }
        return Section(inserted, skipped)
    }

    private suspend fun importGcp(zip: ZipFile, warnings: MutableList<String>): Section {
        val text = readText(zip, ExportLayout.GCP, warnings) ?: return Section()
        val dtos = runCatching { json.decodeFromString<List<GcpSetDto>>(text) }.getOrNull()
        if (dtos == null) {
            warnings += "gcp.json nelze přečíst."
            return Section()
        }
        val existing = gcpDao.getAllSets()
        val existingExternalIds = existing.mapTo(mutableSetOf()) { it.externalId }
        val usedIds = existing.mapTo(mutableSetOf()) { it.id }
        var sets = 0
        var skipped = 0
        var points = 0
        var pointsSkipped = 0

        for (dto in dtos) {
            val externalId = ExternalIds.gcpSet(dto.createdAt, dto.layerId)
            if (externalId in existingExternalIds) {
                skipped++
                pointsSkipped += dto.points.size
                continue
            }
            val setId = runCatching {
                gcpDao.insertSet(
                    GcpSetEntity(
                        id = freeId(dto.id, usedIds),
                        layerId = dto.layerId,
                        name = dto.name,
                        imagePath = dto.imagePath,
                        createdAt = dto.createdAt,
                    ),
                )
            }.getOrNull()
            if (setId == null) {
                warnings += "GCP sadu $externalId se nepodařilo uložit."
                continue
            }
            usedIds += setId
            existingExternalIds += externalId
            sets++

            val entities = dto.points.map {
                GcpPointEntity(
                    setId = setId,
                    srcX = it.srcX,
                    srcY = it.srcY,
                    dstX = it.dstX,
                    dstY = it.dstY,
                    label = it.label,
                )
            }
            if (entities.isNotEmpty()) {
                val ok = runCatching { gcpDao.insertPoints(entities) }.isSuccess
                if (ok) {
                    points += entities.size
                } else {
                    warnings += "Body GCP sady ${dto.externalId} nelze uložit."
                }
            }
        }
        return Section(sets, skipped, points, pointsSkipped)
    }

    /**
     * Restores a photo binary into [AppDirectories.findsPhotoDir] and returns its new absolute
     * path, or null when the archive does not carry the file.
     */
    private fun restorePhoto(zip: ZipFile, photo: JsonObject, warnings: MutableList<String>): String? {
        val entryName = photo.stringOrNull("file") ?: return null
        val entry = zip.getEntry(entryName)
        if (entry == null) {
            warnings += "Fotka $entryName v archivu chybí."
            return null
        }
        val safeName = entryName.substringAfterLast('/').replace(UNSAFE_NAME, "_")
        if (safeName.isBlank()) return null
        val target = File(directories.findsPhotoDir, safeName)
        val ok = runCatching {
            zip.getInputStream(entry).use { input ->
                target.outputStream().use { output -> input.copyBounded(output, MAX_PHOTO_BYTES) }
            }
        }.getOrElse { failure ->
            // Delete the partial file: a half-written photo is worse than none, and a bomb
            // would otherwise leave hundreds of megabytes behind.
            runCatching { target.delete() }
            warnings += "Fotku $entryName nelze rozbalit (${failure.message ?: "chyba"})."
            return null
        }
        if (!ok) {
            warnings += "Fotku $entryName nelze rozbalit."
            return null
        }
        return target.absolutePath
    }

    /** Returns [preferred] when it is a usable, still-free primary key, otherwise 0 (auto-assign). */
    private fun freeId(preferred: Long, used: MutableSet<Long>): Long =
        if (preferred > 0L && used.add(preferred)) preferred else 0L

    private fun readJson(zip: ZipFile, name: String, warnings: MutableList<String>): JsonElement? {
        val text = readText(zip, name, warnings) ?: return null
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
        if (parsed == null) warnings += "$name není platný JSON."
        return parsed
    }

    private fun readText(zip: ZipFile, name: String, warnings: MutableList<String>): String? {
        val entry = zip.getEntry(name) ?: return null
        return readText(zip, entry, warnings)
    }

    private fun readText(zip: ZipFile, entry: ZipEntry, warnings: MutableList<String>): String? {
        // The archive is whatever file the user picked, so its declared sizes are untrusted.
        // The declared size is checked first (cheap), and the actual read is bounded too,
        // because a zip's central directory can lie about how big an entry decompresses to.
        if (entry.size > MAX_TEXT_ENTRY_BYTES) {
            warnings += "Položka ${entry.name} je příliš velká (${entry.size} B)."
            return null
        }
        val text = runCatching {
            zip.getInputStream(entry).use { input ->
                val out = java.io.ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
                input.copyBounded(out, MAX_TEXT_ENTRY_BYTES)
                out.toByteArray().toString(Charsets.UTF_8)
            }
        }.getOrNull()
        if (text == null) warnings += "Položku ${entry.name} nelze přečíst."
        return text
    }

    /**
     * Copies at most [limit] bytes and throws once that is exceeded, so a decompression bomb
     * fails fast instead of exhausting memory or the storage volume.
     */
    private fun java.io.InputStream.copyBounded(out: java.io.OutputStream, limit: Long): Boolean {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw IOException("Položka archivu přesáhla $limit B")
            out.write(buffer, 0, read)
        }
        return true
    }

    private companion object {
        val UNSAFE_NAME = Regex("[^A-Za-z0-9._-]")

        /** A GeoJSON/GPX section this big is already absurd for a personal find log. */
        const val MAX_TEXT_ENTRY_BYTES = 64L * 1024 * 1024

        /** One find photo; the capture flow writes a few MB at most. */
        const val MAX_PHOTO_BYTES = 32L * 1024 * 1024
    }
}
