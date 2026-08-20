package cz.hh.detektormapy.location

import com.google.common.truth.Truth.assertThat
import cz.hh.detektormapy.data.entity.TrackPointEntity
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** GPX has to be readable by desktop tools, so the output is parsed, not just string-matched. */
class GpxWriterTest {

    private val startMillis = 1_700_000_000_000L // 2023-11-14T22:13:20Z

    private fun points(count: Int): List<TrackPointEntity> = (0 until count).map { index ->
        TrackPointEntity(
            id = index + 1L,
            trackId = 7L,
            lat = 50.0871234 + index * 0.0001,
            lon = 14.4207654 + index * 0.0001,
            altitude = 231.5 + index,
            timestamp = startMillis + index * 5_000L,
            accuracyM = 4f,
        )
    }

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    @Test
    fun `writes one trkpt per point`() {
        val xml = GpxWriter.toGpx(points(5), name = "Pochůzka")

        val document = parse(xml)
        assertThat(document.documentElement.tagName).isEqualTo("gpx")
        assertThat(document.documentElement.getAttribute("version")).isEqualTo("1.1")
        assertThat(document.documentElement.getAttribute("creator")).isEqualTo("DetektorMapy")
        assertThat(document.getElementsByTagName("trkpt").length).isEqualTo(5)
        assertThat(document.getElementsByTagName("trkseg").length).isEqualTo(1)
    }

    @Test
    fun `coordinates keep at least six decimals`() {
        val xml = GpxWriter.toGpx(points(1), name = "Pochůzka")

        val point = parse(xml).getElementsByTagName("trkpt").item(0) as Element
        val lat = point.getAttribute("lat")
        val lon = point.getAttribute("lon")
        assertThat(lat.substringAfter('.').length).isAtLeast(6)
        assertThat(lon.substringAfter('.').length).isAtLeast(6)
        assertThat(lat.toDouble()).isWithin(1e-6).of(50.0871234)
        assertThat(lon.toDouble()).isWithin(1e-6).of(14.4207654)
        // A Czech locale would emit a decimal comma and break every parser.
        assertThat(lat).doesNotContain(",")
    }

    @Test
    fun `timestamps are ISO-8601 in UTC`() {
        val xml = GpxWriter.toGpx(points(1), name = "Pochůzka")

        val time = parse(xml).getElementsByTagName("trkpt").item(0)
            .let { (it as Element).getElementsByTagName("time").item(0).textContent }
        assertThat(time).isEqualTo("2023-11-14T22:13:20Z")
    }

    @Test
    fun `elevation is written when the fix had one and skipped otherwise`() {
        val withEle = parse(GpxWriter.toGpx(points(1), name = "X"))
        assertThat(withEle.getElementsByTagName("ele").length).isEqualTo(1)

        val flat = points(1).map { it.copy(altitude = null) }
        assertThat(parse(GpxWriter.toGpx(flat, name = "X")).getElementsByTagName("ele").length)
            .isEqualTo(0)
    }

    @Test
    fun `track name is XML escaped`() {
        val raw = "Louka <U mlýna> & \"Šrot\""

        val xml = GpxWriter.toGpx(points(2), name = raw)

        assertThat(xml).contains("&lt;U mlýna&gt;")
        assertThat(xml).contains("&amp;")
        val document = parse(xml)
        val names = document.getElementsByTagName("name")
        assertThat(names.length).isEqualTo(2) // metadata + trk
        assertThat(names.item(0).textContent).isEqualTo(raw)
        assertThat(names.item(1).textContent).isEqualTo(raw)
    }

    @Test
    fun `points are sorted by time`() {
        val shuffled = points(3).reversed()

        val document = parse(GpxWriter.toGpx(shuffled, name = "X"))

        val times = (0 until 3).map {
            (document.getElementsByTagName("trkpt").item(it) as Element)
                .getElementsByTagName("time").item(0).textContent
        }
        assertThat(times).isInOrder()
    }

    @Test
    fun `an empty track is still a valid document`() {
        val xml = GpxWriter.toGpx(emptyList(), name = "Prázdná")

        val document = parse(xml)
        assertThat(document.getElementsByTagName("trkpt").length).isEqualTo(0)
        assertThat(document.getElementsByTagName("trkseg").length).isEqualTo(1)
    }
}
