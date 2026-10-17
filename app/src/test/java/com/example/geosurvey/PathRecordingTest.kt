package com.example.geosurvey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PathRecordingTest {

    private val lat0 = -26.70
    private val lon0 = 153.10

    private fun pt(northM: Double, eastM: Double, acc: Double = 3.0, t: Long = 0): TrackPoint {
        val (lat, lon) = Geodesy.fromEN(eastM, northM, lat0, lon0)
        return TrackPoint(lat, lon, null, acc, t)
    }

    @Test
    fun firstFixWithinGateIsAccepted() {
        assertEquals(TrackDecision.Accept(0.0), PathRecording.evaluate(null, pt(0.0, 0.0), 10.0))
    }

    @Test
    fun fixWorseThanGateIsRejected() {
        assertEquals(TrackDecision.RejectAccuracy, PathRecording.evaluate(null, pt(0.0, 0.0, acc = 12.0), 10.0))
    }

    @Test
    fun stationaryJitterIsNotLogged() {
        val d = PathRecording.evaluate(pt(0.0, 0.0), pt(0.6, 0.3, acc = 3.67), 10.0)
        assertEquals(TrackDecision.TooClose, d)
    }

    @Test
    fun walkingAtOneHertzIsLoggedAndSummed() {
        // 1.4 m/s at 1 Hz with a ±3.7 m phone fix: every fix moves more than the step threshold.
        var last: TrackPoint? = null
        var total = 0.0
        var logged = 0
        for (i in 0..60) {
            val fix = pt(i * 1.6, 0.0, acc = 3.7, t = i * 1000L)
            val d = PathRecording.evaluate(last, fix, 10.0)
            if (d is TrackDecision.Accept) {
                total += d.addedMeters
                last = fix
                logged++
            }
        }
        assertTrue("logged $logged", logged > 50)
        assertEquals(96.0, total, 0.01)
    }

    @Test
    fun slowWalkStillAccumulatesAgainstLastLoggedPoint() {
        // 0.5 m per fix is under the step, but distance is measured from the last LOGGED point.
        var last: TrackPoint? = pt(0.0, 0.0, acc = 3.0)
        var total = 0.0
        for (i in 1..40) {
            val fix = pt(i * 0.5, 0.0, acc = 3.0)
            val d = PathRecording.evaluate(last, fix, 10.0)
            if (d is TrackDecision.Accept) { total += d.addedMeters; last = fix }
        }
        assertEquals(19.2, total, 1.3) // within one step of the 20 m walked
    }

    @Test
    fun totalOfAnLShapedTrack() {
        val pts = listOf(pt(0.0, 0.0), pt(30.0, 0.0), pt(30.0, 40.0))
        assertEquals(70.0, PathRecording.totalMeters(pts), 0.001)
    }

    private fun line(id: String, from: String, to: String, d: Double, linked: Boolean = false) =
        MeasuredLine(id, from, to, d, 0.0, null, linked, 0)

    @Test
    fun chainFollowsConsecutiveLegsBackFromTheEnd() {
        val lines = listOf(
            line("a", "S1", "S2", 10.0),
            line("b", "S2", "S3", 20.0),
            line("x", "S2", "S9", 99.0),   // radial shot from S2, not part of the S4 chain
            line("c", "S3", "S4", 30.0)
        )
        val chain = chainEndingAt(lines, "S4")
        assertEquals(listOf("a", "b", "c"), chain.map { it.id })
        assertEquals(60.0, chain.sumOf { it.distanceM }, 1e-9)
    }

    @Test
    fun linkedLinesAndCyclesDoNotExtendTheChain() {
        val lines = listOf(
            line("a", "S1", "S2", 10.0),
            line("l", "S4", "S1", 5.0, linked = true),
            line("b", "S2", "S1", 10.0),  // back to S1: cycle
        )
        // S2->S1 is a leg; S1->S2 before it would revisit S1, and the linked S4->S1 is ignored.
        assertEquals(listOf("b"), chainEndingAt(lines, "S1").map { it.id })
        assertEquals(emptyList<MeasuredLine>(), chainEndingAt(lines, null))
    }
}
