package cz.hh.detektormapy.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 1 -> 2: the detector library (machines and their presets).
 *
 * Purely additive -- two new tables and their indices, nothing existing is touched. Version 0.1.0
 * is already on the user's phone with a season of finds in it, so this had to be a real migration
 * rather than a version bump; the accompanying test opens a v1 database with rows in it, migrates,
 * and asserts the rows are still there.
 *
 * The DDL matches what Room exports for version 2 byte for byte. It has to: Room validates the
 * migrated schema against the exported one at open time and throws if they differ, which is
 * exactly what the migration test catches before a release does.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `detectors` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`brand` TEXT NOT NULL, " +
                "`model` TEXT NOT NULL, " +
                "`coil` TEXT NOT NULL, " +
                "`notes` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`isDefault` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_detectors_createdAt` ON `detectors` (`createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_detectors_isDefault` ON `detectors` (`isDefault`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `detector_presets` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`detectorId` INTEGER NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`terrain` TEXT NOT NULL, " +
                "`soil` TEXT NOT NULL, " +
                "`notes` TEXT NOT NULL, " +
                "`sensitivity` TEXT, " +
                "`groundBalance` TEXT, " +
                "`discrimination` TEXT, " +
                "`createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`detectorId`) REFERENCES `detectors`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_detector_presets_detectorId` " +
                "ON `detector_presets` (`detectorId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_detector_presets_createdAt` " +
                "ON `detector_presets` (`createdAt`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_detector_presets_terrain` " +
                "ON `detector_presets` (`terrain`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_detector_presets_soil` " +
                "ON `detector_presets` (`soil`)",
        )
    }
}

/**
 * Schema migration policy for [DetektorDatabase].
 *
 * Version 1 is the baseline: the schema JSON exported to `app/schemas` is the reference every
 * later version is diffed against. Every schema change from here on gets an explicit [Migration]
 * appended here and a matching Room migration test.
 *
 * `fallbackToDestructiveMigration` is deliberately **never** used. This database holds field data
 * -- finds, photos, tracks, hand-tuned calibrations -- that cannot be re-collected. A missing
 * migration must fail loudly at open time so the data can be rescued, not be silently wiped.
 */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
