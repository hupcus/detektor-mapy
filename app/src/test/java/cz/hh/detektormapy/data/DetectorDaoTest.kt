package cz.hh.detektormapy.data

import com.google.common.truth.Truth.assertThat
import cz.hh.detektormapy.data.dao.DetectorDao
import cz.hh.detektormapy.data.entity.DetectorEntity
import cz.hh.detektormapy.data.entity.DetectorPresetEntity
import cz.hh.detektormapy.data.model.SoilCondition
import cz.hh.detektormapy.data.model.Terrain
import cz.hh.detektormapy.data.repository.DetectorRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetectorDaoTest {

    private lateinit var db: DetektorDatabase
    private lateinit var dao: DetectorDao
    private lateinit var repository: DetectorRepository

    @Before
    fun setUp() {
        db = TestData.inMemoryDatabase()
        dao = db.detectorDao()
        repository = DetectorRepository(dao)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `settings survive as free text, including the values a number could not hold`() = runTest {
        val detectorId = dao.insertDetector(detector())
        dao.insertPreset(
            preset(
                detectorId = detectorId,
                sensitivity = "18/25",
                groundBalance = "auto",
                discrimination = "o dva nad železo",
            ),
        )
        val stored = dao.observePresetsFor(detectorId).first().single()
        assertThat(stored.sensitivity).isEqualTo("18/25")
        assertThat(stored.groundBalance).isEqualTo("auto")
        assertThat(stored.discrimination).isEqualTo("o dva nad železo")
    }

    @Test
    fun `enums are stored by name so the declarations can be reordered`() = runTest {
        val detectorId = dao.insertDetector(detector())
        val presetId = dao.insertPreset(
            preset(detectorId = detectorId, terrain = Terrain.RUMISTE, soil = SoilCondition.MOKRO),
        )
        val cursor = db.query(
            "SELECT terrain, soil FROM detector_presets WHERE id = ?",
            arrayOf<Any>(presetId),
        )
        val stored = cursor.use {
            it.moveToFirst()
            it.getString(0) to it.getString(1)
        }
        assertThat(stored).isEqualTo("RUMISTE" to "MOKRO")
    }

    @Test
    fun `deleting a detector takes its presets with it`() = runTest {
        val detectorId = dao.insertDetector(detector())
        dao.insertPreset(preset(detectorId = detectorId))
        dao.insertPreset(preset(detectorId = detectorId, name = "druhý"))
        assertThat(dao.countPresets()).isEqualTo(2)

        dao.deleteDetectorById(detectorId)

        assertThat(dao.countDetectors()).isEqualTo(0)
        assertThat(dao.countPresets()).isEqualTo(0)
    }

    @Test
    fun `the first detector added becomes the default`() = runTest {
        val id = repository.addDetector(detector(name = "první"))
        assertThat(dao.getDetectorById(id)?.isDefault).isTrue()
    }

    @Test
    fun `exactly one detector is ever the default`() = runTest {
        val first = repository.addDetector(detector(name = "první"))
        val second = repository.addDetector(detector(name = "druhý", createdAt = T0 + 1))

        repository.setDefaultDetector(second)

        val all = dao.getAllDetectors()
        assertThat(all.filter { it.isDefault }.map { it.id }).containsExactly(second)
        assertThat(dao.getDetectorById(first)?.isDefault).isFalse()
    }

    @Test
    fun `the library relation carries each detector's own presets`() = runTest {
        val first = repository.addDetector(detector(name = "první"))
        val second = repository.addDetector(detector(name = "druhý", createdAt = T0 + 1))
        dao.insertPreset(preset(detectorId = first, name = "les"))
        dao.insertPreset(preset(detectorId = second, name = "pole"))
        dao.insertPreset(preset(detectorId = second, name = "louka"))

        val library = dao.observeDetectorsWithPresets().first().associateBy { it.detector.name }

        assertThat(library.getValue("první").presets.map { it.name }).containsExactly("les")
        assertThat(library.getValue("druhý").presets.map { it.name })
            .containsExactly("pole", "louka")
    }

    private fun detector(name: String = "Moje stará", createdAt: Long = T0) =
        DetectorEntity(name = name, brand = "Garrett", coil = "11\" DD", createdAt = createdAt)

    private fun preset(
        detectorId: Long,
        name: String = "Les po dešti",
        terrain: Terrain = Terrain.LES,
        soil: SoilCondition = SoilCondition.MOKRO,
        sensitivity: String? = null,
        groundBalance: String? = null,
        discrimination: String? = null,
        createdAt: Long = T0,
    ) = DetectorPresetEntity(
        detectorId = detectorId,
        name = name,
        terrain = terrain,
        soil = soil,
        sensitivity = sensitivity,
        groundBalance = groundBalance,
        discrimination = discrimination,
        createdAt = createdAt,
    )

    private companion object {
        const val T0 = TestData.T0
    }
}
