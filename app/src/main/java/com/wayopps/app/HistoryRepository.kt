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
object HistoryRepository {

    val ACTIVITY_TYPES = listOf("Run", "Ride", "Walk", "Hike", "Other")
    val RETENTION_OPTIONS = listOf(7 to "7 days", 30 to "30 days", 90 to "90 days", 365 to "1 year", 0 to "Forever")

    private const val PREFS = "wayopps_history"

    private class Db(context: Context) : SQLiteOpenHelper(context, "wayopps_history.db", null, 1) {
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
        }

        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
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
    /** Creates the activity row and returns its id (synchronous: one small insert). */
    fun begin(type: String, startedAt: Long): Long? {
        val w = db?.writableDatabase ?: return null
        val name = "$type " + SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date(startedAt))
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
        db?.writableDatabase?.delete("activities", "id = ?", arrayOf(activityId.toString()))
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
