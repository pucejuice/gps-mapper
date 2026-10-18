package com.wayopps.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Activity history in an on-device SQLite database. Points are written as they are
 * recorded, so a run survives the app being closed or killed mid-way. Activities older
 * than the chosen retention period are deleted automatically.
 */
data class CourseSummary(
    val id: Long,
    val name: String,
    val loop: Boolean,
    val lengthM: Double,
    val createdAt: Long,
    val bestMs: Long?,
    val lapCount: Int
)

object HistoryRepository {

    val ACTIVITY_TYPES = listOf("Run", "Ride", "Walk", "Hike", "Other")
    val RETENTION_OPTIONS = listOf(7 to "7 days", 30 to "30 days", 90 to "90 days", 365 to "1 year", 0 to "Forever")

    private const val PREFS = "wayopps_history"

    private class Db(context: Context) : SQLiteOpenHelper(context, "wayopps_history.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE activities (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    type TEXT NOT NULL,
                    started_at INTEGER NOT NULL,
                    ended_at INTEGER,
                    distance_m REAL NOT NULL DEFAULT 0,
                    moving_ms INTEGER NOT NULL DEFAULT 0)"""
            )
            db.execSQL(
                """CREATE TABLE points (
                    activity_id INTEGER NOT NULL REFERENCES activities(id) ON DELETE CASCADE,
                    seq INTEGER NOT NULL,
                    lat REAL NOT NULL, lon REAL NOT NULL, alt REAL, acc REAL NOT NULL, t INTEGER NOT NULL)"""
            )
            db.execSQL("CREATE INDEX idx_points ON points(activity_id, seq)")
            createV2(db)
        }

        /** v2: saved courses and lap times. */
        private fun createV2(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE courses (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    loop INTEGER NOT NULL,
                    length_m REAL NOT NULL,
                    points TEXT NOT NULL,
                    created_at INTEGER NOT NULL)"""
            )
            db.execSQL(
                """CREATE TABLE laps (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    activity_id INTEGER REFERENCES activities(id) ON DELETE SET NULL,
                    course_id INTEGER REFERENCES courses(id) ON DELETE CASCADE,
                    lap_no INTEGER NOT NULL,
                    started_at INTEGER NOT NULL,
                    elapsed_ms INTEGER NOT NULL,
                    distance_m REAL NOT NULL,
                    splits TEXT)"""
            )
            db.execSQL("CREATE INDEX idx_laps_course ON laps(course_id, elapsed_ms)")
            db.execSQL("CREATE INDEX idx_laps_activity ON laps(activity_id, lap_no)")
        }

        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createV2(db)
        }
    }

    private var db: Db? = null
    private var appContext: Context? = null
    /** Single writer thread keeps inserts off the UI thread and in order. */
    private val io = Executors.newSingleThreadExecutor()

    private val _activities = MutableStateFlow<List<ActivitySummary>>(emptyList())
    val activities = _activities.asStateFlow()

    private val _keepDays = MutableStateFlow(30)
    val keepDays = _keepDays.asStateFlow()

    private val _activityType = MutableStateFlow("Run")
    val activityType = _activityType.asStateFlow()

    private val _courses = MutableStateFlow<List<CourseSummary>>(emptyList())
    val courses = _courses.asStateFlow()

    fun attach(context: Context, currentActivityId: Long?) {
        if (db != null) return
        appContext = context.applicationContext
        db = Db(context.applicationContext)
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _keepDays.value = p.getInt("keep_days", 30)
        _activityType.value = p.getString("type", "Run") ?: "Run"
        io.execute {
            finishInterrupted(currentActivityId)
            purgeOld()
            refreshNow()
            refreshCoursesNow()
        }
    }

    fun setActivityType(type: String) {
        _activityType.value = type
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("type", type)?.apply()
    }

    fun setKeepDays(days: Int) {
        _keepDays.value = days
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putInt("keep_days", days)?.apply()
        io.execute { purgeOld(); refreshNow() }
    }

    // ---------------- recording ----------------
    fun defaultName(type: String, startedAt: Long) =
        "$type " + SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date(startedAt))

    /** Creates the activity row and returns its id (synchronous: one small insert). */
    fun begin(type: String, startedAt: Long, name: String = defaultName(type, startedAt)): Long? {
        val w = db?.writableDatabase ?: return null
        return w.insert("activities", null, ContentValues().apply {
            put("name", name); put("type", type); put("started_at", startedAt)
        }).takeIf { it > 0 }
    }

    fun appendPoint(activityId: Long, seq: Int, p: TrackPoint) = io.execute {
        db?.writableDatabase?.insert("points", null, ContentValues().apply {
            put("activity_id", activityId); put("seq", seq); put("lat", p.lat); put("lon", p.lon)
            if (p.alt != null) put("alt", p.alt) else putNull("alt")
            put("acc", p.acc); put("t", p.t)
        })
    }

    fun deleteLastPoint(activityId: Long) = io.execute {
        db?.writableDatabase?.execSQL(
            "DELETE FROM points WHERE activity_id = ? AND seq = (SELECT MAX(seq) FROM points WHERE activity_id = ?)",
            arrayOf(activityId, activityId)
        )
    }

    /** Saves running totals; with [endedAt] the activity is complete and appears in History. */
    fun update(activityId: Long, distanceM: Double, movingMs: Long, endedAt: Long? = null) = io.execute {
        db?.writableDatabase?.update("activities", ContentValues().apply {
            put("distance_m", distanceM); put("moving_ms", movingMs)
            if (endedAt != null) put("ended_at", endedAt)
        }, "id = ?", arrayOf(activityId.toString()))
        if (endedAt != null) { purgeOld(); refreshNow() }
    }

    fun delete(activityId: Long) = io.execute {
        // A deliberate delete also removes its laps (so a bad run can't hold a PB)
        db?.writableDatabase?.delete("laps", "activity_id = ?", arrayOf(activityId.toString()))
        db?.writableDatabase?.delete("activities", "id = ?", arrayOf(activityId.toString()))
        refreshNow()
        refreshCoursesNow()
    }

    fun setType(activityId: Long, type: String) = io.execute {
        db?.writableDatabase?.update("activities", ContentValues().apply { put("type", type) }, "id = ?", arrayOf(activityId.toString()))
        refreshNow()
    }

    fun rename(activityId: Long, name: String) = io.execute {
        db?.writableDatabase?.update("activities", ContentValues().apply { put("name", name.trim()) }, "id = ?", arrayOf(activityId.toString()))
        refreshNow()
    }

    // ---------------- queries (call off the main thread) ----------------
    fun points(activityId: Long): List<TrackPoint> {
        val r = db?.readableDatabase ?: return emptyList()
        val out = mutableListOf<TrackPoint>()
        r.rawQuery("SELECT lat, lon, alt, acc, t FROM points WHERE activity_id = ? ORDER BY seq", arrayOf(activityId.toString())).use { c ->
            while (c.moveToNext()) {
                out.add(TrackPoint(c.getDouble(0), c.getDouble(1), if (c.isNull(2)) null else c.getDouble(2), c.getDouble(3), c.getLong(4)))
            }
        }
        return out
    }

    // ---------------- courses & laps ----------------
    fun saveCourse(name: String, loop: Boolean, points: List<LatLon>, onSaved: (Long) -> Unit = {}) = io.execute {
        val pts = Courses.normalise(points, loop)
        val arr = org.json.JSONArray()
        for (p in pts) arr.put(org.json.JSONArray().put(p.lat).put(p.lon))
        val id = db?.writableDatabase?.insert("courses", null, ContentValues().apply {
            put("name", name.trim().ifEmpty { "Course" }); put("loop", if (loop) 1 else 0)
            put("length_m", PathFollower(pts).totalMeters); put("points", arr.toString())
            put("created_at", System.currentTimeMillis())
        }) ?: -1L
        refreshCoursesNow()
        if (id > 0) android.os.Handler(android.os.Looper.getMainLooper()).post { onSaved(id) }
    }

    fun renameCourse(id: Long, name: String) = io.execute {
        db?.writableDatabase?.update("courses", ContentValues().apply { put("name", name.trim()) }, "id = ?", arrayOf(id.toString()))
        refreshCoursesNow()
    }

    fun deleteCourse(id: Long) = io.execute {
        db?.writableDatabase?.delete("courses", "id = ?", arrayOf(id.toString()))
        refreshCoursesNow()
    }

    /** Full course with points (call off the main thread). */
    fun course(id: Long): Course? {
        val r = db?.readableDatabase ?: return null
        r.rawQuery("SELECT id, name, loop, length_m, points, created_at FROM courses WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return null
            val arr = org.json.JSONArray(c.getString(4))
            val pts = (0 until arr.length()).map { arr.getJSONArray(it).let { p -> LatLon(p.getDouble(0), p.getDouble(1)) } }
            return Course(c.getLong(0), c.getString(1), c.getInt(2) == 1, pts, c.getDouble(3), c.getLong(5))
        }
    }

    fun saveLap(activityId: Long?, lap: LapResult) = io.execute {
        db?.writableDatabase?.insert("laps", null, ContentValues().apply {
            if (activityId != null) put("activity_id", activityId) else putNull("activity_id")
            if (lap.courseId != null) put("course_id", lap.courseId) else putNull("course_id")
            put("lap_no", lap.lapNo); put("started_at", lap.startedAt); put("elapsed_ms", lap.elapsedMs)
            put("distance_m", lap.distanceM)
            put("splits", org.json.JSONArray(lap.splitsMs).toString())
        })
        refreshCoursesNow()
    }

    /** Best lap on a course (call off the main thread). */
    fun personalBest(courseId: Long): LapResult? = laps("course_id = ?", courseId, "elapsed_ms ASC", 1).firstOrNull()

    /** All laps on a course, fastest first (call off the main thread). */
    fun courseLaps(courseId: Long): List<LapResult> = laps("course_id = ?", courseId, "elapsed_ms ASC", 200)

    /** Laps of one activity in order (call off the main thread). */
    fun activityLaps(activityId: Long): List<LapResult> = laps("activity_id = ?", activityId, "lap_no ASC", 500)

    private fun laps(where: String, arg: Long, order: String, limit: Int): List<LapResult> {
        val r = db?.readableDatabase ?: return emptyList()
        val out = mutableListOf<LapResult>()
        r.rawQuery(
            "SELECT lap_no, started_at, elapsed_ms, distance_m, splits, course_id FROM laps WHERE $where ORDER BY $order LIMIT $limit",
            arrayOf(arg.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val splits = if (c.isNull(4)) emptyList() else org.json.JSONArray(c.getString(4)).let { a -> (0 until a.length()).map { a.getLong(it) } }
                out.add(LapResult(c.getInt(0), c.getLong(1), c.getLong(2), c.getDouble(3), splits, if (c.isNull(5)) null else c.getLong(5)))
            }
        }
        return out
    }

    private fun refreshCoursesNow() {
        val r = db?.readableDatabase ?: return
        val out = mutableListOf<CourseSummary>()
        r.rawQuery(
            """SELECT c.id, c.name, c.loop, c.length_m, c.created_at,
                      (SELECT MIN(elapsed_ms) FROM laps l WHERE l.course_id = c.id),
                      (SELECT COUNT(*) FROM laps l WHERE l.course_id = c.id)
               FROM courses c ORDER BY c.name COLLATE NOCASE""",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(CourseSummary(c.getLong(0), c.getString(1), c.getInt(2) == 1, c.getDouble(3), c.getLong(4),
                    if (c.isNull(5)) null else c.getLong(5), c.getInt(6)))
            }
        }
        _courses.value = out
    }

    private fun refreshNow() {
        val r = db?.readableDatabase ?: return
        val out = mutableListOf<ActivitySummary>()
        r.rawQuery(
            """SELECT a.id, a.name, a.type, a.started_at, a.ended_at, a.distance_m, a.moving_ms,
                      (SELECT COUNT(*) FROM points p WHERE p.activity_id = a.id)
               FROM activities a WHERE a.ended_at IS NOT NULL ORDER BY a.started_at DESC""",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ActivitySummary(
                        id = c.getLong(0), name = c.getString(1), type = c.getString(2), startedAt = c.getLong(3),
                        endedAt = if (c.isNull(4)) null else c.getLong(4), distanceM = c.getDouble(5),
                        movingMs = c.getLong(6), pointCount = c.getInt(7)
                    )
                )
            }
        }
        _activities.value = out
    }

    private fun purgeOld() {
        val cutoff = Gpx.retentionCutoff(System.currentTimeMillis(), _keepDays.value) ?: return
        db?.writableDatabase?.delete("activities", "ended_at IS NOT NULL AND started_at < ?", arrayOf(cutoff.toString()))
        // Course laps (PBs) outlive their activity; manual laps go with it
        db?.writableDatabase?.delete("laps", "activity_id IS NULL AND course_id IS NULL", null)
    }

    /** Recordings left open by the app being killed: close them using their saved points. */
    private fun finishInterrupted(currentActivityId: Long?) {
        val w = db?.writableDatabase ?: return
        val open = mutableListOf<Long>()
        w.rawQuery("SELECT id FROM activities WHERE ended_at IS NULL", null).use { c ->
            while (c.moveToNext()) open.add(c.getLong(0))
        }
        for (id in open) {
            if (id == currentActivityId) continue
            val pts = points(id)
            if (pts.size < 2) {
                w.delete("activities", "id = ?", arrayOf(id.toString()))
                continue
            }
            w.update("activities", ContentValues().apply {
                put("distance_m", PathRecording.totalMeters(pts))
                put("moving_ms", pts.last().t - pts.first().t)
                put("ended_at", pts.last().t)
            }, "id = ?", arrayOf(id.toString()))
        }
    }

    /** Runs [block] on the database thread (for exports / loading tracks). */
    fun background(block: () -> Unit) = io.execute(block)
}
