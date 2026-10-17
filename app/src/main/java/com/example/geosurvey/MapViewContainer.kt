package com.example.geosurvey

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

@Composable
fun OsmMapView(
    modifier: Modifier = Modifier,
    currentLocation: Location?,
    pathPoints: List<Location>,
    stations: List<SurveyStation>,
    sightLines: List<SightLineResult>,
    featurePoints: List<SurveyFeaturePoint>,
    importedOverlay: ImportedGeoJsonOverlay?
) {
    val context = LocalContext.current
    var useTopoTiles by remember { mutableStateOf(false) }
    var hasAutoCentered by remember { mutableStateOf(false) }

    val mapView = remember {
        val prefs = context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        Configuration.getInstance().load(context, prefs)
        // Mandatory User-Agent to prevent HTTP 403 on OSM tile servers
        Configuration.getInstance().userAgentValue = context.packageName

        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(19.0)
        }
    }

    LaunchedEffect(useTopoTiles) {
        mapView.setTileSource(
            if (useTopoTiles) TileSourceFactory.OpenTopo else TileSourceFactory.MAPNIK
        )
        mapView.invalidate()
    }

    LaunchedEffect(
        currentLocation,
        pathPoints.size,
        stations.size,
        sightLines.size,
        featurePoints.size,
        importedOverlay
    ) {
        mapView.overlays.clear()

        // 1. Imported GeoJSON Overlay (Purple)
        importedOverlay?.let { overlay ->
            for (line in overlay.polylines) {
                val poly = Polyline().apply {
                    outlinePaint.color = Color.parseColor("#8E24AA")
                    outlinePaint.strokeWidth = 6f
                    setPoints(line.map { GeoPoint(it.first, it.second) })
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

        // 4. Sight Line Vectors (Red)
        for (line in sightLines) {
            val sightPoly = Polyline().apply {
                outlinePaint.color = Color.parseColor("#E53935")
                outlinePaint.strokeWidth = 7f
                setPoints(
                    listOf(
                        GeoPoint(line.fromStation.latitude, line.fromStation.longitude),
                        GeoPoint(line.toStation.latitude, line.toStation.longitude)
                    )
                )
            }
            mapView.overlays.add(sightPoly)
        }

        // 5. SLA Station Markers
        for (st in stations) {
            val marker = Marker(mapView).apply {
                position = GeoPoint(st.latitude, st.longitude)
                title = "${st.name} | Alt ${"%.1f".format(st.altitude)}m (±${"%.2f".format(st.accuracy)}m)"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(marker)
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

            if (!hasAutoCentered) {
                mapView.controller.setCenter(curPoint)
                hasAutoCentered = true
            }
        }

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
                    allGeoPoints.addAll(stations.map { GeoPoint(it.latitude, it.longitude) })
                    allGeoPoints.addAll(featurePoints.map { GeoPoint(it.latitude, it.longitude) })
                    importedOverlay?.polylines?.flatten()?.forEach {
                        allGeoPoints.add(GeoPoint(it.first, it.second))
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

            // Toggle OSM / Topo Map Layer
            SmallFloatingActionButton(
                onClick = { useTopoTiles = !useTopoTiles },
                containerColor = MaterialTheme.colorScheme.surface,
                elevation = FloatingActionButtonDefaults.elevation(4.dp)
            ) {
                Icon(Icons.Default.Layers, contentDescription = "Switch Map Layer")
            }
        }
    }
}
