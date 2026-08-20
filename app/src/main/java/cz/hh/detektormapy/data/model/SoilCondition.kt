package cz.hh.detektormapy.data.model

/**
 * How wet the ground is.
 *
 * Three buckets, not a number: the only source the app can reach offline-ish is a weather model
 * on a roughly 11 km grid, and pretending that resolves to a percentage for one field would be
 * dishonest. Three buckets are also all the user needs -- they change which preset gets picked,
 * not a dial position.
 *
 * Persisted by [name]. [label] is user-facing and therefore Czech.
 */
enum class SoilCondition(val label: String) {
    SUCHO("Sucho"),
    VLHKO("Vlhko"),
    MOKRO("Mokro"),
    ;

    companion object {
        val DEFAULT: SoilCondition = VLHKO

        /** Lenient lookup that never throws; unknown names degrade to [DEFAULT]. */
        fun fromName(value: String?): SoilCondition = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
