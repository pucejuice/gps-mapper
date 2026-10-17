package com.example.geosurvey

import android.content.Context
import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Multi-station straight line measuring, following the SLA web app's workflow:
 * occupy a station (timed, epoch-averaged hold), and every further station is
 * measured from the current origin. Any station can be made the origin at any
 * time; with chain on, each new station becomes the origin automatically.
 */
object MeasureRepository {

    /** A fix older than this is not trusted to represent where the phone is now. */
    private const val STALE_FIX_MS = 3000L
    private const val MIN_LINE_M = 0.5
    private const val STORE_FILE = "measure_v1.json"
    private const val PREFS = "geosurvey_measure"

    private val _points = MutableStateFlow<List<SurveyPoint>>(emptyList())
    val points = _points.asStateFlow()

    private val _lines = MutableStateFlow<List<MeasuredLine>>(emptyList())
    val lines = _lines.asStateFlow()

    private val _originId = MutableStateFlow<String?>(null)
    val originId = _originId.asStateFlow()

    private val _settings = MutableStateFlow(MeasureSettings())
    val settings = _settings.asStateFlow()

    private val _occupation = MutableStateFlow<OccupationProgress?>(null)
    val occupation = _occupation.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private var seq = 0
    private var latestFix: Fix? = null
    private var latestIsMock = false
    private var appContext: Context? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    private class Occupation(
        val name: String,
        val t0: Long,
        val dwellMs: Long,
        val epochs: Int,
        val gateM: Double,
        val wantAlt: Boolean
    ) {
        val fixes = mutableListOf<Fix>()
        var rejected = 0
        var mock = false
    }

    private var occ: Occupation? = null

    /** Session undo history (not persisted; after a restart undo falls back to last line / station). */
    private sealed class Action {
        class Occupied(val pointId: String, val lineId: String?, val prevOrigin: String?) : Action()
        class Linked(val lineIds: List<String>) : Action()
    }

    private val history = ArrayDeque<Action>()

