package cz.hh.detektormapy.data.export

import cz.hh.detektormapy.data.entity.TrackEntity
import cz.hh.detektormapy.data.entity.TrackPointEntity
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Hand-rolled GPX 1.1 writer and reader.
 *
 * Track metadata that GPX has no home for (distance, duration, per-point accuracy and speed)
 * travels in a private `dm:` extension namespace, so the file stays valid GPX for QGIS and Locus
 * while still round-tripping losslessly back into the app. Reading is namespace-unaware and
 * lenient: anything unparseable is skipped, never thrown.
 */
internal object Gpx {

    private const val NS = "https://hubka.cz/detektormapy/gpx/1"

    fun write(track: TrackEntity, points: List<TrackPointEntity>): String {
        val sb = StringBuilder(1024 + points.size * 160)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("<gpx version=\"1.1\" creator=\"DetektorMapy\" ")
            .append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
            .append("xmlns:dm=\"").append(NS).append("\">").append('\n')
        sb.append("  <metadata>").append('\n')
        sb.append("    <name>").append(escape(track.name)).append("</name>").append('\n')
        sb.append("    <time>").append(isoOrEmpty(track.startedAt)).append("</time>").append('\n')
        sb.append("  </metadata>").append('\n')
        sb.append("  <trk>").append('\n')
        sb.append("    <name>").append(escape(track.name)).append("</name>").append('\n')
        sb.append("    <extensions>").append('\n')
        sb.append("      <dm:id>").append(track.id).append("</dm:id>").append('\n')
        sb.append("      <dm:startedAt>").append(track.startedAt).append("</dm:startedAt>").append('\n')
        track.endedAt?.let { sb.append("      <dm:endedAt>").append(it).append("</dm:endedAt>").append('\n') }
        sb.append("      <dm:distanceM>").append(track.distanceM).append("</dm:distanceM>").append('\n')
        sb.append("      <dm:durationMs>").append(track.durationMs).append("</dm:durationMs>").append('\n')
        sb.append("      <dm:pointCount>").append(track.pointCount).append("</dm:pointCount>").append('\n')
        sb.append("    </extensions>").append('\n')
        sb.append("    <trkseg>").append('\n')
        for (p in points) {
            sb.append("      <trkpt lat=\"").append(p.lat).append("\" lon=\"").append(p.lon).append("\">").append('\n')
            p.altitude?.let { sb.append("        <ele>").append(it).append("</ele>").append('\n') }
            sb.append("        <time>").append(isoOrEmpty(p.timestamp)).append("</time>").append('\n')
            sb.append("        <extensions>").append('\n')
            sb.append("          <dm:timestamp>").append(p.timestamp).append("</dm:timestamp>").append('\n')
            p.accuracyM?.let { sb.append("          <dm:accuracyM>").append(it).append("</dm:accuracyM>").append('\n') }
            p.speedMs?.let { sb.append("          <dm:speedMs>").append(it).append("</dm:speedMs>").append('\n') }
            sb.append("        </extensions>").append('\n')
            sb.append("      </trkpt>").append('\n')
        }
        sb.append("    </trkseg>").append('\n')
        sb.append("  </trk>").append('\n')
        sb.append("</gpx>").append('\n')
        return sb.toString()
    }

    /** A track plus its points parsed out of a GPX document, or null when the file is unusable. */
    fun read(xml: String): ParsedTrack? {
        val document = runCatching {
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        }.getOrNull() ?: return null

        val trk = document.getElementsByTagName("trk").item(0) as? Element ?: return null
        val name = childText(trk, "name") ?: ""
        val ext = firstChildElement(trk, "extensions")
        val id = ext?.let { childText(it, "dm:id")?.toLongOrNull() } ?: 0L
        val startedAt = ext?.let { childText(it, "dm:startedAt")?.toLongOrNull() }
        val endedAt = ext?.let { childText(it, "dm:endedAt")?.toLongOrNull() }
        val distanceM = ext?.let { childText(it, "dm:distanceM")?.toDoubleOrNull() } ?: 0.0
        val durationMs = ext?.let { childText(it, "dm:durationMs")?.toLongOrNull() } ?: 0L

        val points = mutableListOf<ParsedPoint>()
        val nodes = document.getElementsByTagName("trkpt")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val lat = el.getAttribute("lat").toDoubleOrNull() ?: continue
            val lon = el.getAttribute("lon").toDoubleOrNull() ?: continue
            val ele = childText(el, "ele")?.toDoubleOrNull()
            val pExt = firstChildElement(el, "extensions")
            val stamp = pExt?.let { childText(it, "dm:timestamp")?.toLongOrNull() }
                ?: childText(el, "time")?.let(::parseIso)
                ?: 0L
            points += ParsedPoint(
                lat = lat,
                lon = lon,
                altitude = ele,
                timestamp = stamp,
                accuracyM = pExt?.let { childText(it, "dm:accuracyM")?.toFloatOrNull() },
                speedMs = pExt?.let { childText(it, "dm:speedMs")?.toFloatOrNull() },
            )
        }

        val start = startedAt ?: points.minOfOrNull { it.timestamp } ?: 0L
        return ParsedTrack(
            id = id,
            name = name,
            startedAt = start,
            endedAt = endedAt ?: points.maxOfOrNull { it.timestamp },
            distanceM = distanceM,
            durationMs = durationMs,
            points = points.sortedBy { it.timestamp },
        )
    }

    private fun isoOrEmpty(millis: Long): String =
        runCatching { Instant.ofEpochMilli(millis).toString() }.getOrDefault("")

    private fun parseIso(value: String): Long? = try {
        Instant.parse(value.trim()).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    private fun firstChildElement(parent: Element, tag: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == tag) return node as Element
        }
        return null
    }

    private fun childText(parent: Element, tag: String): String? = firstChildElement(parent, tag)?.textContent?.trim()

    private fun escape(value: String): String = buildString(value.length + 16) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }
}

/** A GPX track point read back from disk. */
internal data class ParsedPoint(
    val lat: Double,
    val lon: Double,
    val altitude: Double?,
    val timestamp: Long,
    val accuracyM: Float?,
    val speedMs: Float?,
)

/** A GPX track read back from disk. */
internal data class ParsedTrack(
    val id: Long,
    val name: String,
    val startedAt: Long,
    val endedAt: Long?,
    val distanceM: Double,
    val durationMs: Long,
    val points: List<ParsedPoint>,
)
