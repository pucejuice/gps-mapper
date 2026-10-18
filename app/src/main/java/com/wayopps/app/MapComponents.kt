package com.wayopps.app

import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Map tab modes. MEASURE is the multi-station straight line measuring. */
enum class MapMode(val label: String) { MEASURE("Measure"), POINT("Point"), LINE("Line"), POLYGON("Polygon") }

/** Crosshair drawn over the map centre, used to place points / vertices without walking there. */
@Composable
fun MapCrosshair(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(36.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.width / 2
        val dark = Color(0xCC000000)
        for ((color, w) in listOf(dark to 5f, Color.White to 2.2f)) {
            drawLine(color, Offset(c.x, 0f), Offset(c.x, c.y - 5f), w)
            drawLine(color, Offset(c.x, c.y + 5f), Offset(c.x, size.height), w)
            drawLine(color, Offset(0f, c.y), Offset(c.x - 5f, c.y), w)
            drawLine(color, Offset(c.x + 5f, c.y), Offset(size.width, c.y), w)
            drawCircle(color, r * 0.55f, c, style = Stroke(w))
        }
    }
}

private fun vertexFrom(loc: Location?): Vertex? = loc?.let {
    Vertex(it.latitude, it.longitude, if (it.hasAltitude()) it.altitude else null, if (it.hasAccuracy()) it.accuracy.toDouble() else null)
}

private fun vertexAtCrosshair(): Vertex? = MapViewState.center.value?.let { Vertex(it.lat, it.lon) }

/**
 * The Map tab: measure between stations, or map labelled points, lines and polygons
 * by GPS position or at the crosshair.
 */
