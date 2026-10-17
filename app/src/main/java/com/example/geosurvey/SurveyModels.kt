package com.example.geosurvey

/** An averaged occupation. Any station can be the origin for new lines at any time. */
data class SurveyPoint(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val stats: OccupationStats,
    val occupiedUtc: Long,
    val mockGnss: Boolean
)

/** Straight line between two stored stations. [linked] = computed from stored points, not occupied in sequence. */
data class MeasuredLine(
    val id: String,
    val fromId: String,
    val toId: String,
    val distanceM: Double,
    val bearingDeg: Double,
    val sigmaM: Double?,
    val linked: Boolean,
    val timeUtc: Long
)

data class MeasureSettings(
    val dwellS: Int = 30,
    val epochs: Int = 4,
    val gateM: Double = 15.0,
    val recordHeight: Boolean = false,
    /** When on, each new station becomes the origin (consecutive / traverse measuring). */
    val chain: Boolean = false
)

data class OccupationProgress(
    val name: String,
    val dwellMs: Long,
    val epochs: Int,
    val elapsedMs: Long,
    val rejected: Int,
    val gateM: Double,
    val live: OccupationStats?,
    val waitingForFreshFix: Boolean
)

data class SurveyFeaturePoint(
    val id: Long = System.currentTimeMillis(),
    val category: String,
    val note: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val includeInAreaPolygon: Boolean = true
)

data class ImportedLine(
    val name: String,
    val points: List<LatLon>
)

data class ImportedGeoJsonOverlay(
    val fileName: String,
    val lines: List<ImportedLine>,
    val points: List<Triple<Double, Double, String>>
)

data class FollowSession(
    val lineIndex: Int,
    val reversed: Boolean,
    val path: List<LatLon>,
    val guidance: FollowGuidance? = null
)
