package com.wayopps.app

import kotlin.math.max
import kotlin.math.min

// Pure Kotlin (no Android types) so the recording rules can be unit tested on the JVM.

/** One logged point on a recorded path (an immutable copy, never a live Location). */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val acc: Double,
    val t: Long
)

sealed class TrackDecision {
    /** First point, or moved far enough: log it and add [addedMeters] to the total. */
    data class Accept(val addedMeters: Double) : TrackDecision()
    /** Fix worse than the accuracy gate. */
    object RejectAccuracy : TrackDecision()
    /** Not moved far enough from the last logged point (stationary jitter). */
    object TooClose : TrackDecision()
}

object PathRecording {

    /**
     * Movement needed before another point is logged. Scales with the fix's accuracy so
     * standing still doesn't accumulate jitter, but stays small for RTK-grade fixes.
     */
    fun minStepMeters(accM: Double): Double = max(0.5, min(accM, 10.0) * 0.4)

    fun evaluate(last: TrackPoint?, fix: TrackPoint, gateM: Double): TrackDecision {
        if (fix.acc > gateM) return TrackDecision.RejectAccuracy
        if (last == null) return TrackDecision.Accept(0.0)
        val d = Geodesy.groundDistance(last.lat, last.lon, fix.lat, fix.lon)
        return if (d >= minStepMeters(fix.acc)) TrackDecision.Accept(d) else TrackDecision.TooClose
    }

    fun totalMeters(points: List<TrackPoint>): Double =
        points.zipWithNext().sumOf { (a, b) -> Geodesy.groundDistance(a.lat, a.lon, b.lat, b.lon) }
}

/**
 * The run of consecutive measured lines ending at [endId]: each line starts where the
 * previous one finished (ST1→ST2→ST3…). Linked (computed) lines are not part of a chain.
 * Where several lines end at the same station, the most recent one is followed.
 */
fun chainEndingAt(lines: List<MeasuredLine>, endId: String?): List<MeasuredLine> {
    val out = ArrayDeque<MeasuredLine>()
    val seen = mutableSetOf<String>()
    var cur = endId ?: return emptyList()
    seen.add(cur)
    while (true) {
        val l = lines.lastOrNull { it.toId == cur && !it.linked && it.fromId !in seen } ?: break
        out.addFirst(l)
        seen.add(l.fromId)
        cur = l.fromId
    }
    return out.toList()
}
