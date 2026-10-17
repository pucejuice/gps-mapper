package com.example.geosurvey

import android.location.Location
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Work that justifies keeping GPS on while the app is in the background. */
enum class BackgroundTask(val label: String) {
    IDLE(""),
    HOLDING("Holding station"),
    RECORDING("Recording path"),
    FOLLOWING("Following path"),
    RECEIVER("Receiver connected")
}

object SurveyRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Whether the app is on screen; GPS is released when it isn't and nothing needs it
    private val _appVisible = MutableStateFlow(false)
    val appVisible = _appVisible.asStateFlow()

    fun setAppVisible(visible: Boolean) {
        _appVisible.value = visible
    }

    // Live GNSS fix
    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation = _currentLocation.asStateFlow()

    private val _isMockLocationActive = MutableStateFlow(false)
    val isMockLocationActive = _isMockLocationActive.asStateFlow()

    // --- Mode 1: Path Tracker State ---
    private val _isRecordingPath = MutableStateFlow(false)
    val isRecordingPath = _isRecordingPath.asStateFlow()

    private val _pathPoints = MutableStateFlow<List<TrackPoint>>(emptyList())
    val pathPoints = _pathPoints.asStateFlow()

    private val _totalDistanceMeters = MutableStateFlow(0.0)
    val totalDistanceMeters = _totalDistanceMeters.asStateFlow()

    private val _maxAccuracyFilterMeters = MutableStateFlow(10.0f)
    val maxAccuracyFilterMeters = _maxAccuracyFilterMeters.asStateFlow()

    /** Fixes skipped for being worse than the gate since recording started (shown in the Path panel). */
    private val _pathRejectedFixes = MutableStateFlow(0)
    val pathRejectedFixes = _pathRejectedFixes.asStateFlow()

    // --- Mode 2: Measure state lives in MeasureRepository ---

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

    // --- Mode 4: Follow Imported Path ---
    private val _followSession = MutableStateFlow<FollowSession?>(null)
    val followSession = _followSession.asStateFlow()

    private var follower: PathFollower? = null

    val backgroundTask: StateFlow<BackgroundTask> = combine(
        _isRecordingPath, _followSession, MeasureRepository.occupation, ReceiverBridge.state
    ) { recording, follow, occupation, receiver ->
        when {
            occupation != null -> BackgroundTask.HOLDING
            recording -> BackgroundTask.RECORDING
            follow != null -> BackgroundTask.FOLLOWING
            // A connected receiver keeps running so other apps can use it as their location
            receiver is ReceiverBridge.State.Connected -> BackgroundTask.RECEIVER
            else -> BackgroundTask.IDLE
        }
    }.stateIn(scope, SharingStarted.Eagerly, BackgroundTask.IDLE)

    /** Fix quality of the built-in receiver bridge's latest fix, or null when the phone's GPS is the source. */
    private val _receiverQuality = MutableStateFlow<FixQuality?>(null)
    val receiverQuality = _receiverQuality.asStateFlow()

    /** "Stop GPS" from the notification: end anything that keeps GPS on in the background. */
    fun stopBackgroundWork() {
        stopPathRecording()
        stopFollowing()
        if (MeasureRepository.occupation.value != null) MeasureRepository.cancelStation()
        ReceiverBridge.disconnect()
    }

    @Suppress("DEPRECATION")
    private fun checkIsMock(loc: Location): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            loc.isMock
        } else {
            loc.isFromMockProvider
        }
    }

    /** Location from the phone's fused provider (phone GPS, or another app's mock feed). */
    fun onNewLocation(loc: Location) {
        // While the built-in receiver bridge is feeding, it is the only source
        // (fused updates would be our own mock copies or the weaker phone GPS).
        if (ReceiverBridge.isFeeding()) return
        _receiverQuality.value = null
        process(loc, checkIsMock(loc))
    }

    /** Location from the built-in receiver bridge. */
    fun onExternalFix(loc: Location, fix: ReceiverFix) {
        _receiverQuality.value = fix.quality
        process(loc, isExternal = true)
    }

    private fun process(loc: Location, isExternal: Boolean) {
        _currentLocation.value = loc
        _isMockLocationActive.value = isExternal

        // 1. Continuous Path Recording
        if (_isRecordingPath.value) {
            // Copy the values out: never keep the provider's Location object itself
            val fix = TrackPoint(
                lat = loc.latitude,
                lon = loc.longitude,
                alt = if (loc.hasAltitude()) loc.altitude else null,
                acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 999.0,
                t = System.currentTimeMillis()
            )
            val current = _pathPoints.value
            when (val d = PathRecording.evaluate(current.lastOrNull(), fix, _maxAccuracyFilterMeters.value.toDouble())) {
                is TrackDecision.Accept -> {
                    _totalDistanceMeters.value += d.addedMeters
                    _pathPoints.value = current + fix
                }
                TrackDecision.RejectAccuracy -> _pathRejectedFixes.value += 1
                TrackDecision.TooClose -> {}
            }
        }

        // 2. Station occupations (Measure)
        MeasureRepository.onFix(loc, _isMockLocationActive.value)

        // 3. Path Following Guidance
        val session = _followSession.value
        val activeFollower = follower
        if (session != null && activeFollower != null) {
            _followSession.value = session.copy(guidance = activeFollower.update(loc.latitude, loc.longitude))
        }
    }

    // --- Path Controls ---
    fun setAccuracyFilter(maxMeters: Float) {
        _maxAccuracyFilterMeters.value = maxMeters
    }

    fun startPathRecording() {
        _pathRejectedFixes.value = 0
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
            val delta = Geodesy.groundDistance(prev.lat, prev.lon, last.lat, last.lon)
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
            val lines = mutableListOf<ImportedLine>()
            val points = mutableListOf<Triple<Double, Double, String>>()

            fun parseLine(c: JSONArray?, label: String) {
                if (c == null) return
                val line = mutableListOf<LatLon>()
                for (i in 0 until c.length()) {
                    val pt = c.getJSONArray(i)
                    line.add(LatLon(pt.getDouble(1), pt.getDouble(0)))
                }
                if (line.isNotEmpty()) lines.add(ImportedLine(label, line))
            }

            fun parseGeometry(geom: JSONObject, label: String) {
                when (geom.optString("type")) {
                    "Point" -> {
                        val c = geom.optJSONArray("coordinates")
                        if (c != null && c.length() >= 2) {
                            points.add(Triple(c.getDouble(1), c.getDouble(0), label))
                        }
                    }
                    "LineString" -> parseLine(geom.optJSONArray("coordinates"), label)
                    "MultiLineString" -> {
                        val parts = geom.optJSONArray("coordinates") ?: return
                        for (i in 0 until parts.length()) {
                            parseLine(parts.getJSONArray(i), if (parts.length() > 1) "$label (${i + 1})" else label)
                        }
                    }
                    "Polygon" -> {
                        val rings = geom.optJSONArray("coordinates")
                        if (rings != null && rings.length() > 0) parseLine(rings.getJSONArray(0), label)
                    }
                    "MultiPolygon" -> {
                        val polys = geom.optJSONArray("coordinates") ?: return
                        for (i in 0 until polys.length()) {
                            val rings = polys.getJSONArray(i)
                            if (rings.length() > 0) parseLine(rings.getJSONArray(0), "$label (${i + 1})")
                        }
                    }
                    "GeometryCollection" -> {
                        val geoms = geom.optJSONArray("geometries") ?: return
                        for (i in 0 until geoms.length()) parseGeometry(geoms.getJSONObject(i), label)
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

            stopFollowing()
            _importedOverlay.value = ImportedGeoJsonOverlay(fileName, lines, points)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clearImportedOverlay() {
        stopFollowing()
        _importedOverlay.value = null
    }

    // --- Follow Path Controls ---
    fun startFollowing(lineIndex: Int, reversed: Boolean) {
        val line = _importedOverlay.value?.lines?.getOrNull(lineIndex) ?: return
        val path = if (reversed) line.points.reversed() else line.points
        val newFollower = PathFollower(path)
        follower = newFollower
        val guidance = _currentLocation.value?.let { newFollower.update(it.latitude, it.longitude) }
        _followSession.value = FollowSession(lineIndex, reversed, newFollower.points, guidance)
    }

    fun stopFollowing() {
        follower = null
        _followSession.value = null
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
                            put(pt.lon)
                            put(pt.lat)
                            pt.alt?.let { put(it) }
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
