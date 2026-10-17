package com.example.geosurvey

import kotlin.math.*

// Pure Kotlin (no Android types) so it can be unit tested on the JVM.
// Ported from the SLA web app (pucejuice/sla) so both give the same numbers.

/** One GNSS fix. [acc] is the reported 68% horizontal radius in metres. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val acc: Double,
    val alt: Double? = null,
    val altAcc: Double? = null,
    val t: Long
)

data class OccupationStats(
    val nFixes: Int,
    val epochsUsed: Int,
    val epochsRequested: Int,
    val dwellS: Double,
    /** Sample DRMS of the epoch means about the combined position: the quoted uncertainty. */
    val spreadM: Double?,
    val stdErrM: Double?,
    val reportedAccM: Double,
    val rejected: Int = 0,
    val gateM: Double? = null,
    val altN: Int = 0,
    val altSpreadM: Double? = null
)

data class Reduction(val lat: Double, val lon: Double, val alt: Double?, val stats: OccupationStats)

/**
 * WGS84 local tangent plane. A spherical haversine (R = 6 371 008.8 m) is out by
 * about +0.36% north-south and -0.18% east-west at latitude -27, which is
 * systematic and azimuth-dependent; the local radii of curvature are sub-mm here.
 */
object Geodesy {
    private const val A = 6378137.0
    private const val F = 1 / 298.257223563
    private val E2 = F * (2 - F)

    private fun curvature(latDeg: Double): Pair<Double, Double> {
        val s = sin(Math.toRadians(latDeg))
        val w2 = 1 - E2 * s * s
        val m = A * (1 - E2) / (w2 * sqrt(w2))   // meridional
        val n = A / sqrt(w2)                     // prime vertical
        return m to n
    }

    /** Geographic -> local (east, north) metres about origin. */
    fun toEN(lat: Double, lon: Double, oLat: Double, oLon: Double): Pair<Double, Double> {
        val (m, n) = curvature(oLat)
        return Pair(
            Math.toRadians(lon - oLon) * n * cos(Math.toRadians(oLat)),
            Math.toRadians(lat - oLat) * m
        )
    }

    /** Local (east, north) metres about origin -> geographic (lat, lon). */
    fun fromEN(e: Double, nn: Double, oLat: Double, oLon: Double): Pair<Double, Double> {
        val (m, n) = curvature(oLat)
        return Pair(
            oLat + Math.toDegrees(nn / m),
            oLon + Math.toDegrees(e / (n * cos(Math.toRadians(oLat))))
        )
    }

    /** Horizontal ground distance in metres, tangent plane at the midpoint. */
    fun groundDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val oLat = (lat1 + lat2) / 2
        val oLon = (lon1 + lon2) / 2
        val (ae, an) = toEN(lat1, lon1, oLat, oLon)
        val (be, bn) = toEN(lat2, lon2, oLat, oLon)
        return hypot(be - ae, bn - an)
    }

    /** Grid bearing 1 -> 2, degrees clockwise from north. */
    fun gridBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val oLat = (lat1 + lat2) / 2
        val oLon = (lon1 + lon2) / 2
        val (ae, an) = toEN(lat1, lon1, oLat, oLon)
        val (be, bn) = toEN(lat2, lon2, oLat, oLon)
        return (Math.toDegrees(atan2(be - ae, bn - an)) + 360.0) % 360.0
    }

    /**
     * Occupation reduction. Every accepted fix contributes to the position, weighted
     * 1/accuracy^2. The reported uncertainty is the spread between time-separated epoch
     * means, NOT the scatter of consecutive fixes: consumer GNSS error is dominated by
     * bias with a correlation time of tens of seconds, so fixes a second apart cluster
     * around the same wrong point. Only the between-epoch spread sees that bias.
     */
    fun reduceOccupation(fixes: List<Fix>, nEpochs: Int, dwellMs: Long, t0: Long, wantAlt: Boolean): Reduction? {
        if (fixes.isEmpty()) return null
        val oLat = fixes[0].lat
        val oLon = fixes[0].lon
        val span = dwellMs.toDouble() / nEpochs

        val buckets = List(nEpochs) { mutableListOf<Fix>() }
        for (f in fixes) {
            val k = floor((f.t - t0) / span).toInt().coerceIn(0, nEpochs - 1)
            buckets[k].add(f)
        }

        data class Mean(val e: Double, val n: Double)

        // Accuracy floored so a bogus 0 cannot take infinite weight.
        fun wmean(arr: List<Fix>): Mean {
            var we = 0.0
            var wn = 0.0
            var w = 0.0
            for (f in arr) {
                val wt = 1 / max(f.acc, 0.5).pow(2)
                val (e, n) = toEN(f.lat, f.lon, oLat, oLon)
                we += wt * e
                wn += wt * n
                w += wt
            }
            return Mean(we / w, wn / w)
        }

        val all = wmean(fixes)
        val (lat, lon) = fromEN(all.e, all.n, oLat, oLon)
        val epochMeans = buckets.filter { it.isNotEmpty() }.map { wmean(it) }
        val k = epochMeans.size

        var spread: Double? = null
        var stdErr: Double? = null
        if (k >= 2) {
            var ss = 0.0
            for (m in epochMeans) ss += (m.e - all.e).pow(2) + (m.n - all.n).pow(2)
            spread = sqrt(ss / (k - 1))
            stdErr = spread / sqrt(k.toDouble())
        }

        val accs = fixes.map { it.acc }.sorted()
        val medAcc = if (accs.size % 2 == 1) accs[(accs.size - 1) / 2]
        else (accs[accs.size / 2 - 1] + accs[accs.size / 2]) / 2

        var alt: Double? = null
        var altSpread: Double? = null
        var altN = 0
        if (wantAlt) {
            val altWeight = { f: Fix -> 1 / max(f.altAcc ?: (f.acc * 2), 1.0).pow(2) }
            val withAlt = fixes.filter { it.alt != null }
            altN = withAlt.size
            if (altN > 0) {
                val wMean = { arr: List<Fix> ->
                    var sa = 0.0
                    var w = 0.0
                    for (f in arr) {
                        val wt = altWeight(f); sa += wt * f.alt!!; w += wt
                    }
                    sa / w
                }
                val a = wMean(withAlt)
                alt = a
                val altEpochs = buckets.map { b -> b.filter { it.alt != null } }.filter { it.isNotEmpty() }
                if (altEpochs.size >= 2) {
                    altSpread = sqrt(altEpochs.sumOf { (wMean(it) - a).pow(2) } / (altEpochs.size - 1))
                }
            }
        }

        return Reduction(
            lat, lon, alt,
            OccupationStats(
                nFixes = fixes.size,
                epochsUsed = k,
                epochsRequested = nEpochs,
                dwellS = (fixes.last().t - t0) / 1000.0,
                spreadM = spread,
                stdErrM = stdErr,
                reportedAccM = medAcc,
                altN = altN,
                altSpreadM = altSpread
            )
        )
    }

    /** Line uncertainty: the two endpoint spreads in quadrature. */
    fun distUncertainty(a: OccupationStats, b: OccupationStats): Double? {
        val sa = a.spreadM ?: return null
        val sb = b.spreadM ?: return null
        return sqrt(sa * sa + sb * sb)
    }
}
