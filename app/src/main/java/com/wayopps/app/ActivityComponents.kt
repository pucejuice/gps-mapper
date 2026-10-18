package com.wayopps.app

import android.app.Activity
import android.content.Intent
import android.location.Location
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------- Path (activity recording) panel ----------------
/**
 * Three clear states:
 *  - no recording: pick type (and optionally a course), Start new
 *  - recording:    Pause, Lap
 *  - paused:       Resume (keep this track), Finish… (name it and save to History), Discard
 */
@Composable
fun PathPanel(
    currentLocation: Location?,
    onShowAlerts: () -> Unit,
    onShowCourses: () -> Unit
) {
    val context = LocalContext.current
    val isRecording by SurveyRepository.isRecordingPath.collectAsState()
    val pathPoints by SurveyRepository.pathPoints.collectAsState()
    val totalDistance by SurveyRepository.totalDistanceMeters.collectAsState()
    val activityId by SurveyRepository.currentActivityId.collectAsState()
    val name by SurveyRepository.recordingName.collectAsState()
    val gate by SurveyRepository.maxAccuracyFilterMeters.collectAsState()
    val rejected by SurveyRepository.pathRejectedFixes.collectAsState()
    val laps by SurveyRepository.laps.collectAsState()
    val lapStatus by SurveyRepository.lapStatus.collectAsState()
    val course by SurveyRepository.activeCourse.collectAsState()
    val courses by HistoryRepository.courses.collectAsState()
    val pace by AlertEngine.currentPace.collectAsState()
    val distanceAlerts by AlertEngine.distanceAlerts.collectAsState()
    val paceSettings by AlertEngine.pace.collectAsState()
    val type by HistoryRepository.activityType.collectAsState()
    var showTypes by remember { mutableStateOf(false) }
    var showCourseMenu by remember { mutableStateOf(false) }
    var showFinish by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    // Tick once a second so the times update
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isRecording) {
        while (isRecording) { now = System.currentTimeMillis(); delay(1000) }
        now = System.currentTimeMillis()
    }
    val movingMs = SurveyRepository.movingTimeMs(now)
    val avgPace = if (totalDistance >= 10) (movingMs / 1000.0) / (totalDistance / 1000.0) else null
    val hasRecording = activityId != null
    val alertsOn = distanceAlerts.any { it.enabled } || paceSettings.enabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (hasRecording) {
            // Title: the recording's name, tap to rename
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { renaming = true }
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(if (isRecording) Sla.stop else Sla.warn, androidx.compose.foundation.shape.CircleShape)
                )
                Spacer(Modifier.width(6.dp))
                Text(name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.Edit, contentDescription = "Rename", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (isRecording) "  recording" else "  paused", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Distance", formatMeters(totalDistance))
            Stat("Time", formatDuration(movingMs))
            Stat(if (isRecording) "Pace" else "Avg pace", "${Pace.format(if (isRecording) pace ?: avgPace else avgPace)} /km")
            Stat("Speed", currentLocation?.speed?.let { "${"%.1f".format(it * 3.6f)} km/h" } ?: "--")
        }

        // Lap line: course timing, or manual laps
        val ls = lapStatus
        if (hasRecording && ls != null) {
            val lapText = if (ls.running) {
                "Lap ${ls.lapNo} · ${formatDuration(now - ls.lapStartMs)} · ${(ls.fraction * 100).toInt()}%" +
                    (ls.gapMs?.let { g -> " · ${if (g <= 0) "−" else "+"}${formatDuration(kotlin.math.abs(g))} vs PB" } ?: "")
            } else "Go to the start of ${ls.courseName ?: "the course"} — timing starts as you pass it"
            Text(
                lapText + (ls.bestMs?.let { "   (PB ${formatDuration(it)})" } ?: ""),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    ls.offCourse -> Sla.warn
                    ls.gapMs == null -> Sla.accent
                    ls.gapMs <= 0 -> Sla.go
                    else -> Sla.stop
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (ls.offCourse) Text("Off course — get back on the line for the lap to count", fontSize = 11.sp, color = Sla.warn)
        } else if (hasRecording && laps.isNotEmpty()) {
            val last = laps.last()
            Text(
                "Lap ${last.lapNo}: ${formatDuration(last.elapsedMs)} · ${AlertEngine.formatKm(last.distanceM)}" +
                    "   best ${formatDuration(laps.minOf { it.elapsedMs })}",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Sla.accent,
                maxLines = 1
            )
        }

        // Status line, so it's obvious when the gate is skipping fixes
        val acc = currentLocation?.accuracy
        val gateText = if (gate < 1f) "%.1f".format(gate) else "%.0f".format(gate)
        val (status, warn) = when {
            !hasRecording -> (if (course != null) "Timing laps on ${course!!.name}. " else "") +
                "Start new begins a fresh track. Saved tracks and downloads are in ☰ › History." to false
            !isRecording -> "Paused — Resume continues this track · Save… names it and stores it in History · Discard throws it away" to false
            currentLocation == null -> "Recording · waiting for GPS…" to true
            acc != null && acc > gate ->
                "Fix ±${"%.1f".format(acc)} m is worse than the ±$gateText m gate — not logging (Settings)" to true
            else -> "● ±${"%.1f".format(acc ?: 0f)} m · ${pathPoints.size} pts" + (if (rejected > 0) " · $rejected skipped" else "") to false
        }
        Text(status, fontSize = 12.sp, color = if (warn) Sla.warn else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                !hasRecording -> {
                    // Type
                    Box {
                        OutlinedButton(onClick = { showTypes = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(type, maxLines = 1)
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = showTypes, onDismissRequest = { showTypes = false }) {
                            HistoryRepository.ACTIVITY_TYPES.forEach { t ->
                                DropdownMenuItem(text = { Text(t) }, onClick = { HistoryRepository.setActivityType(t); showTypes = false })
                            }
                        }
                    }
                    // Course (lap timing)
                    Box {
                        OutlinedButton(onClick = { showCourseMenu = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(course?.name ?: "No course", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 90.dp))
                        }
                        DropdownMenu(expanded = showCourseMenu, onDismissRequest = { showCourseMenu = false }) {
                            DropdownMenuItem(text = { Text("No course (free recording)") }, onClick = { SurveyRepository.selectCourse(null); showCourseMenu = false })
                            courses.forEach { c ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(c.name)
                                            Text(
                                                "${if (c.loop) "Loop" else "A→B"} · ${AlertEngine.formatKm(c.lengthM)}" +
                                                    (c.bestMs?.let { " · PB ${formatDuration(it)}" } ?: ""),
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = { SurveyRepository.selectCourse(c.id); showCourseMenu = false }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Manage courses…") },
                                leadingIcon = { Icon(Icons.Default.Settings, null) },
                                onClick = { showCourseMenu = false; onShowCourses() }
                            )
                        }
                    }
                    Button(
                        onClick = { SurveyRepository.startPathRecording() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32), contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text("Start new", maxLines = 1, softWrap = false)
                    }
                }
                isRecording -> {
                    Button(
                        onClick = { SurveyRepository.stopPathRecording() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Pause", maxLines = 1, softWrap = false)
                    }
                    // Manual lap (course laps are automatic)
                    if (course == null) {
                        OutlinedButton(onClick = { SurveyRepository.manualLap() }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Lap", maxLines = 1)
                        }
                    }
                    // Stop: pause and go straight to save / discard
                    OutlinedButton(
                        onClick = { SurveyRepository.stopPathRecording(); showFinish = true },
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop", maxLines = 1)
                    }
                }
                else -> {
                    Button(
                        onClick = { SurveyRepository.startPathRecording() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32), contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text("Resume", maxLines = 1, softWrap = false)
                    }
                    Button(
                        onClick = { showFinish = true },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save…", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(onClick = { showDiscard = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(2.dp))
                        Text("Discard", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                    }
                }
            }
            BadgedBox(badge = { if (alertsOn) Badge() }) {
                PanelIcon(if (alertsOn) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone, "Alerts", true, onShowAlerts)
            }
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Name this recording") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { SurveyRepository.renameRecording(text); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } }
        )
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("Discard this recording?") },
            text = { Text("${AlertEngine.formatKm(totalDistance)} · ${formatDuration(movingMs)} will not be saved to History.") },
            confirmButton = {
                TextButton(onClick = { SurveyRepository.clearPath(); showDiscard = false }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("Keep it") } }
        )
    }

    if (showFinish) {
        var text by remember { mutableStateOf(name) }
        var finishType by remember { mutableStateOf(type) }
        val looksLoop = remember(pathPoints.size) { Courses.looksLikeLoop(pathPoints.map { LatLon(it.lat, it.lon) }) }
        var asCourse by remember { mutableStateOf(false) }
        var startNew by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showFinish = false },
            title = { Text("Save this recording") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${AlertEngine.formatKm(totalDistance)} · ${formatDuration(movingMs)} · ${Pace.format(avgPace)} /km" +
                            (if (laps.isNotEmpty()) " · ${laps.size} lap${if (laps.size == 1) "" else "s"}" else ""),
                        fontSize = 13.sp
                    )
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Name") })
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        HistoryRepository.ACTIVITY_TYPES.forEach { t ->
                            FilterChip(selected = finishType == t, onClick = { finishType = t }, label = { Text(t, fontSize = 12.sp) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { startNew = !startNew }) {
                        Checkbox(checked = startNew, onCheckedChange = { startNew = it })
                        Text("Start a new recording straight after")
                    }
                    if (course == null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable(enabled = pathPoints.size >= 2) { asCourse = !asCourse }
                        ) {
                            Checkbox(checked = asCourse, onCheckedChange = { asCourse = it }, enabled = pathPoints.size >= 2)
                            Column {
                                Text("Also save the route as a course")
                                Text(
                                    if (looksLoop) "Looks like a loop — laps will be timed start→start"
                                    else "Point to point — timed from start to finish",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val msg = SurveyRepository.finishRecording(text, finishType, asCourse) ?: "Nothing recorded — discarded"
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    showFinish = false
                    if (startNew) SurveyRepository.startPathRecording()
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showFinish = false }) { Text("Back") }
            }
        )
    }
}

