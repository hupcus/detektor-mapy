package cz.hh.detektormapy.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GeoTest {

    @Test
    fun `distance between prague and brno matches the known great-circle value`() {
        val d = Geo.distanceM(50.0755, 14.4378, 49.1951, 16.6068)
        assertThat(d).isWithin(2_000.0).of(184_000.0)
    }

    @Test
    fun `distance to itself is zero`() {
        assertThat(Geo.distanceM(49.4, 15.1, 49.4, 15.1)).isWithin(1e-6).of(0.0)
    }

    @Test
    fun `bearing due north and east`() {
        assertThat(Geo.bearingDeg(50.0, 15.0, 51.0, 15.0)).isWithin(0.01).of(0.0)
        assertThat(Geo.bearingDeg(50.0, 15.0, 50.0, 16.0)).isWithin(0.5).of(90.0)
        assertThat(Geo.bearingDeg(50.0, 15.0, 49.0, 15.0)).isWithin(0.01).of(180.0)
    }

    @Test
    fun `compass labels wrap correctly`() {
        assertThat(Geo.compassLabel(0.0)).isEqualTo("S")
        assertThat(Geo.compassLabel(45.0)).isEqualTo("SV")
        assertThat(Geo.compassLabel(180.0)).isEqualTo("J")
        assertThat(Geo.compassLabel(359.0)).isEqualTo("S")
        assertThat(Geo.compassLabel(-45.0)).isEqualTo("SZ")
    }

    @Test
    fun `distance formatting switches units`() {
        assertThat(Geo.formatDistance(35.4)).isEqualTo("35 m")
        assertThat(Geo.formatDistance(999.0)).isEqualTo("999 m")
        assertThat(Geo.formatDistance(1234.0)).contains("km")
        assertThat(Geo.formatDistance(45_000.0)).isEqualTo("45 km")
    }

    @Test
    fun `polygon area of a one hectare square is one hectare`() {
        // 100 m x 100 m near 50 N.
        val lat = 50.0
        val dLat = 100.0 / 111_132.0
        val dLon = 100.0 / (111_412.84 * Math.cos(Math.toRadians(lat)))
        val ring = listOf(
            lat to 15.0,
            lat to 15.0 + dLon,
            lat + dLat to 15.0 + dLon,
            lat + dLat to 15.0,
        )
        assertThat(Geo.polygonAreaHa(ring)).isWithin(0.05).of(1.0)
    }

    @Test
    fun `degenerate polygons have no area`() {
        assertThat(Geo.polygonAreaHa(emptyList())).isEqualTo(0.0)
        assertThat(Geo.polygonAreaHa(listOf(50.0 to 15.0, 50.1 to 15.1))).isEqualTo(0.0)
    }

    @Test
    fun `sun times are ordered and plausible for a summer day in czechia`() {
        // 2026-06-21 -> epoch day 20625
        val times = Geo.sunTimes(50.08, 14.42, 20_625L)
        assertThat(times).isNotNull()
        val (rise, set) = times!!
        assertThat(rise).isLessThan(set)
        val dayLengthHours = (set - rise) / 3_600_000.0
        assertThat(dayLengthHours).isGreaterThan(15.0)
        assertThat(dayLengthHours).isLessThan(17.5)
    }

    @Test
    fun `polar night returns no sun times`() {
        assertThat(Geo.sunTimes(89.0, 0.0, 20_800L)).isNull()
    }
}
