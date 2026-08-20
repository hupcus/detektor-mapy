package cz.hh.detektormapy.data.entity

import java.util.Locale

/**
 * Stable identity of a row across export and import.
 *
 * The obvious implementation -- deriving the id from the row's primary key -- is subtly broken:
 * on import a primary key may already be taken, Room then assigns a different one, and the row's
 * identity silently changes. Re-importing the same archive would no longer recognise it and
 * would insert a second copy, and a third on the next import.
 *
 * So identity is derived from **content that the round trip preserves**: the creation timestamp
 * plus whatever makes the row unique at that instant.
 *
 * The fields chosen are all immutable after creation. Editing a find's title or note therefore
 * does not change its identity, and re-importing an older archive still recognises the row.
 *
 * The uniqueness assumption is that a single user cannot create two rows of the same kind in the
 * same millisecond at the same coordinates -- true for anything produced by a shutter press or a
 * long-press on the map. Two such rows would be treated as one on import.
 */
object ExternalIds {

    /** Roughly 1 cm; enough to disambiguate, coarse enough to survive JSON round-tripping. */
    private const val COORD_FORMAT = "%.7f"

    private fun coord(value: Double): String = String.format(Locale.ROOT, COORD_FORMAT, value)

    fun find(createdAt: Long, lat: Double, lon: Double): String = "find-$createdAt-${coord(lat)}-${coord(lon)}"

    fun photo(createdAt: Long, uri: String): String = "photo-$createdAt-${uri.substringAfterLast('/')}"

    fun place(createdAt: Long, lat: Double, lon: Double): String = "place-$createdAt-${coord(lat)}-${coord(lon)}"

    fun area(createdAt: Long, name: String): String = "area-$createdAt-$name"

    fun track(startedAt: Long): String = "track-$startedAt"

    fun calibration(createdAt: Long, layerId: String): String = "calib-$createdAt-$layerId"

    fun gcpSet(createdAt: Long, layerId: String): String = "gcpset-$createdAt-$layerId"

    /**
     * A detector row. Only the creation timestamp is used: the name is the one thing a user
     * renames, and a rename must not turn the row into a different row on the next import.
     */
    fun detector(createdAt: Long): String = "detector-$createdAt"

    /** A preset row, keyed on its creation timestamp for the same reason as [detector]. */
    fun detectorPreset(createdAt: Long): String = "preset-$createdAt"
}
