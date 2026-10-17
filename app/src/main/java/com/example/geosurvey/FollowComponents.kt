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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Stop
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
    currentLocation: Location?
) {
    val magneticAzimuth by rememberCompassAzimuth()
    var selectedLine by remember(overlay) { mutableStateOf(0) }
    var reversed by remember(overlay) { mutableStateOf(false) }

    val lines = overlay?.lines.orEmpty().filter { it.points.size >= 2 }
    val lineLengths = remember(overlay) {
        overlay?.lines.orEmpty().map { PathFollower(it.points).totalMeters }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (lines.isEmpty()) {
            item {
                Column {
                    Text("Follow a Path", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Load a .geojson containing a LineString (or polygon) with the folder button at the top, " +
                            "then come back here to get a direction arrow guiding you along it.",
                        fontSize = 13.sp
                    )
                }
            }
            return@LazyColumn
        }

        if (followSession == null) {
            item { Text("Follow a Path", fontWeight = FontWeight.Bold, fontSize = 15.sp) }

            // Line selector (only relevant when the file holds several lines)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    overlay?.lines?.forEachIndexed { index, line ->
                        if (line.points.size >= 2) {
                            FilterChip(
                                selected = selectedLine == index,
                                onClick = { selectedLine = index },
                                label = {
                                    Text(
                                        "${line.name} (${formatDistance(lineLengths.getOrElse(index) { 0.0 })})",
                                        fontSize = 11.sp
                                    )
                                }
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Direction:", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    FilterChip(selected = !reversed, onClick = { reversed = false }, label = { Text("Start → End", fontSize = 11.sp) })
                    FilterChip(selected = reversed, onClick = { reversed = true }, label = { Text("End → Start", fontSize = 11.sp) })
                }
            }

            item {
                val validIndex = overlay?.lines?.getOrNull(selectedLine)?.points?.size?.let { it >= 2 } == true
                Button(
                    onClick = { SurveyRepository.startFollowing(selectedLine, reversed) },
                    enabled = validIndex,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Navigation, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Start Following")
                }
            }
            return@LazyColumn
        }

        val guidance = followSession.guidance
        val heading = resolveTrueHeading(currentLocation, magneticAzimuth)

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
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
                            modifier = Modifier.size(110.dp)
                        )
                        Text(
                            "You've reached the end of the path",
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
                            color = if (onCourse) Color(0xFF2E7D32) else Color(0xFFE65100),
                            size = 130.dp
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            val instruction = when {
                                relative == null -> "Head ${"%.0f".format(guidance.bearingToTargetDegrees)}° (true)"
                                abs(relative) < 15 -> "Straight ahead"
                                abs(relative) < 60 -> if (relative > 0) "Bear right" else "Bear left"
                                abs(relative) < 140 -> if (relative > 0) "Turn right" else "Turn left"
                                else -> "Turn around"
                            }
                            Text(instruction, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            val off = guidance.crossTrackMeters
                            val offText = when {
                                abs(off) < 2.0 -> "On path"
                                off > 0 -> "${formatDistance(abs(off))} right of path"
                                else -> "${formatDistance(abs(off))} left of path"
                            }
                            Text(offText, fontSize = 13.sp)
                            Text(
                                if (heading != null) "Heading source: ${heading.second}"
                                else "No compass: arrow is north-up",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }

        if (guidance != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Remaining", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                Text(formatDistance(guidance.remainingMeters), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Covered", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                Text(formatDistance(guidance.distanceAlongMeters), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Path Total", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                Text(formatDistance(guidance.totalMeters), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = guidance.progress.toFloat(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        item {
            val lineName = overlay?.lines?.getOrNull(followSession.lineIndex)?.name ?: "Path"
            Text(
                "Following: $lineName (${if (followSession.reversed) "End → Start" else "Start → End"})",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { SurveyRepository.startFollowing(followSession.lineIndex, !followSession.reversed) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Reverse")
                }
                Button(
                    onClick = { SurveyRepository.stopFollowing() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            }
        }
    }
}
