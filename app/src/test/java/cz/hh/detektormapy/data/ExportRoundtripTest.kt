package cz.hh.detektormapy.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.data.entity.GcpPointEntity
import cz.hh.detektormapy.data.entity.GcpSetEntity
import cz.hh.detektormapy.data.entity.SearchedAreaEntity
import cz.hh.detektormapy.data.entity.TrackEntity
import cz.hh.detektormapy.data.export.ProjectExporter
import cz.hh.detektormapy.data.export.ProjectImporter
import cz.hh.detektormapy.data.mapper.layerCalibrationOf
import cz.hh.detektormapy.data.model.AreaStatus
import cz.hh.detektormapy.data.model.FindCategory
import cz.hh.detektormapy.data.model.PlaceType
import cz.hh.detektormapy.util.BBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The backup story from PLAN.md F2-5: export everything, wipe the database, import it back,
 * and lose nothing. This is the only safety net for field data that exists nowhere else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportRoundtripTest {

    private lateinit var db: DetektorDatabase
    private lateinit var dirs: AppDirectories
    private lateinit var exporter: ProjectExporter
    private lateinit var importer: ProjectImporter
    private lateinit var photoFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = TestData.inMemoryDatabase()
        dirs = AppDirectories(context)
        val json = TestData.json()
        exporter = ProjectExporter(
            findDao = db.findDao(),
            placeDao = db.placeDao(),
            areaDao = db.searchedAreaDao(),
            trackDao = db.trackDao(),
            trackPointDao = db.trackPointDao(),
            calibrationDao = db.layerCalibrationDao(),
            gcpDao = db.gcpDao(),
            directories = dirs,
            json = json,
            ioDispatcher = Dispatchers.Unconfined,
        )
        importer = ProjectImporter(
            findDao = db.findDao(),
            photoDao = db.findPhotoDao(),
            placeDao = db.placeDao(),
            areaDao = db.searchedAreaDao(),
            trackDao = db.trackDao(),
            trackPointDao = db.trackPointDao(),
            calibrationDao = db.layerCalibrationDao(),
            gcpDao = db.gcpDao(),
            directories = dirs,
            json = json,
            ioDispatcher = Dispatchers.Unconfined,
        )
        photoFile = File(dirs.findsPhotoDir, "test-photo.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        }
    }

    @After
    fun tearDown() {
        db.close()
        photoFile.delete()
    }

    private suspend fun seed() {
        val findId = db.findDao().insert(
            TestData.find(title = "Pražský groš", category = FindCategory.MINCE, depthCm = 18),
        )
        db.findDao().insert(
            TestData.find(
                title = "Knoflík",
                category = FindCategory.KNOFLIK,
                lat = 50.0012,
                lon = 14.0031,
                createdAt = TestData.T0 + TestData.MINUTE * 17,
            ),
        )
        db.findPhotoDao().insert(TestData.photo(findId, photoFile.absolutePath))

        // Distinct coordinates and timestamps, like real waypoints: identity is derived from
        // content, so two rows created at the same millisecond at the same spot are one row.
        db.placeDao().insert(
            TestData.place(title = "Louka", type = PlaceType.PLAN, lat = 49.51, lon = 15.51),
        )
        db.placeDao().insert(
            TestData.place(
                title = "Zákaz",
                type = PlaceType.ZAKAZ,
                lat = 49.52,
                lon = 15.53,
                createdAt = TestData.T0 + TestData.HOUR,
            ),
        )

        db.searchedAreaDao().insert(
            SearchedAreaEntity(
                name = "Pole za mlýnem",
                polygonGeoJson = """{"type":"Polygon","coordinates":[[[15.0,50.0],""" +
                    """[15.01,50.0],[15.01,50.01],[15.0,50.01],[15.0,50.0]]]}""",
                createdAt = TestData.T0,
                status = AreaStatus.ROZPRACOVANO,
                areaHa = 5.5,
            ),
        )

        val trackId = db.trackDao().insert(
            TrackEntity(startedAt = TestData.T0, name = "Nedělní pochůzka"),
        )
        db.trackPointDao().insertAll(
            listOf(
                TestData.trackPoint(trackId, 50.0, 15.0, TestData.T0),
                TestData.trackPoint(trackId, 50.001, 15.001, TestData.T0 + TestData.MINUTE),
            ),
        )
        db.trackDao().finish(
            id = trackId,
            endedAt = TestData.T0 + TestData.HOUR,
            durationMs = TestData.HOUR,
            distanceM = 140.0,
            pointCount = 2,
            gpxPath = null,
        )

        db.layerCalibrationDao().insert(
            layerCalibrationOf(
                layerId = "vm2",
                label = "Katastr Zbraslavice",
                bbox = BBox(15.0, 49.9, 15.1, 50.0),
                transform = Affine2D.translation(23.5, -11.25),
                createdAt = TestData.T0,
            ),
        )

        val setId = db.gcpDao().insertSet(
            GcpSetEntity(layerId = "vm2", name = "GCP vm2", imagePath = null, createdAt = TestData.T0),
        )
        db.gcpDao().insertPoint(
            GcpPointEntity(setId = setId, srcX = 1.0, srcY = 2.0, dstX = 3.0, dstY = 4.0, label = "kostel"),
        )
    }

    private suspend fun wipe() {
        db.findDao().deleteAll()
        db.placeDao().deleteAll()
        db.searchedAreaDao().deleteAll()
        db.trackDao().deleteAll()
        db.layerCalibrationDao().deleteAll()
        db.gcpDao().deleteAllSets()
    }

    @Test
    fun `export wipe import loses nothing`() = runTest {
        seed()

        val exported = exporter.export(nowMillis = TestData.T0, fileName = "roundtrip.zip")
        assertThat(exported.archive.exists()).isTrue()
        assertThat(exported.counts.finds).isEqualTo(2)
        assertThat(exported.counts.photos).isEqualTo(1)
        assertThat(exported.counts.places).isEqualTo(2)
        assertThat(exported.counts.areas).isEqualTo(1)
        assertThat(exported.counts.tracks).isEqualTo(1)
        assertThat(exported.counts.trackPoints).isEqualTo(2)
        assertThat(exported.counts.calibrations).isEqualTo(1)
        assertThat(exported.missingPhotoFiles).isEqualTo(0)

        wipe()
        assertThat(db.findDao().count()).isEqualTo(0)
        assertThat(db.placeDao().count()).isEqualTo(0)

        val result = importer.import(exported.archive)
        assertThat(result.imported.finds).isEqualTo(2)
        assertThat(result.imported.places).isEqualTo(2)
        assertThat(result.imported.areas).isEqualTo(1)
        assertThat(result.imported.tracks).isEqualTo(1)
        assertThat(result.imported.calibrations).isEqualTo(1)

        assertThat(db.findDao().count()).isEqualTo(2)
        val restored = db.findDao().getAll().firstOrNull { it.title == "Pražský groš" }
        assertThat(restored).isNotNull()
        assertThat(restored?.category).isEqualTo(FindCategory.MINCE)
        assertThat(restored?.depthCm).isEqualTo(18)
        assertThat(restored?.lat).isEqualTo(50.0)

        val calibration = db.layerCalibrationDao().getAll().single()
        assertThat(calibration.label).isEqualTo("Katastr Zbraslavice")
        assertThat(calibration.m2).isWithin(1e-9).of(23.5)
        assertThat(calibration.m5).isWithin(1e-9).of(-11.25)

        exported.archive.delete()
    }

    @Test
    fun `re-importing the same archive inserts nothing twice`() = runTest {
        seed()
        val exported = exporter.export(nowMillis = TestData.T0, fileName = "idempotent.zip")

        val second = importer.import(exported.archive)
        assertThat(second.imported.finds).isEqualTo(0)
        assertThat(second.imported.places).isEqualTo(0)
        assertThat(second.skipped.finds).isEqualTo(2)
        assertThat(db.findDao().count()).isEqualTo(2)

        exported.archive.delete()
    }

    @Test
    fun `re-import stays idempotent even when the original ids are taken`() = runTest {
        // Regression: externalId used to be derived from the row's primary key. On import into a
        // database whose ids were already occupied, Room assigned different ones, the identity
        // changed, and every subsequent import inserted another copy.
        seed()
        val exported = exporter.export(nowMillis = TestData.T0, fileName = "collision.zip")

        // Wipe, then occupy the low id range with unrelated rows so the archive's original ids
        // cannot be reused.
        wipe()
        repeat(5) { index ->
            db.findDao().insert(
                TestData.find(
                    title = "Cizí $index",
                    createdAt = TestData.T0 + TestData.DAY * (index + 1),
                    lat = 51.0 + index,
                    lon = 16.0 + index,
                ),
            )
        }
        val decoyCount = db.findDao().count()

        val first = importer.import(exported.archive)
        assertThat(first.imported.finds).isEqualTo(2)
        assertThat(db.findDao().count()).isEqualTo(decoyCount + 2)

        val second = importer.import(exported.archive)
        assertThat(second.imported.finds).isEqualTo(0)
        assertThat(second.skipped.finds).isEqualTo(2)

        val third = importer.import(exported.archive)
        assertThat(third.imported.finds).isEqualTo(0)
        assertThat(db.findDao().count()).isEqualTo(decoyCount + 2)

        exported.archive.delete()
    }

    @Test
    fun `a deleted photo file does not break the export`() = runTest {
        seed()
        photoFile.delete()

        val exported = exporter.export(nowMillis = TestData.T0, fileName = "missing-photo.zip")
        assertThat(exported.archive.exists()).isTrue()
        assertThat(exported.missingPhotoFiles).isEqualTo(1)
        assertThat(exported.counts.finds).isEqualTo(2)

        exported.archive.delete()
    }

    @Test
    fun `importing a corrupted archive reports a warning instead of throwing`() = runTest {
        val broken = File(dirs.exportsDir, "broken.zip").apply {
            parentFile?.mkdirs()
            writeText("tohle rozhodně není zip")
        }
        val result = importer.import(broken)
        assertThat(result.warnings).isNotEmpty()
        assertThat(result.imported.finds).isEqualTo(0)
        broken.delete()
    }
}
