package com.wayopps.app

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

    /** History row for the recording in progress (null when nothing is recorded yet). */
    private val _currentActivityId = MutableStateFlow<Long?>(null)
    val currentActivityId = _currentActivityId.asStateFlow()

    // Moving time = sum of the recorded (not paused) stretches
    private var movingAccumulatedMs = 0L
    private var activeSinceMs: Long? = null

    fun movingTimeMs(now: Long = System.currentTimeMillis()): Long =
        movingAccumulatedMs + (activeSinceMs?.let { now - it } ?: 0L)

    /** Name of the recording in progress (shown in the Path panel, saved to History). */
    private val _recordingName = MutableStateFlow("")
    val recordingName = _recordingName.asStateFlow()

    // --- Courses & laps ---
    data class LapStatus(
        val courseName: String?,
        val lapNo: Int,
        val running: Boolean,
        val lapStartMs: Long,
        val fraction: Double,
        /** + behind / - ahead of the PB at the same point of the course, ms. */
        val gapMs: Long?,
        val offCourse: Boolean,
        val bestMs: Long?
    )

    /** Course selected for lap timing (persists between recordings until cleared). */
    private val _activeCourse = MutableStateFlow<Course?>(null)
    val activeCourse = _activeCourse.asStateFlow()

    private val _lapStatus = MutableStateFlow<LapStatus?>(null)
    val lapStatus = _lapStatus.asStateFlow()

    /** Laps completed in the current recording. */
    private val _laps = MutableStateFlow<List<LapResult>>(emptyList())
    val laps = _laps.asStateFlow()

    private var courseTimer: CourseTimer? = null
    private var pbMs: Long? = null
    private var pbSplits: List<Long>? = null
    // Manual laps (no course): measured on moving time and recorded distance
    private var manualLapStartMoving = 0L
    private var manualLapStartDist = 0.0
    private var manualLapStartWall = 0L

    private val _maxAccuracyFilterMeters = MutableStateFlow(10.0f)
    val maxAccuracyFilterMeters = _maxAccuracyFilterMeters.asStateFlow()

    /** Fixes skipped for being worse than the gate since recording started (shown in the Path panel). */
    private val _pathRejectedFixes = MutableStateFlow(0)
    val pathRejectedFixes = _pathRejectedFixes.asStateFlow()

    // --- Mode 2: Measure state lives in MeasureRepository ---

    // --- Map features live in MapRepository ---

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
                    val prevTotal = _totalDistanceMeters.value
                    val updated = current + fix
                    _totalDistanceMeters.value = prevTotal + d.addedMeters
                    _pathPoints.value = updated
                    _currentActivityId.value?.let { id ->
                        HistoryRepository.appendPoint(id, updated.size - 1, fix)
                        // Keep running totals saved in case the app is killed mid-recording
                        if (updated.size % 15 == 0) HistoryRepository.update(id, _totalDistanceMeters.value, movingTimeMs())
                    }
                    AlertEngine.onProgress(prevTotal, _totalDistanceMeters.value, updated, movingTimeMs())
                }
                TrackDecision.RejectAccuracy -> _pathRejectedFixes.value += 1
                TrackDecision.TooClose -> {}
            }

            // Course lap timing runs on every fix (not just logged points) for timing precision
            courseTimer?.let { timer ->
                if (fix.acc <= _maxAccuracyFilterMeters.value) {
                    when (val e = timer.update(fix.lat, fix.lon, fix.t)) {
                        is CourseTimer.Event.LapCompleted -> onCourseLap(e.result)
                        is CourseTimer.Event.LapStarted ->
                            if (e.lapNo == 1) AlertEngine.announce("Lap timing started", "${_activeCourse.value?.name ?: "Course"} · go!")
                        null -> {}
                    }
                }
                refreshLapStatus(fix.t)
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

    /** Start a new recording (optionally named), or resume a paused one. */
    fun startPathRecording(name: String? = null) {
        if (_isRecordingPath.value) return
        val now = System.currentTimeMillis()
        if (_currentActivityId.value == null) {
            _pathPoints.value = emptyList()
            _totalDistanceMeters.value = 0.0
            movingAccumulatedMs = 0L
            AlertEngine.reset()
            val type = HistoryRepository.activityType.value
            val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: HistoryRepository.defaultName(type, now)
            _recordingName.value = n
            _currentActivityId.value = HistoryRepository.begin(type, now, n)
            _laps.value = emptyList()
            manualLapStartMoving = 0L
            manualLapStartDist = 0.0
            manualLapStartWall = now
            courseTimer = _activeCourse.value?.let { CourseTimer(it) }
            courseTimer?.let { refreshLapStatus(now) }
        }
        _pathRejectedFixes.value = 0
        activeSinceMs = now
        _isRecordingPath.value = true
    }

    /** Pause (the recording stays open and can be resumed or finished). */
    fun stopPathRecording() {
        if (!_isRecordingPath.value) return
        activeSinceMs?.let { movingAccumulatedMs += System.currentTimeMillis() - it }
        activeSinceMs = null
        _isRecordingPath.value = false
        _currentActivityId.value?.let { HistoryRepository.update(it, _totalDistanceMeters.value, movingAccumulatedMs) }
    }

    fun renameRecording(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        _recordingName.value = n
        _currentActivityId.value?.let { HistoryRepository.rename(it, n) }
    }

    /**
     * Finish: save to History (with [name] and [type]) and clear the map, optionally also
     * saving the route as a course. Returns a short summary, or null if nothing was recorded.
     */
    fun finishRecording(name: String? = null, type: String? = null, saveAsCourse: Boolean = false): String? {
        stopPathRecording()
        val id = _currentActivityId.value ?: return null
        val pts = _pathPoints.value
        name?.let { renameRecording(it) }
        type?.let { HistoryRepository.setType(id, it) }
        val summary = if (pts.size >= 2) {
            HistoryRepository.update(id, _totalDistanceMeters.value, movingAccumulatedMs, endedAt = pts.last().t)
            if (saveAsCourse) {
                val latLons = pts.map { LatLon(it.lat, it.lon) }
                HistoryRepository.saveCourse(_recordingName.value, Courses.looksLikeLoop(latLons), latLons)
            }
            "Saved to History · ${AlertEngine.formatKm(_totalDistanceMeters.value)} in ${formatDuration(movingAccumulatedMs)}" +
                (if (saveAsCourse) " · course saved" else "")
        } else {
            HistoryRepository.delete(id)
            null
        }
        resetPath()
        return summary
    }

    private fun resetPath() {
        _currentActivityId.value = null
        _recordingName.value = ""
        _laps.value = emptyList()
        courseTimer = null
        _lapStatus.value = null
        _isRecordingPath.value = false
        activeSinceMs = null
        movingAccumulatedMs = 0L
        _pathPoints.value = emptyList()
        _totalDistanceMeters.value = 0.0
        AlertEngine.reset()
    }

    // ---------------- courses & laps ----------------
    /** Choose a course for lap timing (null clears it). Loads it and its PB off the main thread. */
    fun selectCourse(id: Long?) {
        if (id == null) {
            _activeCourse.value = null
            courseTimer = null
            _lapStatus.value = null
            pbMs = null
            pbSplits = null
            return
        }
        HistoryRepository.background {
            val c = HistoryRepository.course(id) ?: return@background
            val pb = HistoryRepository.personalBest(id)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                _activeCourse.value = c
                pbMs = pb?.elapsedMs
                pbSplits = pb?.splitsMs
                showCourseOnMap(c)
                // Selecting mid-recording starts timing straight away
                if (_currentActivityId.value != null) {
                    courseTimer = CourseTimer(c)
                    refreshLapStatus(System.currentTimeMillis())
                }
            }
        }
    }

    fun showCourseOnMap(c: Course) {
        stopFollowing()
        _importedOverlay.value = ImportedGeoJsonOverlay(
            fileName = "Course: ${c.name}",
            lines = listOf(ImportedLine(c.name, c.points)),
            points = listOf(Triple(c.points.first().lat, c.points.first().lon, if (c.loop) "Start / finish" else "Start")) +
                (if (c.loop) emptyList() else listOf(Triple(c.points.last().lat, c.points.last().lon, "Finish")))
        )
    }

    private fun refreshLapStatus(now: Long) {
        val timer = courseTimer ?: return
        val running = timer.phase == CourseTimer.Phase.RUNNING
        _lapStatus.value = LapStatus(
            courseName = _activeCourse.value?.name,
            lapNo = if (running) timer.lapNo else timer.lapNo + 1,
            running = running,
            lapStartMs = timer.lapStartMs,
            fraction = timer.lapFraction(),
            gapMs = if (running) Courses.gapToPb(pbSplits, timer.progressM, timer.elapsedMs(now)) else null,
            offCourse = running && timer.offCourse,
            bestMs = pbMs
        )
    }

    private fun onCourseLap(lap: LapResult) {
        val prevBest = pbMs
        val isPb = prevBest == null || lap.elapsedMs < prevBest
        _laps.value = _laps.value + lap
        HistoryRepository.saveLap(_currentActivityId.value, lap)
        if (isPb) {
            pbMs = lap.elapsedMs
            pbSplits = lap.splitsMs
        }
        val diff = prevBest?.let { lap.elapsedMs - it }
        AlertEngine.announce(
            "Lap ${lap.lapNo}: ${formatDuration(lap.elapsedMs)}" + if (isPb) " · New PB!" else "",
            (_activeCourse.value?.name ?: "Course") +
                (diff?.let { " · ${if (it <= 0) "−" else "+"}${formatDuration(kotlin.math.abs(it))} vs ${if (isPb) "previous " else ""}PB" } ?: " · first timed lap")
        )
    }

    /** Manual lap split (when no course is selected): time and distance since the last lap. */
    fun manualLap() {
        if (_currentActivityId.value == null) return
        val now = System.currentTimeMillis()
        val moving = movingTimeMs(now)
        val lap = LapResult(
            lapNo = _laps.value.size + 1,
            startedAt = manualLapStartWall,
            elapsedMs = moving - manualLapStartMoving,
            distanceM = _totalDistanceMeters.value - manualLapStartDist,
            splitsMs = emptyList(),
            courseId = null
        )
        _laps.value = _laps.value + lap
        HistoryRepository.saveLap(_currentActivityId.value, lap)
        manualLapStartMoving = moving
        manualLapStartDist = _totalDistanceMeters.value
        manualLapStartWall = now
        val pace = if (lap.distanceM >= 10) (lap.elapsedMs / 1000.0) / (lap.distanceM / 1000.0) else null
        AlertEngine.announce("Lap ${lap.lapNo}: ${formatDuration(lap.elapsedMs)}", "${AlertEngine.formatKm(lap.distanceM)} · ${Pace.format(pace)} /km")
    }

    /** Load a saved activity onto the map as an overlay (it can then be followed). */
    fun showActivityOnMap(name: String, points: List<TrackPoint>) {
        stopFollowing()
        _importedOverlay.value = ImportedGeoJsonOverlay(
            fileName = name,
            lines = listOf(ImportedLine(name, points.map { LatLon(it.lat, it.lon) })),
            points = emptyList()
        )
    }

    fun undoLastPathPoint() {
        val current = _pathPoints.value
        if (current.size >= 2) {
            val last = current.last()
            val prev = current[current.size - 2]
            val delta = Geodesy.groundDistance(prev.lat, prev.lon, last.lat, last.lon)
            _totalDistanceMeters.value = (_totalDistanceMeters.value - delta).coerceAtLeast(0.0)
            _pathPoints.value = current.dropLast(1)
            _currentActivityId.value?.let { HistoryRepository.deleteLastPoint(it) }
        } else if (current.size == 1) {
            _currentActivityId.value?.let { HistoryRepository.deleteLastPoint(it) }
            _pathPoints.value = emptyList()
            _totalDistanceMeters.value = 0.0
        }
    }

    /** Discard the current recording (not saved to History). */
    fun clearPath() {
        _currentActivityId.value?.let { HistoryRepository.delete(it) }
        resetPath()
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
    /** Follow a line from the loaded file. */
    fun startFollowing(lineIndex: Int, reversed: Boolean) {
        val line = _importedOverlay.value?.lines?.getOrNull(lineIndex) ?: return
        startFollowingPath(line.name, line.points, reversed)
    }

    /** Follow any path: a saved course, a past recording, a mapped line or a file line. */
    fun startFollowingPath(name: String, points: List<LatLon>, reversed: Boolean) {
        if (points.size < 2) return
        val path = if (reversed) points.reversed() else points
        val newFollower = PathFollower(path)
        follower = newFollower
        val guidance = _currentLocation.value?.let { newFollower.update(it.latitude, it.longitude) }
        _followSession.value = FollowSession(name, reversed, points, newFollower.points, guidance)
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

}
