package com.example.geosurvey

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Colour for a fix-quality badge, matching the accuracy colours used in the top bar. */
fun fixQualityColor(q: FixQuality?): Color = when (q) {
    FixQuality.RTK_FIXED -> Color(0xFF2E7D32)
    FixQuality.RTK_FLOAT -> Color(0xFF1565C0)
    FixQuality.DGPS -> Color(0xFFE65100)
    null -> Color(0xFF546E7A)
    else -> Color(0xFFC62828)
}

@SuppressLint("MissingPermission")
@Composable
fun ReceiverDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by ReceiverBridge.state.collectAsState()
    val stats by ReceiverBridge.stats.collectAsState()
    val mock by ReceiverBridge.mockStatus.collectAsState()
    val baud by ReceiverBridge.baud.collectAsState()
    val share by ReceiverBridge.shareAsPhoneLocation.collectAsState()

    var tab by remember { mutableStateOf(ReceiverBridge.Link.USB) }
    var refresh by remember { mutableStateOf(0) }
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); now = SystemClock.elapsedRealtime(); refresh++ }
    }

    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GNSS receiver") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            if (state is ReceiverBridge.State.Connected || state is ReceiverBridge.State.Connecting) {
                TextButton(onClick = { ReceiverBridge.disconnect() }) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val s = state) {
                    is ReceiverBridge.State.Connected -> {
                        Text("Connected · ${s.description}", fontWeight = FontWeight.SemiBold)
                        val fix = stats.lastFix
                        val age = (now - stats.lastFixAtMs) / 1000.0
                        if (fix == null || age > 5) {
                            Text(
                                if (fix == null) "No position yet — check the baud rate and that the receiver outputs NMEA GGA."
                                else "No fix for ${"%.0f".format(age)} s",
                                fontSize = 12.sp,
                                color = Sla.warn
                            )
                        }
                        fix?.let { f ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    f.quality.label,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(fixQualityColor(f.quality))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "±${"%.3f".format(f.accuracyM)} m" + if (f.accuracyFromGst) " (GST)" else " (est. from HDOP)",
                                    fontSize = 13.sp
                                )
                            }
                            Text(
                                "${f.satellites} sats · HDOP ${f.hdop?.let { "%.1f".format(it) } ?: "—"}" +
                                    (f.ageOfCorrectionsS?.let { " · corrections ${"%.0f".format(it)} s old" } ?: "") +
                                    (f.altMsl?.let { " · ${"%.2f".format(it)} m MSL" } ?: ""),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "${stats.sentences} NMEA sentences" + if (stats.badChecksums > 0) " · ${stats.badChecksums} bad checksums" else "",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    is ReceiverBridge.State.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(s.description)
                    }
                    is ReceiverBridge.State.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    ReceiverBridge.State.Disconnected -> Text(
                        "Connect a GNSS receiver to use its positions in GeoSurvey (and, optionally, as the phone's location for other apps).",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Share as phone location (mock)
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Share as phone location", fontSize = 14.sp)
                        Text(
                            when (mock) {
                                ReceiverBridge.MockStatus.PUBLISHING -> "Other apps now get the receiver's position"
                                ReceiverBridge.MockStatus.NOT_ALLOWED -> "Needs GeoSurvey as the mock location app"
                                ReceiverBridge.MockStatus.OFF -> "Mock location for other apps"
                            },
                            fontSize = 11.sp,
                            color = if (mock == ReceiverBridge.MockStatus.NOT_ALLOWED) Sla.warn else MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(checked = share, onCheckedChange = { ReceiverBridge.setShareAsPhoneLocation(it) })
                }
                if (mock == ReceiverBridge.MockStatus.NOT_ALLOWED) {
                    Text(
                        "Developer options › Select mock location app › GeoSurvey. (Enable Developer options by tapping " +
                            "Build number 7 times in About phone.) Only one app can hold this — it replaces the Quescan bridge.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (e: Exception) {
                                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }) { Text("Developer options") }
                        TextButton(onClick = { ReceiverBridge.retryMock() }) { Text("Retry") }
                    }
                }

                // Device pickers (when not connected)
                if (state !is ReceiverBridge.State.Connected && state !is ReceiverBridge.State.Connecting) {
                    HorizontalDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = tab == ReceiverBridge.Link.USB,
                            onClick = { tab = ReceiverBridge.Link.USB },
                            leadingIcon = { Icon(Icons.Default.Usb, null, modifier = Modifier.size(16.dp)) },
                            label = { Text("USB cable") }
                        )
                        FilterChip(
                            selected = tab == ReceiverBridge.Link.BLUETOOTH,
                            onClick = { tab = ReceiverBridge.Link.BLUETOOTH },
                            leadingIcon = { Icon(Icons.Default.Bluetooth, null, modifier = Modifier.size(16.dp)) },
                            label = { Text("Bluetooth") }
                        )
                    }

                    if (tab == ReceiverBridge.Link.USB) {
                        Text("Baud rate", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            ReceiverBridge.BAUD_RATES.forEach { b ->
                                FilterChip(selected = b == baud, onClick = { ReceiverBridge.setBaud(b) }, label = { Text("$b", fontSize = 12.sp) })
                            }
                        }
                        val devices = remember(refresh) { ReceiverBridge.usbCandidates(context) }
                        if (devices.isEmpty()) {
                            Text(
                                "No USB serial device found. Plug the receiver in with a USB-C OTG cable; Android may ask to open GeoSurvey.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        devices.forEach { d ->
                            OutlinedButton(onClick = { ReceiverBridge.connectUsb(context, d.device) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Usb, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(d.label, maxLines = 1)
                            }
                        }
                    } else {
                        if (!ReceiverBridge.hasBluetoothPermission(context)) {
                            Button(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            }) { Text("Allow Bluetooth access") }
                        } else {
                            val paired = remember(refresh) { ReceiverBridge.pairedBluetoothDevices(context) }
                            if (paired.isEmpty()) {
                                Text(
                                    "No paired devices. Pair the receiver in Android Bluetooth settings first.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            paired.forEach { d ->
                                OutlinedButton(onClick = { ReceiverBridge.connectBluetooth(context, d) }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Bluetooth, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(d.name ?: d.address, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}
