package com.wayopps.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertsTest {

    private val every1k = DistanceAlert("a", 1000.0, repeating = true)
    private val at1k = DistanceAlert("b", 1000.0, repeating = false)
    private val at2k = DistanceAlert("c", 2000.0, repeating = false)
    private val every500 = DistanceAlert("d", 500.0, repeating = true)

    @Test
    fun repeatingFiresAtEachMultiple() {
        val alerts = listOf(every1k)
        assertTrue(DistanceAlerts.crossed(alerts, 0.0, 999.0).isEmpty())
        assertEquals(listOf(every1k to 1000.0), DistanceAlerts.crossed(alerts, 999.0, 1001.0))
        assertTrue(DistanceAlerts.crossed(alerts, 1001.0, 1999.0).isEmpty())
        assertEquals(listOf(every1k to 2000.0), DistanceAlerts.crossed(alerts, 1999.0, 2000.0))
    }

    @Test
    fun singleAlertsFireOnceAtTheirOwnDistance() {
        val alerts = listOf(at1k, at2k)
        assertEquals(listOf(at1k to 1000.0), DistanceAlerts.crossed(alerts, 990.0, 1005.0))
        assertTrue(DistanceAlerts.crossed(alerts, 1005.0, 1990.0).isEmpty())
        assertEquals(listOf(at2k to 2000.0), DistanceAlerts.crossed(alerts, 1990.0, 2010.0))
        assertTrue(DistanceAlerts.crossed(alerts, 2010.0, 3010.0).isEmpty())
    }

    @Test
    fun mixedAlertsAndGapsReportLatestMarkOnce() {
        val all = listOf(every1k, at2k, every500)
        val got = DistanceAlerts.crossed(all, 1400.0, 2600.0)
        assertEquals(setOf(every1k to 2000.0, at2k to 2000.0, every500 to 2500.0), got.toSet())
        assertTrue(DistanceAlerts.crossed(all, 100.0, 100.0).isEmpty())
        assertTrue(DistanceAlerts.crossed(listOf(every1k.copy(enabled = false)), 0.0, 5000.0).isEmpty())
    }

    private fun track(speedMps: Double, seconds: Int): List<TrackPoint> =
        (0..seconds).map { s ->
            val (lat, lon) = Geodesy.fromEN(0.0, s * speedMps, -26.7, 153.1)
            TrackPoint(lat, lon, null, 3.0, s * 1000L)
        }

    @Test
    fun rollingPaceOfSteadyRun() {
        // 3.333 m/s = 5:00 /km
        val pace = Pace.rollingSecPerKm(track(1000.0 / 300.0, 120), windowS = 30)!!
        assertEquals(300.0, pace, 0.5)
        assertEquals("5:00", Pace.format(pace))
    }

    @Test
    fun noPaceWhenStandingStill() {
        assertNull(Pace.rollingSecPerKm(track(0.1, 60), windowS = 30))
        assertNull(Pace.rollingSecPerKm(emptyList(), 30))
    }

    @Test
    fun paceZonesAndLimits() {
        val s = PaceAlertSettings(enabled = true, targetSecPerKm = 300, slowTolerancePct = 10.0, fastTolerancePct = 5.0)
        assertEquals(330.0, s.slowLimit, 1e-9)
        assertEquals(285.0, s.fastLimit, 1e-9)
        assertEquals(PaceZone.SLOW, Pace.zone(331.0, s))
        assertEquals(PaceZone.OK, Pace.zone(300.0, s))
        assertEquals(PaceZone.FAST, Pace.zone(284.0, s))
    }

    @Test
    fun monitorAlertsOnLeavingRangeThenRepeats() {
        val s = PaceAlertSettings(enabled = true, targetSecPerKm = 300, repeatS = 60)
        val m = PaceMonitor()
        assertNull(m.update(300.0, s, 0))
        assertEquals(PaceZone.SLOW, m.update(340.0, s, 1_000))
        assertNull(m.update(345.0, s, 30_000))              // still slow, within repeat time
        assertEquals(PaceZone.SLOW, m.update(345.0, s, 61_000))
        assertEquals(PaceZone.FAST, m.update(250.0, s, 62_000)) // zone change fires straight away
        assertNull(m.update(300.0, s, 63_000))              // back in range
        assertEquals(PaceZone.FAST, m.update(250.0, s, 64_000))
        assertNull(m.update(400.0, s.copy(enabled = false), 200_000))
    }

    @Test
    fun paceParsing() {
        assertEquals(312, Pace.parse("5:12"))
        assertEquals(300, Pace.parse("5"))
        assertNull(Pace.parse("5:75"))
        assertNull(Pace.parse("abc"))
        assertEquals("1:02:03", formatDuration(3_723_000))
        assertEquals("4:05", formatDuration(245_000))
    }

    @Test
    fun gpxHasOneTrackWithPointsAndEscapedName() {
        val a = ActivitySummary(1, "Run <home> & back", "Run", 0, 1000, 10.0, 1000, 2)
        val pts = listOf(TrackPoint(-26.7, 153.1, 12.0, 3.0, 0), TrackPoint(-26.7001, 153.1, null, 4.0, 1000))
        val gpx = Gpx.write(listOf(a to pts))
        assertTrue(gpx.contains("<name>Run &lt;home&gt; &amp; back</name>"))
        assertTrue(gpx.contains("<type>running</type>"))
        assertEquals(2, Regex("<trkpt ").findAll(gpx).count())
        assertTrue(gpx.contains("lat=\"-26.7000000\" lon=\"153.1000000\"><ele>12.00</ele><time>1970-01-01T00:00:00Z</time>"))
        assertNull(Gpx.retentionCutoff(10, 0))
        assertEquals(10L * 86_400_000L - 86_400_000L, Gpx.retentionCutoff(10L * 86_400_000L, 1))
    }

    @Test
    fun averagePaceFromSummary() {
        val a = ActivitySummary(1, "x", "Run", 0, 0, 5000.0, 1_500_000, 0)
        assertEquals(300.0, a.avgSecPerKm!!, 1e-9)
        assertEquals(12.0, a.avgKmh!!, 1e-9)
    }
}

