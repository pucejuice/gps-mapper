package com.wayopps.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.*

/** Mirrors pucejuice/sla tests/test_geometry.py so both apps are held to the same checks. */
class GeodesyTest {

    private val lat0 = -26.70
    private val lon0 = 153.10

    // Independent reference: Vincenty inverse on WGS84.
    private fun vincenty(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Double, Double> {
        val a = 6378137.0
        val f = 1 / 298.257223563
        val b = a * (1 - f)
        val l = Math.toRadians(lon2 - lon1)
        val u1 = atan((1 - f) * tan(Math.toRadians(lat1)))
        val u2 = atan((1 - f) * tan(Math.toRadians(lat2)))
        val su1 = sin(u1); val cu1 = cos(u1); val su2 = sin(u2); val cu2 = cos(u2)
        var lam = l
        var sinSigma: Double; var cosSigma: Double; var sigma: Double
        var cos2a: Double; var cos2sm: Double
        var iter = 0
        while (true) {
            val sl = sin(lam); val cl = cos(lam)
            sinSigma = sqrt((cu2 * sl).pow(2) + (cu1 * su2 - su1 * cu2 * cl).pow(2))
            if (sinSigma == 0.0) return 0.0 to 0.0
            cosSigma = su1 * su2 + cu1 * cu2 * cl
            sigma = atan2(sinSigma, cosSigma)
            val sinA = cu1 * cu2 * sl / sinSigma
            cos2a = 1 - sinA * sinA
            cos2sm = if (cos2a != 0.0) cosSigma - 2 * su1 * su2 / cos2a else 0.0
            val c = f / 16 * cos2a * (4 + f * (4 - 3 * cos2a))
            val prev = lam
            lam = l + (1 - c) * f * sinA * (sigma + c * sinSigma * (cos2sm + c * cosSigma * (-1 + 2 * cos2sm * cos2sm)))
            if (abs(lam - prev) < 1e-15 || ++iter > 200) break
        }
        val uSq = cos2a * (a * a - b * b) / (b * b)
        val bigA = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)))
        val bigB = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)))
        val ds = bigB * sinSigma * (cos2sm + bigB / 4 * (cosSigma * (-1 + 2 * cos2sm * cos2sm) -
            bigB / 6 * cos2sm * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2sm * cos2sm)))
        val dist = b * bigA * (sigma - ds)
        val brg = Math.toDegrees(atan2(cu2 * sin(lam), cu1 * su2 - su1 * cu2 * cos(lam)))
        return dist to (brg + 360) % 360
    }

    private fun offset(northM: Double, eastM: Double) = Geodesy.fromEN(eastM, northM, lat0, lon0)

    @Test
    fun distanceAgreesWithVincentyUnder1mmOver1km() {
        var worst = 0.0
        for (d in listOf(10.0, 50.0, 200.0, 500.0, 1000.0)) {
            for (az in 0 until 360 step 15) {
                val (lat1, lon1) = offset(d * cos(Math.toRadians(az.toDouble())), d * sin(Math.toRadians(az.toDouble())))
                val truth = vincenty(lat0, lon0, lat1, lon1).first
                worst = max(worst, abs(Geodesy.groundDistance(lat0, lon0, lat1, lon1) - truth))
            }
        }
        assertTrue("worst ${worst * 1000} mm", worst < 0.001)
    }

    @Test
    fun bearingAgreesWithVincentyUnder001Deg() {
        var worst = 0.0
        for (az in 0 until 360 step 10) {
            val (lat1, lon1) = offset(300 * cos(Math.toRadians(az.toDouble())), 300 * sin(Math.toRadians(az.toDouble())))
            val tb = vincenty(lat0, lon0, lat1, lon1).second
            val got = Geodesy.gridBearing(lat0, lon0, lat1, lon1)
            worst = max(worst, abs(GeoMath.angleDiff(tb, got)))
        }
        assertTrue("worst $worst deg", worst < 0.01)
    }

    @Test
    fun symmetricFourEpochPatternGivesCentreAndKnownSpread() {
        val t0 = 1_700_000_000_000L
        val offsets = listOf(2.0 to 0.0, 0.0 to 2.0, -2.0 to 0.0, 0.0 to -2.0) // E, N
        val fixes = offsets.flatMapIndexed { i, (de, dn) ->
            (0 until 5).map { j ->
                val (lat, lon) = offset(dn, de)
                Fix(lat, lon, 5.0, t = t0 + i * 7500L + j * 1200L)
            }
        }
        val r = Geodesy.reduceOccupation(fixes, 4, 30_000, t0, false)!!
        assertTrue(vincenty(lat0, lon0, r.lat, r.lon).first < 0.005)
        val expect = sqrt(4 * 4.0 / 3)
        assertEquals(expect, r.stats.spreadM!!, 0.01)
        assertEquals(expect / 2, r.stats.stdErrM!!, 0.01)
        assertEquals(20, r.stats.nFixes)
        assertEquals(4, r.stats.epochsUsed)
        assertNull(r.alt)
    }

    @Test
    fun poorFixesDoNotDragTheMean() {
        val t0 = 1_700_000_000_000L
        val good = (0 until 4).map { Fix(lat0, lon0, 2.0, t = t0 + it * 1000L) }
        val (bLat, bLon) = offset(0.0, 40.0)
        val bad = (0 until 4).map { Fix(bLat, bLon, 20.0, t = t0 + 4000 + it * 1000L) }
        val r = Geodesy.reduceOccupation(good + bad, 2, 8000, t0, false)!!
        assertTrue(vincenty(lat0, lon0, r.lat, r.lon).first < 0.6)
    }

    @Test
    fun singleEpochHasNoSpread() {
        val t0 = 0L
        val r = Geodesy.reduceOccupation(listOf(Fix(lat0, lon0, 3.0, t = 100)), 4, 30_000, t0, false)!!
        assertNull(r.stats.spreadM)
        assertNull(Geodesy.distUncertainty(r.stats, r.stats))
    }
}
