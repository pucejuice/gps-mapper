package com.wayopps.app

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Magnetic azimuth of the top of the screen (degrees, 0 = magnetic north), or null if no sensor. */
@Composable
fun rememberCompassAzimuth(): State<Float?> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val azimuth = remember { mutableStateOf<Float?>(null) }

    DisposableEffect(context, lifecycleOwner) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rotationMatrix = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        var smoothSin = 0.0
        var smoothCos = 1.0
        var initialised = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val (axisX, axisY) = when (displayRotation(context)) {
                    android.view.Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    android.view.Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    android.view.Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remapped)
                SensorManager.getOrientation(remapped, orientation)
                val raw = orientation[0].toDouble()

                // Low-pass filter on the unit circle so 359° -> 1° doesn't spin the arrow.
                val alpha = if (initialised) 0.15 else 1.0
                smoothSin += alpha * (sin(raw) - smoothSin)
                smoothCos += alpha * (cos(raw) - smoothCos)
                initialised = true
                val deg = Math.toDegrees(atan2(smoothSin, smoothCos))
                azimuth.value = ((deg + 360.0) % 360.0).toFloat()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        // Only listen while the app is on screen
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME ->
                    if (sensor != null) sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                Lifecycle.Event.ON_PAUSE -> sensorManager.unregisterListener(listener)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sensorManager.unregisterListener(listener)
        }
    }
    return azimuth
}

@Suppress("DEPRECATION")
private fun displayRotation(context: Context): Int = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display?.rotation ?: android.view.Surface.ROTATION_0
    } else {
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }
} catch (e: Exception) {
    android.view.Surface.ROTATION_0
}

/** True heading of travel: GPS course when moving, otherwise compass corrected for declination. */
fun resolveTrueHeading(location: Location?, magneticAzimuth: Float?): Pair<Double, String>? {
    if (location != null && location.hasBearing() && location.speed >= 1.5f) {
        return location.bearing.toDouble() to "GPS course"
    }
    if (magneticAzimuth == null) return null
    val declination = location?.let {
        GeomagneticField(
            it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()
        ).declination
    } ?: 0f
    return ((magneticAzimuth + declination + 360.0) % 360.0) to "Compass"
}

@Composable
fun DirectionArrow(rotationDegrees: Float, color: Color, size: Dp = 150.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawCircle(color = color.copy(alpha = 0.12f), radius = w / 2)
        drawCircle(color = color.copy(alpha = 0.35f), radius = w / 2, style = Stroke(width = 3f))
        rotate(rotationDegrees) {
            val arrow = Path().apply {
                moveTo(w * 0.5f, h * 0.10f)   // tip
                lineTo(w * 0.80f, h * 0.78f)
                lineTo(w * 0.5f, h * 0.62f)
                lineTo(w * 0.20f, h * 0.78f)
                close()
            }
            drawPath(arrow, color)
        }
        drawCircle(color = Color.White, radius = 5f, center = Offset(w / 2, h / 2))
    }
}

private fun formatDistance(m: Double): String =
    if (abs(m) >= 1000) "${"%.2f".format(m / 1000)} km" else "${"%.0f".format(m)} m"

