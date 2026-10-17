package com.example.geosurvey

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var pendingGeoJsonToSave: String = ""

    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/geo+json")
    ) { uri: Uri? ->
        if (uri != null && pendingGeoJsonToSave.isNotEmpty()) {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(pendingGeoJsonToSave.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "GeoJSON saved to device!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (!text.isNullOrBlank()) {
                    val ok = SurveyRepository.importGeoJson(uri.lastPathSegment ?: "Overlay", text)
                    val msg = if (ok) "GeoJSON overlay loaded!" else "Invalid GeoJSON format"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen(
                    onStartService = { startTrackingService() },
                    onSaveGeoJson = { json, fileName ->
                        pendingGeoJsonToSave = json
                        saveFileLauncher.launch(fileName)
                    },
                    onShareGeoJson = { json, title -> shareGeoJson(json, title) },
                    onImportGeoJson = { importFileLauncher.launch("*/*") }
                )
            }
        }
    }

    private fun startTrackingService() {
        val serviceIntent = Intent(this, TrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun shareGeoJson(content: String, title: String) {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, content)
            putExtra(Intent.EXTRA_SUBJECT, title)
            type = "application/geo+json"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Share $title")
        startActivity(shareIntent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onStartService: () -> Unit,
    onSaveGeoJson: (String, String) -> Unit,
    onShareGeoJson: (String, String) -> Unit,
    onImportGeoJson: () -> Unit
) {
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineGranted) {
            onStartService()
        }
    }

    LaunchedEffect(Unit) {
        val fineLoc = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fineLoc == PackageManager.PERMISSION_GRANTED) {
            onStartService()
        } else {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    // Repository State Flows
    val curLocation by SurveyRepository.currentLocation.collectAsState()
    val isMockActive by SurveyRepository.isMockLocationActive.collectAsState()

    val isRecordingPath by SurveyRepository.isRecordingPath.collectAsState()
    val pathPoints by SurveyRepository.pathPoints.collectAsState()
    val totalDistance by SurveyRepository.totalDistanceMeters.collectAsState()
    val maxAccuracyFilter by SurveyRepository.maxAccuracyFilterMeters.collectAsState()

    val stations by SurveyRepository.stations.collectAsState()
    val sightLines by SurveyRepository.sightLines.collectAsState()
    val isSamplingEpochs by SurveyRepository.isSamplingEpochs.collectAsState()
    val currentEpochCount by SurveyRepository.currentEpochCount.collectAsState()
    val targetEpochs by SurveyRepository.targetEpochs.collectAsState()

    val featurePoints by SurveyRepository.featurePoints.collectAsState()
    val polygonAreaSqM by SurveyRepository.polygonAreaSqMeters.collectAsState()
    val polygonPerimeterM by SurveyRepository.polygonPerimeterMeters.collectAsState()

    val importedOverlay by SurveyRepository.importedOverlay.collectAsState()

    var selectedTab by remember { mutableStateOf(0) } // 0 = Path, 1 = SLA, 2 = Features/Area
    var selectedStationPreset by remember { mutableStateOf("Station A (Driveway)") }
    var selectedSpeedZoneKmh by remember { mutableStateOf(50) } // AS/NZS 2890.1 reference speed
    var selectedFeatureCategory by remember { mutableStateOf("Kerb/Edge") }
    var customFeatureNote by remember { mutableStateOf("") }

    // AS 2890.1 / Austroads Minimum Sight Distance lookup (meters)
    val requiredSightDistanceMeters = when (selectedSpeedZoneKmh) {
        40 -> 35.0
        50 -> 45.0
        60 -> 65.0
        70 -> 85.0
        80 -> 105.0
        else -> 45.0
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GeoSurvey GNSS", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    // Import GeoJSON Overlay Button
                    IconButton(onClick = onImportGeoJson) {
                        Icon(Icons.Default.FileOpen, contentDescription = "Load GeoJSON Overlay")
                    }

                    // Mock GNSS vs Phone GPS Indicator
                    curLocation?.let { loc ->
                        val sourceColor = if (isMockActive) Color(0xFF00695C) else Color(0xFFEF6C00)
                        val sourceLabel = if (isMockActive) "EXT GNSS" else "PHONE GPS"
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = sourceColor,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text(
                                text = sourceLabel,
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }

                        // Horizontal Accuracy Pill
                        val acc = loc.accuracy
                        val badgeColor = when {
                            acc <= 0.25f -> Color(0xFF2E7D32) // RTK Fix
                            acc <= 1.0f -> Color(0xFF1565C0)  // Sub-meter
                            acc <= 3.0f -> Color(0xFFE65100)  // DGPS
                            else -> Color(0xFFC62828)
                        }
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = badgeColor,
                            modifier = Modifier.padding(end = 10.dp)
                        ) {
                            Text(
                                text = "±${"%.2f".format(acc)}m",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 3-Mode Navigation Tabs
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Path", fontSize = 13.sp) },
                    icon = { Icon(Icons.Default.Route, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Sight Lines", fontSize = 13.sp) },
                    icon = { Icon(Icons.Default.Straighten, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Area / Survey", fontSize = 13.sp) },
                    icon = { Icon(Icons.Default.SquareFoot, contentDescription = null) }
                )
            }

            // Interactive OpenStreetMap View
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.95f)
            ) {
                OsmMapView(
                    modifier = Modifier.fillMaxSize(),
                    currentLocation = curLocation,
                    pathPoints = pathPoints,
                    stations = stations,
                    sightLines = sightLines,
                    featurePoints = featurePoints,
                    importedOverlay = importedOverlay
                )

                // Banner if an external GeoJSON overlay is active
                importedOverlay?.let { overlay ->
                    Surface(
                        color = Color(0xDD4A148C),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "Overlay: ${overlay.fileName}",
                                color = Color.White,
                                fontSize = 11.sp
                            )
                            Spacer(Modifier.width(6.dp))
                            IconButton(
                                onClick = { SurveyRepository.clearImportedOverlay() },
                                modifier = Modifier.size(18.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove Overlay",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }

            // Mode Control Panel
            Surface(
                tonalElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.05f)
            ) {
                when (selectedTab) {
                    // ==========================================
                    // TAB 0: PATH TRACKER (supreme-funicular)
                    // ==========================================
                    0 -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("Distance", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                            val distStr = if (totalDistance >= 1000) {
                                                "${"%.2f".format(totalDistance / 1000)} km"
                                            } else {
                                                "${"%.1f".format(totalDistance)} m"
                                            }
                                            Text(distStr, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("Points", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                            Text("${pathPoints.size}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("Elevation", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                            val altStr = curLocation?.altitude?.let { "${"%.1f".format(it)}m" } ?: "--"
                                            Text(altStr, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("Speed", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                            val spdStr = curLocation?.speed?.let { "${"%.1f".format(it * 3.6f)} km/h" } ?: "--"
                                            Text(spdStr, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            // Accuracy Filter Row
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Accuracy Gate:", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    listOf(0.5f to "≤0.5m (RTK)", 2.0f to "≤2m", 10.0f to "All").forEach { (gate, label) ->
                                        FilterChip(
                                            selected = maxAccuracyFilter == gate,
                                            onClick = { SurveyRepository.setAccuracyFilter(gate) },
                                            label = { Text(label, fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }

                            // Start / Stop / Undo / Clear Buttons
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (!isRecordingPath) {
                                        Button(
                                            onClick = { SurveyRepository.startPathRecording() },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                            modifier = Modifier.weight(1.3f)
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                                            Spacer(Modifier.width(4.dp))
                                            Text("Start")
                                        }
                                    } else {
                                        Button(
                                            onClick = { SurveyRepository.stopPathRecording() },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                                            modifier = Modifier.weight(1.3f)
                                        ) {
                                            Icon(Icons.Default.Stop, contentDescription = null)
                                            Spacer(Modifier.width(4.dp))
                                            Text("Pause")
                                        }
                                    }

                                    OutlinedButton(
                                        onClick = { SurveyRepository.undoLastPathPoint() },
                                        enabled = pathPoints.isNotEmpty(),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Undo, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Undo")
                                    }

                                    OutlinedButton(
                                        onClick = { SurveyRepository.clearPath() },
                                        enabled = pathPoints.isNotEmpty(),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Clear")
                                    }
                                }
                            }

                            // Save & Share Row
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            val json = SurveyRepository.exportPathGeoJson()
                                            onSaveGeoJson(json, "path_${System.currentTimeMillis() / 1000}.geojson")
                                        },
                                        enabled = pathPoints.isNotEmpty(),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Save, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Save .geojson")
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            val json = SurveyRepository.exportPathGeoJson()
                                            onShareGeoJson(json, "path_survey.geojson")
                                        },
                                        enabled = pathPoints.isNotEmpty(),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Share")
                                    }
                                }
                            }
                        }
                    }

                    // ==========================================
                    // TAB 1: SIGHT LINE ANALYSIS (SLA)
                    // ==========================================
                    1 -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Driveway Sight Line Survey",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    // Epoch Count Toggle (4 vs 10 epochs)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        listOf(4, 10).forEach { ep ->
                                            FilterChip(
                                                selected = targetEpochs == ep,
                                                onClick = { SurveyRepository.setTargetEpochs(ep) },
                                                label = { Text("${ep}x Avg", fontSize = 11.sp) }
                                            )
                                        }
                                    }
                                }
                            }

                            // AS/NZS 2890.1 Speed Zone Target Selector
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                ) {
                                    Text("Speed Zone:", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    listOf(40, 50, 60, 70, 80).forEach { spd ->
                                        FilterChip(
                                            selected = selectedSpeedZoneKmh == spd,
                                            onClick = { selectedSpeedZoneKmh = spd },
                                            label = { Text("${spd}km/h", fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }

                            // Station Selector Chips
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(
                                        "Station A (Driveway)",
                                        "Station B (Left Sight)",
                                        "Station C (Right Sight)",
                                        "Station D (Target)"
                                    ).forEach { preset ->
                                        FilterChip(
                                            selected = selectedStationPreset == preset,
                                            onClick = { selectedStationPreset = preset },
                                            label = { Text(preset, fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }

                            // Capture Station Button / Progress
                            item {
                                if (isSamplingEpochs) {
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                "Hold Stationary: Sampling Epoch ($currentEpochCount/$targetEpochs)...",
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            LinearProgressIndicator(
                                                progress = currentEpochCount.toFloat() / targetEpochs.toFloat(),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = { SurveyRepository.startStationCapture(selectedStationPreset) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.MyLocation, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Capture $selectedStationPreset ($targetEpochs Epochs)")
                                    }
                                }
                            }

                            // Calculated Sight Line Results
                            if (sightLines.isNotEmpty()) {
                                item {
                                    Text(
                                        "Sight Line Vectors (Min Req @ ${selectedSpeedZoneKmh}km/h: ${requiredSightDistanceMeters.toInt()}m)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                                items(sightLines) { line ->
                                    val passesStandard = line.horizontalDistanceMeters >= requiredSightDistanceMeters
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    "${line.fromStation.name} ➔ ${line.toStation.name}",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp
                                                )
                                                Surface(
                                                    color = if (passesStandard) Color(0xFF2E7D32) else Color(0xFFC62828),
                                                    shape = MaterialTheme.shapes.extraSmall
                                                ) {
                                                    Text(
                                                        text = if (passesStandard) "PASS (≥${requiredSightDistanceMeters.toInt()}m)" else "SHORT (<${requiredSightDistanceMeters.toInt()}m)",
                                                        color = Color.White,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text("2D Dist: ${"%.2f".format(line.horizontalDistanceMeters)} m", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                                Text("3D Slope: ${"%.2f".format(line.slopeDistanceMeters)} m", fontSize = 12.sp)
                                            }
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text("Grade: ${"%.1f".format(line.gradePercent)}% (Δh ${"%.2f".format(line.verticalDeltaMeters)}m)", fontSize = 12.sp)
                                                Text("Bearing: ${"%.1f".format(line.bearingDegrees)}°", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }

                            // Captured Stations List
                            if (stations.isNotEmpty()) {
                                item {
                                    Text("Averaged Stations (${stations.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                items(stations) { st ->
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(st.name, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                            Text(
                                                "Alt ${"%.1f".format(st.altitude)}m | Spread ±${"%.2f".format(st.epochSpreadMeters)}m",
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }

                            // Export / Reset Row
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            val json = SurveyRepository.exportSlaGeoJson()
                                            onSaveGeoJson(json, "sight_lines_${System.currentTimeMillis() / 1000}.geojson")
                                        },
                                        enabled = stations.isNotEmpty(),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Save, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Save SLA")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val json = SurveyRepository.exportSlaGeoJson()
                                            onShareGeoJson(json, "driveway_sight_lines.geojson")
                                        },
                                        enabled = stations.isNotEmpty(),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Share")
                                    }
                                    OutlinedButton(
                                        onClick = { SurveyRepository.clearStations() },
                                        enabled = stations.isNotEmpty(),
                                        modifier = Modifier.weight(0.8f)
                                    ) {
                                        Text("Reset")
                                    }
                                }
                            }
                        }
                    }

                    // ==========================================
                    // TAB 2: FEATURE & AREA SURVEY
                    // ==========================================
                    2 -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Live Area & Perimeter Metrics Card
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceAround
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("Enclosed Area", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                            val areaStr = if (polygonAreaSqM >= 10000.0) {
                                                "${"%.3f".format(polygonAreaSqM / 10000.0)} ha"
                                            } else {
                                                "${"%.1f".format(polygonAreaSqM)} m²"
                                            }
                                            Text(areaStr, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("Perimeter", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                            Text("${"%.1f".format(polygonPerimeterM)} m", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("Logged Features", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                            Text("${featurePoints.size}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            // Feature Category Presets
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(
                                        "Kerb/Edge",
                                        "Boundary",
                                        "Pit/Drain",
                                        "Power/Util",
                                        "Tree",
                                        "Sign/Post"
                                    ).forEach { cat ->
                                        FilterChip(
                                            selected = selectedFeatureCategory == cat,
                                            onClick = { selectedFeatureCategory = cat },
                                            label = { Text(cat, fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }

                            // Optional Note Input + Capture Point Button
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = customFeatureNote,
                                        onValueChange = { customFeatureNote = it },
                                        label = { Text("Feature Note (optional)", fontSize = 12.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = {
                                            SurveyRepository.captureFeaturePoint(
                                                category = selectedFeatureCategory,
                                                note = customFeatureNote,
                                                includeInPolygon = true
                                            )
                                            customFeatureNote = ""
                                        }
                                    ) {
                                        Icon(Icons.Default.AddLocationAlt, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Log Pt")
                                    }
                                }
                            }

                            // Actions Row: Undo, Clear, Save, Share
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            val json = SurveyRepository.exportFeaturesGeoJson()
                                            onSaveGeoJson(json, "features_${System.currentTimeMillis() / 1000}.geojson")
                                        },
                                        enabled = featurePoints.isNotEmpty(),
                                        modifier = Modifier.weight(1.2f)
                                    ) {
                                        Icon(Icons.Default.Save, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Save")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val json = SurveyRepository.exportFeaturesGeoJson()
                                            onShareGeoJson(json, "feature_survey.geojson")
                                        },
                                        enabled = featurePoints.isNotEmpty(),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Share")
                                    }
                                    OutlinedButton(
                                        onClick = { SurveyRepository.undoLastFeaturePoint() },
                                        enabled = featurePoints.isNotEmpty(),
                                        modifier = Modifier.weight(0.9f)
                                    ) {
                                        Text("Undo")
                                    }
                                    OutlinedButton(
                                        onClick = { SurveyRepository.clearFeatures() },
                                        enabled = featurePoints.isNotEmpty(),
                                        modifier = Modifier.weight(0.9f)
                                    ) {
                                        Text("Clear")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
