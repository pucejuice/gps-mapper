package com.wayopps.app

import kotlin.math.sqrt

// Pure Kotlin (no Android types) so sentence parsing can be unit tested on the JVM.

/** GGA fix quality indicator. */
enum class FixQuality(val code: Int, val label: String, val short: String) {
    INVALID(0, "No fix", "NO FIX"),
    GPS(1, "Autonomous", "GNSS"),
    DGPS(2, "DGNSS / SBAS", "DGPS"),
    PPS(3, "PPS", "PPS"),
    RTK_FIXED(4, "RTK fixed", "RTK"),
    RTK_FLOAT(5, "RTK float", "FLOAT"),
    DEAD_RECKONING(6, "Dead reckoning", "DR"),
    MANUAL(7, "Manual", "MAN"),
    SIMULATED(8, "Simulated", "SIM");

    companion object {
        fun of(code: Int) = values().firstOrNull { it.code == code } ?: INVALID
    }
}

/** A position assembled from one receiver epoch (GGA, plus RMC/GST when present). */
data class ReceiverFix(
    val lat: Double,
    val lon: Double,
    /** Height above mean sea level (geoid), metres. */
    val altMsl: Double?,
    /** Geoid separation N (ellipsoid - geoid), metres. */
    val geoidSep: Double?,
    val quality: FixQuality,
    val satellites: Int,
    val hdop: Double?,
    /** Horizontal accuracy (68% radius style), metres. From GST when available, else HDOP-based estimate. */
    val accuracyM: Double,
    val accuracyFromGst: Boolean,
    val verticalAccuracyM: Double?,
    val speedMps: Double?,
    val courseDeg: Double?,
    val ageOfCorrectionsS: Double?
) {
    /** WGS84 ellipsoidal height, which is what Android's Location.altitude is defined as. */
    val altEllipsoid: Double? get() = if (altMsl != null && geoidSep != null) altMsl + geoidSep else altMsl
}

object Nmea {

    /** True when the sentence has a valid `*hh` checksum (sentences without one are rejected). */
    fun checksumOk(sentence: String): Boolean {
        val s = sentence.trim()
        if (!s.startsWith("$") && !s.startsWith("!")) return false
        val star = s.lastIndexOf('*')
        if (star < 0 || star + 3 > s.length) return false
        var sum = 0
        for (i in 1 until star) sum = sum xor s[i].code
        return s.substring(star + 1, star + 3).toIntOrNull(16) == sum
    }

    /** Fields after the talker+type, e.g. "$GNGGA,a,b*hh" -> type "GGA", fields [a, b]. */
    fun split(sentence: String): Pair<String, List<String>>? {
        if (!checksumOk(sentence)) return null
        val s = sentence.trim()
        val body = s.substring(1, s.lastIndexOf('*'))
        val parts = body.split(',')
        val head = parts[0]
        if (head.length < 5) return null
        return head.substring(head.length - 3) to parts.drop(1)
    }

    /** ddmm.mmmm + hemisphere -> signed decimal degrees. */
    fun coord(value: String, hemi: String): Double? {
        if (value.isEmpty() || hemi.isEmpty()) return null
        val v = value.toDoubleOrNull() ?: return null
        val deg = (v / 100).toInt()
        val dec = deg + (v - deg * 100) / 60.0
        return if (hemi == "S" || hemi == "W") -dec else dec
    }

    data class Gga(
        val lat: Double, val lon: Double, val quality: FixQuality, val satellites: Int,
        val hdop: Double?, val altMsl: Double?, val geoidSep: Double?, val ageOfCorrections: Double?
    )

    data class Rmc(val valid: Boolean, val speedMps: Double?, val courseDeg: Double?)

    /** GST: standard deviations (1 sigma) of latitude, longitude and altitude error, metres. */
    data class Gst(val sdLat: Double, val sdLon: Double, val sdAlt: Double?)

