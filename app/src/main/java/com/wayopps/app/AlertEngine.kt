package com.wayopps.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Distance and pace alerts while recording a path. Each alert plays its own chosen
 * system notification sound and posts a short notification (works with the screen off,
 * because recording keeps the location service in the foreground).
 */
object AlertEngine {

    private const val PREFS = "wayopps_alerts"
    private const val CHANNEL_ID = "wayopps_alerts"

    private val _distanceAlerts = MutableStateFlow<List<DistanceAlert>>(emptyList())
    val distanceAlerts = _distanceAlerts.asStateFlow()

    private val _pace = MutableStateFlow(PaceAlertSettings())
    val pace = _pace.asStateFlow()

    /** Live rolling pace while recording, s/km. */
    private val _currentPace = MutableStateFlow<Double?>(null)
    val currentPace = _currentPace.asStateFlow()

    private val paceMonitor = PaceMonitor()
    private var appContext: Context? = null
    private var playing: Ringtone? = null
    private var notificationId = 2000

    fun attach(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        load()
        // Silent channel: the alert's own chosen sound is played directly, so each alert can differ.
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Distance & pace alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Distance markers and pace warnings while recording"
                setSound(null, null)
                enableVibration(true)
            }
        )
    }

    // ---------------- settings ----------------
    fun saveDistanceAlerts(list: List<DistanceAlert>) {
        _distanceAlerts.value = list
        persist()
    }

    fun savePace(settings: PaceAlertSettings) {
        _pace.value = settings
        paceMonitor.reset()
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        for (a in _distanceAlerts.value) arr.put(JSONObject().apply {
            put("id", a.id); put("d", a.distanceM); put("rep", a.repeating)
            put("uri", a.soundUri ?: JSONObject.NULL); put("title", a.soundTitle); put("on", a.enabled)
        })
        val p = _pace.value
        val pace = JSONObject().apply {
            put("on", p.enabled); put("target", p.targetSecPerKm); put("slowPct", p.slowTolerancePct); put("fastPct", p.fastTolerancePct)
            put("slowUri", p.slowSoundUri ?: JSONObject.NULL); put("slowTitle", p.slowSoundTitle)
            put("fastUri", p.fastSoundUri ?: JSONObject.NULL); put("fastTitle", p.fastSoundTitle)
            put("repeat", p.repeatS); put("window", p.windowS)
        }
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString("distance", arr.toString())
            ?.putString("pace", pace.toString())
            ?.apply()
    }

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k)

    private fun load() {
        val p = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        try {
            val arr = JSONArray(p.getString("distance", "[]"))
            _distanceAlerts.value = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                DistanceAlert(o.getString("id"), o.getDouble("d"), o.getBoolean("rep"), o.str("uri"), o.optString("title", "Default"), o.optBoolean("on", true))
            }
            p.getString("pace", null)?.let { s ->
                val o = JSONObject(s)
                _pace.value = PaceAlertSettings(
                    enabled = o.optBoolean("on"), targetSecPerKm = o.optInt("target", 300),
                    slowTolerancePct = o.optDouble("slowPct", 10.0), fastTolerancePct = o.optDouble("fastPct", 10.0),
                    slowSoundUri = o.str("slowUri"), slowSoundTitle = o.optString("slowTitle", "Default"),
                    fastSoundUri = o.str("fastUri"), fastSoundTitle = o.optString("fastTitle", "Default"),
                    repeatS = o.optInt("repeat", 60), windowS = o.optInt("window", 30)
                )
            }
        } catch (e: Exception) {
            // Corrupt settings: start clean
        }
    }

    // ---------------- runtime ----------------
    fun reset() {
        paceMonitor.reset()
        _currentPace.value = null
    }

    /** Called with each newly logged point while recording. */
    fun onProgress(prevTotalM: Double, totalM: Double, points: List<TrackPoint>, movingMs: Long) {
        val pace = Pace.rollingSecPerKm(points, _pace.value.windowS)
        _currentPace.value = pace

        for ((alert, mark) in DistanceAlerts.crossed(_distanceAlerts.value, prevTotalM, totalM)) {
            val avg = if (totalM > 0) (movingMs / 1000.0) / (totalM / 1000.0) else null
            play(alert.soundUri)
            notify(
                "${formatKm(mark)} reached",
                "Time ${formatDuration(movingMs)} · avg ${Pace.format(avg)} /km" + (pace?.let { " · now ${Pace.format(it)} /km" } ?: "")
            )
        }

        val s = _pace.value
        when (paceMonitor.update(pace, s, System.currentTimeMillis())) {
            PaceZone.SLOW -> {
                play(s.slowSoundUri)
                notify("Too slow: ${Pace.format(pace)} /km", "Target ${Pace.format(s.targetSecPerKm.toDouble())} · range ${Pace.format(s.fastLimit)}–${Pace.format(s.slowLimit)} /km")
            }
            PaceZone.FAST -> {
                play(s.fastSoundUri)
                notify("Too fast: ${Pace.format(pace)} /km", "Target ${Pace.format(s.targetSecPerKm.toDouble())} · range ${Pace.format(s.fastLimit)}–${Pace.format(s.slowLimit)} /km")
            }
            else -> {}
        }
    }

    fun formatKm(m: Double): String = if (m >= 1000) "${"%.2f".format(m / 1000).trimEnd('0').trimEnd('.')} km" else "${m.toInt()} m"

    /** Plays [uri], or the phone's default notification sound when null. */
    fun play(uri: String?) {
        val ctx = appContext ?: return
        try {
            playing?.stop()
            val u: Uri = uri?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            playing = RingtoneManager.getRingtone(ctx, u)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
            vibrate()
        } catch (e: Exception) {
            // A sound that no longer exists shouldn't stop the recording
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val ctx = appContext ?: return
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1))
    }

    private fun notify(title: String, text: String) {
        val ctx = appContext ?: return
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try {
            nm.notify(
                notificationId++,
                NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_logo)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setTimeoutAfter(5 * 60_000L)
                    .build()
            )
        } catch (e: SecurityException) {
            // Notification permission withdrawn
        }
    }
}

/** User-editable list of feature types for point logging. All defaults can be removed. */
object FeatureTypes {
    val DEFAULTS = listOf(
        "Point of interest", "Landmark", "Viewpoint", "Junction", "Gate", "Bridge",
        "Water", "Tree", "Building", "Sign", "Parking", "Hazard"
    )
    private const val PREFS = "wayopps_feature_types"

    private val _types = MutableStateFlow(DEFAULTS)
    val types = _types.asStateFlow()
    private var appContext: Context? = null

    fun attach(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val saved = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("types", null)
        if (saved != null) {
            try {
                val arr = JSONArray(saved)
                _types.value = (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) { /* keep defaults */ }
        }
    }

    private fun save(list: List<String>) {
        _types.value = list
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString("types", JSONArray(list).toString())?.apply()
    }

    fun add(name: String) {
        val n = name.trim()
        if (n.isEmpty() || _types.value.any { it.equals(n, ignoreCase = true) }) return
        save(_types.value + n)
    }

    fun remove(name: String) = save(_types.value - name)
    fun clearAll() = save(emptyList())
    fun restoreDefaults() = save((DEFAULTS + _types.value).distinct())
}
