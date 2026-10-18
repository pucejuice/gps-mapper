package com.wayopps.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PathFollowerTest {

    // ~1 m in degrees of latitude / longitude near -33.9 (Sydney)
    private val baseLat = -33.9
    private val baseLon = 151.2
    private val dLat = 1.0 / 110_946.0
    private val dLon = 1.0 / 92_386.0

    private fun pt(eastM: Double, northM: Double) = LatLon(baseLat + northM * dLat, baseLon + eastM * dLon)

    // 100 m north, then 100 m east (an L shape)
    private val lPath = listOf(pt(0.0, 0.0), pt(0.0, 100.0), pt(100.0, 100.0))

    @Test
    fun totalLengthMatchesSegments() {
        assertEquals(200.0, PathFollower(lPath).totalMeters, 0.5)
    }

    @Test
    fun onPathPointsAhead() {
        val g = PathFollower(lPath).update(pt(0.0, 50.0).lat, pt(0.0, 50.0).lon)
        assertNotNull(g)
        g!!
        assertEquals(0.0, g.bearingToTargetDegrees, 1.0) // north
        assertEquals(50.0, g.distanceAlongMeters, 0.5)
        assertEquals(150.0, g.remainingMeters, 0.5)
        assertTrue(abs(g.crossTrackMeters) < 0.5)
        assertFalse(g.arrived)
    }

    @Test
    fun offPathToTheRightSteersBackLeft() {
        val here = pt(10.0, 50.0) // 10 m east of a north-bound leg = right of path
        val g = PathFollower(lPath).update(here.lat, here.lon)!!
        assertEquals(10.0, g.crossTrackMeters, 0.5)
        // Should head north-west back towards the line
        val diff = GeoMath.angleDiff(0.0, g.bearingToTargetDegrees)
        assertTrue("expected left of north, got $diff", diff < -10 && diff > -90)
    }

    @Test
    fun afterCornerPointsEast() {
        val g = PathFollower(lPath).update(pt(50.0, 100.0).lat, pt(50.0, 100.0).lon)!!
        assertEquals(90.0, g.bearingToTargetDegrees, 1.0)
        assertEquals(150.0, g.distanceAlongMeters, 0.5)
    }

    @Test
    fun arrivesAtEnd() {
        val g = PathFollower(lPath).update(pt(99.0, 100.0).lat, pt(99.0, 100.0).lon)!!
        assertTrue(g.arrived)
    }

    @Test
    fun reversedPathStartsFromOtherEnd() {
        val g = PathFollower(lPath.reversed()).update(pt(50.0, 100.0).lat, pt(50.0, 100.0).lon)!!
        assertEquals(270.0, g.bearingToTargetDegrees, 1.0) // west
        assertEquals(50.0, g.distanceAlongMeters, 0.5)
    }

    @Test
    fun outAndBackPathKeepsProgress() {
        // 100 m north then straight back south along the same line.
        val outBack = listOf(pt(0.0, 0.0), pt(0.0, 100.0), pt(0.5, 0.0))
        val follower = PathFollower(outBack)
        var g: FollowGuidance? = null
        for (n in listOf(10.0, 40.0, 70.0, 95.0, 100.0, 90.0, 60.0)) {
            g = follower.update(pt(0.0, n).lat, pt(0.0, n).lon)
        }
        // Walking back south at 60 m north should be on the return leg (~140 m along)
        assertEquals(140.0, g!!.distanceAlongMeters, 2.0)
        assertEquals(180.0, g.bearingToTargetDegrees, 10.0)
    }

    @Test
    fun angleDiffWraps() {
        assertEquals(20.0, GeoMath.angleDiff(350.0, 10.0), 1e-9)
        assertEquals(-20.0, GeoMath.angleDiff(10.0, 350.0), 1e-9)
    }
}
