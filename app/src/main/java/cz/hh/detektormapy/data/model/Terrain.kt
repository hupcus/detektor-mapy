package cz.hh.detektormapy.data.model

/**
 * Where the search is happening.
 *
 * The list is deliberately short and picked by hand rather than derived from any land-cover
 * dataset: the app has no offline land cover, so terrain is always something the user states,
 * never something the app claims to know. The values are the distinctions that actually change
 * how a machine is set up -- iron-littered woodland behaves nothing like ploughed field.
 *
 * Persisted by [name]. [label] is user-facing and therefore Czech.
 */
enum class Terrain(val label: String) {
    LES("Les"),
    LOUKA("Louka"),
    POLE("Pole"),
    ZAHRADA("Zahrada"),
    RUMISTE("Rumiště"),
    PLAZ("Pláž"),
    ;

    companion object {
        val DEFAULT: Terrain = LOUKA

        /** Lenient lookup that never throws; unknown names degrade to [DEFAULT]. */
        fun fromName(value: String?): Terrain = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
