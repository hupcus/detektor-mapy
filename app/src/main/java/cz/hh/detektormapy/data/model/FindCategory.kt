package cz.hh.detektormapy.data.model

/**
 * Category of a metal-detecting find.
 *
 * Persisted by [name] (never by ordinal) so the enum can be reordered or extended without a
 * database migration. [label] and [marker] are user-facing and therefore Czech.
 */
enum class FindCategory(val label: String, val marker: String) {
    MINCE("Mince", "🪙"),
    KNOFLIK("Knoflík", "⚪"),
    VOJENSKE("Vojenské", "🪖"),
    SPONA("Spona", "📎"),
    PRSTEN("Prsten", "💍"),
    NASTROJ("Nástroj", "🔨"),
    SROT("Šrot", "🗑"),
    OSTATNI("Ostatní", "❓"),
    ;

    companion object {
        /** Default used whenever an unknown or corrupted value comes back from the database. */
        val DEFAULT: FindCategory = OSTATNI

        /** Lenient lookup that never throws; unknown names degrade to [DEFAULT]. */
        fun fromName(value: String?): FindCategory = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
