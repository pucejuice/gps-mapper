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

    // Repository State Flows (used by the map; each panel collects its own state)
    val curLocation by SurveyRepository.currentLocation.collectAsState()
    val isMockActive by SurveyRepository.isMockLocationActive.collectAsState()
    val pathPoints by SurveyRepository.pathPoints.collectAsState()
    val measurePoints by SurveyRepository.measurePoints.collectAsState()
    val featurePoints by SurveyRepository.featurePoints.collectAsState()
    val importedOverlay by SurveyRepository.importedOverlay.collectAsState()
    val followSession by SurveyRepository.followSession.collectAsState()

    var selectedTab by remember { mutableStateOf(0) } // 0 = Path, 1 = Measure, 2 = Area, 3 = Follow
    var showMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // Match system bars to the theme
    val view = LocalView.current
    val barColor = MaterialTheme.colorScheme.surfaceContainer
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = barColor.toArgb()
            window.navigationBarColor = barColor.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    // Keep the screen awake while following a path
    val isFollowing = followSession != null
    DisposableEffect(isFollowing) {
        val window = (context as? Activity)?.window
        if (isFollowing) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    if (showSettings) {
        SettingsDialog(
            darkTheme = darkTheme,
            onDarkThemeChange = onDarkThemeChange,
            mapStyle = mapStyle,
            onMapStyleChange = onMapStyleChange,
            onDismiss = { showSettings = false }
        )
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
                    measurePoints = measurePoints,
                    featurePoints = featurePoints,
                    importedOverlay = importedOverlay,
                    followSession = followSession,
                    mapStyle = mapStyle,
                    onMapStyleChange = onMapStyleChange
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
                        Text(
                            overlay.fileName,
                            color = Color.White,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Compact mode panel (sized to its content)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    when (selectedTab) {
                        0 -> PathPanel(curLocation, onSaveGeoJson, onShareGeoJson)
                        1 -> MeasurePanel(curLocation, onSaveGeoJson, onShareGeoJson)
                        2 -> AreaPanel(curLocation, onSaveGeoJson, onShareGeoJson)
                        3 -> FollowPanel(importedOverlay, followSession, curLocation, onImportGeoJson)
                    }

                    // Mode tabs along the bottom
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        listOf(
                            "Path" to Icons.Default.Route,
                            "Measure" to Icons.Default.Straighten,
                            "Area" to Icons.Default.SquareFoot,
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
