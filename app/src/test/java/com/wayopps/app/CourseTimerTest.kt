package com.wayopps.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseTimerTest {

    private val lat0 = -26.70
    private val lon0 = 153.10

    private fun ll(eastM: Double, northM: Double): LatLon {
        val (lat, lon) = Geodesy.fromEN(eastM, northM, lat0, lon0)
        return LatLon(lat, lon)
    }

    // 100 m square loop, anticlockwise from the origin: 400 m
    private val square = listOf(ll(0.0, 0.0), ll(100.0, 0.0), ll(100.0, 100.0), ll(0.0, 100.0), ll(0.0, 0.0))

    private fun course(points: List<LatLon>, loop: Boolean) =
        Course(1, "test", loop, points, PathFollower(points).totalMeters, 0)

    /** Position after walking [d] metres along [pts] (wrapping for loops). */
    private fun along(pts: List<LatLon>, d: Double): LatLon = PathFollower(pts).pointAt(d)

    @Test
    fun loopLapsAreTimedBackToBack() {
        val c = course(square, loop = true)
        val timer = CourseTimer(c)
        val laps = mutableListOf<LapResult>()
        var started = 0
        // Approach from 30 m before the start, then 3 laps at 4 m/s, then 30 m past.
        var t = 0L
        val step = 4.0
        val total = 3 * c.lengthM
        var d = -30.0
        while (d <= total + 30) {
            val p = if (d < 0) ll(d, 0.0) else along(square, d % c.lengthM)
            when (val e = timer.update(p.lat, p.lon, t)) {
                is CourseTimer.Event.LapStarted -> started++
                is CourseTimer.Event.LapCompleted -> laps.add(e.result)
                null -> {}
            }
            d += step
            t += 1000
        }
        assertEquals(3, laps.size)
        assertTrue(started >= 1)
        for (lap in laps) {
            assertEquals("lap ${lap.lapNo}", 100_000.0, lap.elapsedMs.toDouble(), 1_500.0) // 400 m at 4 m/s
            assertEquals(8, lap.splitsMs.size) // every 50 m of 400 m
        }
        // Back to back: each lap starts when the previous one ends
        assertEquals(laps[0].startedAt + laps[0].elapsedMs, laps[1].startedAt)
    }

    @Test
    fun cuttingTheCourseShortDoesNotCountALap() {
        val c = course(square, loop = true)
        val timer = CourseTimer(c)
        var t = 0L
        val events = mutableListOf<CourseTimer.Event>()
        // Start, go 60 m along, then turn back to the start
        val path = (-30..60 step 4).map { it.toDouble() } + (60 downTo -20 step 4).map { it.toDouble() }
        for (d in path) {
            val p = if (d < 0) ll(d, 0.0) else along(square, d)
            timer.update(p.lat, p.lon, t)?.let { events.add(it) }
            t += 1000
        }
        assertTrue(events.none { it is CourseTimer.Event.LapCompleted })
    }

    @Test
    fun pointToPointFinishesAtTheEndAndWaitsForStartAgain() {
        val line = listOf(ll(0.0, 0.0), ll(0.0, 300.0))
        val c = course(line, loop = false)
        val timer = CourseTimer(c)
        var t = 0L
        var result: LapResult? = null
        var d = -30.0
        while (d <= 330) {
            val p = ll(0.0, d)
            (timer.update(p.lat, p.lon, t) as? CourseTimer.Event.LapCompleted)?.let { result = it.result }
            d += 3.0
            t += 1000
        }
        assertNotNull(result)
        assertEquals(100_000.0, result!!.elapsedMs.toDouble(), 1_500.0) // 300 m at 3 m/s
        assertEquals(CourseTimer.Phase.WAITING_FOR_START, timer.phase)
    }

    @Test
    fun gapToPbInterpolatesSplits() {
        val pb = listOf(10_000L, 20_000L, 30_000L) // 50 m every 10 s
        assertEquals(-1_000L, Courses.gapToPb(pb, 75.0, 14_000)) // PB at 75 m = 15 s
        assertEquals(2_000L, Courses.gapToPb(pb, 25.0, 7_000))   // PB at 25 m = 5 s
        assertNull(Courses.gapToPb(null, 25.0, 7_000))
        assertNull(Courses.gapToPb(pb, 400.0, 7_000))
    }

    @Test
    fun loopDetection() {
        assertTrue(Courses.looksLikeLoop(square))
        assertTrue(!Courses.looksLikeLoop(listOf(ll(0.0, 0.0), ll(0.0, 300.0), ll(0.0, 600.0))))
        assertEquals(square.size + 1, Courses.normalise(square.dropLast(1) + ll(5.0, 5.0), true).size)
    }
}
