package com.example.geosurvey

import android.location.Location
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

object SurveyRepository {

    // Live GNSS fix
    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation = _currentLocation.asStateFlow()

    private val _isMockLocationActive = MutableStateFlow(false)
    val isMockLocationActive = _isMockLocationActive.asStateFlow()

    // --- Mode 1: Path Tracker State ---
    private val _isRecordingPath = MutableStateFlow(false)
    val isRecordingPath = _isRecordingPath.asStateFlow()

    private val _pathPoints = MutableStateFlow<List<Location>>(emptyList())
    val pathPoints = _pathPoints.asStateFlow()

    private val _totalDistanceMeters = MutableStateFlow(0.0)
    val totalDistanceMeters = _totalDistanceMeters.asStateFlow()

    private val _maxAccuracyFilterMeters = MutableStateFlow(5.0f)
    val maxAccuracyFilterMeters = _maxAccuracyFilterMeters.asStateFlow()

    // --- Mode 2: Sight Line Analysis (SLA) State ---
    private val _stations = MutableStateFlow<List<SurveyStation>>(emptyList())
    val stations = _stations.asStateFlow()

    private val _sightLines = MutableStateFlow<List<SightLineResult>>(emptyList())
    val sightLines = _sightLines.asStateFlow()

    private val _isSamplingEpochs = MutableStateFlow(false)
    val isSamplingEpochs = _isSamplingEpochs.asStateFlow()

    private val _currentEpochCount = MutableStateFlow(0)
    val currentEpochCount = _currentEpochCount.asStateFlow()

    private val _targetEpochs = MutableStateFlow(4)
    val targetEpochs = _targetEpochs.asStateFlow()

    private val epochBuffer = mutableListOf<Location>()
    private var pendingStationName: String = ""

    // --- Mode 3: Feature & Area Survey State ---
    private val _featurePoints = MutableStateFlow<List<SurveyFeaturePoint>>(emptyList())
    val featurePoints = _featurePoints.asStateFlow()

    private val _polygonAreaSqMeters = MutableStateFlow(0.0)
    val polygonAreaSqMeters = _polygonAreaSqMeters.asStateFlow()

    private val _polygonPerimeterMeters = MutableStateFlow(0.0)
    val polygonPerimeterMeters = _polygonPerimeterMeters.asStateFlow()

    // --- Imported GeoJSON Overlay ---
    private val _importedOverlay = MutableStateFlow<ImportedGeoJsonOverlay?>(null)
    val importedOverlay = _importedOverlay.asStateFlow()

