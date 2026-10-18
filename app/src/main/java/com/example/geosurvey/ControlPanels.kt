package com.example.geosurvey

import android.location.Location
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Compact bottom panels: each mode fits in ~2 rows so the map keeps most of the screen.

val accuracyGates = listOf(0.5f to "≤0.5 m (RTK)", 2.0f to "≤2 m", 5.0f to "≤5 m", 10.0f to "≤10 m", 25.0f to "≤25 m (any)")

fun formatMeters(m: Double): String =
    if (kotlin.math.abs(m) >= 1000) "${"%.2f".format(m / 1000)} km" else "${"%.1f".format(m)} m"

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

@Composable
fun PanelIcon(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

/** Single button with Save / Share / Clear choices. */
@Composable
fun ExportButton(
    enabled: Boolean,
    fileStem: String,
    buildJson: () -> String,
    onSave: (String, String) -> Unit,
    onShare: (String, String) -> Unit,
    clearLabel: String,
    onClear: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        PanelIcon(Icons.Default.Save, "Save or share", enabled) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Save .geojson") },
                leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                onClick = {
                    open = false
                    onSave(buildJson(), "${fileStem}_${System.currentTimeMillis() / 1000}.geojson")
                }
            )
            DropdownMenuItem(
                text = { Text("Share") },
                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                onClick = {
                    open = false
                    onShare(buildJson(), "$fileStem.geojson")
                }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(clearLabel, color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    open = false
                    onClear()
                }
            )
        }
    }
}

@Composable
private fun PanelColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

// ---------------- Area ----------------

@Composable
fun AreaPanel(
    currentLocation: Location?,
    onSave: (String, String) -> Unit,
    onShare: (String, String) -> Unit
) {
    val featurePoints by SurveyRepository.featurePoints.collectAsState()
    val areaSqM by SurveyRepository.polygonAreaSqMeters.collectAsState()
    val perimeterM by SurveyRepository.polygonPerimeterMeters.collectAsState()

    val types by FeatureTypes.types.collectAsState()
    var chosen by remember { mutableStateOf<String?>(null) }
    // Falls back to the first type (or a plain "Feature" when the list is empty)
    val category = chosen?.takeIf { it in types } ?: types.firstOrNull() ?: "Feature"
    var note by remember { mutableStateOf("") }
    var showCategories by remember { mutableStateOf(false) }
    var showTypeEditor by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }

    PanelColumn {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            Stat("Area", if (areaSqM >= 10000.0) "${"%.3f".format(areaSqM / 10000.0)} ha" else "${"%.1f".format(areaSqM)} m²")
            Stat("Perimeter", formatMeters(perimeterM))
            Stat("Points", "${featurePoints.size}")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { showCategories = true },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(category, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = showCategories, onDismissRequest = { showCategories = false }) {
                    types.forEach { cat ->
                        DropdownMenuItem(text = { Text(cat) }, onClick = { chosen = cat; showCategories = false })
                    }
                    if (types.isNotEmpty()) HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Edit feature types…") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { showCategories = false; showTypeEditor = true }
                    )
                }
            }
            if (note.isBlank()) {
                PanelIcon(Icons.Default.EditNote, "Add note to next point") { showNoteDialog = true }
            } else {
                BadgedBox(badge = { Badge() }) {
                    PanelIcon(Icons.Default.EditNote, "Edit note: $note") { showNoteDialog = true }
                }
            }
            Button(
                onClick = {
                    SurveyRepository.captureFeaturePoint(category = category, note = note, includeInPolygon = true)
                    note = ""
                },
                enabled = currentLocation != null,
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Log", maxLines = 1, softWrap = false)
            }
            PanelIcon(Icons.Default.Undo, "Undo last point", featurePoints.isNotEmpty()) { SurveyRepository.undoLastFeaturePoint() }
            ExportButton(
                featurePoints.isNotEmpty(), "features", SurveyRepository::exportFeaturesGeoJson, onSave, onShare,
                "Clear all points", SurveyRepository::clearFeatures
            )
        }
    }

    if (showTypeEditor) FeatureTypesDialog(onDismiss = { showTypeEditor = false })

    if (showNoteDialog) {
        var draft by remember { mutableStateOf(note) }
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text("Note for next point") },
            text = {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true, label = { Text("Note (optional)") })
            },
            confirmButton = { TextButton(onClick = { note = draft; showNoteDialog = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { showNoteDialog = false }) { Text("Cancel") } }
        )
    }
}

