package cz.hh.detektormapy.location

import cz.hh.detektormapy.data.entity.TrackPointEntity
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Serialises a recorded track to GPX 1.1.
 *
 * GPX is what every desktop tool (QGIS, Locus, Garmin BaseCamp) reads, so it is the format the
 * user gets on the USB cable. The writer is pure -- no Android, no file system -- so the exact
 * bytes can be asserted in a JVM test; the service only decides *where* to put them.
 *
 * Coordinates are printed with seven decimals (~1 cm), which is more than GPS resolution but
 * costs nothing and avoids a visible "staircase" when a track is re-imported.
 */
object GpxWriter {

    private const val COORD_FORMAT = "%.7f"
    private const val ELE_FORMAT = "%.2f"

    /** ISO-8601 in UTC, whole seconds -- what the GPX schema's `xsd:dateTime` expects. */
    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    /** Convenience wrapper around [write] for callers that just want the document. */
    fun toGpx(points: List<TrackPointEntity>, name: String = "", createdAt: Long? = null): String =
        StringBuilder().also { write(points, name, createdAt, it) }.toString()

    /**
     * Writes the whole document into [out].
     *
     * [createdAt] ends up in `<metadata><time>`; when null the first point's timestamp is used,
     * so a track always carries a date even if the caller does not care.
     */
    fun write(points: List<TrackPointEntity>, name: String, createdAt: Long?, out: Appendable) {
        val ordered = points.sortedBy { it.timestamp }
        val stamp = createdAt ?: ordered.firstOrNull()?.timestamp

        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<gpx version=\"1.1\" creator=\"DetektorMapy\"\n")
        out.append("     xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
        out.append("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        out.append(
            "     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 " +
                "http://www.topografix.com/GPX/1/1/gpx.xsd\">\n",
        )

        out.append("  <metadata>\n")
        out.append("    <name>").append(escape(name)).append("</name>\n")
        if (stamp != null) {
            out.append("    <time>").append(formatTime(stamp)).append("</time>\n")
        }
        out.append("  </metadata>\n")

        out.append("  <trk>\n")
        out.append("    <name>").append(escape(name)).append("</name>\n")
        out.append("    <trkseg>\n")
        ordered.forEach { point -> writePoint(point, out) }
        out.append("    </trkseg>\n")
        out.append("  </trk>\n")
        out.append("</gpx>\n")
    }

    private fun writePoint(point: TrackPointEntity, out: Appendable) {
        out.append("      <trkpt lat=\"").append(format(COORD_FORMAT, point.lat))
        out.append("\" lon=\"").append(format(COORD_FORMAT, point.lon)).append("\">\n")
        point.altitude?.let {
            out.append("        <ele>").append(format(ELE_FORMAT, it)).append("</ele>\n")
        }
        // The GPX schema fixes the child order: ele before time.
        out.append("        <time>").append(formatTime(point.timestamp)).append("</time>\n")
        out.append("      </trkpt>\n")
    }

    private fun formatTime(epochMillis: Long): String = TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis))

    /** Locale.US on purpose: a Czech locale would print `50,123` and break the document. */
    private fun format(pattern: String, value: Double): String = String.format(Locale.US, pattern, value)

    /** Minimal XML escaping; track names come from the user and may contain anything. */
    private fun escape(raw: String): String {
        val builder = StringBuilder(raw.length)
        raw.forEach { char ->
            when (char) {
                '&' -> builder.append("&amp;")
                '<' -> builder.append("&lt;")
                '>' -> builder.append("&gt;")
                '"' -> builder.append("&quot;")
                '\'' -> builder.append("&apos;")
                else -> builder.append(char)
            }
        }
        return builder.toString()
    }
}