@Composable
fun FollowPanel(
    overlay: ImportedGeoJsonOverlay?,
    followSession: FollowSession?,
    currentLocation: Location?,
    onLoadGeoJson: () -> Unit
) {
    val magneticAzimuth by rememberCompassAzimuth()
    val courses by HistoryRepository.courses.collectAsState()
    val activities by HistoryRepository.activities.collectAsState()
    val features by MapRepository.features.collectAsState()
    var chosen by remember { mutableStateOf<Pair<String, List<LatLon>>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reversed by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    /** Pick a path: show it on the map and remember it for Go. DB paths load off the main thread. */
    fun choose(name: String, load: () -> List<LatLon>, fromDb: Boolean) {
        showPicker = false
        val apply = { pts: List<LatLon> ->
            loading = false
            if (pts.size >= 2) {
                chosen = name to pts
                MapViewState.focus(pts)
            }
        }
        if (fromDb) {
            loading = true
            HistoryRepository.background {
                val pts = load()
                android.os.Handler(android.os.Looper.getMainLooper()).post { apply(pts) }
            }
        } else apply(load())
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (followSession == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Main choice: saved courses, past recordings, mapped lines, loaded file
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { showPicker = true },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (loading) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            chosen?.let { (n, pts) -> "$n · ${formatDistance(PathFollower(pts).totalMeters)}" } ?: "Choose a path to follow",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = showPicker, onDismissRequest = { showPicker = false }) {
                        if (courses.isNotEmpty()) {
                            MenuHeader("COURSES")
                            courses.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text("${c.name} · ${formatDistance(c.lengthM)}") },
                                    leadingIcon = { Icon(Icons.Default.Flag, null) },
                                    onClick = { choose(c.name, { HistoryRepository.course(c.id)?.points.orEmpty() }, fromDb = true) }
                                )
                            }
                        }
                        val lines = features.filter { it.kind != FeatureKind.POINT && it.vertices.size >= 2 }
                        if (lines.isNotEmpty()) {
                            MenuHeader("MAPPED")
                            lines.forEach { f ->
                                DropdownMenuItem(
                                    text = { Text("${f.label} · ${formatDistance(f.lengthM)}") },
                                    leadingIcon = { Icon(if (f.kind == FeatureKind.LINE) Icons.Default.Timeline else Icons.Default.CropSquare, null) },
                                    onClick = {
                                        choose(f.label, {
                                            val p = f.vertices.map { LatLon(it.lat, it.lon) }
                                            if (f.kind == FeatureKind.POLYGON) p + p.first() else p
                                        }, fromDb = false)
                                    }
                                )
                            }
                        }
                        if (activities.isNotEmpty()) {
                            MenuHeader("RECENT RECORDINGS")
                            activities.take(15).forEach { a ->
                                DropdownMenuItem(
                                    text = { Text("${a.name} · ${formatDistance(a.distanceM)}") },
                                    leadingIcon = { Icon(Icons.Default.History, null) },
                                    onClick = { choose(a.name, { HistoryRepository.points(a.id).map { LatLon(it.lat, it.lon) } }, fromDb = true) }
                                )
                            }
                        }
                        val fileLines = overlay?.lines.orEmpty().filter { it.points.size >= 2 }
                        if (fileLines.isNotEmpty()) {
                            MenuHeader("LOADED FILE")
                            fileLines.forEach { l ->
                                DropdownMenuItem(
                                    text = { Text("${l.name} · ${formatDistance(PathFollower(l.points).totalMeters)}") },
                                    leadingIcon = { Icon(Icons.Default.Description, null) },
                                    onClick = { choose(l.name, { l.points }, fromDb = false) }
                                )
                            }
                        }
                        if (courses.isEmpty() && lines.isEmpty() && activities.isEmpty() && fileLines.isEmpty()) {
                            Text(
                                "Nothing saved yet — record or map a path, or load a file.",
                                fontSize = 12.sp,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
                // Secondary: load a GeoJSON file
                IconButton(onClick = onLoadGeoJson) {
                    Icon(Icons.Default.FileOpen, contentDescription = "Load a GeoJSON file")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilledTonalButton(onClick = { reversed = !reversed }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = "Swap direction", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (reversed) "End → start" else "Start → end", maxLines = 1, softWrap = false)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { chosen?.let { (n, pts) -> SurveyRepository.startFollowingPath(n, pts, reversed) } },
                    enabled = chosen != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100), contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 14.dp)
                ) {
                    Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Follow", maxLines = 1, softWrap = false)
                }
            }
            return@Column
        }

        val guidance = followSession.guidance
        val heading = resolveTrueHeading(currentLocation, magneticAzimuth)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                guidance == null -> {
                    Text("Waiting for GNSS fix…", fontSize = 14.sp, modifier = Modifier.weight(1f))
                }
                guidance.arrived -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        "End of path reached",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
                else -> {
                    val relative = heading?.let { GeoMath.angleDiff(it.first, guidance.bearingToTargetDegrees) }
                    val onCourse = relative != null && abs(relative) < 20
                    DirectionArrow(
                        rotationDegrees = (relative ?: guidance.bearingToTargetDegrees).toFloat(),
                        color = if (onCourse) Color(0xFF43A047) else Color(0xFFFB8C00),
                        size = 76.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        val instruction = when {
                            relative == null -> "Head ${"%.0f".format(guidance.bearingToTargetDegrees)}° true"
                            abs(relative) < 15 -> "Straight ahead"
                            abs(relative) < 60 -> if (relative > 0) "Bear right" else "Bear left"
                            abs(relative) < 140 -> if (relative > 0) "Turn right" else "Turn left"
                            else -> "Turn around"
                        }
                        Text(instruction, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
                        val off = guidance.crossTrackMeters
                        val offText = when {
                            abs(off) < 2.0 -> "On path"
                            off > 0 -> "${formatDistance(abs(off))} right of path"
                            else -> "${formatDistance(abs(off))} left of path"
                        }
                        Text(
                            "$offText · ${formatDistance(guidance.remainingMeters)} to go",
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            heading?.second ?: "No compass: north-up",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalIconButton(
                    onClick = { SurveyRepository.startFollowingPath(followSession.name, followSession.sourcePoints, !followSession.reversed) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = "Reverse direction")
                }
                FilledIconButton(
                    onClick = { SurveyRepository.stopFollowing() },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFC62828), contentColor = Color.White),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop following")
                }
            }
        }
        if (guidance != null) {
            LinearProgressIndicator(
                progress = guidance.progress.toFloat(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun MenuHeader(text: String) = Text(
    text,
    fontSize = 11.sp,
    letterSpacing = 1.sp,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
)
