package cz.hh.detektormapy.data

import androidx.room.migration.Migration

/**
 * Schema migration policy for [DetektorDatabase].
 *
 * Version 1 is the baseline: the schema JSON exported to `app/schemas` is the reference every
 * later version is diffed against. Every schema change from here on gets an explicit [Migration]
 * appended to [ALL_MIGRATIONS] and a matching Room migration test.
 *
 * `fallbackToDestructiveMigration` is deliberately **never** used. This database holds field data
 * -- finds, photos, tracks, hand-tuned calibrations -- that cannot be re-collected. A missing
 * migration must fail loudly at open time so the data can be rescued, not be silently wiped.
 */
val ALL_MIGRATIONS: Array<Migration> = emptyArray()
