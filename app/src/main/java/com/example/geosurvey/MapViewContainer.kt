package com.example.geosurvey

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.location.Location
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Remove
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
import kotlin.math.roundToInt
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

@Composable
fun OsmMapView(
    modifier: Modifier = Modifier,
    currentLocation: Location?,
    pathPoints: List<TrackPoint>,
    isRecordingPath: Boolean,
    stations: List<SurveyPoint>,
    measureLines: List<MeasuredLine>,
    originId: String?,
    showMeasureGuide: Boolean,
    featurePoints: List<SurveyFeaturePoint>,
    importedOverlay: ImportedGeoJsonOverlay?,
    followSession: FollowSession?,
    mapStyle: MapStyle,
    onMapStyleChange: (MapStyle) -> Unit,
    /** True heading of the user in degrees, or null when unknown. */
    headingDegrees: Double?
) {
    val context = LocalContext.current
    var hasAutoCentered by remember { mutableStateOf(false) }
    var showLayerMenu by remember { mutableStateOf(false) }

    val mapView = remember {
        val prefs = context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        Configuration.getInstance().load(context, prefs)
        // Tile servers (OSM in particular) reject generic/example User-Agents with HTTP 403
        Configuration.getInstance().userAgentValue =
            "wayopps/${BuildConfig.VERSION_NAME} (Android; +https://github.com/pucejuice/gps-mapper)"

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
            // Built-in zoom buttons replaced by styled ones in the control column
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            maxZoomLevel = 21.0
            controller.setZoom(19.0)
        }
    }

    // Live position: flat dot with a heading triangle, plus a faint accuracy circle.
    // Kept as persistent overlays so heading changes only redraw this icon.
    val liveMarker = remember {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> true }
            icon = userLocationIcon(context, null)
        }
    }
    val accuracyCircle = remember {
        Polygon().apply {
            fillPaint.color = Color.argb(40, 61, 169, 252)
            outlinePaint.color = Color.argb(120, 61, 169, 252)
            outlinePaint.strokeWidth = 2f
            setInfoWindow(null)
        }
    }
    // Quantised so sensor jitter doesn't redraw the icon every frame
    val headingStep = headingDegrees?.let { ((it / 3.0).roundToInt() * 3) % 360 }
    LaunchedEffect(headingStep) {
        liveMarker.icon = userLocationIcon(context, headingStep?.toDouble())
        mapView.invalidate()
    }

    LaunchedEffect(mapStyle) {
        mapView.setTileSource(mapStyle.tileSource())
        mapView.overlayManager.tilesOverlay.setColorFilter(mapStyle.tileColorFilter())
        mapView.invalidate()
    }

    LaunchedEffect(
        currentLocation,
        pathPoints.size,
        isRecordingPath,
        stations,
        measureLines,
        originId,
        showMeasureGuide,
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
                setPoints(pathPoints.map { GeoPoint(it.lat, it.lon) })
            }
            mapView.overlays.add(polyline)
        }
        // While recording, a dashed tail from the last logged point to the live position
        val lastLogged = pathPoints.lastOrNull()
        if (isRecordingPath && lastLogged != null && currentLocation != null) {
            val tail = Polyline().apply {
                outlinePaint.color = Color.parseColor("#1E88E5")
                outlinePaint.strokeWidth = 5f
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
                setPoints(listOf(GeoPoint(lastLogged.lat, lastLogged.lon), GeoPoint(currentLocation.latitude, currentLocation.longitude)))
            }
            mapView.overlays.add(tail)
        }

        // 4. Measured Lines (SLA accent blue; linked lines dashed) with distance labels
        val byId = stations.associateBy { it.id }
        val showLabels = mapView.zoomLevelDouble >= 16.5
        for (l in measureLines) {
            val a = byId[l.fromId] ?: continue
            val b = byId[l.toId] ?: continue
            val active = l.fromId == originId || l.toId == originId
            val line = Polyline().apply {
                outlinePaint.color = Color.parseColor("#3DA9FC")
                outlinePaint.alpha = if (active) 255 else 150
                outlinePaint.strokeWidth = if (active) 8f else 6f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                if (l.linked) outlinePaint.pathEffect = DashPathEffect(floatArrayOf(22f, 14f), 0f)
                setPoints(listOf(GeoPoint(a.lat, a.lon), GeoPoint(b.lat, b.lon)))
            }
            mapView.overlays.add(line)
            if (showLabels) {
                val label = Marker(mapView).apply {
                    position = GeoPoint((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)
                    icon = distanceLabelIcon(context, "${"%.2f".format(l.distanceM)} m")
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ -> true }
                }
                mapView.overlays.add(label)
            }
        }

        // 4b. Live guide from origin to the current position (Measure tab)
        val origin = byId[originId]
        if (showMeasureGuide && origin != null && currentLocation != null) {
            val guide = Polyline().apply {
                outlinePaint.color = Color.parseColor("#3DA9FC")
                outlinePaint.alpha = 200
                outlinePaint.strokeWidth = 5f
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(14f, 14f), 0f)
                setPoints(listOf(GeoPoint(origin.lat, origin.lon), GeoPoint(currentLocation.latitude, currentLocation.longitude)))
            }
            mapView.overlays.add(guide)
        }

        // 5. Station markers: tap one to measure from it
        for (st in stations) {
            val isOrigin = st.id == originId
            val marker = Marker(mapView).apply {
                position = GeoPoint(st.lat, st.lon)
                val (drawable, anchorX) = stationIcon(context, st.name, if (isOrigin) "#34C759" else "#3DA9FC")
                icon = drawable
                setAnchor(anchorX, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ ->
                    MeasureRepository.setOrigin(st.id)
                    true
                }
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
            if (loc.hasAccuracy() && loc.accuracy > 0.5f) {
                accuracyCircle.points = Polygon.pointsAsCircle(curPoint, loc.accuracy.toDouble())
                mapView.overlays.add(accuracyCircle)
            }
            liveMarker.position = curPoint
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
                    allGeoPoints.addAll(pathPoints.map { GeoPoint(it.lat, it.lon) })
                    allGeoPoints.addAll(stations.map { GeoPoint(it.lat, it.lon) })
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

            Spacer(Modifier.height(6.dp))

            // Zoom in / out, styled like the other map controls
            SmallFloatingActionButton(
                onClick = { mapView.controller.zoomIn() },
                containerColor = MaterialTheme.colorScheme.surface,
                elevation = FloatingActionButtonDefaults.elevation(4.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Zoom in")
            }
            SmallFloatingActionButton(
                onClick = { mapView.controller.zoomOut() },
                containerColor = MaterialTheme.colorScheme.surface,
                elevation = FloatingActionButtonDefaults.elevation(4.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Zoom out")
            }
        }
    }
}

/** Station dot with its name beside it (white text, dark halo). Returns the icon and the dot's x anchor (0..1). */
private fun stationIcon(context: Context, name: String, fill: String): Pair<BitmapDrawable, Float> {
    val d = context.resources.displayMetrics.density
    val r = 7f * d
    val stroke = 2f * d
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * d
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(3f * d, 0f, 0f, Color.BLACK)
    }
    val textW = textPaint.measureText(name)
    val pad = 4f * d
    val cx = r + stroke + pad
    val w = (cx + r + stroke + 4f * d + textW + pad).toInt()
    val h = (maxOf(2 * (r + stroke), textPaint.textSize * 1.4f) + 2 * pad).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cy = h / 2f
    c.drawCircle(cx, cy, r + stroke, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(2f * d, 0f, 1f * d, Color.argb(150, 0, 0, 0))
    })
    c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(fill) })
    c.drawText(name, cx + r + stroke + 4f * d, cy + textPaint.textSize * 0.35f, textPaint)
    return BitmapDrawable(context.resources, bmp) to cx / w
}

