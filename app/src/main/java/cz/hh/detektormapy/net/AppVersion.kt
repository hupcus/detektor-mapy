package cz.hh.detektormapy.net

/**
 * Comparing released versions of the app.
 *
 * Kept as pure functions with no Android in sight, because getting this wrong is silent: an
 * update check that misreads a version either nags forever or never mentions the release that
 * fixes the thing the user is complaining about.
 */
object AppVersion {

    /**
     * Splits a version into comparable numbers, tolerating what release tags look like in
     * practice: a `v` prefix, a pre-release suffix, or a fourth component someone added.
     * Non-numeric junk becomes 0 rather than an exception -- a malformed tag must never crash
     * the app that merely asked whether it was out of date.
     */
    fun parse(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        val core = raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        val parts = core.split('.')
        if (parts.isEmpty()) return emptyList()
        return parts.map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
    }

    /** Negative when [a] is older than [b], 0 when equal, positive when newer. */
    fun compare(a: String?, b: String?): Int {
        val left = parse(a)
        val right = parse(b)
        if (left.isEmpty() || right.isEmpty()) return 0
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val diff = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    /**
     * True when [latest] is a release the user does not have yet.
     *
     * Deliberately strict: anything unparseable, equal, or *older* than what is installed
     * answers false. A user running a build newer than the last published release -- which is
     * every user who ever built it themselves -- must not be told to downgrade.
     */
    fun isNewer(latest: String?, installed: String?): Boolean = compare(latest, installed) > 0
}
