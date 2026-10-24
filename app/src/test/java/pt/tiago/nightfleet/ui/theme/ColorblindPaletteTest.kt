package pt.tiago.nightfleet.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Checks the colour-blind palettes: under a simulation of deuteranopia, protanopia and
 * tritanopia, the hit colour and the accent must stay at least ΔE2000 20 away from the miss colour in every
 * theme and every palette. The simulation uses the Machado, Oliveira and Fernandes (2009) matrices at
 * severity 1.0, applied to linear RGB. Everything is implemented here, without external libraries.
 */
class ColorblindPaletteTest {

    private enum class Cvd(val matrix: Array<DoubleArray>) {
        PROTANOPIA(
            arrayOf(
                doubleArrayOf(0.152286, 1.052583, -0.204868),
                doubleArrayOf(0.114503, 0.786281, 0.099216),
                doubleArrayOf(-0.003882, -0.048116, 1.051998),
            ),
        ),
        DEUTERANOPIA(
            arrayOf(
                doubleArrayOf(0.367322, 0.860646, -0.227968),
                doubleArrayOf(0.280085, 0.672501, 0.047413),
                doubleArrayOf(-0.011820, 0.042940, 0.968881),
            ),
        ),
        TRITANOPIA(
            arrayOf(
                doubleArrayOf(1.255528, -0.076749, -0.178779),
                doubleArrayOf(-0.078411, 0.930809, 0.147602),
                doubleArrayOf(0.004733, 0.691367, 0.303900),
            ),
        ),
    }

    private data class Lab(val l: Double, val a: Double, val b: Double)

    /** Minimum ΔE2000 between the pairs that the palette must keep apart. */
    private val minimumDeltaE = 20.0

    // ----- colour maths -----

