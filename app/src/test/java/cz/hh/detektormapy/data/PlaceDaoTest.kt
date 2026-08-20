package cz.hh.detektormapy.data

import com.google.common.truth.Truth.assertThat
import cz.hh.detektormapy.data.dao.PlaceDao
import cz.hh.detektormapy.data.model.PlaceType
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
class PlaceDaoTest {

    private lateinit var db: DetektorDatabase
    private lateinit var dao: PlaceDao

    @Before
    fun setUp() {
        db = TestData.inMemoryDatabase()
        dao = db.placeDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `insert and read back preserves every field`() = runTest {
        val id = dao.insert(
            TestData.place(
                lat = 49.4123,
                lon = 15.6789,
                type = PlaceType.ZAKAZ,
                title = "Soukromý pozemek",
                note = "Majitel nesouhlasí",
            ),
        )
        val row = dao.getById(id)
        assertThat(row).isNotNull()
        assertThat(row?.lat).isEqualTo(49.4123)
        assertThat(row?.lon).isEqualTo(15.6789)
        assertThat(row?.type).isEqualTo(PlaceType.ZAKAZ)
        assertThat(row?.title).isEqualTo("Soukromý pozemek")
        assertThat(row?.note).isEqualTo("Majitel nesouhlasí")
        assertThat(row?.visited).isFalse()
    }

    @Test
    fun `enum survives as a name so reordering the enum cannot corrupt data`() = runTest {
        val id = dao.insert(TestData.place(type = PlaceType.PARKOVANI))
        val cursor = db.query("SELECT type FROM places WHERE id = ?", arrayOf<Any>(id))
        val stored = cursor.use {
            it.moveToFirst()
            it.getString(0)
        }
        assertThat(stored).isEqualTo("PARKOVANI")
    }

    @Test
    fun `markVisited flips the flag and stores the timestamp`() = runTest {
        val id = dao.insert(TestData.place())
        dao.setVisited(id, true, TestData.T0 + TestData.DAY)
        val row = dao.getById(id)
        assertThat(row?.visited).isTrue()
        assertThat(row?.visitedAt).isEqualTo(TestData.T0 + TestData.DAY)
    }

    @Test
    fun `bbox query returns only places inside`() = runTest {
        dao.insert(TestData.place(lat = 50.0, lon = 15.0, title = "uvnitř"))
        dao.insert(TestData.place(lat = 48.0, lon = 13.0, title = "venku"))
        val inside = dao.observeInBBox(14.0, 49.0, 16.0, 51.0).first()
        assertThat(inside.map { it.title }).containsExactly("uvnitř")
    }

    @Test
    fun `observeAll is ordered newest first`() = runTest {
        dao.insert(TestData.place(title = "starší", createdAt = TestData.T0))
        dao.insert(TestData.place(title = "novější", createdAt = TestData.T0 + TestData.DAY))
        val all = dao.observeAll().first()
        assertThat(all.first().title).isEqualTo("novější")
    }

    @Test
    fun `delete removes the row`() = runTest {
        val id = dao.insert(TestData.place())
        dao.deleteById(id)
        assertThat(dao.getById(id)).isNull()
        assertThat(dao.count()).isEqualTo(0)
    }
}
