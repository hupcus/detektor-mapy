package cz.hh.detektormapy.data.model

/**
 * Kind of a user-placed waypoint.
 *
 * Persisted by [name]. [label] and [marker] are user-facing and therefore Czech.
 */
enum class PlaceType(val label: String, val marker: String) {
    PLAN("Plánovaná lokalita", "📍"),
    ZAJIMAVOST("Zajímavost", "⭐"),
    ZAKAZ("Zákaz vstupu", "⛔"),
    SRAZ("Sraz", "🤝"),
    PARKOVANI("Parkování", "🅿"),
    ;

    companion object {
        val DEFAULT: PlaceType = ZAJIMAVOST

        /** Lenient lookup that never throws; unknown names degrade to [DEFAULT]. */
        fun fromName(value: String?): PlaceType = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