    fun parseGga(f: List<String>): Gga? {
        if (f.size < 9) return null
        val lat = coord(f[1], f[2]) ?: return null
        val lon = coord(f[3], f[4]) ?: return null
        val q = FixQuality.of(f[5].toIntOrNull() ?: 0)
        if (q == FixQuality.INVALID) return null
        return Gga(
            lat = lat, lon = lon, quality = q,
            satellites = f[6].toIntOrNull() ?: 0,
            hdop = f[7].toDoubleOrNull(),
            altMsl = f[8].toDoubleOrNull(),
            geoidSep = f.getOrNull(10)?.toDoubleOrNull(),
            ageOfCorrections = f.getOrNull(12)?.toDoubleOrNull()
        )
    }

    fun parseRmc(f: List<String>): Rmc? {
        if (f.size < 8) return null
        return Rmc(
            valid = f[1] == "A",
            speedMps = f[6].toDoubleOrNull()?.let { it * 0.514444 },
            courseDeg = f[7].toDoubleOrNull()
        )
    }

    fun parseGst(f: List<String>): Gst? {
        if (f.size < 8) return null
        val sdLat = f[5].toDoubleOrNull() ?: return null
        val sdLon = f[6].toDoubleOrNull() ?: return null
        return Gst(sdLat, sdLon, f[7].toDoubleOrNull())
    }

    /**
     * Horizontal accuracy when the receiver gives no GST: HDOP times a typical
     * user-equivalent range error for the fix type. Deliberately conservative.
     */
    fun estimatedAccuracy(quality: FixQuality, hdop: Double?): Double {
        val h = (hdop ?: 1.5).coerceAtLeast(0.5)
        val uere = when (quality) {
            FixQuality.RTK_FIXED -> 0.02
            FixQuality.RTK_FLOAT -> 0.3
            FixQuality.DGPS -> 1.0
            else -> 3.0
        }
        return h * uere
    }
}

/**
 * Feeds NMEA lines in, emits a [ReceiverFix] for every valid GGA. RMC and GST from the
 * same stream are folded in (receivers send them in the same epoch, in any order, so the
 * most recent of each is used if it is under [maxAgeMs] old).
 */
class NmeaAssembler(private val clock: () -> Long = System::currentTimeMillis, private val maxAgeMs: Long = 2000) {
    private var rmc: Nmea.Rmc? = null
    private var rmcAt = 0L
    private var gst: Nmea.Gst? = null
    private var gstAt = 0L

    var sentences = 0
        private set
    var badChecksums = 0
        private set

    fun accept(line: String): ReceiverFix? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed[0] != '$') return null
        val parsed = Nmea.split(trimmed)
        if (parsed == null) {
            badChecksums++
            return null
        }
        sentences++
        val (type, f) = parsed
        val now = clock()
        when (type) {
            "RMC" -> Nmea.parseRmc(f)?.let { rmc = it; rmcAt = now }
            "GST" -> Nmea.parseGst(f)?.let { gst = it; gstAt = now }
            "GGA" -> {
                val g = Nmea.parseGga(f) ?: return null
                val r = rmc?.takeIf { now - rmcAt <= maxAgeMs && it.valid }
                val s = gst?.takeIf { now - gstAt <= maxAgeMs }
                val acc = s?.let { sqrt(it.sdLat * it.sdLat + it.sdLon * it.sdLon) }
                return ReceiverFix(
                    lat = g.lat,
                    lon = g.lon,
                    altMsl = g.altMsl,
                    geoidSep = g.geoidSep,
                    quality = g.quality,
                    satellites = g.satellites,
                    hdop = g.hdop,
                    accuracyM = acc ?: Nmea.estimatedAccuracy(g.quality, g.hdop),
                    accuracyFromGst = acc != null,
                    verticalAccuracyM = s?.sdAlt,
                    speedMps = r?.speedMps,
                    courseDeg = r?.courseDeg,
                    ageOfCorrectionsS = g.ageOfCorrections
                )
            }
        }
        return null
    }
}