// ---------------- Settings ----------------
@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 14.sp)
    }
}

@Composable
private fun <T> ChipRow(label: String, options: List<T>, selected: T, format: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        Text(label, fontSize = 13.sp, modifier = Modifier.width(52.dp))
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(format(o), fontSize = 12.sp) })
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

@Composable
fun SettingsDialog(
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val accuracyGate by SurveyRepository.maxAccuracyFilterMeters.collectAsState()
    val measure by MeasureRepository.settings.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = darkTheme, onClick = { onDarkThemeChange(!darkTheme) })
                ) {
                    Icon(Icons.Default.DarkMode, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Dark theme", modifier = Modifier.weight(1f))
                    Switch(checked = darkTheme, onCheckedChange = onDarkThemeChange)
                }

                SectionLabel("Path accuracy gate (points worse than this are skipped)")
                accuracyGates.forEach { (gate, label) ->
                    RadioRow(label, accuracyGate == gate) { SurveyRepository.setAccuracyFilter(gate) }
                }

                SectionLabel("Station occupation (Measure)")
                ChipRow("Hold", listOf(10, 30, 60, 120), measure.dwellS, { "$it s" }) { v -> MeasureRepository.updateSettings { it.copy(dwellS = v) } }
                ChipRow("Epochs", listOf(2, 4, 6, 10), measure.epochs, { "$it" }) { v -> MeasureRepository.updateSettings { it.copy(epochs = v) } }
                ChipRow("Gate", listOf(5.0, 10.0, 15.0, 30.0), measure.gateM, { "±${it.toInt()} m" }) { v -> MeasureRepository.updateSettings { it.copy(gateM = v) } }
                Text(
                    "Every fix inside the hold is averaged (weighted 1/accuracy²); the quoted ± is the spread between epochs. " +
                        "A longer hold beats more epochs.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .selectable(selected = measure.recordHeight, onClick = {
                            MeasureRepository.updateSettings { it.copy(recordHeight = !it.recordHeight) }
                        })
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Record height", fontSize = 14.sp)
                        Text("Adds Δh and slope distance. GNSS height is uncorrected, not AHD.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(checked = measure.recordHeight, onCheckedChange = { on -> MeasureRepository.updateSettings { it.copy(recordHeight = on) } })
                }
            }
        }
    )
}

/** Add / delete feature types; every default can be removed. */
@Composable
fun FeatureTypesDialog(onDismiss: () -> Unit) {
    val types by FeatureTypes.types.collectAsState()
    var newType by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Feature types") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            Row {
                TextButton(onClick = { FeatureTypes.restoreDefaults() }) { Text("Restore defaults") }
                TextButton(onClick = { FeatureTypes.clearAll() }, enabled = types.isNotEmpty()) {
                    Text("Clear all", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = newType,
                        onValueChange = { newType = it },
                        singleLine = true,
                        placeholder = { Text("New type") },
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalIconButton(onClick = { FeatureTypes.add(newType); newType = "" }, enabled = newType.isNotBlank()) {
                        Icon(Icons.Default.Add, contentDescription = "Add type")
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (types.isEmpty()) {
                    Text("No types: points are logged as \"Feature\".", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                types.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t, modifier = Modifier.weight(1f))
                        IconButton(onClick = { FeatureTypes.remove(t) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove $t")
                        }
                    }
                }
            }
        }
    )
}
