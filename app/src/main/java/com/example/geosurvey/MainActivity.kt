package com.example.geosurvey

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat

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
        val settings = getSharedPreferences("geosurvey_settings", MODE_PRIVATE)
        setContent {
            var darkTheme by remember { mutableStateOf(settings.getBoolean("dark_theme", true)) }
            var mapStyle by remember {
                mutableStateOf(MapStyle.fromName(settings.getString("map_style", null), darkTheme))
            }
            MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
                MainScreen(
                    darkTheme = darkTheme,
                    onDarkThemeChange = { dark ->
                        darkTheme = dark
                        // Swap the matching basemap along with the theme
                        if (mapStyle == MapStyle.DARK && !dark) mapStyle = MapStyle.STREETS
                        if (mapStyle == MapStyle.STREETS && dark) mapStyle = MapStyle.DARK
                        settings.edit()
                            .putBoolean("dark_theme", dark)
                            .putString("map_style", mapStyle.name)
                            .apply()
                    },
                    mapStyle = mapStyle,
                    onMapStyleChange = { style ->
                        mapStyle = style
                        settings.edit().putString("map_style", style.name).apply()
                    },
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
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    mapStyle: MapStyle,
    onMapStyleChange: (MapStyle) -> Unit,
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

    val measurePoints by SurveyRepository.measurePoints.collectAsState()
    val measurement by SurveyRepository.measurement.collectAsState()
    val samplingSlot by SurveyRepository.samplingSlot.collectAsState()
    val isSamplingEpochs by SurveyRepository.isSamplingEpochs.collectAsState()
    val currentEpochCount by SurveyRepository.currentEpochCount.collectAsState()
    val targetEpochs by SurveyRepository.targetEpochs.collectAsState()

    val featurePoints by SurveyRepository.featurePoints.collectAsState()
    val polygonAreaSqM by SurveyRepository.polygonAreaSqMeters.collectAsState()
    val polygonPerimeterM by SurveyRepository.polygonPerimeterMeters.collectAsState()

    val importedOverlay by SurveyRepository.importedOverlay.collectAsState()
    val followSession by SurveyRepository.followSession.collectAsState()

    var selectedTab by remember { mutableStateOf(0) } // 0 = Path, 1 = Measure, 2 = Features/Area, 3 = Follow
    var selectedFeatureCategory by remember { mutableStateOf("Kerb/Edge") }
    var customFeatureNote by remember { mutableStateOf("") }

    // Match system bars to the theme
    val view = LocalView.current
    val barColor = MaterialTheme.colorScheme.surfaceContainer
    val navColor = MaterialTheme.colorScheme.surface
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = barColor.toArgb()
            window.navigationBarColor = navColor.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    var showMenu by remember { mutableStateOf(false) }

    // Keep the screen awake while following a path
    val isFollowing = followSession != null
    DisposableEffect(isFollowing) {
        val window = (context as? Activity)?.window
        if (isFollowing) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "GeoSurvey",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    // Compact GNSS status: source (EXT / GPS) + horizontal accuracy, coloured by accuracy
                    curLocation?.let { loc ->
                        val acc = loc.accuracy
                        val badgeColor = when {
                            acc <= 0.25f -> Color(0xFF2E7D32) // RTK Fix
                            acc <= 1.0f -> Color(0xFF1565C0)  // Sub-meter
                            acc <= 3.0f -> Color(0xFFE65100)  // DGPS
                            else -> Color(0xFFC62828)
                        }
                        Surface(shape = MaterialTheme.shapes.small, color = badgeColor) {
                            Text(
                                text = "${if (isMockActive) "EXT" else "GPS"} ±${"%.2f".format(acc)}m",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }

                    // Menu
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Load GeoJSON overlay") },
                                leadingIcon = { Icon(Icons.Default.FileOpen, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    onImportGeoJson()
                                }
                            )
                            if (importedOverlay != null) {
                                DropdownMenuItem(
                                    text = { Text("Remove overlay") },
                                    leadingIcon = { Icon(Icons.Default.LayersClear, contentDescription = null) },
                                    onClick = {
                                        showMenu = false
                                        SurveyRepository.clearImportedOverlay()
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Dark theme") },
                                leadingIcon = { Icon(Icons.Default.DarkMode, contentDescription = null) },
                                trailingIcon = { Switch(checked = darkTheme, onCheckedChange = null) },
                                onClick = { onDarkThemeChange(!darkTheme) }
                            )
                            HorizontalDivider()
                            Text(
                                "Map layer",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                            MapStyle.values().forEach { style ->
                                DropdownMenuItem(
                                    text = { Text(style.label) },
                                    leadingIcon = { RadioButton(selected = style == mapStyle, onClick = null) },
                                    onClick = {
                                        onMapStyleChange(style)
                                        showMenu = false
                                    }
                                )
                            }
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
            // Mode Navigation Tabs
            TabRow(selectedTabIndex = selectedTab) {
                listOf(
                    "Path" to Icons.Default.Route,
                    "Measure" to Icons.Default.Straighten,
                    "Area" to Icons.Default.SquareFoot,
                    "Follow" to Icons.Default.Navigation
                ).forEachIndexed { index, (label, icon) ->
                    Tab(selected = selectedTab == index, onClick = { selectedTab = index }) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 6.dp)
                        ) {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                            Text(label, fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
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
                    measurePoints = measurePoints,
                    featurePoints = featurePoints,
                    importedOverlay = importedOverlay,
                    followSession = followSession,
                    mapStyle = mapStyle,
                    onMapStyleChange = onMapStyleChange
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
                                        Text("Undo", maxLines = 1, softWrap = false)
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
                                        Text("Save", maxLines = 1, softWrap = false)
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
                    // TAB 1: STRAIGHT LINE MEASUREMENT
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
                                        "Straight Line Measure",
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

                            // Capture Point A / Point B (or sampling progress)
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
                                                "Hold Stationary: ${samplingSlot?.label ?: ""} epoch ($currentEpochCount/$targetEpochs)...",
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            LinearProgressIndicator(
                                                progress = currentEpochCount.toFloat() / targetEpochs.toFloat(),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            TextButton(onClick = { SurveyRepository.cancelPointCapture() }) {
                                                Text("Cancel")
                                            }
                                        }
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        MeasureSlot.values().forEach { slot ->
                                            val captured = measurePoints.containsKey(slot)
                                            Button(
                                                onClick = { SurveyRepository.startPointCapture(slot) },
                                                enabled = curLocation != null,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(Icons.Default.MyLocation, contentDescription = null)
                                                Spacer(Modifier.width(6.dp))
                                                Text(if (captured) "Redo ${slot.name}" else "Capture ${slot.name}", maxLines = 1, softWrap = false)
                                            }
                                        }
                                    }
                                }
                            }

                            // Measured result A -> B
                            measurement?.let { m ->
                                item {
                                    MeasurementCard(title = "Point A ➔ Point B", m = m)
                                }
                            }

                            // Live distance from Point A to the current position
                            val pointA = measurePoints[MeasureSlot.A]
                            val live = curLocation
                            if (pointA != null && live != null) {
                                item {
                                    val livePoint = MeasurePoint(
                                        name = "Current position",
                                        latitude = live.latitude,
                                        longitude = live.longitude,
                                        altitude = live.altitude,
                                        accuracy = live.accuracy
                                    )
                                    MeasurementCard(
                                        title = "Point A ➔ Current position (live)",
                                        m = SurveyRepository.measureBetween(pointA, livePoint)
                                    )
                                }
                            }

                            // Captured Points List
                            if (measurePoints.isNotEmpty()) {
                                item {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Averaged Points (${measurePoints.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        measurePoints.values.sortedBy { it.name }.forEach { pt ->
                                            Card(modifier = Modifier.fillMaxWidth()) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(8.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(pt.name, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                                    Text(
                                                        "Alt ${"%.1f".format(pt.altitude)}m | ±${"%.2f".format(pt.accuracy)}m | Spread ±${"%.2f".format(pt.epochSpreadMeters)}m",
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }
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
                                            val json = SurveyRepository.exportMeasurementGeoJson()
                                            onSaveGeoJson(json, "measurement_${System.currentTimeMillis() / 1000}.geojson")
                                        },
                                        enabled = measurePoints.isNotEmpty(),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Save, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Save")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val json = SurveyRepository.exportMeasurementGeoJson()
                                            onShareGeoJson(json, "measurement.geojson")
                                        },
                                        enabled = measurePoints.isNotEmpty(),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Share")
                                    }
                                    OutlinedButton(
                                        onClick = { SurveyRepository.clearMeasurement() },
                                        enabled = measurePoints.isNotEmpty(),
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

                    // ==========================================
                    // TAB 3: FOLLOW IMPORTED PATH
                    // ==========================================
                    3 -> {
                        FollowPanel(
                            overlay = importedOverlay,
                            followSession = followSession,
                            currentLocation = curLocation
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MeasurementCard(title: String, m: StraightLineMeasurement) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Horizontal: ${"%.2f".format(m.horizontalDistanceMeters)} m", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Slope: ${"%.2f".format(m.slopeDistanceMeters)} m", fontSize = 12.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Δh: ${"%+.2f".format(m.verticalDeltaMeters)} m", fontSize = 12.sp)
                Text("Bearing: ${"%.1f".format(m.bearingDegrees)}°", fontSize = 12.sp)
            }
        }
    }
}
