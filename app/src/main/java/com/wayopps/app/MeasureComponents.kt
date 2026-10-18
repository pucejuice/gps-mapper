package com.wayopps.app

import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private fun f2(v: Double) = "%.2f".format(v)

// ---------------- compact bottom panel ----------------
@Composable
fun MeasurePanel(
    currentLocation: Location?,
    onShowRecords: () -> Unit,
    onSave: (String, String) -> Unit,
    onShare: (String, String) -> Unit
) {
    val points by MeasureRepository.points.collectAsState()
    val lines by MeasureRepository.lines.collectAsState()
    val originId by MeasureRepository.originId.collectAsState()
    val settings by MeasureRepository.settings.collectAsState()
    val origin = points.firstOrNull { it.id == originId }
    var showOriginMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Row 1: origin picker + live distance from origin
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { showOriginMenu = true },
                    enabled = points.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (origin != null) "From ${origin.name}" else "No station yet",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = showOriginMenu, onDismissRequest = { showOriginMenu = false }) {
                    points.forEach { p ->
                        val n = lines.count { it.fromId == p.id || it.toId == p.id }
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("From ${p.name}", fontWeight = if (p.id == originId) FontWeight.Bold else FontWeight.Normal)
                                    Text(
                                        "$n line${if (n == 1) "" else "s"}" + (p.stats.spreadM?.let { " · ±${f2(it)} m" } ?: ""),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            leadingIcon = { RadioButton(selected = p.id == originId, onClick = null) },
                            onClick = {
                                MeasureRepository.setOrigin(p.id)
                                showOriginMenu = false
                            }
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (origin != null && currentLocation != null) {
                    val d = MeasureRepository.distance(origin, currentLocation.latitude, currentLocation.longitude)
                    val b = MeasureRepository.bearing(origin, currentLocation.latitude, currentLocation.longitude)
                    Text("${f2(d)} m", fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(
                        "${"%.1f".format(b)}° · phone ±${"%.0f".format(currentLocation.accuracy)} m",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                } else {
                    Text("—", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (currentLocation == null) "waiting for GPS" else "set a station",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Last measured line (or a hint)
        val last = lines.lastOrNull()
        val lastText = last?.let { l ->
            val a = MeasureRepository.point(l.fromId)?.name ?: "?"
            val b = MeasureRepository.point(l.toId)?.name ?: "?"
            "Last: $a → $b  ${f2(l.distanceM)} m" + (l.sigmaM?.let { " ±${f2(it)}" } ?: "") +
                "  ·  ${"%.1f".format(l.bearingDeg)}°" + (MeasureRepository.heightDelta(l)?.let { "  ·  Δh ${"%+.2f".format(it)}" } ?: "")
        } ?: if (points.isEmpty()) "Press ＋ Station and hold still to set an origin." else "Walk to the next point and press ＋ Station."
        Text(lastText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)

        // Chain: cumulative distance of consecutive legs ending at the current origin
        val chain = chainEndingAt(lines, originId)
        if (chain.isNotEmpty() && (settings.chain || chain.size >= 2)) {
            val total = chain.sumOf { it.distanceM }
            val start = MeasureRepository.point(chain.first().fromId)?.name ?: "?"
            val end = MeasureRepository.point(chain.last().toId)?.name ?: "?"
            val toYou = if (origin != null && currentLocation != null)
                MeasureRepository.distance(origin, currentLocation.latitude, currentLocation.longitude) else null
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Timeline, contentDescription = null, tint = Sla.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    "Chain $start→$end · ${chain.size} leg${if (chain.size == 1) "" else "s"} · ",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text("${f2(total)} m", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Sla.accent, maxLines = 1)
                if (toYou != null && settings.chain) {
                    Text(
                        "  (+${f2(toYou)} to you = ${f2(total + toYou)})",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Row 2: actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { MeasureRepository.startStation() },
                enabled = currentLocation != null,
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Station", maxLines = 1, softWrap = false)
            }
            // Chain: each new station becomes the origin (consecutive measuring)
            FilledTonalIconToggleButton(
                checked = settings.chain,
                onCheckedChange = { MeasureRepository.setChain(it) },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Default.Timeline, contentDescription = if (settings.chain) "Chain on" else "Chain off", modifier = Modifier.size(20.dp))
            }
            BadgedBox(badge = { if (lines.isNotEmpty()) Badge { Text("${lines.size}") } }) {
                PanelIcon(Icons.Default.FormatListBulleted, "Records", points.isNotEmpty(), onShowRecords)
            }
            PanelIcon(Icons.Default.Undo, "Undo", points.isNotEmpty() || lines.isNotEmpty()) { MeasureRepository.undo() }
            ExportButton(
                points.isNotEmpty(), "measurements", MeasureRepository::exportGeoJson, onSave, onShare,
                "Clear all stations", MeasureRepository::clearAll
            )
        }
    }
}

// ---------------- occupation overlay ----------------
@Composable
fun OccupationOverlay(progress: OccupationProgress) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            VoronoiBackground(modifier = Modifier.fillMaxSize(), seed = 11, cellSizePx = 260f, scrim = Color(0xD90A0D10))
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("OCCUPYING STATION", fontSize = 11.sp, letterSpacing = 1.6.sp, color = Sla.accent)
                Spacer(Modifier.height(6.dp))
                Text(progress.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Sla.text)
                Text("Hold the phone still and level", fontSize = 14.sp, color = Sla.textDim)
                Spacer(Modifier.height(26.dp))

                val frac = (progress.elapsedMs.toFloat() / progress.dwellMs).coerceIn(0f, 1f)
                val left = ((progress.dwellMs - progress.elapsedMs).coerceAtLeast(0) + 999) / 1000
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(172.dp)) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = 9.dp.toPx()
                        val inset = stroke / 2 + 4.dp.toPx()
                        val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
                        drawArc(Sla.line, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                        drawArc(Sla.accent, -90f, 360f * frac, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$left", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Sla.text)
                        Text("seconds left", fontSize = 12.sp, color = Sla.textFaint)
                    }
                }
                Spacer(Modifier.height(22.dp))

                val span = progress.dwellMs / progress.epochs
                val cur = minOf(progress.epochs - 1, (progress.elapsedMs / span).toInt())
                Text("Epoch ${cur + 1} of ${progress.epochs}", fontSize = 13.sp, color = Sla.text)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    repeat(progress.epochs) { i ->
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(if (i < cur) Sla.go else if (i == cur) Sla.warn else Sla.line)
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))

                val st = progress.live
                Text(
                    if (st == null) "waiting for an acceptable fix…"
                    else "fixes kept ${st.nFixes}" + (if (progress.rejected > 0) "   rejected ${progress.rejected}" else "") +
                        "\nspread between epochs " + (st.spreadM?.let { "±${f2(it)} m" } ?: "needs 2 epochs") +
                        "\nphone says ±${"%.1f".format(st.reportedAccM)} m",
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    color = Sla.textDim,
                    textAlign = TextAlign.Center
                )
                if (progress.rejected > 0 && st == null) {
                    Text("${progress.rejected} fixes rejected (worse than ±${"%.0f".format(progress.gateM)} m)", fontSize = 12.sp, color = Sla.warn)
                }
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { MeasureRepository.cancelStation() }, modifier = Modifier.widthIn(min = 160.dp)) {
                    Text("Cancel", color = Sla.text)
                }
            }
        }
    }
}

