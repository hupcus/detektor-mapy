package cz.hh.detektormapy.calibration

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A 2D affine transform stored row-major as
 *
 * ```
 * | a  b  tx |
 * | c  d  ty |
 * |  0  0  1 |
 * ```
 *
 * Everything in this app that "moves a map" ends up here. The transform operates in
 * **Web Mercator metres** (EPSG:3857), never in pixels and never in degrees, so a
 * calibration recorded at one zoom level stays valid at every other zoom level.
 *
 * The six numbers are exactly what is persisted in `LayerCalibration.matrix`.
 */
data class Affine2D(val a: Double, val b: Double, val tx: Double, val c: Double, val d: Double, val ty: Double) {

    fun applyX(x: Double, y: Double): Double = a * x + b * y + tx

    fun applyY(x: Double, y: Double): Double = c * x + d * y + ty

    fun apply(p: Pair<Double, Double>): Pair<Double, Double> = applyX(p.first, p.second) to applyY(p.first, p.second)

    val determinant: Double get() = a * d - b * c

    fun isIdentity(eps: Double = 1e-9): Boolean = abs(a - 1.0) < eps && abs(b) < eps && abs(tx) < eps &&
        abs(c) < eps && abs(d - 1.0) < eps && abs(ty) < eps

    /** Uniform scale factor, meaningful for similarity transforms. */
    val scale: Double get() = sqrt(abs(determinant))

    /** Rotation in radians, meaningful for similarity transforms. */
    val rotationRad: Double get() = atan2(c, a)

    fun inverse(): Affine2D {
        val det = determinant
        require(abs(det) > 1e-12) { "Affine transform is singular and cannot be inverted" }
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Affine2D(
            a = ia,
            b = ib,
            tx = -(ia * tx + ib * ty),
            c = ic,
            d = id,
            ty = -(ic * tx + id * ty),
        )
    }

    /** `this` applied *after* [other]. */
    fun concat(other: Affine2D): Affine2D = Affine2D(
        a = a * other.a + b * other.c,
        b = a * other.b + b * other.d,
        tx = a * other.tx + b * other.ty + tx,
        c = c * other.a + d * other.c,
        d = c * other.b + d * other.d,
        ty = c * other.tx + d * other.ty + ty,
    )

    fun toFloatArray(): DoubleArray = doubleArrayOf(a, b, tx, c, d, ty)

