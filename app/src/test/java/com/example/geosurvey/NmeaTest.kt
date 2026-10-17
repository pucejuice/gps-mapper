package com.example.geosurvey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaTest {

    /** Appends a correct checksum so test sentences stay readable. */
    private fun withSum(body: String): String {
        var sum = 0
        for (c in body) sum = sum xor c.code
        return "$$body*%02X".format(sum)
    }

    @Test
    fun knownSentenceChecksum() {
        // Widely published example sentence with its real checksum.
        assertTrue(Nmea.checksumOk("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"))
        assertFalse(Nmea.checksumOk("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*48"))
        assertFalse(Nmea.checksumOk("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"))
    }

    @Test
    fun ggaSouthernEasternHemisphereRtkFixed() {
        val a = NmeaAssembler(clock = { 1000 })
        val fix = a.accept(withSum("GNGGA,031500.00,2642.0000,S,15306.0000,E,4,21,0.6,12.345,M,40.100,M,1.0,0000"))
        assertNotNull(fix)
        fix!!
        assertEquals(-26.70, fix.lat, 1e-9)
        assertEquals(153.10, fix.lon, 1e-9)
        assertEquals(FixQuality.RTK_FIXED, fix.quality)
        assertEquals(21, fix.satellites)
        assertEquals(12.345, fix.altMsl!!, 1e-9)
        assertEquals(52.445, fix.altEllipsoid!!, 1e-9)
        assertEquals(1.0, fix.ageOfCorrectionsS!!, 1e-9)
        // No GST: HDOP x 2 cm
        assertFalse(fix.accuracyFromGst)
        assertEquals(0.012, fix.accuracyM, 1e-9)
    }

    @Test
    fun gstGivesAccuracyAndRmcGivesSpeedCourse() {
        val a = NmeaAssembler(clock = { 5000 })
        assertNull(a.accept(withSum("GNRMC,031500.00,A,2642.0000,S,15306.0000,E,1.944,87.5,171026,,,R,V")))
        assertNull(a.accept(withSum("GNGST,031500.00,0.9,0.012,0.008,45.0,0.009,0.012,0.020")))
        val fix = a.accept(withSum("GNGGA,031500.00,2642.0000,S,15306.0000,E,5,18,0.8,12.0,M,40.1,M,,"))!!
        assertTrue(fix.accuracyFromGst)
        assertEquals(0.015, fix.accuracyM, 1e-9)   // sqrt(0.009^2 + 0.012^2)
        assertEquals(0.020, fix.verticalAccuracyM!!, 1e-9)
        assertEquals(1.0001, fix.speedMps!!, 1e-3) // 1.944 kn
        assertEquals(87.5, fix.courseDeg!!, 1e-9)
        assertEquals(FixQuality.RTK_FLOAT, fix.quality)
    }

    @Test
    fun staleRmcAndGstAreIgnored() {
        var now = 0L
        val a = NmeaAssembler(clock = { now })
        a.accept(withSum("GPGST,000000,0.9,0.5,0.4,0,0.3,0.4,0.6"))
        now = 10_000
        val fix = a.accept(withSum("GPGGA,000010,2642.0000,S,15306.0000,E,1,08,1.2,10.0,M,40.0,M,,"))!!
        assertFalse(fix.accuracyFromGst)
        assertEquals(3.6, fix.accuracyM, 1e-9) // 1.2 x 3 m
    }

    @Test
    fun noFixAndCorruptLinesProduceNothing() {
        val a = NmeaAssembler()
        assertNull(a.accept(withSum("GPGGA,000000,,,,,0,00,99.9,,M,,M,,")))
        assertNull(a.accept("\$GPGGA,garbage*00"))
        assertNull(a.accept("binary\u0000junk"))
        assertEquals(1, a.badChecksums)
    }

    @Test
    fun coordinateConversion() {
        assertEquals(48.1173, Nmea.coord("4807.038", "N")!!, 1e-9)
        assertEquals(-11.516666666666667, Nmea.coord("01131.000", "W")!!, 1e-12)
        assertNull(Nmea.coord("", "N"))
    }
}
