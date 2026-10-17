package com.example.geosurvey

enum class MeasureSlot(val label: String) { A("Point A"), B("Point B") }

data class MeasurePoint(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val epochSpreadMeters: Double = 0.0,
    val epochCount: Int = 4,
    val isMockProvider: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class StraightLineMeasurement(
    val from: MeasurePoint,
    val to: MeasurePoint,
    val horizontalDistanceMeters: Double,
    val slopeDistanceMeters: Double,
    val verticalDeltaMeters: Double,
    val bearingDegrees: Double
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