// ---------------- records sheet ----------------
private sealed class RecordDialog {
    class Rename(val point: SurveyPoint) : RecordDialog()
    class Link(val point: SurveyPoint) : RecordDialog()
    class DeletePoint(val point: SurveyPoint) : RecordDialog()
    class DeleteLine(val line: MeasuredLine) : RecordDialog()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsSheet(onDismiss: () -> Unit) {
    val points by MeasureRepository.points.collectAsState()
    val lines by MeasureRepository.lines.collectAsState()
    val originId by MeasureRepository.originId.collectAsState()
    MeasureRepository.settings.collectAsState() // recompose on height setting change
    var dialog by remember { mutableStateOf<RecordDialog?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        // Voronoi header strip
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            VoronoiBackground(modifier = Modifier.matchParentSize(), seed = 3, cellSizePx = 120f, scrim = Color(0x8C0A0D10))
            Column(modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp)) {
                Text("RECORDS", fontSize = 11.sp, letterSpacing = 1.2.sp, color = Sla.textDim)
                val chain = chainEndingAt(lines, originId)
                Text(
                    "${points.size} station${if (points.size == 1) "" else "s"} · ${lines.size} line${if (lines.size == 1) "" else "s"}" +
                        (if (chain.size >= 2) " · chain ${f2(chain.sumOf { it.distanceM })} m" else ""),
                    fontWeight = FontWeight.Bold,
                    color = Sla.text
                )
            }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (points.isEmpty()) {
                item {
                    Text("No records yet.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            for (p in points) {
                val mine = lines.filter { it.fromId == p.id }
                item(key = p.id) {
                    StationRow(
                        p = p,
                        isOrigin = p.id == originId,
                        lineCount = mine.size,
                        onUse = { MeasureRepository.setOrigin(p.id) },
                        onRename = { dialog = RecordDialog.Rename(p) },
                        onLink = { dialog = RecordDialog.Link(p) },
                        onDelete = { dialog = RecordDialog.DeletePoint(p) }
                    )
                }
                items(mine, key = { it.id }) { l -> LineRow(l, onDelete = { dialog = RecordDialog.DeleteLine(l) }) }
            }
        }
    }

