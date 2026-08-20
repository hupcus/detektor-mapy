package cz.hh.detektormapy.data

import com.google.common.truth.Truth.assertThat
import cz.hh.detektormapy.data.dao.FindDao
import cz.hh.detektormapy.data.dao.FindPhotoDao
import cz.hh.detektormapy.data.model.FindCategory
import cz.hh.detektormapy.data.repository.FindsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Insert / query / filter / delete, photo cascade and the bbox query of [FindDao]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FindDaoTest {

    private lateinit var db: DetektorDatabase
    private lateinit var findDao: FindDao
    private lateinit var photoDao: FindPhotoDao

    @Before
    fun setUp() {
        db = TestData.inMemoryDatabase()
        findDao = db.findDao()
        photoDao = db.findPhotoDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert and read back keeps every field`() = runTest {
        val id = findDao.insert(
            TestData.find(
                lat = 50.1234,
                lon = 14.5678,
                title = "Pražský groš",
                category = FindCategory.MINCE,
                depthCm = 18,
                note = "Pod mezí",
                favorite = true,
            ),
        )

        val loaded = findDao.getById(id)

        assertThat(loaded).isNotNull()
        assertThat(loaded?.title).isEqualTo("Pražský groš")
        assertThat(loaded?.category).isEqualTo(FindCategory.MINCE)
        assertThat(loaded?.depthCm).isEqualTo(18)
        assertThat(loaded?.favorite).isTrue()
        assertThat(loaded?.lat).isEqualTo(50.1234)
        assertThat(loaded?.lon).isEqualTo(14.5678)
        assertThat(loaded?.altitude).isEqualTo(310.5)
        assertThat(loaded?.accuracyM).isEqualTo(4.5f)
        assertThat(loaded?.layerContextId).isEqualTo("ii-vm")
    }

    @Test
    fun `observeAll returns newest first`() = runTest {
        findDao.insert(TestData.find(createdAt = TestData.T0, title = "starší"))
        findDao.insert(TestData.find(createdAt = TestData.T0 + TestData.DAY, title = "novější"))

        val all = findDao.observeAll().first()

        assertThat(all.map { it.title }).containsExactly("novější", "starší").inOrder()
    }

    @Test
    fun `filter narrows by category, date range and favourite`() = runTest {
        findDao.insert(
            TestData.find(createdAt = TestData.T0, category = FindCategory.MINCE, title = "mince-1"),
        )
        findDao.insert(
            TestData.find(
                createdAt = TestData.T0 + TestData.DAY,
                category = FindCategory.KNOFLIK,
                title = "knoflik",
            ),
        )
        findDao.insert(
            TestData.find(
                createdAt = TestData.T0 + 2 * TestData.DAY,
                category = FindCategory.MINCE,
                title = "mince-2",
                favorite = true,
            ),
        )

        val byCategory = findDao.observeFiltered(
            categories = listOf(FindCategory.MINCE.name),
            ignoreCategories = false,
            fromMillis = Long.MIN_VALUE,
            toMillis = Long.MAX_VALUE,
            favoriteOnly = false,
        ).first()
        assertThat(byCategory.map { it.title }).containsExactly("mince-2", "mince-1")

        val byDate = findDao.observeFiltered(
            categories = emptyList(),
            ignoreCategories = true,
            fromMillis = TestData.T0 + TestData.DAY,
            toMillis = TestData.T0 + TestData.DAY,
            favoriteOnly = false,
        ).first()
        assertThat(byDate.map { it.title }).containsExactly("knoflik")

        val favourites = findDao.observeFiltered(
            categories = emptyList(),
            ignoreCategories = true,
            fromMillis = Long.MIN_VALUE,
            toMillis = Long.MAX_VALUE,
            favoriteOnly = true,
        ).first()
        assertThat(favourites.map { it.title }).containsExactly("mince-2")

        val everything = findDao.observeFiltered(
            categories = emptyList(),
            ignoreCategories = true,
            fromMillis = Long.MIN_VALUE,
            toMillis = Long.MAX_VALUE,
            favoriteOnly = false,
        ).first()
        assertThat(everything).hasSize(3)
    }

    @Test
    fun `bbox query returns only finds inside the box`() = runTest {
        findDao.insert(TestData.find(lat = 50.0, lon = 14.0, title = "uvnitr"))
        findDao.insert(TestData.find(lat = 50.4, lon = 14.4, title = "na-hrane"))
        findDao.insert(TestData.find(lat = 51.5, lon = 16.0, title = "mimo"))

        val inside = findDao.observeInBBox(west = 13.9, south = 49.9, east = 14.4, north = 50.4).first()

        assertThat(inside.map { it.title }).containsExactly("uvnitr", "na-hrane")
    }

    @Test
    fun `deleting a find cascades to its photos`() = runTest {
        val findId = findDao.insert(TestData.find())
        val otherId = findDao.insert(TestData.find(title = "druhy"))
        photoDao.insert(TestData.photo(findId, "/tmp/a.jpg"))
        photoDao.insert(TestData.photo(findId, "/tmp/b.jpg", isPrimary = false))
        photoDao.insert(TestData.photo(otherId, "/tmp/c.jpg"))
        assertThat(photoDao.count()).isEqualTo(3)

        findDao.deleteById(findId)

        assertThat(findDao.count()).isEqualTo(1)
        assertThat(photoDao.count()).isEqualTo(1)
        assertThat(photoDao.getForFind(findId)).isEmpty()
        assertThat(photoDao.getForFind(otherId)).hasSize(1)
    }

    @Test
    fun `find with photos loads the relation and picks a primary photo`() = runTest {
        val findId = findDao.insert(TestData.find())
        photoDao.insert(TestData.photo(findId, "/tmp/a.jpg", createdAt = TestData.T0, isPrimary = false))
        photoDao.insert(
            TestData.photo(findId, "/tmp/b.jpg", createdAt = TestData.T0 + TestData.HOUR, isPrimary = true),
        )

        val withPhotos = findDao.getWithPhotos(findId)

        assertThat(withPhotos).isNotNull()
        assertThat(withPhotos?.photos).hasSize(2)
        assertThat(withPhotos?.primaryPhoto?.uri).isEqualTo("/tmp/b.jpg")
    }

    @Test
    fun `setPrimary demotes the previous primary photo`() = runTest {
        val findId = findDao.insert(TestData.find())
        val first = photoDao.insert(TestData.photo(findId, "/tmp/a.jpg", isPrimary = true))
        val second = photoDao.insert(TestData.photo(findId, "/tmp/b.jpg", isPrimary = false))

        photoDao.setPrimary(findId, second)

        val photos = photoDao.getForFind(findId).associateBy { it.id }
        assertThat(photos[first]?.isPrimary).isFalse()
        assertThat(photos[second]?.isPrimary).isTrue()
    }

    @Test
    fun `stats aggregate over an empty table without crashing`() = runTest {
        val empty = findDao.observeStats().first()

        assertThat(empty.total).isEqualTo(0)
        assertThat(empty.favorites).isEqualTo(0)
        assertThat(empty.firstCreatedAt).isNull()
        assertThat(empty.deepestCm).isNull()
    }

    @Test
    fun `stats and category histogram match the inserted rows`() = runTest {
        findDao.insert(TestData.find(createdAt = TestData.T0, depthCm = 10, category = FindCategory.MINCE))
        findDao.insert(
            TestData.find(
                createdAt = TestData.T0 + TestData.DAY,
                depthCm = 30,
                category = FindCategory.MINCE,
                favorite = true,
            ),
        )
        findDao.insert(
            TestData.find(
                createdAt = TestData.T0 + 2 * TestData.DAY,
                depthCm = 20,
                category = FindCategory.VOJENSKE,
            ),
        )

        val stats = findDao.observeStats().first()
        assertThat(stats.total).isEqualTo(3)
        assertThat(stats.favorites).isEqualTo(1)
        assertThat(stats.firstCreatedAt).isEqualTo(TestData.T0)
        assertThat(stats.lastCreatedAt).isEqualTo(TestData.T0 + 2 * TestData.DAY)
        assertThat(stats.deepestCm).isEqualTo(30)

        val histogram = findDao.observeCountsByCategory().first().associate { it.category to it.count }
        assertThat(histogram[FindCategory.MINCE.name]).isEqualTo(2)
        assertThat(histogram[FindCategory.VOJENSKE.name]).isEqualTo(1)
    }

    @Test
    fun `setFavorite toggles the flag`() = runTest {
        val id = findDao.insert(TestData.find(favorite = false))

        findDao.setFavorite(id, true)
        assertThat(findDao.getById(id)?.favorite).isTrue()

        findDao.setFavorite(id, false)
        assertThat(findDao.getById(id)?.favorite).isFalse()
    }

    @Test
    fun `unknown category name degrades to the default instead of crashing`() = runTest {
        val id = findDao.insert(TestData.find(category = FindCategory.MINCE))
        db.openHelper.writableDatabase.execSQL("UPDATE finds SET category = 'METEORIT' WHERE id = $id")

        assertThat(findDao.getById(id)?.category).isEqualTo(FindCategory.OSTATNI)
    }

    @Test
    fun `countNear counts by real distance, not by the bbox corners`() = runTest {
        val repository = FindsRepository(findDao, photoDao)
        // At lat 50.5 one degree of latitude is ~111 km, so 0.001° ~ 111 m.
        val lat = 50.5123
        val lon = 16.0116
        findDao.insert(TestData.find(lat = lat, lon = lon, title = "přesně tady"))
        findDao.insert(TestData.find(lat = lat + 0.0009, lon = lon, title = "100 m severně"))
        // ~140 m severně i východně = ~198 m diagonálně: uvnitř bboxu, vně kruhu 150 m.
        findDao.insert(TestData.find(lat = lat + 0.00126, lon = lon + 0.00198, title = "roh bboxu"))
        findDao.insert(TestData.find(lat = lat + 0.01, lon = lon, title = "kilometr daleko"))

        assertThat(repository.countNear(lat, lon)).isEqualTo(2)
        assertThat(repository.countNear(lat + 0.01, lon)).isEqualTo(1)
        assertThat(repository.countNear(51.0, 15.0)).isEqualTo(0)
    }
}
