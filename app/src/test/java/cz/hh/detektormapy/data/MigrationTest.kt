package cz.hh.detektormapy.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import cz.hh.detektormapy.data.entity.DetectorEntity
import cz.hh.detektormapy.data.entity.DetectorPresetEntity
import cz.hh.detektormapy.data.model.SoilCondition
import cz.hh.detektormapy.data.model.Terrain
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proof that 1 -> 2 does not cost the user their season.
 *
 * Version 0.1.0 is already installed on a real phone with real finds in it, so the interesting
 * question is not "does the new table appear" but "are the old rows still there afterwards".
 * The test therefore writes rows into a genuine version-1 database built from the exported
 * schema, migrates it, and reads them back.
 *
 * [MigrationTestHelper.runMigrationsAndValidate] additionally diffs the migrated schema against
 * the exported version-2 schema, so a typo in the hand-written DDL fails here rather than on the
 * user's phone at app start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DetektorDatabase::class.java,
    )

    @Test
    fun `migration 1 to 2 keeps the rows that were already there`() = runTest {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO places (lat, lon, type, title, note, createdAt, visited) " +
                    "VALUES (49.5, 15.5, 'PLAN', 'Louka u lesa', 'zkusit po orbě', 1717236000000, 0)",
            )
            db.execSQL(
                "INSERT INTO finds (lat, lon, altitude, accuracyM, createdAt, title, category, " +
                    "depthCm, note, favorite, layerContextId, trackId) " +
                    "VALUES (50.0, 14.0, 310.5, 4.5, 1717236000000, 'Mince', 'MINCE', 12, '', 1, 'ii-vm', NULL)",
            )
        }

        // Validates the migrated schema against the exported version 2 as a side effect.
        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).close()

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            DetektorDatabase::class.java,
            TEST_DB,
        ).addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build()

        try {
            assertThat(db.placeDao().count()).isEqualTo(1)
            assertThat(db.placeDao().getAll().single().title).isEqualTo("Louka u lesa")
            assertThat(db.findDao().count()).isEqualTo(1)
            assertThat(db.findDao().getAll().single().title).isEqualTo("Mince")
            // The new tables exist and are empty, not merely present.
            assertThat(db.detectorDao().countDetectors()).isEqualTo(0)
            assertThat(db.detectorDao().countPresets()).isEqualTo(0)
        } finally {
            db.close()
        }
    }

    @Test
    fun `migrated database accepts detectors and cascades their presets`() = runTest {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).close()

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            DetektorDatabase::class.java,
            TEST_DB,
        ).addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build()

        try {
            val dao = db.detectorDao()
            val detectorId = dao.insertDetector(
                DetectorEntity(name = "Moje stará", brand = "Garrett", createdAt = 1_717_236_000_000L),
            )
            dao.insertPreset(
                DetectorPresetEntity(
                    detectorId = detectorId,
                    name = "Les po dešti",
                    terrain = Terrain.LES,
                    soil = SoilCondition.MOKRO,
                    sensitivity = "18/25",
                    createdAt = 1_717_236_000_000L,
                ),
            )
            assertThat(dao.countPresets()).isEqualTo(1)

            dao.deleteDetectorById(detectorId)
            assertThat(dao.countPresets()).isEqualTo(0)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
