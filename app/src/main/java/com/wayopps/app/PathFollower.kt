package com.wayopps.app

import kotlin.math.*

// Pure Kotlin (no Android types) so the guidance maths can be unit tested on the JVM.

data class LatLon(val lat: Double, val lon: Double)

object GeoMath {
    private const val EARTH_RADIUS_M = 6371008.8

    fun haversineMeters(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial true bearing from a to b, 0..360 degrees clockwise from north. */
    fun bearingDegrees(a: LatLon, b: LatLon): Double {
        val phi1 = Math.toRadians(a.lat)
        val phi2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Signed smallest difference (to - from), in -180..180. Positive = clockwise (turn right). */
    fun angleDiff(fromDeg: Double, toDeg: Double): Double {
        var d = (toDeg - fromDeg) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    /** Local tangent plane (east, north) metres relative to origin. Accurate for a few km. */
    fun toEnu(origin: LatLon, p: LatLon): Pair<Double, Double> {
        val latRad = Math.toRadians(origin.lat)
        val mPerDegLat = 111132.92 - 559.82 * cos(2 * latRad) + 1.175 * cos(4 * latRad)
        val mPerDegLon = 111412.84 * cos(latRad) - 93.5 * cos(3 * latRad)
        return Pair((p.lon - origin.lon) * mPerDegLon, (p.lat - origin.lat) * mPerDegLat)
    }

    fun interpolate(a: LatLon, b: LatLon, t: Double): LatLon =
        LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
}

data class FollowGuidance(
    val target: LatLon,
    val snapped: LatLon,
    val bearingToTargetDegrees: Double,
    val distanceToTargetMeters: Double,
    /** Signed offset from the path: positive = right of the direction of travel. */
    val crossTrackMeters: Double,
    val distanceAlongMeters: Double,
    val remainingMeters: Double,
    val totalMeters: Double,
    val pathBearingDegrees: Double,
    val arrived: Boolean
) {
    val progress: Double get() = if (totalMeters > 0) (distanceAlongMeters / totalMeters).coerceIn(0.0, 1.0) else 1.0
}

/**
 * Guides the user along a polyline. Each fix is snapped to the closest point on the path,
 * then the arrow points at a "carrot" a short look-ahead distance further along the path,
 * which naturally steers the user back onto the line when they drift off it.
 */
class PathFollower(
    path: List<LatLon>,
    private val lookAheadMeters: Double = 8.0,
    private val arrivalRadiusMeters: Double = 5.0,
    /** How far behind / ahead of the last progress to search first (stops jumps on loops). */
    private val windowBackMeters: Double = 30.0,
    private val windowAheadMeters: Double = 150.0
) {
    val points: List<LatLon> = path.zipWithNext().filter { (a, b) -> a != b }.map { it.first } +
        listOfNotNull(path.lastOrNull())

    /** Cumulative distance at each vertex. */
    private val cumulative: DoubleArray = DoubleArray(points.size).also { cum ->
        for (i in 1 until points.size) cum[i] = cum[i - 1] + GeoMath.haversineMeters(points[i - 1], points[i])
    }

    val totalMeters: Double get() = cumulative.lastOrNull() ?: 0.0

    private var lastAlong: Double? = null
    private var hasArrived = false

    private data class Snap(val segment: Int, val t: Double, val offset: Double, val along: Double)

    fun update(lat: Double, lon: Double): FollowGuidance? {
        if (points.size < 2) return null
        val here = LatLon(lat, lon)

        val global = nearest(here, 0.0, Double.MAX_VALUE, null)
        val previous = lastAlong
        val snap = if (previous == null) {
            global
        } else {
            val windowed = nearest(here, previous - windowBackMeters, previous + windowAheadMeters, previous)
            // Stay on the expected stretch unless another part of the path is clearly closer.
            if (windowed != null && windowed.offset <= (global?.offset ?: Double.MAX_VALUE) + 15.0) windowed else global
        } ?: return null

        lastAlong = snap.along

        val snappedPoint = GeoMath.interpolate(points[snap.segment], points[snap.segment + 1], snap.t)
        val target = pointAt(snap.along + lookAheadMeters)
        val pathBearing = GeoMath.bearingDegrees(points[snap.segment], points[snap.segment + 1])

        val bearingToSnap = GeoMath.bearingDegrees(snappedPoint, here)
        val side = if (GeoMath.angleDiff(pathBearing, bearingToSnap) >= 0) 1.0 else -1.0
        val crossTrack = side * snap.offset

        val remaining = (totalMeters - snap.along).coerceAtLeast(0.0)
        val distanceToEnd = GeoMath.haversineMeters(here, points.last())
        if (remaining <= arrivalRadiusMeters && distanceToEnd <= arrivalRadiusMeters * 2) hasArrived = true

        val distToTarget = GeoMath.haversineMeters(here, target)
        val bearingToTarget = if (distToTarget < 0.5) pathBearing else GeoMath.bearingDegrees(here, target)

        return FollowGuidance(
            target = target,
            snapped = snappedPoint,
            bearingToTargetDegrees = bearingToTarget,
            distanceToTargetMeters = distToTarget,
            crossTrackMeters = crossTrack,
            distanceAlongMeters = snap.along,
            remainingMeters = remaining,
            totalMeters = totalMeters,
            pathBearingDegrees = pathBearing,
            arrived = hasArrived
        )
    }

    fun reset() {
        lastAlong = null
        hasArrived = false
    }

    /** Start tracking from a known progress (e.g. 0 at the start of a loop, where start and end overlap). */
    fun seed(alongMeters: Double) {
        lastAlong = alongMeters
        hasArrived = false
    }

    /**
     * Closest point on the path within [fromAlong, toAlong]. When [previous] progress is known,
     * candidates that jump backwards or far forwards are penalised, so overlapping stretches
     * (out-and-back routes, loops) keep the progress the user has already made.
     */
    private fun nearest(here: LatLon, fromAlong: Double, toAlong: Double, previous: Double?): Snap? {
        var best: Snap? = null
        var bestScore = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            if (cumulative[i + 1] < fromAlong || cumulative[i] > toAlong) continue
            val (ax, ay) = GeoMath.toEnu(here, points[i])
            val (bx, by) = GeoMath.toEnu(here, points[i + 1])
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            // Here is the ENU origin (0, 0); project it onto segment a->b.
            val t = if (len2 > 0) ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val px = ax + t * dx
            val py = ay + t * dy
            val offset = sqrt(px * px + py * py)
            val along = cumulative[i] + t * (cumulative[i + 1] - cumulative[i])
            val score = if (previous == null) offset else {
                offset + 0.5 * max(0.0, previous - along) + 0.5 * max(0.0, along - previous - 20.0)
            }
            if (score < bestScore) {
                bestScore = score
                best = Snap(i, t, offset, along)
            }
        }
        return best
    }

    /** Point at a given distance along the path (clamped to the ends). */
    fun pointAt(alongMeters: Double): LatLon {
        if (alongMeters <= 0.0) return points.first()
        if (alongMeters >= totalMeters) return points.last()
        var i = 0
        while (i < points.size - 2 && cumulative[i + 1] < alongMeters) i++
        val segLen = cumulative[i + 1] - cumulative[i]
        val t = if (segLen > 0) (alongMeters - cumulative[i]) / segLen else 0.0
        return GeoMath.interpolate(points[i], points[i + 1], t)
    }
}
