package com.example.geosurvey

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * High-accuracy GNSS updates. Runs only while the app is on screen, or while
 * something genuinely needs the background (recording a path, following a path,
 * or holding a station). Otherwise it stops, which releases GPS and clears the
 * location icon from the status bar.
 */
class TrackingService : Service() {

    companion object {
        private const val CHANNEL_ID = "geosurvey_tracking_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.example.geosurvey.STOP_TRACKING"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) {
                SurveyRepository.onNewLocation(location)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "GNSS Tracking", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while wayopps is using GPS"
            }
        )
        startForeground(NOTIFICATION_ID, buildNotification(BackgroundTask.IDLE))
        startLocationTracking()

        // Keep the notification describing what is running, and stop as soon as
        // the app is in the background with nothing that needs GPS.
        scope.launch {
            combine(
                SurveyRepository.appVisible,
                SurveyRepository.backgroundTask
            ) { visible, task -> visible to task }.collect { (visible, task) ->
                if (!visible && task == BackgroundTask.IDLE) {
                    stopSelf()
                } else {
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(task))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SurveyRepository.stopBackgroundWork()
            stopSelf()
        }
        // Never let the system restart GPS on its own after the process is killed.
        return START_NOT_STICKY
    }

    /** Swiping the app away from recents stops GPS unless a recording is running. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        SurveyRepository.setAppVisible(false)
        if (SurveyRepository.backgroundTask.value == BackgroundTask.IDLE) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(task: BackgroundTask) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(if (task == BackgroundTask.IDLE) "wayopps is using GPS" else "wayopps: ${task.label}")
        .setContentText(
            if (task == BackgroundTask.IDLE) "Stops when you leave the app"
            else "GPS stays on in the background until you stop"
        )
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .addAction(
            0, "Stop GPS",
            PendingIntent.getService(
                this, 1,
                Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun startLocationTracking() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            500L // 500ms intervals (supports 1Hz–2Hz GNSS receivers)
        ).apply {
            setMinUpdateIntervalMillis(200L)
            setMinUpdateDistanceMeters(0.0f) // Receive all epochs even when stationary for averaging
        }.build()

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
