package com.wayopps.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {

    private var pendingFileToSave: String = ""

    private fun writePending(uri: Uri?) {
        if (uri == null || pendingFileToSave.isEmpty()) return
        try {
            contentResolver.openOutputStream(uri)?.use { out -> out.write(pendingFileToSave.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "Saved to device", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val saveGeoJsonLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/geo+json")
    ) { writePending(it) }

    private val saveGpxLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { writePending(it) }

    /** Save [content] via the system file picker; the extension of [fileName] picks the type. */
    private fun saveFile(content: String, fileName: String) {
        pendingFileToSave = content
        if (fileName.endsWith(".gpx")) saveGpxLauncher.launch(fileName) else saveGeoJsonLauncher.launch(fileName)
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
        val settings = getSharedPreferences("wayopps_settings", MODE_PRIVATE)
        MeasureRepository.attach(this)
        ReceiverBridge.attach(this)
        FeatureTypes.attach(this)
        AlertEngine.attach(this)
        HistoryRepository.attach(this, SurveyRepository.currentActivityId.value)
        MapRepository.attach(this)
        handleUsbAttach(intent)
        setContent {
            var darkTheme by remember { mutableStateOf(settings.getBoolean("dark_theme", true)) }
            var mapStyle by remember {
                mutableStateOf(MapStyle.fromName(settings.getString("map_style", null), darkTheme))
            }
            MaterialTheme(colorScheme = if (darkTheme) slaDarkColorScheme() else slaLightColorScheme()) {
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
                    onSaveGeoJson = { content, fileName -> saveFile(content, fileName) },
                    onShareGeoJson = { content, fileName -> shareFile(content, fileName) },
                    onImportGeoJson = { importFileLauncher.launch("*/*") }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbAttach(intent)
    }

    /** Opened by plugging in a GNSS receiver (see res/xml/usb_device_filter.xml): connect to it. */
    private fun handleUsbAttach(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        @Suppress("DEPRECATION")
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        device?.let { ReceiverBridge.onUsbAttached(this, it) }
    }

    // GPS runs while the app is on screen. When it leaves the screen the service stops
    // itself unless a path recording, path following or station hold is in progress.
    override fun onStart() {
        super.onStart()
        SurveyRepository.setAppVisible(true)
        startTrackingService()
    }

    override fun onStop() {
        // A rotation restarts the activity; don't drop GPS for that.
        if (!isChangingConfigurations) SurveyRepository.setAppVisible(false)
        super.onStop()
    }

    private fun startTrackingService() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted && SurveyRepository.appVisible.value) TrackingService.start(this)
    }

    /** Shares [content] as a real file (cache/exports/[fileName]) so other apps can open it. */
    private fun shareFile(content: String, fileName: String) {
        try {
            val dir = java.io.File(cacheDir, "exports").apply { mkdirs() }
            val file = java.io.File(dir, fileName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            file.writeText(content)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val mime = if (fileName.endsWith(".gpx")) "application/gpx+xml" else "application/geo+json"
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(sendIntent, "Share $fileName"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
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

    // Repository State Flows (used by the map; each panel collects its own state)
    val curLocation by SurveyRepository.currentLocation.collectAsState()
    val isMockActive by SurveyRepository.isMockLocationActive.collectAsState()
    val pathPoints by SurveyRepository.pathPoints.collectAsState()
    val isRecordingPath by SurveyRepository.isRecordingPath.collectAsState()
    val stations by MeasureRepository.points.collectAsState()
    val measureLines by MeasureRepository.lines.collectAsState()
    val originId by MeasureRepository.originId.collectAsState()
    val occupation by MeasureRepository.occupation.collectAsState()

    // Facing direction for the location marker: GPS course when moving, compass otherwise
    val magneticAzimuth by rememberCompassAzimuth()
    val heading = resolveTrueHeading(curLocation, magneticAzimuth)?.first
    val mapFeatures by MapRepository.features.collectAsState()
    val mapDraft by MapRepository.draft.collectAsState()
    val importedOverlay by SurveyRepository.importedOverlay.collectAsState()
    val followSession by SurveyRepository.followSession.collectAsState()

    var selectedTab by remember { mutableStateOf(0) } // 0 = Record, 1 = Map, 2 = Follow
    var mapMode by remember { mutableStateOf(MapMode.MEASURE) }
    var showFeatures by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showRecords by remember { mutableStateOf(false) }
    var showReceiver by remember { mutableStateOf(false) }
    var showAlerts by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showCourses by remember { mutableStateOf(false) }
    val receiverQuality by SurveyRepository.receiverQuality.collectAsState()

    // Status bar matches the Voronoi top bar; navigation bar matches the bottom panel
    val view = LocalView.current
    val navColor = MaterialTheme.colorScheme.surfaceContainerHigh
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Sla.mesh[0].toArgb()
            window.navigationBarColor = navColor.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    // Measure messages (station set, line measured, ...)
    LaunchedEffect(Unit) {
        MeasureRepository.events.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    // Keep the screen awake while following a path or occupying a station
    val keepAwake = followSession != null || occupation != null
    DisposableEffect(keepAwake) {
        val window = (context as? Activity)?.window
        if (keepAwake) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    occupation?.let { OccupationOverlay(it) }
    if (showRecords) RecordsSheet(onDismiss = { showRecords = false })
    if (showReceiver) ReceiverDialog(onDismiss = { showReceiver = false })
    if (showAlerts) AlertsDialog(onDismiss = { showAlerts = false })
    if (showHistory) HistorySheet(onDismiss = { showHistory = false }, onSave = onSaveGeoJson, onShare = onShareGeoJson)
    if (showCourses) CoursesDialog(onDismiss = { showCourses = false })
    if (showFeatures) FeaturesSheet(
        onDismiss = { showFeatures = false },
        onSave = onSaveGeoJson,
        onShare = onShareGeoJson,
        onFollow = { selectedTab = 2 }
    )

    if (showSettings) {
        SettingsDialog(
            darkTheme = darkTheme,
            onDarkThemeChange = onDarkThemeChange,
            onDismiss = { showSettings = false }
        )
    }

    Scaffold(
        topBar = {
          Box {
            VoronoiBackground(modifier = Modifier.matchParentSize(), seed = 5, cellSizePx = 150f, scrim = Color(0x590A0D10))
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.ic_logo),
                            contentDescription = null,
                            tint = Sla.text,
                            modifier = Modifier.size(width = 20.dp, height = 25.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "wayopps",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Sla.text,
                    actionIconContentColor = Sla.text
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
                        // Source: built-in receiver shows its fix type (RTK/FLOAT/DGPS…),
                        // another app's mock feed shows EXT, the phone's own GPS shows GPS.
                        val source = receiverQuality?.short ?: if (isMockActive) "EXT" else "GPS"
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (receiverQuality != null) fixQualityColor(receiverQuality) else badgeColor,
                            onClick = { showReceiver = true }
                        ) {
                            Text(
                                text = "$source ±${"%.2f".format(acc)}m",
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
                            DropdownMenuItem(
                                text = { Text("History") },
                                leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showHistory = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Courses & PBs") },
                                leadingIcon = { Icon(Icons.Default.EmojiEvents, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showCourses = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("GNSS receiver") },
                                leadingIcon = { Icon(Icons.Default.SatelliteAlt, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showReceiver = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showSettings = true
                                }
                            )
                        }
                    }
                }
            )
          }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Map takes all remaining space
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                OsmMapView(
                    modifier = Modifier.fillMaxSize(),
                    currentLocation = curLocation,
                    pathPoints = pathPoints,
                    isRecordingPath = isRecordingPath,
                    stations = stations,
                    measureLines = measureLines,
                    originId = originId,
                    showMeasureGuide = selectedTab == 1 && mapMode == MapMode.MEASURE,
                    mapFeatures = mapFeatures,
                    draft = mapDraft,
                    importedOverlay = importedOverlay,
                    followSession = followSession,
                    mapStyle = mapStyle,
                    onMapStyleChange = onMapStyleChange,
                    headingDegrees = heading
                )

                // Small label if an external GeoJSON overlay is active
                importedOverlay?.let { overlay ->
                    Surface(
                        color = Color(0xCC4A148C),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .widthIn(max = 220.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                overlay.fileName,
                                color = Color.White,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
                            )
                            IconButton(onClick = { SurveyRepository.clearImportedOverlay() }, modifier = Modifier.size(26.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Remove from map", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                // Crosshair for placing points / vertices at the map centre
                if (selectedTab == 1 && mapMode != MapMode.MEASURE) {
                    MapCrosshair(modifier = Modifier.align(Alignment.Center))
                }
            }

            // Compact mode panel (sized to its content)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    when (selectedTab) {
                        0 -> PathPanel(curLocation, { showAlerts = true }, { showCourses = true })
                        1 -> MapPanel(
                            curLocation, mapMode, { mapMode = it },
                            onShowRecords = { showRecords = true },
                            onShowFeatures = { showFeatures = true },
                            onSave = onSaveGeoJson,
                            onShare = onShareGeoJson
                        )
                        2 -> FollowPanel(importedOverlay, followSession, curLocation, onImportGeoJson)
                    }

                    // Mode tabs along the bottom
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        listOf(
                            "Record" to Icons.Default.FiberManualRecord,
                            "Map" to Icons.Default.Map,
                            "Follow" to Icons.Default.Navigation
                        ).forEachIndexed { index, (label, icon) ->
                            Tab(selected = selectedTab == index, onClick = { selectedTab = index }) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Text(label, fontSize = 11.sp, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
