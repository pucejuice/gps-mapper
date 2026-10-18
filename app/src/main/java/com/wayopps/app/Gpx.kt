package com.wayopps.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Pure Kotlin (no Android types): GPX / GeoJSON writers for saved activities.

data class ActivitySummary(
    val id: Long,
    val name: String,
    val type: String,
    val startedAt: Long,
    val endedAt: Long?,
    val distanceM: Double,
    val movingMs: Long,
    val pointCount: Int
) {
    val avgSecPerKm: Double? get() = if (distanceM >= 10) (movingMs / 1000.0) / (distanceM / 1000.0) else null
    val avgKmh: Double? get() = if (movingMs >= 1000) distanceM / 1000.0 / (movingMs / 3_600_000.0) else null
}

object Gpx {
    private fun iso(t: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun gpxType(type: String) = when (type) {
        "Run" -> "running"
        "Ride" -> "cycling"
        "Walk" -> "walking"
        "Hike" -> "hiking"
        else -> type.lowercase(Locale.US)
    }

    /** GPX 1.1 with one <trk> per activity (Strava, Garmin Connect, QGIS etc. read this). */
    fun write(activities: List<Pair<ActivitySummary, List<TrackPoint>>>, creator: String = "wayopps"): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"${esc(creator)}\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for ((a, pts) in activities) {
            sb.append("  <trk>\n")
            sb.append("    <name>${esc(a.name)}</name>\n")
            sb.append("    <type>${esc(gpxType(a.type))}</type>\n")
            sb.append("    <trkseg>\n")
            for (p in pts) {
                sb.append("      <trkpt lat=\"%.7f\" lon=\"%.7f\">".format(Locale.US, p.lat, p.lon))
                p.alt?.let { sb.append("<ele>%.2f</ele>".format(Locale.US, it)) }
                sb.append("<time>${iso(p.t)}</time>")
                sb.append("<hdop>%.1f</hdop>".format(Locale.US, p.acc))
                sb.append("</trkpt>\n")
            }
            sb.append("    </trkseg>\n")
            sb.append("  </trk>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /** Milliseconds before [now] older than which activities are purged; null keeps everything. */
    fun retentionCutoff(now: Long, keepDays: Int): Long? = if (keepDays <= 0) null else now - keepDays * 86_400_000L
}