    when (val d = dialog) {
        is RecordDialog.Rename -> RenameDialog(d.point, onDismiss = { dialog = null })
        is RecordDialog.Link -> LinkDialog(d.point, onDismiss = { dialog = null })
        is RecordDialog.DeletePoint -> {
            val n = lines.count { it.fromId == d.point.id || it.toId == d.point.id }
            ConfirmDialog(
                title = "Delete ${d.point.name}?",
                text = if (n > 0) "Its $n line${if (n == 1) "" else "s"} will be deleted too. This cannot be undone." else "This cannot be undone.",
                onConfirm = { MeasureRepository.deletePoint(d.point.id) },
                onDismiss = { dialog = null }
            )
        }
        is RecordDialog.DeleteLine -> {
            val a = MeasureRepository.point(d.line.fromId)?.name ?: "?"
            val b = MeasureRepository.point(d.line.toId)?.name ?: "?"
            ConfirmDialog(
                title = "Delete line $a → $b?",
                text = "The stations are kept.",
                onConfirm = { MeasureRepository.deleteLine(d.line.id) },
                onDismiss = { dialog = null }
            )
        }
        null -> {}
    }
}

@Composable
private fun Pill(label: String, primary: Boolean = false, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = when {
        danger -> Sla.stop.copy(alpha = 0.15f)
        primary -> Sla.accent.copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    }
    val fg = when {
        danger -> Sla.stop
        primary -> Sla.accent
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (enabled) fg else fg.copy(alpha = 0.35f),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun StationRow(
    p: SurveyPoint,
    isOrigin: Boolean,
    lineCount: Int,
    onUse: () -> Unit,
    onRename: () -> Unit,
    onLink: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isOrigin) Sla.go else Sla.accent)
            )
            Spacer(Modifier.width(8.dp))
            Text(p.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                "  ${if (isOrigin) "origin" else "station"} · $lineCount line${if (lineCount == 1) "" else "s"}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            "${p.stats.nFixes} fixes / ${"%.0f".format(p.stats.dwellS)} s  |  spread " +
                (p.stats.spreadM?.let { "±${f2(it)} m" } ?: "n/a") +
                "  |  phone ±${"%.1f".format(p.stats.reportedAccM)} m" +
                (if (p.mockGnss) "  |  EXT" else ""),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(if (isOrigin) "origin" else "use", primary = !isOrigin, enabled = !isOrigin, onClick = onUse)
            Pill("link…", onClick = onLink)
            Pill("rename", onClick = onRename)
            Pill("delete", danger = true, onClick = onDelete)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun LineRow(l: MeasuredLine, onDelete: () -> Unit) {
    val to = MeasureRepository.point(l.toId)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("→ ${to?.name ?: "?"}  ", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("${f2(l.distanceM)} m", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                l.sigmaM?.let { Text("  ±${f2(it)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (l.linked) {
                    Text(
                        "  linked",
                        fontSize = 10.sp,
                        color = Sla.warn
                    )
                }
            }
            val dh = MeasureRepository.heightDelta(l)
            Text(
                "brg ${"%.1f".format(l.bearingDeg)}°" +
                    (dh?.let { "  |  Δh ${"%+.2f".format(it)} m  |  slope ${f2(MeasureRepository.slopeDistance(l) ?: 0.0)} m" } ?: "") +
                    (if (l.linked) "  |  computed from stored points" else ""),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Delete line", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(start = 32.dp))
}

@Composable
private fun RenameDialog(p: SurveyPoint, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename ${p.name}") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { MeasureRepository.rename(p.id, name); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Pick stored stations to add lines to from [from], computed from the stored coordinates. */
@Composable
private fun LinkDialog(from: SurveyPoint, onDismiss: () -> Unit) {
    val candidates = remember(from) { MeasureRepository.linkCandidates(from.id) }
    val selected = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link ${from.name} to…") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Adds a line from ${from.name} to each station chosen, computed from the stored coordinates. " +
                        "Nothing is re-occupied; the line is flagged linked in the export.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (candidates.isEmpty()) {
                    Text("${from.name} already has a line to every station.")
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (selected.size == candidates.size) selected.clear()
                                else { selected.clear(); selected.addAll(candidates.map { it.id }) }
                            }
                    ) {
                        Checkbox(checked = selected.size == candidates.size, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Select all", fontWeight = FontWeight.SemiBold)
                    }
                    candidates.forEach { c ->
                        val checked = c.id in selected
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { if (checked) selected.remove(c.id) else selected.add(c.id) }
                                .padding(vertical = 2.dp)
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(c.name)
                                Text(
                                    "${f2(MeasureRepository.distance(from, c.lat, c.lon))} m · brg ${"%.1f".format(MeasureRepository.bearing(from, c.lat, c.lon))}°",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { MeasureRepository.link(from.id, selected.toList()); onDismiss() },
                enabled = selected.isNotEmpty()
            ) { Text("Link") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