// ---------------- sound picker ----------------
/** Opens the system notification-sound picker; returns (uri or null for default, title). */
@Suppress("DEPRECATION")
private fun pickedUri(data: Intent?): Uri? =
    if (Build.VERSION.SDK_INT >= 33) data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
    else data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)

@Composable
fun rememberSoundPicker(onPicked: (String?, String) -> Unit): (String?) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri: Uri? = pickedUri(result.data)
        val title = uri?.let { RingtoneManager.getRingtone(context, it)?.getTitle(context) } ?: "Silent"
        onPicked(uri?.toString(), title)
    }
    return { current ->
        launcher.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alert sound")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        })
    }
}

@Composable
private fun SoundRow(label: String, title: String, uri: String?, onPick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.width(64.dp))
        OutlinedButton(onClick = onPick, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.MusicNote, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
        }
        IconButton(onClick = { AlertEngine.play(uri) }) { Icon(Icons.Default.PlayCircleOutline, "Test sound") }
    }
}

// ---------------- alerts dialog ----------------
@Composable
fun AlertsDialog(onDismiss: () -> Unit) {
    val alerts by AlertEngine.distanceAlerts.collectAsState()
    val pace by AlertEngine.pace.collectAsState()
    var pickingFor by remember { mutableStateOf<String?>(null) } // alert id, "slow" or "fast"

    val picker = rememberSoundPicker { uri, title ->
        when (val target = pickingFor) {
            "slow" -> AlertEngine.savePace(AlertEngine.pace.value.copy(slowSoundUri = uri, slowSoundTitle = title))
            "fast" -> AlertEngine.savePace(AlertEngine.pace.value.copy(fastSoundUri = uri, fastSoundTitle = title))
            null -> {}
            else -> AlertEngine.saveDistanceAlerts(AlertEngine.distanceAlerts.value.map {
                if (it.id == target) it.copy(soundUri = uri, soundTitle = title) else it
            })
        }
    }

    var newDistance by remember { mutableStateOf("1") }
    var newUnitKm by remember { mutableStateOf(true) }
    var newRepeating by remember { mutableStateOf(true) }
    var targetText by remember(pace.targetSecPerKm) { mutableStateOf(Pace.format(pace.targetSecPerKm.toDouble())) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alerts") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("DISTANCE", fontSize = 11.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary)
                if (alerts.isEmpty()) {
                    Text("No distance alerts yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                alerts.forEach { a ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    (if (a.repeating) "Every " else "At ") + AlertEngine.formatKm(a.distanceM),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(checked = a.enabled, onCheckedChange = { on ->
                                    AlertEngine.saveDistanceAlerts(alerts.map { if (it.id == a.id) it.copy(enabled = on) else it })
                                })
                                IconButton(onClick = { AlertEngine.saveDistanceAlerts(alerts.filterNot { it.id == a.id }) }) {
                                    Icon(Icons.Default.Delete, "Delete alert")
                                }
                            }
                            SoundRow("Sound", a.soundTitle, a.soundUri) { pickingFor = a.id; picker(a.soundUri) }
                        }
                    }
                }
                // Add a distance alert
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = newDistance,
                        onValueChange = { newDistance = it.filter { c -> c.isDigit() || c == '.' } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(80.dp)
                    )
                    FilterChip(selected = newUnitKm, onClick = { newUnitKm = !newUnitKm }, label = { Text(if (newUnitKm) "km" else "m") })
                    FilterChip(selected = newRepeating, onClick = { newRepeating = !newRepeating }, label = { Text(if (newRepeating) "Repeat" else "Once") })
                }
                Button(onClick = {
                    val v = newDistance.toDoubleOrNull()
                    if (v != null && v > 0) {
                        val m = if (newUnitKm) v * 1000 else v
                        AlertEngine.saveDistanceAlerts(alerts + DistanceAlert("d-${System.currentTimeMillis()}", m, newRepeating))
                    }
                }) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add distance alert")
                }

                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PACE", fontSize = 11.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    Switch(checked = pace.enabled, onCheckedChange = { AlertEngine.savePace(pace.copy(enabled = it)) })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Target", fontSize = 13.sp, modifier = Modifier.width(64.dp))
                    OutlinedTextField(
                        value = targetText,
                        onValueChange = { t ->
                            targetText = t
                            Pace.parse(t)?.let { AlertEngine.savePace(pace.copy(targetSecPerKm = it)) }
                        },
                        singleLine = true,
                        suffix = { Text("/km") },
                        isError = Pace.parse(targetText) == null,
                        modifier = Modifier.width(120.dp)
                    )
                }
                PctRow("Too slow", "+", pace.slowTolerancePct) { AlertEngine.savePace(pace.copy(slowTolerancePct = it)) }
                PctRow("Too fast", "−", pace.fastTolerancePct) { AlertEngine.savePace(pace.copy(fastTolerancePct = it)) }
                Text(
                    "Range ${Pace.format(pace.fastLimit)} – ${Pace.format(pace.slowLimit)} /km (pace over the last ${pace.windowS} s — change in Settings). " +
                        "Alerts when you leave the range, then every ${pace.repeatS} s until you're back in it.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SoundRow("Slow", pace.slowSoundTitle, pace.slowSoundUri) { pickingFor = "slow"; picker(pace.slowSoundUri) }
                SoundRow("Fast", pace.fastSoundTitle, pace.fastSoundUri) { pickingFor = "fast"; picker(pace.fastSoundUri) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Repeat", fontSize = 13.sp, modifier = Modifier.width(64.dp))
                    listOf(30, 60, 120).forEach { s ->
                        FilterChip(
                            selected = pace.repeatS == s,
                            onClick = { AlertEngine.savePace(pace.copy(repeatS = s)) },
                            label = { Text("${s}s", fontSize = 12.sp) },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun PctRow(label: String, sign: String, value: Double, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.width(64.dp))
        listOf(5.0, 10.0, 15.0, 20.0).forEach { p ->
            FilterChip(
                selected = value == p,
                onClick = { onChange(p) },
                label = { Text("$sign${p.toInt()}%", fontSize = 12.sp) },
                modifier = Modifier.padding(end = 4.dp)
            )
        }
    }
}

// ---------------- history sheet ----------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onShare: (String, String) -> Unit
) {
    val context = LocalContext.current
    val activities by HistoryRepository.activities.collectAsState()
    val keepDays by HistoryRepository.keepDays.collectAsState()
    var renaming by remember { mutableStateOf<ActivitySummary?>(null) }
    var deleting by remember { mutableStateOf<ActivitySummary?>(null) }
    var savingCourse by remember { mutableStateOf<ActivitySummary?>(null) }
    var lapsFor by remember { mutableStateOf<ActivitySummary?>(null) }
    val dateFmt = remember { SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.getDefault()) }

    /** Loads points off the main thread, then runs [then] on the main thread. */
    fun withPoints(a: ActivitySummary, then: (List<TrackPoint>) -> Unit) {
        HistoryRepository.background {
            val pts = HistoryRepository.points(a.id)
            android.os.Handler(android.os.Looper.getMainLooper()).post { then(pts) }
        }
    }

    fun fileStem(a: ActivitySummary) =
        "${a.type.lowercase()}_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(a.startedAt))

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Box(modifier = Modifier.fillMaxWidth().height(52.dp)) {
            VoronoiBackground(modifier = Modifier.matchParentSize(), seed = 9, cellSizePx = 120f, scrim = Color(0x8C0A0D10))
            Column(modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp)) {
                Text("HISTORY", fontSize = 11.sp, letterSpacing = 1.2.sp, color = Sla.textDim)
                val total = activities.sumOf { it.distanceM }
                Text(
                    "${activities.size} activit${if (activities.size == 1) "y" else "ies"} · ${AlertEngine.formatKm(total)}",
                    fontWeight = FontWeight.Bold,
                    color = Sla.text
                )
            }
        }
        // Retention + export all
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text("Keep", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(HistoryRepository.RETENTION_OPTIONS.firstOrNull { it.first == keepDays }?.second ?: "$keepDays days", fontSize = 12.sp)
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    HistoryRepository.RETENTION_OPTIONS.forEach { (d, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { HistoryRepository.setKeepDays(d); open = false })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                enabled = activities.isNotEmpty(),
                onClick = {
                    val list = activities
                    HistoryRepository.background {
                        val gpx = Gpx.write(list.map { it to HistoryRepository.points(it.id) })
                        android.os.Handler(android.os.Looper.getMainLooper()).post { onShare(gpx, "wayopps_history.gpx") }
                    }
                }
            ) {
                Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("All as GPX", fontSize = 12.sp)
            }
        }
        Text(
            "Recordings save automatically when you press Finish (and survive the app closing). Older ones are deleted after the time above.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 14.dp)
        )
        HorizontalDivider(Modifier.padding(top = 6.dp))

        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (activities.isEmpty()) {
                item { Text("No activities yet.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(activities, key = { it.id }) { a ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            withPoints(a) { pts ->
                                SurveyRepository.showActivityOnMap(a.name, pts)
                                onDismiss()
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Icon(
                        when (a.type) {
                            "Run" -> Icons.Default.DirectionsRun
                            "Ride" -> Icons.Default.DirectionsBike
                            "Walk" -> Icons.Default.DirectionsWalk
                            "Hike" -> Icons.Default.Hiking
                            else -> Icons.Default.Route
                        },
                        contentDescription = a.type,
                        tint = Sla.accent
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(a.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${AlertEngine.formatKm(a.distanceM)} · ${formatDuration(a.movingMs)} · " +
                                if (a.type == "Ride") "${a.avgKmh?.let { "%.1f".format(it) } ?: "--"} km/h" else "${Pace.format(a.avgSecPerKm)} /km",
                            fontSize = 13.sp
                        )
                        Text(dateFmt.format(Date(a.startedAt)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Actions") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Show on map") }, leadingIcon = { Icon(Icons.Default.Map, null) }, onClick = {
                                menu = false
                                withPoints(a) { pts -> SurveyRepository.showActivityOnMap(a.name, pts); onDismiss() }
                            })
                            DropdownMenuItem(text = { Text("Save GPX") }, leadingIcon = { Icon(Icons.Default.Save, null) }, onClick = {
                                menu = false
                                withPoints(a) { pts -> onSave(Gpx.write(listOf(a to pts)), fileStem(a) + ".gpx") }
                            })
                            DropdownMenuItem(text = { Text("Share GPX") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = {
                                menu = false
                                withPoints(a) { pts -> onShare(Gpx.write(listOf(a to pts)), fileStem(a) + ".gpx") }
                            })
                            DropdownMenuItem(text = { Text("Save GeoJSON") }, leadingIcon = { Icon(Icons.Default.Description, null) }, onClick = {
                                menu = false
                                withPoints(a) { pts -> onSave(activityGeoJson(a, pts), fileStem(a) + ".geojson") }
                            })
                            DropdownMenuItem(text = { Text("Laps") }, leadingIcon = { Icon(Icons.Default.Timer, null) }, onClick = {
                                menu = false; lapsFor = a
                            })
                            DropdownMenuItem(text = { Text("Save as course…") }, leadingIcon = { Icon(Icons.Default.Flag, null) }, onClick = {
                                menu = false; savingCourse = a
                            })
                            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = {
                                menu = false; renaming = a
                            })
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; deleting = a }
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }

    renaming?.let { a ->
        var name by remember(a.id) { mutableStateOf(a.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { HistoryRepository.rename(a.id, name); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }
    lapsFor?.let { a -> LapsDialog(title = "Laps · ${a.name}", load = { HistoryRepository.activityLaps(a.id) }, ranked = false, onDismiss = { lapsFor = null }) }
    savingCourse?.let { a ->
        var name by remember(a.id) { mutableStateOf(a.name) }
        var loop by remember(a.id) { mutableStateOf<Boolean?>(null) }
        var pts by remember(a.id) { mutableStateOf<List<LatLon>?>(null) }
        LaunchedEffect(a.id) {
            withPoints(a) { p ->
                val l = p.map { LatLon(it.lat, it.lon) }
                pts = l
                loop = Courses.looksLikeLoop(l)
            }
        }
        AlertDialog(
            onDismissRequest = { savingCourse = null },
            title = { Text("Save as course") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Course name") })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = loop == true, onClick = { loop = true }, label = { Text("Loop") })
                        FilterChip(selected = loop == false, onClick = { loop = false }, label = { Text("Point to point") })
                    }
                    Text(
                        "A loop is timed lap after lap as you pass the start; point to point is timed start → finish.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = pts != null && (pts?.size ?: 0) >= 2, onClick = {
                    HistoryRepository.saveCourse(name, loop == true, pts.orEmpty()) {
                        Toast.makeText(context, "Course saved — choose it on the Path tab to time laps", Toast.LENGTH_LONG).show()
                    }
                    savingCourse = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { savingCourse = null }) { Text("Cancel") } }
        )
    }
    deleting?.let { a ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${a.name}?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { HistoryRepository.delete(a.id); deleting = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

/** GeoJSON LineString for one saved activity, with its summary in the properties. */
fun activityGeoJson(a: ActivitySummary, pts: List<TrackPoint>): String {
    val coords = org.json.JSONArray()
    for (p in pts) coords.put(org.json.JSONArray().apply { put(p.lon); put(p.lat); p.alt?.let { put(it) } })
    return org.json.JSONObject().apply {
        put("type", "FeatureCollection")
        put("features", org.json.JSONArray().put(org.json.JSONObject().apply {
            put("type", "Feature")
            put("properties", org.json.JSONObject().apply {
                put("name", a.name); put("activity", a.type); put("started_utc", a.startedAt)
                put("distance_m", Math.round(a.distanceM * 10) / 10.0); put("moving_s", a.movingMs / 1000)
                a.avgSecPerKm?.let { put("avg_pace_s_per_km", Math.round(it)) }
            })
            put("geometry", org.json.JSONObject().apply { put("type", "LineString"); put("coordinates", coords) })
        }))
    }.toString(2)
}

// ---------------- courses ----------------
/** Saved courses: PB, best laps, show on map, rename, delete. */
@Composable
fun CoursesDialog(onDismiss: () -> Unit) {
    val courses by HistoryRepository.courses.collectAsState()
    val active by SurveyRepository.activeCourse.collectAsState()
    var leaderboardFor by remember { mutableStateOf<CourseSummary?>(null) }
    var renaming by remember { mutableStateOf<CourseSummary?>(null) }
    var deleting by remember { mutableStateOf<CourseSummary?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Courses") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Save a route as a course (Finish… or History › Save as course) and choose it before " +
                        "starting a recording: laps are timed automatically as you pass the start, with your gap to the PB.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (courses.isEmpty()) Text("No courses yet.", fontSize = 13.sp)
                courses.forEach { c ->
                    var menu by remember { mutableStateOf(false) }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
                            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(
                                    c.name + if (active?.id == c.id) "  ✓" else "",
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${if (c.loop) "Loop" else "A→B"} · ${AlertEngine.formatKm(c.lengthM)} · " +
                                        (c.bestMs?.let { "PB ${formatDuration(it)} · ${c.lapCount} lap${if (c.lapCount == 1) "" else "s"}" } ?: "no laps yet"),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Actions") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text(if (active?.id == c.id) "Selected for timing" else "Time laps on this") },
                                        leadingIcon = { Icon(Icons.Default.Timer, null) },
                                        onClick = { menu = false; SurveyRepository.selectCourse(c.id); onDismiss() })
                                    DropdownMenuItem(text = { Text("Best times") }, leadingIcon = { Icon(Icons.Default.EmojiEvents, null) },
                                        onClick = { menu = false; leaderboardFor = c })
                                    DropdownMenuItem(text = { Text("Show on map") }, leadingIcon = { Icon(Icons.Default.Map, null) }, onClick = {
                                        menu = false
                                        HistoryRepository.background {
                                            HistoryRepository.course(c.id)?.let { full ->
                                                android.os.Handler(android.os.Looper.getMainLooper()).post { SurveyRepository.showCourseOnMap(full); onDismiss() }
                                            }
                                        }
                                    })
                                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                                        onClick = { menu = false; renaming = c })
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = { menu = false; deleting = c })
                                }
                            }
                        }
                    }
                }
            }
        }
    )

    leaderboardFor?.let { c -> LapsDialog(title = "Best times · ${c.name}", load = { HistoryRepository.courseLaps(c.id) }, ranked = true, onDismiss = { leaderboardFor = null }) }
    renaming?.let { c ->
        var name by remember(c.id) { mutableStateOf(c.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename course") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { HistoryRepository.renameCourse(c.id, name); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${c.name}?") },
            text = { Text("Its ${c.lapCount} timed lap${if (c.lapCount == 1) "" else "s"} and PB are deleted too. Recordings in History are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    if (SurveyRepository.activeCourse.value?.id == c.id) SurveyRepository.selectCourse(null)
                    HistoryRepository.deleteCourse(c.id); deleting = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

/** Lap list, loaded off the main thread. [ranked] = fastest first with positions. */
@Composable
fun LapsDialog(title: String, load: () -> List<LapResult>, ranked: Boolean, onDismiss: () -> Unit) {
    var laps by remember { mutableStateOf<List<LapResult>?>(null) }
    LaunchedEffect(title) {
        HistoryRepository.background {
            val l = load()
            android.os.Handler(android.os.Looper.getMainLooper()).post { laps = l }
        }
    }
    val dateFmt = remember { SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        text = {
            val list = laps
            when {
                list == null -> CircularProgressIndicator()
                list.isEmpty() -> Text("No laps recorded.")
                else -> Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    val best = list.minOf { it.elapsedMs }
                    list.forEachIndexed { i, lap ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                            Text(if (ranked) "${i + 1}." else "Lap ${lap.lapNo}", modifier = Modifier.width(52.dp), fontSize = 13.sp)
                            Text(
                                formatDuration(lap.elapsedMs),
                                fontWeight = FontWeight.Bold,
                                color = if (lap.elapsedMs == best) Sla.go else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(72.dp)
                            )
                            Column {
                                val pace = if (lap.distanceM >= 10) (lap.elapsedMs / 1000.0) / (lap.distanceM / 1000.0) else null
                                Text("${AlertEngine.formatKm(lap.distanceM)} · ${Pace.format(pace)} /km", fontSize = 12.sp)
                                Text(dateFmt.format(Date(lap.startedAt)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    )
}