    private fun srgbToLinear(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun linearToSrgb(c: Double): Double {
        val clamped = c.coerceIn(0.0, 1.0)
        return if (clamped <= 0.0031308) clamped * 12.92 else 1.055 * clamped.pow(1.0 / 2.4) - 0.055
    }

    /** Simulates [cvd] on [color]: sRGB → linear RGB → matrix → sRGB, clamped to the gamut. */
    private fun simulate(color: Color, cvd: Cvd): Color {
        val lin = doubleArrayOf(
            srgbToLinear(color.red.toDouble()),
            srgbToLinear(color.green.toDouble()),
            srgbToLinear(color.blue.toDouble()),
        )
        val out = DoubleArray(3) { row -> cvd.matrix[row].indices.sumOf { col -> cvd.matrix[row][col] * lin[col] } }
        return Color(
            red = linearToSrgb(out[0]).toFloat(),
            green = linearToSrgb(out[1]).toFloat(),
            blue = linearToSrgb(out[2]).toFloat(),
            alpha = 1f,
        )
    }

    private fun toLab(color: Color): Lab {
        val r = srgbToLinear(color.red.toDouble())
        val g = srgbToLinear(color.green.toDouble())
        val b = srgbToLinear(color.blue.toDouble())
        // sRGB D65 matrix; reference white D65.
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
        fun f(t: Double): Double = if (t > 216.0 / 24389.0) t.pow(1.0 / 3.0) else (24389.0 / 27.0 * t + 16.0) / 116.0
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return Lab(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    /** CIEDE2000 colour difference (Sharma, Wu and Dalal, 2005) with kL = kC = kH = 1. */
    private fun deltaE2000(c1: Color, c2: Color): Double = deltaE2000(toLab(c1), toLab(c2))

    private fun deltaE2000(p: Lab, q: Lab): Double {
        val c1 = hypot(p.a, p.b)
        val c2 = hypot(q.a, q.b)
        val cBar = (c1 + c2) / 2
        val cBar7 = cBar.pow(7)
        val g = 0.5 * (1 - sqrt(cBar7 / (cBar7 + 25.0.pow(7))))
        val a1 = (1 + g) * p.a
        val a2 = (1 + g) * q.a
        val cp1 = hypot(a1, p.b)
        val cp2 = hypot(a2, q.b)
        val hp1 = hue(a1, p.b)
        val hp2 = hue(a2, q.b)

        val dL = q.l - p.l
        val dC = cp2 - cp1
        val dhDeg = when {
            cp1 * cp2 == 0.0 -> 0.0
            abs(hp2 - hp1) <= 180 -> hp2 - hp1
            hp2 - hp1 > 180 -> hp2 - hp1 - 360
            else -> hp2 - hp1 + 360
        }
        val dH = 2 * sqrt(cp1 * cp2) * sin(Math.toRadians(dhDeg / 2))

        val lBar = (p.l + q.l) / 2
        val cpBar = (cp1 + cp2) / 2
        val hBar = when {
            cp1 * cp2 == 0.0 -> hp1 + hp2
            abs(hp1 - hp2) <= 180 -> (hp1 + hp2) / 2
            hp1 + hp2 < 360 -> (hp1 + hp2 + 360) / 2
            else -> (hp1 + hp2 - 360) / 2
        }
        val t = 1 - 0.17 * cos(rad(hBar - 30)) + 0.24 * cos(rad(2 * hBar)) +
            0.32 * cos(rad(3 * hBar + 6)) - 0.20 * cos(rad(4 * hBar - 63))
        val dTheta = 30 * exp(-((hBar - 275) / 25).pow(2))
        val cpBar7 = cpBar.pow(7)
        val rc = 2 * sqrt(cpBar7 / (cpBar7 + 25.0.pow(7)))
        val lShift = (lBar - 50).pow(2)
        val sL = 1 + 0.015 * lShift / sqrt(20 + lShift)
        val sC = 1 + 0.045 * cpBar
        val sH = 1 + 0.015 * cpBar * t
        val rt = -sin(rad(2 * dTheta)) * rc
        val termL = dL / sL
        val termC = dC / sC
        val termH = dH / sH
        return sqrt(termL * termL + termC * termC + termH * termH + rt * termC * termH)
    }

    private fun hue(a: Double, b: Double): Double {
        if (a == 0.0 && b == 0.0) return 0.0
        val deg = Math.toDegrees(atan2(b, a))
        return if (deg < 0) deg + 360 else deg
    }

    private fun rad(deg: Double): Double = Math.toRadians(deg)

    // ----- tests -----

    /** Every palette (the normal one included) under each deficiency, for hit/miss and accent/miss, in each theme. */
    @Test
    fun hitAndAccentStayApartFromMissUnderEveryCvdSimulation() {
        val report = StringBuilder("palette/theme/cvd pair: dE2000\n")
        val failures = mutableListOf<String>()
        for (themeId in ThemeId.entries) for (palette in ColorblindPalette.entries) {
            for (grandAdmiral in listOf(false, true)) {
                if (grandAdmiral && palette != ColorblindPalette.NORMAL) continue
                val colors = fleetColors(themeId, palette, grandAdmiral)
                val label = "$themeId/$palette${if (grandAdmiral) "/grandAdmiral" else ""}"
                for (cvd in Cvd.entries) {
                    val miss = simulate(colors.miss, cvd)
                    for ((pair, colour) in listOf("hit" to colors.hit, "accent" to colors.accent)) {
                        val dE = deltaE2000(simulate(colour, cvd), miss)
                        report.append("%-36s %-12s %-5s-miss %6.1f\n".format(label, cvd, pair, dE))
                        if (dE < minimumDeltaE) failures += "$label $cvd $pair/miss dE=%.1f".format(dE)
                    }
                }
            }
        }
        println(report)
        assertTrue("Pairs under ΔE2000 $minimumDeltaE:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun deltaE2000MatchesSharmaReferencePairs() {
        // Reference pairs from Sharma, Wu and Dalal (2005), table 1 (Lab values, kL = kC = kH = 1).
        val pairs = listOf(
            Lab(50.0, 2.6772, -79.7751) to Lab(50.0, 0.0, -82.7485) to 2.0425,
            Lab(50.0, 3.1571, -77.2803) to Lab(50.0, 0.0, -82.7485) to 2.8615,
            Lab(50.0, 2.8361, -74.0200) to Lab(50.0, 0.0, -82.7485) to 3.4412,
            Lab(50.0, -1.3802, -84.2814) to Lab(50.0, 0.0, -82.7485) to 1.0000,
        )
        for ((labs, expected) in pairs) {
            val (p, q) = labs
            assertTrue(
                "dE2000 of $p and $q is ${deltaE2000(p, q)}, expected $expected",
                abs(deltaE2000(p, q) - expected) < 0.001,
            )
        }
    }

    @Test
    fun simulationLeavesGreysUnchanged() {
        // Every CVD matrix keeps neutral greys neutral, so a grey never changes hue under simulation.
        for (cvd in Cvd.entries) {
            for (level in listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)) {
                val grey = Color(level, level, level)
                val out = simulate(grey, cvd)
                assertTrue("$cvd grey $level red", abs(out.red - level) < 0.01f)
                assertTrue("$cvd grey $level blue", abs(out.blue - level) < 0.01f)
            }
        }
    }

    private fun assertDelta(name: String, value: Double, expected: Double) =
        assertTrue("$name: $value vs $expected", abs(value - expected) < 0.01)

    @Test
    fun deltaEIsZeroForIdenticalColours() {
        assertDelta("identical", deltaE2000(Color(0xFFD2493A), Color(0xFFD2493A)), 0.0)
    }
}