    companion object {
        val IDENTITY = Affine2D(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)

        fun translation(dx: Double, dy: Double) = Affine2D(1.0, 0.0, dx, 0.0, 1.0, dy)

        fun fromArray(v: DoubleArray): Affine2D {
            require(v.size == 6) { "Affine transform needs exactly 6 coefficients, got ${v.size}" }
            return Affine2D(v[0], v[1], v[2], v[3], v[4], v[5])
        }

        /**
         * Similarity transform (4 DOF: translate + rotate + uniform scale) around [pivotX]/[pivotY].
         * This is what the two-finger calibration gesture produces.
         */
        fun similarity(
            pivotX: Double,
            pivotY: Double,
            dx: Double,
            dy: Double,
            rotationRad: Double,
            scale: Double,
        ): Affine2D {
            val cosR = cos(rotationRad) * scale
            val sinR = sin(rotationRad) * scale
            // translate(-pivot) -> rotate+scale -> translate(pivot + d)
            return Affine2D(
                a = cosR,
                b = -sinR,
                tx = pivotX + dx - (cosR * pivotX - sinR * pivotY),
                c = sinR,
                d = cosR,
                ty = pivotY + dy - (sinR * pivotX + cosR * pivotY),
            )
        }

        /**
         * Pure translation (2 DOF) from >= 1 point pair -- the mean offset.
         *
         * One point is not a degenerate case to be rejected but the most common one in the
         * field: you recognise the church on the 1840s sheet, you tap the same church on the
         * ortophoto, and the whole sheet should slide over. Refusing to fit anything until a
         * second point exists is what made the editor look broken.
         */
        fun fitTranslation(pairs: List<PointPair>): Affine2D? {
            if (pairs.isEmpty()) return null
            val dx = pairs.sumOf { it.dstX - it.srcX } / pairs.size
            val dy = pairs.sumOf { it.dstY - it.srcY } / pairs.size
            return translation(dx, dy)
        }

        /**
         * Least-squares affine fit (6 DOF) from >= 3 non-collinear point pairs.
         * Source points are the *overlay* coordinates, destination the *reference* ones.
         * Returns null when the system is degenerate.
         */
        fun fitAffine(pairs: List<PointPair>): Affine2D? {
            if (pairs.size < 3) return null
            // Normal equations for [a b tx] and [c d ty] share the same 3x3 matrix.
            var sxx = 0.0
            var sxy = 0.0
            var sx = 0.0
            var syy = 0.0
            var sy = 0.0
            var n = 0.0
            var sxu = 0.0
            var syu = 0.0
            var su = 0.0
            var sxv = 0.0
            var syv = 0.0
            var sv = 0.0
            for (p in pairs) {
                val x = p.srcX
                val y = p.srcY
                val u = p.dstX
                val v = p.dstY
                sxx += x * x
                sxy += x * y
                sx += x
                syy += y * y
                sy += y
                n += 1.0
                sxu += x * u
                syu += y * u
                su += u
                sxv += x * v
                syv += y * v
                sv += v
            }
            val m = arrayOf(
                doubleArrayOf(sxx, sxy, sx),
                doubleArrayOf(sxy, syy, sy),
                doubleArrayOf(sx, sy, n),
            )
            val first = solve3x3(m, doubleArrayOf(sxu, syu, su)) ?: return null
            val second = solve3x3(m, doubleArrayOf(sxv, syv, sv)) ?: return null
            return Affine2D(first[0], first[1], first[2], second[0], second[1], second[2])
        }

        /**
         * Least-squares *similarity* fit (4 DOF) from >= 2 point pairs. Preferred over the
         * full affine fit when the user only clicked two or three points, because a 6-DOF
         * fit on three points will happily shear a map into nonsense.
         */
        fun fitSimilarity(pairs: List<PointPair>): Affine2D? {
            if (pairs.size < 2) return null
            val n = pairs.size
            val mx = pairs.sumOf { it.srcX } / n
            val my = pairs.sumOf { it.srcY } / n
            val mu = pairs.sumOf { it.dstX } / n
            val mv = pairs.sumOf { it.dstY } / n
            var sxxsyy = 0.0
            var num1 = 0.0
            var num2 = 0.0
            for (p in pairs) {
                val x = p.srcX - mx
                val y = p.srcY - my
                val u = p.dstX - mu
                val v = p.dstY - mv
                sxxsyy += x * x + y * y
                num1 += u * x + v * y
                num2 += v * x - u * y
            }
            if (sxxsyy < 1e-12) return null
            val alpha = num1 / sxxsyy
            val beta = num2 / sxxsyy
            return Affine2D(
                a = alpha,
                b = -beta,
                tx = mu - (alpha * mx - beta * my),
                c = beta,
                d = alpha,
                ty = mv - (beta * mx + alpha * my),
            )
        }

        /** RMSE of a transform over the given pairs, in the units of the destination space. */
        fun rmse(transform: Affine2D, pairs: List<PointPair>): Double {
            if (pairs.isEmpty()) return 0.0
            var sum = 0.0
            for (p in pairs) {
                val dx = transform.applyX(p.srcX, p.srcY) - p.dstX
                val dy = transform.applyY(p.srcX, p.srcY) - p.dstY
                sum += dx * dx + dy * dy
            }
            return sqrt(sum / pairs.size)
        }

        /** Per-point residual distances, used to highlight the worst GCP in the editor. */
        fun residuals(transform: Affine2D, pairs: List<PointPair>): List<Double> = pairs.map { p ->
            hypot(
                transform.applyX(p.srcX, p.srcY) - p.dstX,
                transform.applyY(p.srcX, p.srcY) - p.dstY,
            )
        }

        private fun solve3x3(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
            val m = Array(3) { r -> DoubleArray(4) { c -> if (c < 3) matrix[r][c] else rhs[r] } }
            for (col in 0 until 3) {
                var pivot = col
                for (r in col + 1 until 3) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
                if (abs(m[pivot][col]) < 1e-12) return null
                val tmp = m[col]
                m[col] = m[pivot]
                m[pivot] = tmp
                val p = m[col][col]
                for (c in col until 4) m[col][c] /= p
                for (r in 0 until 3) {
                    if (r == col) continue
                    val f = m[r][col]
                    if (f == 0.0) continue
                    for (c in col until 4) m[r][c] -= f * m[col][c]
                }
            }
            return doubleArrayOf(m[0][3], m[1][3], m[2][3])
        }
    }
}

/** One ground control point pair, in whatever coordinate space the caller is fitting in. */
data class PointPair(val srcX: Double, val srcY: Double, val dstX: Double, val dstY: Double)