/** Small dark rounded label for a line's distance. */
private fun distanceLabelIcon(context: Context, text: String): BitmapDrawable {
    val d = context.resources.displayMetrics.density
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8ECEF")
        textSize = 11f * d
        typeface = Typeface.DEFAULT_BOLD
    }
    val padX = 6f * d
    val padY = 3f * d
    val w = (textPaint.measureText(text) + 2 * padX).toInt()
    val h = (textPaint.textSize + 2 * padY).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 6f * d, 6f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 27, 31, 36)
    })
    c.drawText(text, padX, padY + textPaint.textSize * 0.85f, textPaint)
    return BitmapDrawable(context.resources, bmp)
}

/**
 * Flat user-location icon: a filled dot with a white ring centred on the fix, and a
 * triangle just outside the ring pointing along [headingDeg] (true, clockwise from north;
 * the map is always north-up). The triangle is omitted when the heading is unknown.
 */
private fun userLocationIcon(context: Context, headingDeg: Double?): BitmapDrawable {
    val d = context.resources.displayMetrics.density
    val r = 8f * d                 // dot radius
    val ring = 2.5f * d
    val gap = 2.5f * d             // dot ring -> triangle base
    val triH = 7f * d
    val triW = 2 * r * 0.8f        // slightly narrower than the dot diameter
    val half = r + ring + gap + triH + 2f * d
    val size = (2 * half).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2f
    val accent = Color.parseColor("#3DA9FC")

    if (headingDeg != null) {
        c.save()
        c.rotate(headingDeg.toFloat(), cx, cy)
        val baseY = cy - (r + ring + gap)
        val tri = Path().apply {
            moveTo(cx, baseY - triH)
            lineTo(cx + triW / 2, baseY)
            lineTo(cx - triW / 2, baseY)
            close()
        }
        // Thin white edge keeps it readable on imagery
        c.drawPath(tri, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * d
            strokeJoin = Paint.Join.ROUND
        })
        c.drawPath(tri, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
        c.restore()
    }
    c.drawCircle(cx, cy, r + ring, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
    return BitmapDrawable(context.resources, bmp)
}
