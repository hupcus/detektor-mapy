package cz.hh.detektormapy.data.model

/**
 * Progress state of a manually drawn searched area.
 *
 * Persisted by [name]. [label] is user-facing and therefore Czech.
 */
enum class AreaStatus(val label: String) {
    ROZPRACOVANO("Rozpracováno"),
    HOTOVO("Hotovo"),
    ;

    companion object {
        val DEFAULT: AreaStatus = ROZPRACOVANO

        /** Lenient lookup that never throws; unknown names degrade to [DEFAULT]. */
        fun fromName(value: String?): AreaStatus = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
