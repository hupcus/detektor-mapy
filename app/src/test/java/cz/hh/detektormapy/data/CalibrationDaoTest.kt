package cz.hh.detektormapy.data

import com.google.common.truth.Truth.assertThat
import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.data.dao.LayerCalibrationDao
import cz.hh.detektormapy.data.entity.LayerCalibrationEntity
import cz.hh.detektormapy.util.BBox
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rule these tests defend is the heart of PLAN.md section 6: when several stored
 * calibrations cover the same spot, the *tightest* one wins, because it was recorded closest
 * to where the user is standing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalibrationDaoTest {

    private lateinit var db: DetektorDatabase
    private lateinit var dao: LayerCalibrationDao

    @Before
    fun setUp() {
        db = TestData.inMemoryDatabase()
        dao = db.layerCalibrationDao()
    }

    @After
    fun tearDown() = db.close()

    private fun calibration(
        layerId: String = "vm2",
        label: String,
        bbox: BBox,
        transform: Affine2D = Affine2D.translation(12.0, -8.0),
        createdAt: Long = TestData.T0,
        active: Boolean = true,
    ) = LayerCalibrationEntity(
        layerId = layerId,
        label = label,
        west = bbox.west,
        south = bbox.south,
        east = bbox.east,
        north = bbox.north,
        m0 = transform.a,
        m1 = transform.b,
        m2 = transform.tx,
        m3 = transform.c,
        m4 = transform.d,
        m5 = transform.ty,
        createdAt = createdAt,
        updatedAt = createdAt,
        active = active,
    )

    @Test
    fun `tightest containing bbox wins`() = runTest {
        dao.insert(calibration(label = "kraj", bbox = BBox(14.0, 49.0, 16.0, 51.0)))
        dao.insert(calibration(label = "okres", bbox = BBox(14.8, 49.8, 15.2, 50.2)))
        dao.insert(calibration(label = "katastr", bbox = BBox(14.98, 49.98, 15.02, 50.02)))

        val best = dao.getBestFor("vm2", 50.0, 15.0)
        assertThat(best).isNotNull()
        assertThat(best?.label).isEqualTo("katastr")
    }

    @Test
    fun `position outside every bbox yields nothing`() = runTest {
        dao.insert(calibration(label = "okres", bbox = BBox(14.8, 49.8, 15.2, 50.2)))
        assertThat(dao.getBestFor("vm2", 48.0, 13.0)).isNull()
    }

    @Test
    fun `calibrations of another layer are never returned`() = runTest {
        dao.insert(calibration(layerId = "vm3", label = "cizí", bbox = BBox(14.0, 49.0, 16.0, 51.0)))
        assertThat(dao.getBestFor("vm2", 50.0, 15.0)).isNull()
        assertThat(dao.getBestFor("vm3", 50.0, 15.0)?.label).isEqualTo("cizí")
    }

    @Test
    fun `observeForLayer emits only that layer ordered consistently`() = runTest {
        dao.insert(calibration(label = "a", bbox = BBox(14.0, 49.0, 16.0, 51.0)))
        dao.insert(calibration(layerId = "vm3", label = "b", bbox = BBox(14.0, 49.0, 16.0, 51.0)))
        val forVm2 = dao.observeForLayer("vm2").first()
        assertThat(forVm2).hasSize(1)
        assertThat(forVm2.first().label).isEqualTo("a")
    }

    @Test
    fun `rename and setActive update the row and the timestamp`() = runTest {
        val id = dao.insert(calibration(label = "původní", bbox = BBox(14.0, 49.0, 16.0, 51.0)))
        dao.rename(id, "nový název", TestData.T0 + TestData.HOUR)
        dao.setActive(id, false, TestData.T0 + 2 * TestData.HOUR)

        val row = dao.getById(id)
        assertThat(row?.label).isEqualTo("nový název")
        assertThat(row?.active).isFalse()
        assertThat(row?.updatedAt).isEqualTo(TestData.T0 + 2 * TestData.HOUR)
    }

    @Test
    fun `stored coefficients round-trip through Affine2D`() = runTest {
        val transform = Affine2D(1.0004, -0.0007, 15.5, 0.0007, 1.0004, -8.25)
        val id = dao.insert(
            calibration(label = "přesná", bbox = BBox(14.0, 49.0, 16.0, 51.0), transform = transform),
        )
        val row = dao.getById(id)
        assertThat(row).isNotNull()
        val restored = Affine2D(row!!.m0, row.m1, row.m2, row.m3, row.m4, row.m5)
        assertThat(restored).isEqualTo(transform)
    }

    @Test
    fun `deleting removes the row and the count follows`() = runTest {
        val id = dao.insert(calibration(label = "smazat", bbox = BBox(14.0, 49.0, 16.0, 51.0)))
        assertThat(dao.count()).isEqualTo(1)
        dao.deleteById(id)
        assertThat(dao.count()).isEqualTo(0)
        assertThat(dao.getById(id)).isNull()
    }

    @Test
    fun `bbox edges are inclusive so a point on the border still matches`() = runTest {
        dao.insert(calibration(label = "hrana", bbox = BBox(14.0, 49.0, 15.0, 50.0)))
        assertThat(dao.getBestFor("vm2", 50.0, 15.0)?.label).isEqualTo("hrana")
        assertThat(dao.getBestFor("vm2", 49.0, 14.0)?.label).isEqualTo("hrana")
    }
}
