package cz.hh.detektormapy.calibration

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random

class Affine2DTest {

    @Test
    fun `identity leaves points untouched`() {
        val t = Affine2D.IDENTITY
        assertThat(t.isIdentity()).isTrue()
        assertThat(t.applyX(123.456, -987.0)).isWithin(1e-9).of(123.456)
        assertThat(t.applyY(123.456, -987.0)).isWithin(1e-9).of(-987.0)
    }

    @Test
    fun `translation moves by exactly the offset`() {
        val t = Affine2D.translation(50.0, -20.0)
        assertThat(t.applyX(0.0, 0.0)).isWithin(1e-9).of(50.0)
        assertThat(t.applyY(0.0, 0.0)).isWithin(1e-9).of(-20.0)
        assertThat(t.isIdentity()).isFalse()
    }

    @Test
    fun `inverse round-trips arbitrary points`() {
        val t = Affine2D(1.2, 0.3, 400.0, -0.25, 0.9, -180.0)
        val inv = t.inverse()
        val rnd = Random(42)
        repeat(200) {
            val x = rnd.nextDouble(-2e6, 2e6)
            val y = rnd.nextDouble(-2e6, 2e6)
            val fx = t.applyX(x, y)
            val fy = t.applyY(x, y)
            assertThat(inv.applyX(fx, fy)).isWithin(1e-6).of(x)
            assertThat(inv.applyY(fx, fy)).isWithin(1e-6).of(y)
        }
    }

    @Test
    fun `concat equals sequential application`() {
        val a = Affine2D(1.1, 0.2, 10.0, -0.1, 0.95, -5.0)
        val b = Affine2D(0.8, -0.3, -40.0, 0.4, 1.05, 7.0)
        val combined = a.concat(b)
        val x = 1234.5
        val y = -6789.0
        val bx = b.applyX(x, y)
        val by = b.applyY(x, y)
        assertThat(combined.applyX(x, y)).isWithin(1e-7).of(a.applyX(bx, by))
        assertThat(combined.applyY(x, y)).isWithin(1e-7).of(a.applyY(bx, by))
    }

    @Test
    fun `similarity around pivot keeps the pivot fixed when there is no offset`() {
        val t = Affine2D.similarity(
            pivotX = 1_500_000.0,
            pivotY = 6_400_000.0,
            dx = 0.0,
            dy = 0.0,
            rotationRad = PI / 7,
            scale = 1.35,
        )
        assertThat(t.applyX(1_500_000.0, 6_400_000.0)).isWithin(1e-6).of(1_500_000.0)
        assertThat(t.applyY(1_500_000.0, 6_400_000.0)).isWithin(1e-6).of(6_400_000.0)
    }

    @Test
    fun `similarity reports back its own rotation and scale`() {
        val t = Affine2D.similarity(0.0, 0.0, 0.0, 0.0, 0.4, 2.0)
        assertThat(t.scale).isWithin(1e-9).of(2.0)
        assertThat(t.rotationRad).isWithin(1e-9).of(0.4)
    }

    @Test
    fun `fitAffine recovers a known transform exactly`() {
        val truth = Affine2D(1.03, -0.07, 250.0, 0.05, 0.98, -410.0)
        val src = listOf(0.0 to 0.0, 1000.0 to 0.0, 0.0 to 1000.0, 700.0 to 900.0)
        val pairs = src.map { (x, y) ->
            PointPair(x, y, truth.applyX(x, y), truth.applyY(x, y))
        }
        val fitted = Affine2D.fitAffine(pairs)!!
        assertThat(fitted.a).isWithin(1e-6).of(truth.a)
        assertThat(fitted.b).isWithin(1e-6).of(truth.b)
        assertThat(fitted.tx).isWithin(1e-4).of(truth.tx)
        assertThat(fitted.c).isWithin(1e-6).of(truth.c)
        assertThat(fitted.d).isWithin(1e-6).of(truth.d)
        assertThat(fitted.ty).isWithin(1e-4).of(truth.ty)
        assertThat(Affine2D.rmse(fitted, pairs)).isLessThan(1e-4)
    }

    @Test
    fun `fitAffine needs three points and rejects collinear input`() {
        assertThat(Affine2D.fitAffine(emptyList())).isNull()
        assertThat(
            Affine2D.fitAffine(listOf(PointPair(0.0, 0.0, 1.0, 1.0), PointPair(1.0, 1.0, 2.0, 2.0))),
        ).isNull()
        val collinear = (0..4).map { i ->
            val x = i.toDouble()
            PointPair(x, 2 * x, x + 1, 2 * x + 1)
        }
        assertThat(Affine2D.fitAffine(collinear)).isNull()
    }

    @Test
    fun `fitSimilarity recovers rotation and scale from two points`() {
        val truth = Affine2D.similarity(0.0, 0.0, 120.0, -30.0, 0.25, 1.4)
        val src = listOf(0.0 to 0.0, 500.0 to 250.0)
        val pairs = src.map { (x, y) -> PointPair(x, y, truth.applyX(x, y), truth.applyY(x, y)) }
        val fitted = Affine2D.fitSimilarity(pairs)!!
        assertThat(fitted.scale).isWithin(1e-6).of(1.4)
        assertThat(fitted.rotationRad).isWithin(1e-6).of(0.25)
        assertThat(Affine2D.rmse(fitted, pairs)).isLessThan(1e-6)
    }

    @Test
    fun `fitSimilarity never shears unlike fitAffine`() {
        // Three points with deliberately inconsistent, shear-like displacements.
        val pairs = listOf(
            PointPair(0.0, 0.0, 0.0, 0.0),
            PointPair(100.0, 0.0, 100.0, 0.0),
            PointPair(0.0, 100.0, 40.0, 100.0),
        )
        val sim = Affine2D.fitSimilarity(pairs)!!
        // A similarity has a == d and b == -c by construction.
        assertThat(abs(sim.a - sim.d)).isLessThan(1e-9)
        assertThat(abs(sim.b + sim.c)).isLessThan(1e-9)

        val aff = Affine2D.fitAffine(pairs)!!
        assertThat(abs(aff.b + aff.c)).isGreaterThan(1e-6)
    }

    @Test
    fun `rmse and residuals agree`() {
        val t = Affine2D.translation(3.0, 4.0)
        val pairs = listOf(
            PointPair(0.0, 0.0, 0.0, 0.0),
            PointPair(10.0, 10.0, 10.0, 10.0),
        )
        assertThat(Affine2D.residuals(t, pairs)).containsExactly(5.0, 5.0)
        assertThat(Affine2D.rmse(t, pairs)).isWithin(1e-9).of(5.0)
    }

    @Test
    fun `array round-trip preserves all six coefficients`() {
        val t = Affine2D(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)
        assertThat(Affine2D.fromArray(t.toFloatArray())).isEqualTo(t)
    }

    @Test
    fun `inverting a singular transform fails loudly`() {
        val singular = Affine2D(1.0, 2.0, 0.0, 2.0, 4.0, 0.0)
        try {
            singular.inverse()
            throw AssertionError("Expected an IllegalArgumentException for a singular transform")
        } catch (expected: IllegalArgumentException) {
            assertThat(expected).hasMessageThat().contains("singular")
        }
    }
}
