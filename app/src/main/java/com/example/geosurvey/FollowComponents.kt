package com.example.geosurvey

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
    val azimuth = remember { mutableStateOf<Float?>(null) }

    DisposableEffect(context) {
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

        if (sensor != null) {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { sensorManager.unregisterListener(listener) }
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
    var selectedLine by remember(overlay) { mutableStateOf(overlay?.lines?.indexOfFirst { it.points.size >= 2 }?.coerceAtLeast(0) ?: 0) }
    var reversed by remember(overlay) { mutableStateOf(false) }
    var showLines by remember { mutableStateOf(false) }

    val lineLengths = remember(overlay) {
        overlay?.lines.orEmpty().map { PathFollower(it.points).totalMeters }
    }
    val hasLines = overlay?.lines.orEmpty().any { it.points.size >= 2 }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (!hasLines) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Load a .geojson with a line to follow.",
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = onLoadGeoJson) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Load", maxLines = 1)
                }
            }
            return@Column
        }

        if (followSession == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Line picker
                Box(modifier = Modifier.weight(1f)) {
                    val line = overlay?.lines?.getOrNull(selectedLine)
                    OutlinedButton(
                        onClick = { showLines = true },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "${line?.name ?: "Line"} · ${formatDistance(lineLengths.getOrElse(selectedLine) { 0.0 })}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = showLines, onDismissRequest = { showLines = false }) {
                        overlay?.lines?.forEachIndexed { index, l ->
                            if (l.points.size >= 2) {
                                DropdownMenuItem(
                                    text = { Text("${l.name} (${formatDistance(lineLengths.getOrElse(index) { 0.0 })})") },
                                    onClick = { selectedLine = index; showLines = false }
                                )
                            }
                        }
                    }
                }
                // Direction toggle
                FilledTonalButton(
                    onClick = { reversed = !reversed },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = "Swap direction", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(if (reversed) "E→S" else "S→E", maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = { SurveyRepository.startFollowing(selectedLine, reversed) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100), contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Go", maxLines = 1, softWrap = false)
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
                    onClick = { SurveyRepository.startFollowing(followSession.lineIndex, !followSession.reversed) },
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
