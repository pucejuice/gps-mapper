package com.wayopps.app

import kotlin.math.floor
import kotlin.math.roundToInt

// Pure Kotlin (no Android types) so alert rules can be unit tested on the JVM.

/** Plays when the recorded distance passes [distanceM]; [repeating] = every multiple of it. */
data class DistanceAlert(
    val id: String,
    val distanceM: Double,
    val repeating: Boolean,
    val soundUri: String? = null,
    val soundTitle: String = "Default",
    val enabled: Boolean = true
)

data class PaceAlertSettings(
    val enabled: Boolean = false,
    /** Target pace, seconds per km (300 = 5:00 /km). */
    val targetSecPerKm: Int = 300,
    /** Alert when slower than target by more than this percentage. */
    val slowTolerancePct: Double = 10.0,
    /** Alert when faster than target by more than this percentage. */
    val fastTolerancePct: Double = 10.0,
    val slowSoundUri: String? = null,
    val slowSoundTitle: String = "Default",
    val fastSoundUri: String? = null,
    val fastSoundTitle: String = "Default",
    /** While still outside the range, alert again after this many seconds. */
    val repeatS: Int = 60,
    /** Pace is averaged over this trailing window (Settings › Pace measurement). */
    val windowS: Int = 30
) {
    /** Slowest acceptable pace, s/km (a larger number is slower). */
    val slowLimit: Double get() = targetSecPerKm * (1 + slowTolerancePct / 100)
    /** Fastest acceptable pace, s/km. */
    val fastLimit: Double get() = targetSecPerKm * (1 - fastTolerancePct / 100)
}

enum class PaceZone { FAST, OK, SLOW }

object DistanceAlerts {
    /**
     * Alerts triggered by the distance moving from [prevM] to [nowM], each with the mark
     * it passed (e.g. 2000.0 for the 2 km mark of a repeating 1 km alert). If several
     * repeats are passed at once (a gap in fixes), only the latest mark is reported.
     */
    fun crossed(alerts: List<DistanceAlert>, prevM: Double, nowM: Double): List<Pair<DistanceAlert, Double>> {
        if (nowM <= prevM) return emptyList()
        val out = mutableListOf<Pair<DistanceAlert, Double>>()
        for (a in alerts) {
            if (!a.enabled || a.distanceM <= 0) continue
            if (a.repeating) {
                val k1 = floor(prevM / a.distanceM)
                val k2 = floor(nowM / a.distanceM)
                if (k2 > k1 && k2 >= 1) out.add(a to k2 * a.distanceM)
            } else if (prevM < a.distanceM && nowM >= a.distanceM) {
                out.add(a to a.distanceM)
            }
        }
        return out
    }
}

object Pace {
    /**
     * Pace over the trailing [windowS] seconds of [points], seconds per km, or null when
     * there isn't enough movement to say (under [minDistM] or [minTimeS] in the window).
     */
    fun rollingSecPerKm(
        points: List<TrackPoint>,
        windowS: Int,
        // Shorter windows accept less movement so they still give a reading
        minDistM: Double = minOf(15.0, windowS * 0.5),
        minTimeS: Double = minOf(8.0, windowS * 0.6)
    ): Double? {
        if (points.size < 2) return null
        val end = points.last()
        val startT = end.t - windowS * 1000L
        var i = points.size - 1
        var dist = 0.0
        while (i > 0 && points[i - 1].t >= startT) {
            val a = points[i - 1]
            val b = points[i]
            dist += Geodesy.groundDistance(a.lat, a.lon, b.lat, b.lon)
            i--
        }
        val dt = (end.t - points[i].t) / 1000.0
        if (dist < minDistM || dt < minTimeS) return null
        return dt / (dist / 1000.0)
    }

    fun zone(secPerKm: Double, s: PaceAlertSettings): PaceZone = when {
        secPerKm > s.slowLimit -> PaceZone.SLOW
        secPerKm < s.fastLimit -> PaceZone.FAST
        else -> PaceZone.OK
    }

    /** 312.4 -> "5:12" */
    fun format(secPerKm: Double?): String {
        if (secPerKm == null || secPerKm.isNaN() || secPerKm.isInfinite() || secPerKm > 5999) return "--:--"
        val total = secPerKm.roundToInt()
        return "%d:%02d".format(total / 60, total % 60)
    }

    /** "5:12" or "5" -> seconds per km, null if not understood. */
    fun parse(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val parts = t.split(':')
        return when (parts.size) {
            1 -> parts[0].toIntOrNull()?.let { it * 60 }
            2 -> {
                val m = parts[0].toIntOrNull() ?: return null
                val s = parts[1].toIntOrNull() ?: return null
                if (s !in 0..59) null else m * 60 + s
            }
            else -> null
        }?.takeIf { it > 0 }
    }
}

/**
 * Decides when to sound a pace alert: on leaving the target range, then again every
 * [PaceAlertSettings.repeatS] while still outside it. Coming back into range resets it.
 */
class PaceMonitor {
    private var lastZone = PaceZone.OK
    private var lastAlertMs = 0L

    fun update(secPerKm: Double?, s: PaceAlertSettings, nowMs: Long): PaceZone? {
        if (!s.enabled || secPerKm == null) return null
        val z = Pace.zone(secPerKm, s)
        if (z == PaceZone.OK) {
            lastZone = PaceZone.OK
            return null
        }
        val fire = z != lastZone || nowMs - lastAlertMs >= s.repeatS * 1000L
        lastZone = z
        if (!fire) return null
        lastAlertMs = nowMs
        return z
    }

    fun reset() {
        lastZone = PaceZone.OK
        lastAlertMs = 0L
    }
}

/** Formats a duration in ms as h:mm:ss or m:ss. */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

object Speed {
    fun kmh(mps: Double): Double = mps * 3.6

    /** Speed equivalent of a pace (s/km -> km/h). */
    fun fromPaceKmh(secPerKm: Double?): Double? = secPerKm?.takeIf { it > 0 }?.let { 3600.0 / it }

    /** Average moving speed: distance travelled / moving time (pauses excluded). */
    fun averageKmh(distanceM: Double, movingMs: Long): Double? =
        if (movingMs < 1000 || distanceM < 1) null else (distanceM / 1000.0) / (movingMs / 3_600_000.0)

    /**
     * Current speed: the receiver's own (Doppler) speed when the fix carries one, which is
     * instantaneous and doesn't lag; otherwise the speed over the recent track ([trackKmh]).
     */
    fun currentKmh(fixHasSpeed: Boolean, fixSpeedMps: Double?, trackKmh: Double?): Double? =
        if (fixHasSpeed && fixSpeedMps != null && fixSpeedMps >= 0) kmh(fixSpeedMps) else trackKmh
}
