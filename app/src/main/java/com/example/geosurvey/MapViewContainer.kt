package com.example.geosurvey

import android.content.Context
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.location.Location
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

@Composable
fun OsmMapView(
    modifier: Modifier = Modifier,
    currentLocation: Location?,
    pathPoints: List<Location>,
    measurePoints: Map<MeasureSlot, MeasurePoint>,
    featurePoints: List<SurveyFeaturePoint>,
    importedOverlay: ImportedGeoJsonOverlay?,
    followSession: FollowSession?,
    mapStyle: MapStyle,
    onMapStyleChange: (MapStyle) -> Unit
) {
    val context = LocalContext.current
    var hasAutoCentered by remember { mutableStateOf(false) }
    var showLayerMenu by remember { mutableStateOf(false) }

    val mapView = remember {
        val prefs = context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        Configuration.getInstance().load(context, prefs)
        // Tile servers (OSM in particular) reject generic/example User-Agents with HTTP 403
        Configuration.getInstance().userAgentValue =
            "GeoSurveyGNSS/${BuildConfig.VERSION_NAME} (Android; +https://github.com/pucejuice/gps-mapper)"

        // One-off purge of "Access blocked" tiles cached while the old User-Agent was in use
        if (!prefs.getBoolean("purged_blocked_tiles_v1", false)) {
            try {
                SqlTileWriter().apply {
                    purgeCache("Mapnik")
                    purgeCache("OpenTopoMap")
                    onDetach()
                }
            } catch (e: Exception) {
                // Cache may not exist yet
            }
            prefs.edit().putBoolean("purged_blocked_tiles_v1", true).apply()
        }

        MapView(context).apply {
            setTileSource(mapStyle.tileSource())
            setMultiTouchControls(true)
            maxZoomLevel = 21.0
            controller.setZoom(19.0)
        }
    }

    LaunchedEffect(mapStyle) {
        mapView.setTileSource(mapStyle.tileSource())
        mapView.invalidate()
    }

    LaunchedEffect(
        currentLocation,
        pathPoints.size,
        measurePoints,
        featurePoints.size,
        importedOverlay,
        followSession
    ) {
        mapView.overlays.clear()

        // 1. Imported GeoJSON Overlay (Purple)
        importedOverlay?.let { overlay ->
            overlay.lines.forEachIndexed { index, line ->
                val isFollowed = followSession?.lineIndex == index
                val poly = Polyline().apply {
                    outlinePaint.color = Color.parseColor(if (isFollowed) "#FB8C00" else "#8E24AA")
                    outlinePaint.strokeWidth = if (isFollowed) 12f else 6f
                    setPoints(line.points.map { GeoPoint(it.lat, it.lon) })
                }
                mapView.overlays.add(poly)
            }
            for (pt in overlay.points) {
                val m = Marker(mapView).apply {
                    position = GeoPoint(pt.first, pt.second)
                    title = pt.third
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                mapView.overlays.add(m)
            }
        }

        // 2. Feature & Area Survey Polygon (Green)
        val polyVertices = featurePoints.filter { it.includeInAreaPolygon }
        if (polyVertices.size >= 3) {
            val polygon = Polygon().apply {
                fillPaint.color = Color.argb(55, 46, 125, 50)
                outlinePaint.color = Color.parseColor("#2E7D32")
                outlinePaint.strokeWidth = 5f
                points = polyVertices.map { GeoPoint(it.latitude, it.longitude) }
            }
            mapView.overlays.add(polygon)
        } else if (polyVertices.size == 2) {
            val line = Polyline().apply {
                outlinePaint.color = Color.parseColor("#2E7D32")
                outlinePaint.strokeWidth = 5f
                setPoints(polyVertices.map { GeoPoint(it.latitude, it.longitude) })
            }
            mapView.overlays.add(line)
        }

        // Feature Markers
        for (fp in featurePoints) {
            val marker = Marker(mapView).apply {
                position = GeoPoint(fp.latitude, fp.longitude)
                title = "${fp.category}: ${fp.note} (±${"%.2f".format(fp.accuracy)}m)"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(marker)
        }

        // 3. Recorded Path Polyline (Blue)
        if (pathPoints.size >= 2) {
            val polyline = Polyline().apply {
                outlinePaint.color = Color.parseColor("#1E88E5")
                outlinePaint.strokeWidth = 8f
                outlinePaint.style = Paint.Style.STROKE
                setPoints(pathPoints.map { GeoPoint(it.latitude, it.longitude) })
            }
            mapView.overlays.add(polyline)
        }

        // 4. Straight Line Measurement (Red)
        val a = measurePoints[MeasureSlot.A]
        val b = measurePoints[MeasureSlot.B]
        if (a != null && b != null) {
            val measureLine = Polyline().apply {
                outlinePaint.color = Color.parseColor("#E53935")
                outlinePaint.strokeWidth = 7f
                setPoints(listOf(GeoPoint(a.latitude, a.longitude), GeoPoint(b.latitude, b.longitude)))
            }
            mapView.overlays.add(measureLine)
        }

        // 5. Measure Point Markers
        for (pt in measurePoints.values) {
            val marker = Marker(mapView).apply {
                position = GeoPoint(pt.latitude, pt.longitude)
                title = "${pt.name} | Alt ${"%.1f".format(pt.altitude)}m (±${"%.2f".format(pt.accuracy)}m)"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(marker)
        }

        // 5b. Follow Guidance: dashed line from live fix to the look-ahead target
        val guidance = followSession?.guidance
        if (guidance != null && currentLocation != null && !guidance.arrived) {
            val guideLine = Polyline().apply {
                outlinePaint.color = Color.parseColor("#E65100")
                outlinePaint.strokeWidth = 5f
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
                setPoints(
                    listOf(
                        GeoPoint(currentLocation.latitude, currentLocation.longitude),
                        GeoPoint(guidance.target.lat, guidance.target.lon)
                    )
                )
            }
            mapView.overlays.add(guideLine)
        }
        followSession?.path?.lastOrNull()?.let { end ->
            val endMarker = Marker(mapView).apply {
                position = GeoPoint(end.lat, end.lon)
                title = "Path end"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(endMarker)
        }

        // 6. Live GNSS Location
        currentLocation?.let { loc ->
            val curPoint = GeoPoint(loc.latitude, loc.longitude)
            val liveMarker = Marker(mapView).apply {
                position = curPoint
                title = "Live GNSS (±${"%.2f".format(loc.accuracy)}m)"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            mapView.overlays.add(liveMarker)

            if (!hasAutoCentered || followSession != null) {
                mapView.controller.setCenter(curPoint)
                hasAutoCentered = true
            }
        }

        // Tile provider attribution (required by the tile licences)
        mapView.overlays.add(CopyrightOverlay(context))

        mapView.invalidate()
    }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // Floating Map Action Buttons (Right side, matching supreme-funicular controls)
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Center on My Location
            SmallFloatingActionButton(
                onClick = {
                    currentLocation?.let {
                        mapView.controller.animateTo(GeoPoint(it.latitude, it.longitude))
                        mapView.controller.setZoom(19.5)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                elevation = FloatingActionButtonDefaults.elevation(4.dp)
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Center on GNSS")
            }

            // Fit to All Recorded Points / Path
            SmallFloatingActionButton(
                onClick = {
                    val allGeoPoints = mutableListOf<GeoPoint>()
                    allGeoPoints.addAll(pathPoints.map { GeoPoint(it.latitude, it.longitude) })
                    allGeoPoints.addAll(measurePoints.values.map { GeoPoint(it.latitude, it.longitude) })
                    allGeoPoints.addAll(featurePoints.map { GeoPoint(it.latitude, it.longitude) })
                    importedOverlay?.lines?.flatMap { it.points }?.forEach {
                        allGeoPoints.add(GeoPoint(it.lat, it.lon))
                    }
                    if (allGeoPoints.size >= 2) {
                        val box = BoundingBox.fromGeoPoints(allGeoPoints)
                        mapView.zoomToBoundingBox(box, true, 80)
                    } else if (allGeoPoints.size == 1) {
                        mapView.controller.animateTo(allGeoPoints.first())
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                elevation = FloatingActionButtonDefaults.elevation(4.dp)
            ) {
                Icon(Icons.Default.FitScreen, contentDescription = "Fit to Survey Bounds")
            }

            // Map Layer Picker
            Box {
                SmallFloatingActionButton(
                    onClick = { showLayerMenu = true },
                    containerColor = MaterialTheme.colorScheme.surface,
                    elevation = FloatingActionButtonDefaults.elevation(4.dp)
                ) {
                    Icon(Icons.Default.Layers, contentDescription = "Switch Map Layer")
                }
                DropdownMenu(expanded = showLayerMenu, onDismissRequest = { showLayerMenu = false }) {
                    MapStyle.values().forEach { style ->
                        DropdownMenuItem(
                            text = { Text(style.label) },
                            leadingIcon = { RadioButton(selected = style == mapStyle, onClick = null) },
                            onClick = {
                                onMapStyleChange(style)
                                showLayerMenu = false
                            }
                        )
                    }
                }
            }
        }
    }
}