    // ---------------- lifecycle / persistence ----------------
    fun attach(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        loadSettings()
        load()
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadSettings() {
        val p = prefs() ?: return
        _settings.value = MeasureSettings(
            dwellS = p.getInt("dwell_s", 30),
            epochs = p.getInt("epochs", 4),
            gateM = p.getFloat("gate_m", 15f).toDouble(),
            recordHeight = p.getBoolean("record_height", false),
            chain = p.getBoolean("chain", false)
        )
    }

    fun updateSettings(transform: (MeasureSettings) -> MeasureSettings) {
        val s = transform(_settings.value)
        _settings.value = s
        prefs()?.edit()
            ?.putInt("dwell_s", s.dwellS)
            ?.putInt("epochs", s.epochs)
            ?.putFloat("gate_m", s.gateM.toFloat())
            ?.putBoolean("record_height", s.recordHeight)
            ?.putBoolean("chain", s.chain)
            ?.apply()
    }

    private fun storeFile(): File? = appContext?.let { File(it.filesDir, STORE_FILE) }

    private fun save() {
        val f = storeFile() ?: return
        try {
            val root = JSONObject()
            root.put("seq", seq)
            root.put("originId", _originId.value ?: JSONObject.NULL)
            root.put("points", JSONArray().apply { _points.value.forEach { put(pointToJson(it)) } })
            root.put("lines", JSONArray().apply { _lines.value.forEach { put(lineToJson(it)) } })
            f.writeText(root.toString())
        } catch (e: Exception) {
            _events.tryEmit("Could not save records locally")
        }
    }

    private fun load() {
        val f = storeFile() ?: return
        if (!f.exists()) return
        try {
            val root = JSONObject(f.readText())
            seq = root.optInt("seq", 0)
            val pts = root.optJSONArray("points") ?: JSONArray()
            _points.value = (0 until pts.length()).map { pointFromJson(pts.getJSONObject(it)) }
            val lns = root.optJSONArray("lines") ?: JSONArray()
            _lines.value = (0 until lns.length()).map { lineFromJson(lns.getJSONObject(it)) }
            _originId.value = root.optString("originId").takeIf { id -> _points.value.any { it.id == id } }
        } catch (e: Exception) {
            _events.tryEmit("Saved records could not be read")
        }
    }

    private fun JSONObject.putNullable(key: String, v: Any?) = put(key, v ?: JSONObject.NULL)
    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (isNull(key) || !has(key)) null else getDouble(key)

    private fun statsToJson(s: OccupationStats) = JSONObject().apply {
        put("n_fixes", s.nFixes); put("epochs_used", s.epochsUsed); put("epochs_requested", s.epochsRequested)
        put("dwell_s", s.dwellS); putNullable("spread_m", s.spreadM); putNullable("std_err_m", s.stdErrM)
        put("reported_acc_m", s.reportedAccM); put("rejected", s.rejected); putNullable("gate_m", s.gateM)
        put("alt_n", s.altN); putNullable("alt_spread_m", s.altSpreadM)
    }

    private fun statsFromJson(o: JSONObject) = OccupationStats(
        nFixes = o.optInt("n_fixes"), epochsUsed = o.optInt("epochs_used"),
        epochsRequested = o.optInt("epochs_requested"), dwellS = o.optDouble("dwell_s"),
        spreadM = o.optDoubleOrNull("spread_m"), stdErrM = o.optDoubleOrNull("std_err_m"),
        reportedAccM = o.optDouble("reported_acc_m"), rejected = o.optInt("rejected"),
        gateM = o.optDoubleOrNull("gate_m"), altN = o.optInt("alt_n"), altSpreadM = o.optDoubleOrNull("alt_spread_m")
    )

    private fun pointToJson(p: SurveyPoint) = JSONObject().apply {
        put("id", p.id); put("name", p.name); put("lat", p.lat); put("lon", p.lon); putNullable("alt", p.alt)
        put("stats", statsToJson(p.stats)); put("t", p.occupiedUtc); put("mock", p.mockGnss)
    }

    private fun pointFromJson(o: JSONObject) = SurveyPoint(
        id = o.getString("id"), name = o.getString("name"), lat = o.getDouble("lat"), lon = o.getDouble("lon"),
        alt = o.optDoubleOrNull("alt"), stats = statsFromJson(o.getJSONObject("stats")),
        occupiedUtc = o.optLong("t"), mockGnss = o.optBoolean("mock")
    )

    private fun lineToJson(l: MeasuredLine) = JSONObject().apply {
        put("id", l.id); put("from", l.fromId); put("to", l.toId); put("d", l.distanceM); put("brg", l.bearingDeg)
        putNullable("sigma", l.sigmaM); put("linked", l.linked); put("t", l.timeUtc)
    }

    private fun lineFromJson(o: JSONObject) = MeasuredLine(
        id = o.getString("id"), fromId = o.getString("from"), toId = o.getString("to"),
        distanceM = o.getDouble("d"), bearingDeg = o.getDouble("brg"), sigmaM = o.optDoubleOrNull("sigma"),
        linked = o.optBoolean("linked"), timeUtc = o.optLong("t")
    )

    // ---------------- queries ----------------
    fun origin(): SurveyPoint? = _points.value.firstOrNull { it.id == _originId.value }
    fun point(id: String): SurveyPoint? = _points.value.firstOrNull { it.id == id }

    fun distance(a: SurveyPoint, lat: Double, lon: Double) = Geodesy.groundDistance(a.lat, a.lon, lat, lon)
    fun bearing(a: SurveyPoint, lat: Double, lon: Double) = Geodesy.gridBearing(a.lat, a.lon, lat, lon)

    /** Height difference to.alt - from.alt, only when heights are recorded on both. */
    fun heightDelta(line: MeasuredLine): Double? {
        if (!_settings.value.recordHeight) return null
        val a = point(line.fromId)?.alt ?: return null
        val b = point(line.toId)?.alt ?: return null
        return b - a
    }

    fun slopeDistance(line: MeasuredLine): Double? =
        heightDelta(line)?.let { sqrt(line.distanceM.pow(2) + it.pow(2)) }

    private fun linked(a: String, b: String) =
        _lines.value.any { (it.fromId == a && it.toId == b) || (it.fromId == b && it.toId == a) }

    // ---------------- GNSS ----------------
    fun onFix(loc: Location, isMock: Boolean) {
        val fix = Fix(
            lat = loc.latitude,
            lon = loc.longitude,
            acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 999.0,
            // Height is null when not reported, never a fabricated 0 m.
            alt = if (loc.hasAltitude()) loc.altitude else null,
            altAcc = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else null,
            t = System.currentTimeMillis()
        )
        latestFix = fix
        latestIsMock = isMock
        occ?.let { collect(it, fix, isMock) }
    }

    private fun collect(o: Occupation, fix: Fix, isMock: Boolean) {
        if (o.t0 - fix.t > STALE_FIX_MS) return
        if (fix.acc > o.gateM) {
            o.rejected++
            return
        }
        if (o.fixes.isNotEmpty() && o.fixes.last().t == fix.t) return
        o.fixes.add(fix)
        if (isMock) o.mock = true
    }

    // ---------------- occupation ----------------
    fun startStation() {
        if (occ != null) return
        val fix = latestFix
        if (fix == null) {
            _events.tryEmit("Waiting for a GPS fix")
            return
        }
        val s = _settings.value
        val o = Occupation(
            name = "ST${seq + 1}",
            t0 = System.currentTimeMillis(),
            dwellMs = s.dwellS * 1000L,
            epochs = s.epochs,
            gateM = s.gateM,
            wantAlt = s.recordHeight
        )
        occ = o
        // Seed with the fix in hand so the first second is not dead time, but only if it is current.
        if (o.t0 - fix.t <= STALE_FIX_MS) collect(o, fix, latestIsMock)
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                tick()
                delay(200)
            }
        }
    }

    private fun tick() {
        val o = occ ?: return
        val elapsed = System.currentTimeMillis() - o.t0
        val live = if (o.fixes.isEmpty()) null
        else Geodesy.reduceOccupation(o.fixes, o.epochs, o.dwellMs, o.t0, o.wantAlt)?.stats
        _occupation.value = OccupationProgress(
            name = o.name,
            dwellMs = o.dwellMs,
            epochs = o.epochs,
            elapsedMs = elapsed,
            rejected = o.rejected,
            gateM = o.gateM,
            live = live,
            waitingForFreshFix = o.fixes.isEmpty()
        )
        if (elapsed >= o.dwellMs) finish()
    }

    fun cancelStation() {
        ticker?.cancel()
        occ = null
        _occupation.value = null
        _events.tryEmit("Station cancelled")
    }

    private fun finish() {
        val o = occ ?: return
        ticker?.cancel()
        occ = null
        _occupation.value = null

        val r = Geodesy.reduceOccupation(o.fixes, o.epochs, o.dwellMs, o.t0, o.wantAlt)
        if (r == null) {
            _events.tryEmit("No fix passed the ±${fmt0(o.gateM)} m gate — nothing recorded")
            return
        }
        seq++
        val point = SurveyPoint(
            id = "ST-${o.t0}",
            name = o.name,
            lat = r.lat,
            lon = r.lon,
            alt = r.alt,
            stats = r.stats.copy(rejected = o.rejected, gateM = o.gateM),
            occupiedUtc = o.t0,
            mockGnss = o.mock
        )
        _points.value = _points.value + point

        val from = origin()
        val prevOrigin = _originId.value
        var newLineId: String? = null
        val spreadText = point.stats.spreadM?.let { "±${"%.2f".format(it)} m" } ?: "spread n/a"
        if (from == null) {
            _originId.value = point.id
            _events.tryEmit("${point.name} set as origin — $spreadText")
        } else {
            val d = Geodesy.groundDistance(from.lat, from.lon, point.lat, point.lon)
            if (d < MIN_LINE_M) {
                _events.tryEmit("${point.name} is within 0.5 m of ${from.name} — stored, no line")
            } else {
                val line = makeLine(from, point, linked = false)
                _lines.value = _lines.value + line
                newLineId = line.id
                _events.tryEmit(
                    "${from.name} → ${point.name}: ${"%.2f".format(line.distanceM)} m" +
                        (line.sigmaM?.let { " ±${"%.2f".format(it)}" } ?: "")
                )
            }
            if (_settings.value.chain) _originId.value = point.id
        }
        history.addLast(Action.Occupied(point.id, newLineId, prevOrigin))
        save()
    }

    private fun makeLine(a: SurveyPoint, b: SurveyPoint, linked: Boolean) = MeasuredLine(
        id = "L-${System.currentTimeMillis()}-${(Math.random() * 1e6).toInt()}",
        fromId = a.id,
        toId = b.id,
        distanceM = Geodesy.groundDistance(a.lat, a.lon, b.lat, b.lon),
        bearingDeg = Geodesy.gridBearing(a.lat, a.lon, b.lat, b.lon),
        sigmaM = Geodesy.distUncertainty(a.stats, b.stats),
        linked = linked,
        timeUtc = System.currentTimeMillis()
    )

    // ---------------- editing ----------------
    fun setOrigin(id: String) {
        val p = point(id) ?: return
        if (_originId.value == id) return
        _originId.value = id
        save()
        _events.tryEmit("Measuring from ${p.name}")
    }

    fun setChain(on: Boolean) {
        updateSettings { it.copy(chain = on) }
        _events.tryEmit(if (on) "Chain on — each new station becomes the origin" else "Chain off — origin stays put")
    }

    fun rename(id: String, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        _points.value = _points.value.map { if (it.id == id) it.copy(name = n) else it }
        save()
    }

    /** Candidate stations that [fromId] has no line to yet. */
    fun linkCandidates(fromId: String): List<SurveyPoint> =
        _points.value.filter { it.id != fromId && !linked(fromId, it.id) }

    /** Lines from [fromId] to each of [toIds], computed from stored coordinates. */
    fun link(fromId: String, toIds: List<String>) {
        val from = point(fromId) ?: return
        var made = 0
        var close = 0
        val added = mutableListOf<MeasuredLine>()
        for (tid in toIds) {
            val to = point(tid) ?: continue
            if (linked(from.id, to.id) || added.any { it.toId == to.id }) continue
            if (Geodesy.groundDistance(from.lat, from.lon, to.lat, to.lon) < MIN_LINE_M) {
                close++; continue
            }
            added.add(makeLine(from, to, linked = true))
            made++
        }
        if (made > 0) {
            _lines.value = _lines.value + added
            history.addLast(Action.Linked(added.map { it.id }))
            save()
        }
        _events.tryEmit(
            if (made > 0) "$made line${if (made == 1) "" else "s"} added" + (if (close > 0) " · $close skipped (< 0.5 m)" else "")
            else if (close > 0) "Skipped — stations within 0.5 m" else "Nothing to add"
        )
    }

    fun deleteLine(id: String) {
        _lines.value = _lines.value.filterNot { it.id == id }
        save()
    }

    fun deletePoint(id: String) {
        val p = point(id) ?: return
        _lines.value = _lines.value.filterNot { it.fromId == id || it.toId == id }
        _points.value = _points.value.filterNot { it.id == id }
        if (_originId.value == id) _originId.value = _points.value.lastOrNull()?.id
        if (p.name == "ST$seq" && _points.value.none { it.name == p.name }) seq = maxOf(0, seq - 1)
        save()
        _events.tryEmit("${p.name} deleted")
    }

    /** Steps back the most recent occupation (station and its line) or link. */
    fun undo() {
        when (val a = history.removeLastOrNull()) {
            is Action.Occupied -> {
                val name = point(a.pointId)?.name
                _lines.value = _lines.value.filterNot { it.fromId == a.pointId || it.toId == a.pointId }
                _points.value = _points.value.filterNot { it.id == a.pointId }
                if (name == "ST$seq") seq = maxOf(0, seq - 1)
                _originId.value = a.prevOrigin?.takeIf { point(it) != null } ?: _points.value.lastOrNull()?.id
                _events.tryEmit("${name ?: "Station"} removed")
            }
            is Action.Linked -> {
                _lines.value = _lines.value.filterNot { it.id in a.lineIds }
                _events.tryEmit("Linked lines removed")
            }
            null -> {
                // History lost (app restarted): step back the last line, else the last station.
                val lastLine = _lines.value.lastOrNull()
                if (lastLine != null) {
                    _lines.value = _lines.value.dropLast(1)
                    _events.tryEmit("Last line removed")
                } else {
                    _points.value.lastOrNull()?.let { deletePoint(it.id); return }
                }
            }
        }
        save()
    }

    fun canUndo() = history.isNotEmpty() || _lines.value.isNotEmpty() || _points.value.isNotEmpty()

    fun clearAll() {
        history.clear()
        _points.value = emptyList()
        _lines.value = emptyList()
        _originId.value = null
        seq = 0
        save()
    }

    // ---------------- export ----------------
    private fun fmt0(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
    private fun r7(v: Double) = Math.round(v * 1e7) / 1e7
    private fun r2(v: Double) = Math.round(v * 100) / 100.0
    private fun r3(v: Double) = Math.round(v * 1000) / 1000.0

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun iso(t: Long) = isoFmt.format(Date(t))

    private fun coords(p: SurveyPoint) = JSONArray().apply {
        put(r7(p.lon)); put(r7(p.lat))
        if (_settings.value.recordHeight && p.alt != null) put(r2(p.alt))
    }

    private fun methodNote(): String {
        val s = _settings.value
        return "Each station occupied for ${s.dwellS} s. Every GNSS fix in the window with accuracy better " +
            "than ±${fmt0(s.gateM)} m contributes to the position, weighted 1/accuracy^2. spread_m is the " +
            "sample DRMS of ${s.epochs} time-separated epoch means about that position; std_err_m is " +
            "spread_m/sqrt(epochs). Distances are horizontal ground distances on the WGS84 ellipsoid via a " +
            "local tangent plane at the line midpoint. Line uncertainty is the two endpoint spreads in quadrature."
    }

    private fun heightNote() = if (!_settings.value.recordHeight) "not recorded"
    else "GNSS height as reported by the device, uncorrected. Not AHD; no geoid separation applied."

    fun exportGeoJson(): String {
        val features = JSONArray()
        val recordHeight = _settings.value.recordHeight
        for (p in _points.value) {
            val s = p.stats
            features.put(JSONObject().apply {
                put("type", "Feature")
                put("properties", JSONObject().apply {
                    put("feature_type", "station")
                    put("name", p.name)
                    put("occupied_utc", iso(p.occupiedUtc))
                    put("n_fixes", s.nFixes)
                    put("epochs_used", s.epochsUsed)
                    put("epochs_requested", s.epochsRequested)
                    put("dwell_s", s.dwellS)
                    putNullable("spread_m", s.spreadM?.let(::r3))
                    putNullable("std_err_m", s.stdErrM?.let(::r3))
                    put("reported_acc_m", r2(s.reportedAccM))
                    put("rejected_fixes", s.rejected)
                    putNullable("gate_m", s.gateM)
                    putNullable("height_m", if (recordHeight) p.alt?.let(::r2) else null)
                    putNullable("height_spread_m", if (recordHeight) s.altSpreadM?.let(::r2) else null)
                    put("external_gnss", p.mockGnss)
                    put("height_datum", heightNote())
                })
                put("geometry", JSONObject().apply {
                    put("type", "Point")
                    put("coordinates", coords(p))
                })
            })
        }
        for (l in _lines.value) {
            val a = point(l.fromId) ?: continue
            val b = point(l.toId) ?: continue
            features.put(JSONObject().apply {
                put("type", "Feature")
                put("properties", JSONObject().apply {
                    put("feature_type", "measured_line")
                    put("label", "${a.name}-${b.name}")
                    put("from", a.name)
                    put("to", b.name)
                    put("distance_m", r3(l.distanceM))
                    putNullable("distance_uncertainty_m", l.sigmaM?.let(::r2))
                    put("bearing_deg", r2(l.bearingDeg))
                    putNullable("from_spread_m", a.stats.spreadM?.let(::r3))
                    putNullable("to_spread_m", b.stats.spreadM?.let(::r3))
                    putNullable("height_difference_m", heightDelta(l)?.let(::r2))
                    putNullable("slope_distance_m", slopeDistance(l)?.let(::r3))
                    put("linked", l.linked)
                    put("observation", if (l.linked) "computed from stored station coordinates" else "measured from origin at occupation")
                    put("time_utc", iso(l.timeUtc))
                    put("method", methodNote())
                    put("height_datum", heightNote())
                })
                put("geometry", JSONObject().apply {
                    put("type", "LineString")
                    put("coordinates", JSONArray().apply { put(coords(a)); put(coords(b)) })
                })
            })
        }
        return JSONObject().apply {
            put("type", "FeatureCollection")
            put("features", features)
        }.toString(2)
    }
}
