package com.example.geosurvey

data class SurveyStation(
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

data class SightLineResult(
    val fromStation: SurveyStation,
    val toStation: SurveyStation,
    val horizontalDistanceMeters: Double,
    val slopeDistanceMeters: Double,
    val verticalDeltaMeters: Double,
    val gradePercent: Double,
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

data class ImportedGeoJsonOverlay(
    val fileName: String,
    val polylines: List<List<Pair<Double, Double>>>,
    val points: List<Triple<Double, Double, String>>
)
