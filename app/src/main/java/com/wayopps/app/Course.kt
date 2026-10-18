package com.wayopps.app

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

// Pure Kotlin (no Android types) so lap timing can be unit tested on the JVM.

/** A saved route to time yourself on: a loop (finish = start) or point to point. */
data class Course(
    val id: Long,
    val name: String,
    val loop: Boolean,
    val points: List<LatLon>,
    val lengthM: Double,
    val createdAt: Long
)

data class LapResult(
    val lapNo: Int,
    val startedAt: Long,
    val elapsedMs: Long,
    val distanceM: Double,
    /** Elapsed ms at every [CourseTimer.SPLIT_M] metres along the course (for comparing with a PB). */
    val splitsMs: List<Long>,
    val courseId: Long?
)

object Courses {
    /** A track whose ends are within this distance is treated as a loop. */
    const val LOOP_CLOSE_M = 40.0

    fun looksLikeLoop(points: List<LatLon>): Boolean =
        points.size > 2 && GeoMath.haversineMeters(points.first(), points.last()) <= LOOP_CLOSE_M

    /** Close a loop exactly so start and finish are the same point. */
    fun normalise(points: List<LatLon>, loop: Boolean): List<LatLon> =
        if (loop && points.size > 2 && points.first() != points.last()) points + points.first() else points

    /**
     * Time ahead (-) or behind (+) the PB at [progressM] along the course, ms, interpolating
     * the PB's split times. Null when there is no PB split to compare against yet.
     */
    fun gapToPb(pbSplitsMs: List<Long>?, progressM: Double, elapsedMs: Long): Long? {
        if (pbSplitsMs.isNullOrEmpty() || progressM <= 0) return null
        val k = progressM / CourseTimer.SPLIT_M
        val i = floor(k).toInt()
        if (i >= pbSplitsMs.size) return null
        val before = if (i == 0) 0L else pbSplitsMs[i - 1]
        val after = pbSplitsMs[i]
        val pbAt = before + ((after - before) * (k - i)).toLong()
        return elapsedMs - pbAt
    }
}

/**
 * Automatic lap timing on a [Course]. A lap starts at your closest pass to the start point
 * (within [radiusM]) and finishes at your closest pass to the finish point (the start
 * again for a loop) once you've covered at least [completeFraction] of the course. On a
 * loop the next lap starts at the same instant, so laps run back to back.
 */
class CourseTimer(
    private val course: Course,
    private val radiusM: Double = 20.0,
    private val completeFraction: Double = 0.8
) {
    companion object {
        const val SPLIT_M = 50.0
        /** Further than this from the course line counts as off course. */
        const val OFF_COURSE_M = 40.0
        /** A pass is decided once you are this much further away than your closest point. */
        private const val PASS_HYSTERESIS_M = 3.0
    }

    sealed class Event {
        data class LapStarted(val lapNo: Int, val atMs: Long) : Event()
        data class LapCompleted(val result: LapResult) : Event()
    }

    enum class Phase { WAITING_FOR_START, RUNNING }

    var phase = Phase.WAITING_FOR_START
        private set
    var lapNo = 0
        private set
    var lapStartMs = 0L
        private set
    var progressM = 0.0
        private set
    var offCourse = false
        private set

    private val follower = PathFollower(course.points, lookAheadMeters = 10.0, arrivalRadiusMeters = 0.0)
    private val start = course.points.first()
    private val finish = course.points.last()
    private var maxProgress = 0.0
    private val splits = mutableListOf<Long>()

    // Closest-approach tracking for the start / finish "gate"
    private var gateMin = Double.MAX_VALUE
    private var gateMinT = 0L

    fun elapsedMs(nowMs: Long) = if (phase == Phase.RUNNING) nowMs - lapStartMs else 0L

    fun update(lat: Double, lon: Double, t: Long): Event? {
        val here = LatLon(lat, lon)
        return when (phase) {
            Phase.WAITING_FOR_START -> {
                val passT = gatePass(GeoMath.haversineMeters(here, start), t) ?: return null
                beginLap(passT)
                Event.LapStarted(lapNo, passT)
            }
            Phase.RUNNING -> running(here, t)
        }
    }

    private fun running(here: LatLon, t: Long): Event? {
        follower.update(here.lat, here.lon)?.let { g ->
            progressM = g.distanceAlongMeters
            offCourse = abs(g.crossTrackMeters) > OFF_COURSE_M
            if (!offCourse && progressM > maxProgress) {
                // Record a split for every SPLIT_M boundary passed (interpolated in time)
                val prevMax = maxProgress
                maxProgress = progressM
                var next = (splits.size + 1) * SPLIT_M
                while (next <= maxProgress && next <= course.lengthM) {
                    val frac = if (maxProgress > prevMax) (next - prevMax) / (maxProgress - prevMax) else 1.0
                    val lastT = lastFixT ?: t
                    splits.add((lastT + (t - lastT) * frac.coerceIn(0.0, 1.0)).toLong() - lapStartMs)
                    next = (splits.size + 1) * SPLIT_M
                }
            }
        }
        lastFixT = t
        if (maxProgress < course.lengthM * completeFraction) return null

        val passT = gatePass(GeoMath.haversineMeters(here, finish), t) ?: return null
        val result = LapResult(
            lapNo = lapNo,
            startedAt = lapStartMs,
            elapsedMs = passT - lapStartMs,
            distanceM = course.lengthM,
            splitsMs = splits.toList(),
            courseId = course.id
        )
        if (course.loop) beginLap(passT) else phase = Phase.WAITING_FOR_START
        return Event.LapCompleted(result)
    }

    private var lastFixT: Long? = null

    /**
     * Returns the time of the closest approach once it is decided (you've moved
     * [PASS_HYSTERESIS_M] further away again, or left the gate radius), else null.
     */
    private fun gatePass(d: Double, t: Long): Long? {
        if (d <= radiusM && d < gateMin) {
            gateMin = d
            gateMinT = t
            return null
        }
        if (gateMin == Double.MAX_VALUE) return null
        if (d > gateMin + PASS_HYSTERESIS_M || d > radiusM) {
            val passT = gateMinT
            gateMin = Double.MAX_VALUE
            return passT
        }
        return null
    }

    private fun beginLap(atMs: Long) {
        lapNo++
        lapStartMs = atMs
        phase = Phase.RUNNING
        maxProgress = 0.0
        progressM = 0.0
        splits.clear()
        lastFixT = null
        follower.seed(0.0)
        gateMin = Double.MAX_VALUE
    }

    /** Fraction of the current lap covered, 0..1. */
    fun lapFraction(): Double = if (course.lengthM > 0) (max(maxProgress, 0.0) / course.lengthM).coerceIn(0.0, 1.0) else 0.0
}