class PaceWindowTest {
    private fun track(speedMps: Double, seconds: Int): List<TrackPoint> =
        (0..seconds).map { s ->
            val (lat, lon) = Geodesy.fromEN(0.0, s * speedMps, -26.7, 153.1)
            TrackPoint(lat, lon, null, 3.0, s * 1000L)
        }

    @Test
    fun everyWindowOptionGivesAWalkingPace() {
        // Brisk walk, 1.4 m/s = 11:54 /km, logged once a second
        for (w in PACE_WINDOWS_FOR_TEST) {
            val p = Pace.rollingSecPerKm(track(1.4, 200), w)
            org.junit.Assert.assertNotNull("window $w", p)
            org.junit.Assert.assertEquals(1000 / 1.4, p!!, 1.0)
        }
    }

    @Test
    fun windowOnlyUsesRecentPoints() {
        // 60 s slow (2 m/s) then 30 s fast (4 m/s): a 20 s window sees only the fast part.
        val slow = track(2.0, 60)
        val last = slow.last()
        val fast = (1..30).map { s ->
            val (lat, lon) = Geodesy.fromEN(0.0, 120.0 + s * 4.0, -26.7, 153.1)
            TrackPoint(lat, lon, null, 3.0, last.t + s * 1000L)
        }
        org.junit.Assert.assertEquals(250.0, Pace.rollingSecPerKm(slow + fast, 20)!!, 0.5)
        org.junit.Assert.assertTrue(Pace.rollingSecPerKm(slow + fast, 120)!! > 300.0)
    }

    private val PACE_WINDOWS_FOR_TEST = listOf(10, 20, 30, 60, 120)
}