    @Suppress("DEPRECATION")
    private fun checkIsMock(loc: Location): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            loc.isMock
        } else {
            loc.isFromMockProvider
        }
    }

    fun onNewLocation(loc: Location) {
        _currentLocation.value = loc
        _isMockLocationActive.value = checkIsMock(loc)

        // 1. Continuous Path Recording
        if (_isRecordingPath.value) {
            if (loc.accuracy <= _maxAccuracyFilterMeters.value) {
                val currentList = _pathPoints.value
                if (currentList.isNotEmpty()) {
                    val lastLoc = currentList.last()
                    val delta = lastLoc.distanceTo(loc)
                    if (delta >= 0.35f) {
                        _totalDistanceMeters.value += delta
                        _pathPoints.value = currentList + loc
                    }
                } else {
                    _pathPoints.value = listOf(loc)
                }
            }
        }

        // 2. Multi-Epoch Station Averaging (SLA)
        if (_isSamplingEpochs.value) {
            epochBuffer.add(loc)
            _currentEpochCount.value = epochBuffer.size
            val req = _targetEpochs.value

            if (epochBuffer.size >= req) {
                val avgLat = epochBuffer.map { it.latitude }.average()
                val avgLon = epochBuffer.map { it.longitude }.average()
                val avgAlt = epochBuffer.map { it.altitude }.average()
                val avgAcc = epochBuffer.map { it.accuracy }.average().toFloat()

                // Compute max horizontal spread (RMS/jitter) across the epochs
                var maxSpread = 0.0
                for (sample in epochBuffer) {
                    val d = FloatArray(1)
                    Location.distanceBetween(avgLat, avgLon, sample.latitude, sample.longitude, d)
                    if (d[0] > maxSpread) maxSpread = d[0].toDouble()
                }

                val newStation = SurveyStation(
                    name = pendingStationName.ifBlank { "Station ${_stations.value.size + 1}" },
                    latitude = avgLat,
                    longitude = avgLon,
                    altitude = avgAlt,
                    accuracy = avgAcc,
                    epochSpreadMeters = maxSpread,
                    epochCount = req,
                    isMockProvider = checkIsMock(loc)
                )

                val updatedStations = _stations.value.filter { it.name != newStation.name } + newStation
                _stations.value = updatedStations
                recalculateSightLines(updatedStations)

                epochBuffer.clear()
                _isSamplingEpochs.value = false
                _currentEpochCount.value = 0
            }
        }
    }

    // --- Path Controls ---
    fun setAccuracyFilter(maxMeters: Float) {
        _maxAccuracyFilterMeters.value = maxMeters
    }

    fun startPathRecording() {
        _isRecordingPath.value = true
    }

    fun stopPathRecording() {
        _isRecordingPath.value = false
    }

    fun undoLastPathPoint() {
        val current = _pathPoints.value
        if (current.size >= 2) {
            val last = current.last()
            val prev = current[current.size - 2]
            val delta = prev.distanceTo(last)
            _totalDistanceMeters.value = (_totalDistanceMeters.value - delta).coerceAtLeast(0.0)
            _pathPoints.value = current.dropLast(1)
        } else if (current.size == 1) {
            _pathPoints.value = emptyList()
            _totalDistanceMeters.value = 0.0
        }
    }

    fun clearPath() {
        _isRecordingPath.value = false
        _pathPoints.value = emptyList()
        _totalDistanceMeters.value = 0.0
    }

    // --- SLA Controls ---
    fun setTargetEpochs(epochs: Int) {
        _targetEpochs.value = epochs.coerceIn(1, 20)
    }

    fun startStationCapture(stationName: String) {
        pendingStationName = stationName
        epochBuffer.clear()
        _currentEpochCount.value = 0
        _isSamplingEpochs.value = true
    }

    fun cancelStationCapture() {
        _isSamplingEpochs.value = false
        epochBuffer.clear()
        _currentEpochCount.value = 0
    }

    fun clearStations() {
        _stations.value = emptyList()
        _sightLines.value = emptyList()
        cancelStationCapture()
    }

    private fun recalculateSightLines(stationsList: List<SurveyStation>) {
        if (stationsList.size < 2) {
            _sightLines.value = emptyList()
            return
        }

        val results = mutableListOf<SightLineResult>()
        val primary = stationsList.first()

        for (i in 1 until stationsList.size) {
            val target = stationsList[i]
            val distanceResult = FloatArray(2)
            Location.distanceBetween(
                primary.latitude, primary.longitude,
                target.latitude, target.longitude,
                distanceResult
            )
            val hDist = distanceResult[0].toDouble()
            val bearing = (distanceResult[1].toDouble() + 360.0) % 360.0
            val vDelta = target.altitude - primary.altitude
            val slopeDist = sqrt(hDist.pow(2) + vDelta.pow(2))
            val grade = if (hDist > 0.0) (vDelta / hDist) * 100.0 else 0.0

            results.add(
                SightLineResult(
                    fromStation = primary,
                    toStation = target,
                    horizontalDistanceMeters = hDist,
                    slopeDistanceMeters = slopeDist,
                    verticalDeltaMeters = vDelta,
                    gradePercent = grade,
                    bearingDegrees = bearing
                )
            )
        }
        _sightLines.value = results
    }

    // --- Feature & Area Survey Controls ---
    fun captureFeaturePoint(category: String, note: String, includeInPolygon: Boolean = true) {
        val loc = _currentLocation.value ?: return
        val newPt = SurveyFeaturePoint(
            category = category,
            note = note.ifBlank { "$category #${_featurePoints.value.size + 1}" },
            latitude = loc.latitude,
            longitude = loc.longitude,
            altitude = loc.altitude,
            accuracy = loc.accuracy,
            includeInAreaPolygon = includeInPolygon
        )
        val updated = _featurePoints.value + newPt
        _featurePoints.value = updated
        recalculatePolygonMetrics(updated)
    }

    fun undoLastFeaturePoint() {
        val current = _featurePoints.value
        if (current.isNotEmpty()) {
            val updated = current.dropLast(1)
            _featurePoints.value = updated
            recalculatePolygonMetrics(updated)
        }
    }

    fun clearFeatures() {
        _featurePoints.value = emptyList()
        _polygonAreaSqMeters.value = 0.0
        _polygonPerimeterMeters.value = 0.0
    }

    private fun recalculatePolygonMetrics(points: List<SurveyFeaturePoint>) {
        val polyPts = points.filter { it.includeInAreaPolygon }
        if (polyPts.size < 2) {
            _polygonPerimeterMeters.value = 0.0
            _polygonAreaSqMeters.value = 0.0
            return
        }

        // Calculate Perimeter
        var perimeter = 0.0
        for (i in 0 until polyPts.size) {
            val a = polyPts[i]
            val b = polyPts[(i + 1) % polyPts.size]
            val d = FloatArray(1)
            Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, d)
            if (polyPts.size > 2 || i == 0) {
                perimeter += d[0]
            }
        }
        _polygonPerimeterMeters.value = perimeter

        if (polyPts.size < 3) {
            _polygonAreaSqMeters.value = 0.0
            return
        }

        // Local Tangent Plane (ENU meters) Shoelace Area Formula
        val originLatRad = Math.toRadians(polyPts[0].latitude)
        val originLon = polyPts[0].longitude
        val originLat = polyPts[0].latitude
        val metersPerDegLat = 111132.92 - 559.82 * cos(2 * originLatRad) + 1.175 * cos(4 * originLatRad)
        val metersPerDegLon = 111412.84 * cos(originLatRad) - 93.5 * cos(3 * originLatRad)

        val xy = polyPts.map { pt ->
            val x = (pt.longitude - originLon) * metersPerDegLon
            val y = (pt.latitude - originLat) * metersPerDegLat
            Pair(x, y)
        }

        var areaSum = 0.0
        for (i in xy.indices) {
            val j = (i + 1) % xy.size
            areaSum += (xy[i].first * xy[j].second) - (xy[j].first * xy[i].second)
        }
        _polygonAreaSqMeters.value = abs(areaSum) / 2.0
    }

    // --- GeoJSON Import Parser ---
    fun importGeoJson(fileName: String, jsonText: String): Boolean {
        return try {
            val root = JSONObject(jsonText)
            val polylines = mutableListOf<List<Pair<Double, Double>>>()
            val points = mutableListOf<Triple<Double, Double, String>>()

            fun parseGeometry(geom: JSONObject, label: String) {
                when (geom.optString("type")) {
                    "Point" -> {
                        val c = geom.optJSONArray("coordinates")
                        if (c != null && c.length() >= 2) {
                            points.add(Triple(c.getDouble(1), c.getDouble(0), label))
                        }
                    }
                    "LineString" -> {
                        val c = geom.optJSONArray("coordinates")
                        if (c != null) {
                            val line = mutableListOf<Pair<Double, Double>>()
                            for (i in 0 until c.length()) {
                                val pt = c.getJSONArray(i)
                                line.add(Pair(pt.getDouble(1), pt.getDouble(0)))
                            }
                            if (line.isNotEmpty()) polylines.add(line)
                        }
                    }
                    "Polygon" -> {
                        val rings = geom.optJSONArray("coordinates")
                        if (rings != null && rings.length() > 0) {
                            val outer = rings.getJSONArray(0)
                            val line = mutableListOf<Pair<Double, Double>>()
                            for (i in 0 until outer.length()) {
                                val pt = outer.getJSONArray(i)
                                line.add(Pair(pt.getDouble(1), pt.getDouble(0)))
                            }
                            if (line.isNotEmpty()) polylines.add(line)
                        }
                    }
                }
            }

            if (root.optString("type") == "FeatureCollection") {
                val features = root.optJSONArray("features") ?: JSONArray()
                for (i in 0 until features.length()) {
                    val f = features.getJSONObject(i)
                    val props = f.optJSONObject("properties")
                    val name = props?.optString("name")?.ifBlank { null }
                        ?: props?.optString("note")?.ifBlank { null }
                        ?: "Imported #${i + 1}"
                    val geom = f.optJSONObject("geometry")
                    if (geom != null) parseGeometry(geom, name)
                }
            } else if (root.optString("type") == "Feature") {
                val geom = root.optJSONObject("geometry")
                if (geom != null) parseGeometry(geom, fileName)
            } else {
                parseGeometry(root, fileName)
            }

            _importedOverlay.value = ImportedGeoJsonOverlay(fileName, polylines, points)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clearImportedOverlay() {
        _importedOverlay.value = null
    }

    // --- GeoJSON Exporters ---
    fun exportPathGeoJson(): String {
        val root = JSONObject().apply { put("type", "FeatureCollection") }
        val features = JSONArray()

        val points = _pathPoints.value
        if (points.isNotEmpty()) {
            val lineFeature = JSONObject().apply {
                put("type", "Feature")
                put("properties", JSONObject().apply {
                    put("type", "RecordedPath")
                    put("pointCount", points.size)
                    put("totalDistance_m", "%.2f".format(_totalDistanceMeters.value))
                    put("timestamp", System.currentTimeMillis())
                })
                put("geometry", JSONObject().apply {
                    put("type", "LineString")
                    val coords = JSONArray()
                    for (pt in points) {
                        coords.put(JSONArray().apply {
                            put(pt.longitude)
                            put(pt.latitude)
                            put(pt.altitude)
                        })
                    }
                    put("coordinates", coords)
                })
            }
            features.put(lineFeature)
        }
        root.put("features", features)
        return root.toString(2)
    }

    fun exportSlaGeoJson(): String {
        val root = JSONObject().apply { put("type", "FeatureCollection") }
        val features = JSONArray()

        for (st in _stations.value) {
            val ptFeature = JSONObject().apply {
                put("type", "Feature")
                put("properties", JSONObject().apply {
                    put("name", st.name)
                    put("featureType", "SurveyStation")
                    put("altitude_m", "%.2f".format(st.altitude))
                    put("accuracy_m", "%.3f".format(st.accuracy))
                    put("epochSpread_m", "%.3f".format(st.epochSpreadMeters))
                    put("epochs", st.epochCount)
                    put("mockGnss", st.isMockProvider)
                })
                put("geometry", JSONObject().apply {
                    put("type", "Point")
                    put("coordinates", JSONArray().apply {
                        put(st.longitude)
                        put(st.latitude)
                        put(st.altitude)
                    })
                })
            }
            features.put(ptFeature)
        }

        for (line in _sightLines.value) {
            val lineFeature = JSONObject().apply {
                put("type", "Feature")
                put("properties", JSONObject().apply {
                    put("featureType", "SightLine")
                    put("fromStation", line.fromStation.name)
                    put("toStation", line.toStation.name)
                    put("horizontalDistance_m", "%.2f".format(line.horizontalDistanceMeters))
                    put("slopeDistance_m", "%.2f".format(line.slopeDistanceMeters))
                    put("verticalDelta_m", "%.2f".format(line.verticalDeltaMeters))
                    put("grade_percent", "%.2f".format(line.gradePercent))
                    put("bearing_deg", "%.1f".format(line.bearingDegrees))
                })
                put("geometry", JSONObject().apply {
                    put("type", "LineString")
                    put("coordinates", JSONArray().apply {
                        put(JSONArray().apply {
                            put(line.fromStation.longitude)
                            put(line.fromStation.latitude)
                            put(line.fromStation.altitude)
                        })
                        put(JSONArray().apply {
                            put(line.toStation.longitude)
                            put(line.toStation.latitude)
                            put(line.toStation.altitude)
                        })
                    })
                })
            }
            features.put(lineFeature)
        }

        root.put("features", features)
        return root.toString(2)
    }

    fun exportFeaturesGeoJson(): String {
        val root = JSONObject().apply { put("type", "FeatureCollection") }
        val features = JSONArray()

        val pts = _featurePoints.value
        for (pt in pts) {
            val f = JSONObject().apply {
                put("type", "Feature")
                put("properties", JSONObject().apply {
                    put("category", pt.category)
                    put("note", pt.note)
                    put("altitude_m", "%.2f".format(pt.altitude))
                    put("accuracy_m", "%.3f".format(pt.accuracy))
                })
                put("geometry", JSONObject().apply {
                    put("type", "Point")
                    put("coordinates", JSONArray().apply {
                        put(pt.longitude)
                        put(pt.latitude)
                        put(pt.altitude)
                    })
                })
            }
            features.put(f)
        }

        val polyPts = pts.filter { it.includeInAreaPolygon }
        if (polyPts.size >= 3) {
            val polyFeature = JSONObject().apply {
                put("type", "Feature")
                put("properties", JSONObject().apply {
                    put("featureType", "SurveyPolygon")
                    put("area_sq_m", "%.2f".format(_polygonAreaSqMeters.value))
                    put("area_hectares", "%.4f".format(_polygonAreaSqMeters.value / 10000.0))
                    put("perimeter_m", "%.2f".format(_polygonPerimeterMeters.value))
                })
                put("geometry", JSONObject().apply {
                    put("type", "Polygon")
                    val ring = JSONArray()
                    for (p in polyPts) {
                        ring.put(JSONArray().apply {
                            put(p.longitude)
                            put(p.latitude)
                            put(p.altitude)
                        })
                    }
                    // Close GeoJSON Polygon ring
                    val first = polyPts.first()
                    ring.put(JSONArray().apply {
                        put(first.longitude)
                        put(first.latitude)
                        put(first.altitude)
                    })
                    put("coordinates", JSONArray().apply { put(ring) })
                })
            }
            features.put(polyFeature)
        }

        root.put("features", features)
        return root.toString(2)
    }
}