@Composable
fun MapPanel(
    currentLocation: Location?,
    mode: MapMode,
    onModeChange: (MapMode) -> Unit,
    onShowRecords: () -> Unit,
    onShowFeatures: () -> Unit,
    onSave: (String, String) -> Unit,
    onShare: (String, String) -> Unit
) {
    val features by MapRepository.features.collectAsState()
    val draft by MapRepository.draft.collectAsState()

    Column {
        // Mode chips + feature list
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 6.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            ) {
                MapMode.values().forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { onModeChange(m) },
                        enabled = draft == null || mode == m, // finish or cancel a drawing first
                        label = { Text(m.label, fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(
                                when (m) {
                                    MapMode.MEASURE -> Icons.Default.Straighten
                                    MapMode.POINT -> Icons.Default.Place
                                    MapMode.LINE -> Icons.Default.Timeline
                                    MapMode.POLYGON -> Icons.Default.CropSquare
                                },
                                null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
            if (mode != MapMode.MEASURE) {
                BadgedBox(badge = { if (features.isNotEmpty()) Badge { Text("${features.size}") } }) {
                    IconButton(onClick = onShowFeatures) { Icon(Icons.Default.FormatListBulleted, "Mapped features") }
                }
            }
        }

        when (mode) {
            MapMode.MEASURE -> MeasurePanel(currentLocation, onShowRecords, onSave, onShare)
            MapMode.POINT -> PointPanel(currentLocation)
            MapMode.LINE, MapMode.POLYGON -> ShapePanel(currentLocation, if (mode == MapMode.LINE) FeatureKind.LINE else FeatureKind.POLYGON)
        }
    }
}

@Composable
private fun TypePicker(type: String, onType: (String) -> Unit, modifier: Modifier = Modifier) {
    val types by FeatureTypes.types.collectAsState()
    var open by remember { mutableStateOf(false) }
    var editTypes by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.fillMaxWidth()) {
            Text(type.ifBlank { "Type" }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            types.forEach { t -> DropdownMenuItem(text = { Text(t) }, onClick = { onType(t); open = false }) }
            if (types.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Edit feature types…") },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { open = false; editTypes = true }
            )
        }
    }
    if (editTypes) FeatureTypesDialog(onDismiss = { editTypes = false })
}

/** Label / type / note dialog used when saving or editing a feature. */
@Composable
fun FeatureLabelDialog(
    title: String,
    initialLabel: String,
    initialType: String,
    initialNote: String,
    summary: String?,
    onSave: (label: String, type: String, note: String) -> Unit,
    onDismiss: () -> Unit
) {
    var label by remember { mutableStateOf(initialLabel) }
    var type by remember { mutableStateOf(initialType) }
    var note by remember { mutableStateOf(initialNote) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                summary?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedTextField(value = label, onValueChange = { label = it }, singleLine = true, label = { Text("Label") })
                TypePicker(type, { type = it }, Modifier.fillMaxWidth())
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(label, type, note) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun defaultType(): String = FeatureTypes.types.value.firstOrNull() ?: "Feature"

@Composable
private fun PointPanel(currentLocation: Location?) {
    var type by remember { mutableStateOf(defaultType()) }
    var pending by remember { mutableStateOf<Vertex?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "Map a point at your GPS position, or move the map to put the crosshair on it.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TypePicker(type, { type = it }, Modifier.weight(1f))
            Button(onClick = { pending = vertexFrom(currentLocation) }, enabled = currentLocation != null, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Icon(Icons.Default.MyLocation, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("At GPS", maxLines = 1)
            }
            FilledTonalButton(onClick = { pending = vertexAtCrosshair() }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Icon(Icons.Default.CenterFocusStrong, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("At +", maxLines = 1)
            }
        }
    }
    pending?.let { v ->
        FeatureLabelDialog(
            title = "New point",
            initialLabel = MapRepository.defaultLabel(FeatureKind.POINT, type),
            initialType = type,
            initialNote = "",
            summary = v.acc?.let { "GPS ±${"%.1f".format(it)} m" } ?: "At the crosshair",
            onSave = { l, t, n -> MapRepository.addPoint(v, l, t, n); type = t; pending = null },
            onDismiss = { pending = null }
        )
    }
}

@Composable
private fun ShapePanel(currentLocation: Location?, kind: FeatureKind) {
    val draft by MapRepository.draft.collectAsState()
    var type by remember(kind) { mutableStateOf(defaultType()) }
    var finishing by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    val d = draft?.takeIf { it.kind == kind }
    val noun = kind.label.lowercase()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (d == null) {
            Text(
                "Start a $noun, then add corners by GPS (walk the shape) or at the crosshair (move the map).",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TypePicker(type, { type = it }, Modifier.weight(1f))
                Button(onClick = { MapRepository.startDraft(kind) }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New $noun", maxLines = 1)
                }
            }
        } else {
            val n = d.vertices.size
            val length = FeatureGeometry.lengthM(d.vertices, closed = kind == FeatureKind.POLYGON)
            Text(
                "Drawing $noun · $n point${if (n == 1) "" else "s"} · " +
                    if (kind == FeatureKind.POLYGON) "area ${FeatureGeometry.formatArea(FeatureGeometry.areaM2(d.vertices))} · perimeter ${formatMeters(length)}"
                    else "length ${formatMeters(length)}",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Sla.warn
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { vertexFrom(currentLocation)?.let { MapRepository.addVertex(it) } },
                    enabled = currentLocation != null,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.MyLocation, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add at GPS", maxLines = 1, softWrap = false)
                }
                FilledTonalButton(
                    onClick = { vertexAtCrosshair()?.let { MapRepository.addVertex(it) } },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CenterFocusStrong, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add at +", maxLines = 1, softWrap = false)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { MapRepository.undoVertex() }, enabled = n > 0, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Icon(Icons.Default.Undo, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Undo")
                }
                OutlinedButton(onClick = { if (n == 0) MapRepository.cancelDraft() else cancelling = true }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text("Cancel", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { finishing = true },
                    enabled = n >= if (kind == FeatureKind.POLYGON) 3 else 2,
                    colors = ButtonDefaults.buttonColors(containerColor = Sla.go, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save…")
                }
            }
        }
    }

    if (finishing && d != null) {
        val length = FeatureGeometry.lengthM(d.vertices, closed = kind == FeatureKind.POLYGON)
        FeatureLabelDialog(
            title = "Save $noun",
            initialLabel = MapRepository.defaultLabel(kind, type),
            initialType = type,
            initialNote = "",
            summary = if (kind == FeatureKind.POLYGON)
                "${FeatureGeometry.formatArea(FeatureGeometry.areaM2(d.vertices))} · perimeter ${formatMeters(length)}"
            else "Length ${formatMeters(length)}",
            onSave = { l, t, n -> MapRepository.finishDraft(l, t, n); type = t; finishing = false },
            onDismiss = { finishing = false }
        )
    }
    if (cancelling) {
        AlertDialog(
            onDismissRequest = { cancelling = false },
            title = { Text("Cancel this $noun?") },
            text = { Text("The points added so far will be discarded.") },
            confirmButton = { TextButton(onClick = { MapRepository.cancelDraft(); cancelling = false }) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { cancelling = false }) { Text("Keep drawing") } }
        )
    }
}

/** List of mapped features: show, edit label, follow (lines), delete; export all. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeaturesSheet(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onShare: (String, String) -> Unit,
    onFollow: () -> Unit
) {
    val features by MapRepository.features.collectAsState()
    var editing by remember { mutableStateOf<MapFeature?>(null) }
    var deleting by remember { mutableStateOf<MapFeature?>(null) }
    var clearing by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Box(modifier = Modifier.fillMaxWidth().height(52.dp)) {
            VoronoiBackground(modifier = Modifier.matchParentSize(), seed = 13, cellSizePx = 120f, scrim = Color(0x8C0A0D10))
            Column(modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp)) {
                Text("MAPPED FEATURES", fontSize = 11.sp, letterSpacing = 1.2.sp, color = Sla.textDim)
                Text(
                    FeatureKind.values().joinToString(" · ") { k -> "${features.count { it.kind == k }} ${k.label.lowercase()}s" },
                    fontWeight = FontWeight.Bold,
                    color = Sla.text
                )
            }
        }
        Row(modifier = Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { onSave(MapRepository.exportGeoJson(), "mapped_features_${System.currentTimeMillis() / 1000}.geojson") }, enabled = features.isNotEmpty()) {
                Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Save GeoJSON")
            }
            TextButton(onClick = { onShare(MapRepository.exportGeoJson(), "mapped_features.geojson") }, enabled = features.isNotEmpty()) {
                Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Share")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { clearing = true }, enabled = features.isNotEmpty()) { Text("Clear all", color = MaterialTheme.colorScheme.error) }
        }
        HorizontalDivider()
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (features.isEmpty()) item { Text("Nothing mapped yet.", modifier = Modifier.padding(16.dp)) }
            items(features.reversed(), key = { it.id }) { f ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            MapViewState.focus(f.vertices.map { LatLon(it.lat, it.lon) })
                            onDismiss()
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        when (f.kind) {
                            FeatureKind.POINT -> Icons.Default.Place
                            FeatureKind.LINE -> Icons.Default.Timeline
                            FeatureKind.POLYGON -> Icons.Default.CropSquare
                        },
                        null,
                        tint = Sla.warn
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(f.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                f.type.ifBlank { null },
                                when (f.kind) {
                                    FeatureKind.POINT -> null
                                    FeatureKind.LINE -> formatMeters(f.lengthM)
                                    FeatureKind.POLYGON -> "${FeatureGeometry.formatArea(f.areaM2)} · ${formatMeters(f.lengthM)} perimeter"
                                },
                                f.note.ifBlank { null }
                            ).joinToString(" · "),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Actions") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Show on map") }, leadingIcon = { Icon(Icons.Default.Map, null) }, onClick = {
                                menu = false
                                MapViewState.focus(f.vertices.map { LatLon(it.lat, it.lon) })
                                onDismiss()
                            })
                            if (f.kind != FeatureKind.POINT) {
                                DropdownMenuItem(text = { Text("Follow") }, leadingIcon = { Icon(Icons.Default.Navigation, null) }, onClick = {
                                    menu = false
                                    val pts = f.vertices.map { LatLon(it.lat, it.lon) }
                                    SurveyRepository.startFollowingPath(f.label, if (f.kind == FeatureKind.POLYGON) pts + pts.first() else pts, false)
                                    onDismiss()
                                    onFollow()
                                })
                            }
                            DropdownMenuItem(text = { Text("Edit label") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; editing = f })
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; deleting = f }
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }

    editing?.let { f ->
        FeatureLabelDialog(
            title = "Edit ${f.kind.label.lowercase()}",
            initialLabel = f.label, initialType = f.type, initialNote = f.note, summary = null,
            onSave = { l, t, n -> MapRepository.update(f.id, l, t, n); editing = null },
            onDismiss = { editing = null }
        )
    }
    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${f.label}?") },
            confirmButton = { TextButton(onClick = { MapRepository.delete(f.id); deleting = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text("Delete all ${features.size} mapped features?") },
            text = { Text("Export them first if you want to keep them.") },
            confirmButton = { TextButton(onClick = { MapRepository.clearAll(); clearing = false }) { Text("Delete all", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { clearing = false }) { Text("Cancel") } }
        )
    }
}
