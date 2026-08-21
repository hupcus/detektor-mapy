package cz.hh.detektormapy.ui.calibration

import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.util.WebMercator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Turns an affine calibration into the one line a user can act on.
 *
 * Two things this deliberately does *not* do, because both were actively misleading:
 *
 *  - it never reports `tx`/`ty` as "the shift". Those are the transform's translation column,
 *    measured from the Web Mercator origin somewhere in the Gulf of Guinea. Add half a degree of
 *    rotation to a Czech calibration and they run into the millions of metres, which is how a
 *    perfectly good 12 m nudge ended up displayed as "posun 483210 m". The shift is measured
 *    where the user is instead: how far the ground under [pivotX]/[pivotY] actually moved.
 *  - it never reports Mercator metres as metres. Mercator inflates by 1/cos(latitude), about
 *    1.56x over Bohemia, so the raw figure overstates every distance by half as much again.
 */
object CalibrationReadout {

    private val CS = Locale.forLanguageTag("cs")

    /** Below these the value is noise from finger tremor, not something the user chose. */
    private const val ROTATION_EPS_DEG = 0.05
    private const val SCALE_EPS = 0.001

    /**
     * @param pivotX EPSG:3857 metres of the place the shift is measured at, usually the map centre
     * @param pivotY EPSG:3857 metres, same point
     */
    fun describe(transform: Affine2D, pivotX: Double, pivotY: Double): String {
        if (transform.isIdentity()) return "Zatím beze změny"

        val groundFactor = cos(Math.toRadians(WebMercator.metersToLat(pivotY)))
        val dx = (transform.applyX(pivotX, pivotY) - pivotX) * groundFactor
        val dy = (transform.applyY(pivotX, pivotY) - pivotY) * groundFactor

        val parts = mutableListOf(String.format(CS, "posun %.0f m", hypot(dx, dy)))
        val rotationDeg = Math.toDegrees(transform.rotationRad)
        if (abs(rotationDeg) >= ROTATION_EPS_DEG) {
            parts += String.format(CS, "otočení %.1f°", rotationDeg)
        }
        val scale = transform.scale
        if (abs(scale - 1.0) >= SCALE_EPS) {
            parts += String.format(CS, "měřítko %.3f×", scale)
        }
        return parts.joinToString(" • ")
    }

    /** Same, for a stored calibration: measured at the centre of the area it covers. */
    fun describeAt(transform: Affine2D, centerLat: Double, centerLon: Double): String = describe(
        transform,
        WebMercator.lonToMeters(centerLon),
        WebMercator.latToMeters(centerLat),
    )
}
