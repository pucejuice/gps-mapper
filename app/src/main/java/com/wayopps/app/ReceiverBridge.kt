package com.wayopps.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.InputStream
import java.util.UUID

/**
 * Built-in GNSS receiver bridge: reads NMEA from a receiver on USB (serial / CDC) or
 * Bluetooth (SPP), feeds the fixes straight into wayopps, and — when wayopps is
 * selected as the mock location app in Developer options — publishes them as the
 * phone's location so other apps use the receiver too.
 */
object ReceiverBridge {

    enum class Link { USB, BLUETOOTH }

    sealed class State {
        object Disconnected : State()
        data class Connecting(val description: String) : State()
        data class Connected(val link: Link, val description: String) : State()
        data class Failed(val message: String) : State()
    }

    enum class MockStatus { OFF, PUBLISHING, NOT_ALLOWED }

    data class Stats(
        val lastFix: ReceiverFix? = null,
        val lastFixAtMs: Long = 0,
        val sentences: Int = 0,
        val badChecksums: Int = 0,
        val bytes: Long = 0
    )

    private const val PREFS = "wayopps_receiver"
    private const val ACTION_USB_PERMISSION = "com.wayopps.app.USB_PERMISSION"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    val BAUD_RATES = listOf(9600, 38400, 57600, 115200, 230400, 460800)

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state = _state.asStateFlow()

    private val _stats = MutableStateFlow(Stats())
    val stats = _stats.asStateFlow()

    private val _mockStatus = MutableStateFlow(MockStatus.OFF)
    val mockStatus = _mockStatus.asStateFlow()

    private val _baud = MutableStateFlow(115200)
    val baud = _baud.asStateFlow()

    private val _shareAsPhoneLocation = MutableStateFlow(true)
    val shareAsPhoneLocation = _shareAsPhoneLocation.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var readJob: Job? = null
    private var openResource: Closeable? = null
    private var appContext: Context? = null
    private var receiversRegistered = false
    private var pendingUsbDevice: UsbDevice? = null

    private var locationManager: LocationManager? = null
    private var fused: FusedLocationProviderClient? = null

    fun attach(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _baud.value = p.getInt("baud", 115200)
        _shareAsPhoneLocation.value = p.getBoolean("share", true)
        locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        fused = LocationServices.getFusedLocationProviderClient(ctx)
    }

    val isConnected: Boolean get() = _state.value is State.Connected

    /** True while fixes are arriving from the receiver (phone GPS is then ignored). */
    fun isFeeding(): Boolean = isConnected && SystemClock.elapsedRealtime() - _stats.value.lastFixAtMs < 3000

    fun setBaud(value: Int) {
        _baud.value = value
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putInt("baud", value)?.apply()
    }

    fun setShareAsPhoneLocation(on: Boolean) {
        _shareAsPhoneLocation.value = on
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean("share", on)?.apply()
        if (isConnected) {
            if (on) enableMock() else disableMock()
        }
    }

    // ---------------- device discovery ----------------
    data class UsbCandidate(val device: UsbDevice, val label: String)

    fun usbCandidates(context: Context): List<UsbCandidate> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val known = UsbSerialProber.getDefaultProber().findAllDrivers(manager).map { it.device.deviceId }.toSet()
        return manager.deviceList.values
            .filter { it.deviceId in known || hasCdcInterface(it) }
            .map { d ->
                val name = listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank { "USB device" }
                UsbCandidate(d, "$name (%04X:%04X)".format(d.vendorId, d.productId))
            }
    }

    private fun hasCdcInterface(d: UsbDevice): Boolean =
        (0 until d.interfaceCount).any {
            val c = d.getInterface(it).interfaceClass
            c == UsbConstants.USB_CLASS_COMM || c == UsbConstants.USB_CLASS_CDC_DATA
        }

    private fun driverFor(manager: UsbManager, device: UsbDevice): UsbSerialDriver? =
        UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull { it.device.deviceId == device.deviceId }
            ?: if (hasCdcInterface(device)) CdcAcmSerialDriver(device) else null

    fun hasBluetoothPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun pairedBluetoothDevices(context: Context): List<BluetoothDevice> {
        if (!hasBluetoothPermission(context)) return emptyList()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return emptyList()
        return try {
            adapter.bondedDevices?.sortedBy { it.name ?: it.address } ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    // ---------------- connect / disconnect ----------------
    fun connectUsb(context: Context, device: UsbDevice) {
        attach(context)
        registerReceivers()
        val ctx = appContext ?: return
        val manager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!manager.hasPermission(device)) {
            pendingUsbDevice = device
            _state.value = State.Connecting("Waiting for USB permission…")
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            manager.requestPermission(device, PendingIntent.getBroadcast(ctx, 0, intent, flags))
            return
        }
        val driver = driverFor(manager, device)
        if (driver == null) {
            _state.value = State.Failed("No serial driver for this USB device")
            return
        }
        disconnect()
        val label = listOfNotNull(device.productName).joinToString().ifBlank { "USB receiver" }
        _state.value = State.Connecting("Opening $label…")
        scope.launch {
            try {
                val port = withContext(Dispatchers.IO) {
                    val connection = manager.openDevice(driver.device) ?: error("Could not open the USB device")
                    val p = driver.ports.first()
                    p.open(connection)
                    p.setParameters(_baud.value, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    try { p.setDTR(true); p.setRTS(true) } catch (e: Exception) { /* not all chips support it */ }
                    p
                }
                openResource = Closeable { try { port.close() } catch (e: Exception) {} }
                onConnected(Link.USB, "$label · ${_baud.value} baud")
                startReading { buf -> port.read(buf, 500) }
            } catch (e: Exception) {
                fail("USB: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectBluetooth(context: Context, device: BluetoothDevice) {
        attach(context)
        if (!hasBluetoothPermission(context)) {
            _state.value = State.Failed("Bluetooth permission needed")
            return
        }
        disconnect()
        val label = device.name ?: device.address
        _state.value = State.Connecting("Connecting to $label…")
        scope.launch {
            try {
                val socket: BluetoothSocket = withContext(Dispatchers.IO) {
                    val adapter = (appContext?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                    adapter?.cancelDiscovery()
                    val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    s.connect()
                    s
                }
                openResource = socket
                val input: InputStream = socket.inputStream
                onConnected(Link.BLUETOOTH, label)
                startReading { buf -> input.read(buf) }
            } catch (e: Exception) {
                fail("Bluetooth: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun disconnect() {
        readJob?.cancel()
        readJob = null
        try { openResource?.close() } catch (e: Exception) {}
        openResource = null
        disableMock()
        if (_state.value !is State.Failed) _state.value = State.Disconnected
    }

    private fun fail(message: String) {
        disconnect()
        _state.value = State.Failed(message)
    }

    private fun onConnected(link: Link, description: String) {
        _stats.value = Stats()
        _state.value = State.Connected(link, description)
        if (_shareAsPhoneLocation.value) enableMock()
    }

    /** Reads raw bytes on IO, splits NMEA lines, and hands fixes to the main thread. */
    private fun startReading(read: (ByteArray) -> Int) {
        readJob?.cancel()
        readJob = scope.launch(Dispatchers.IO) {
            val assembler = NmeaAssembler()
            val buf = ByteArray(4096)
            val line = StringBuilder()
            var bytes = 0L
            try {
                while (isActive) {
                    val n = read(buf)
                    if (n < 0) throw java.io.IOException("Connection closed")
                    if (n == 0) continue
                    bytes += n
                    for (i in 0 until n) {
                        val c = buf[i].toInt().toChar()
                        if (c == '\n' || c == '\r') {
                            if (line.isNotEmpty()) {
                                val fix = assembler.accept(line.toString())
                                line.setLength(0)
                                if (fix != null) {
                                    val sentences = assembler.sentences
                                    val bad = assembler.badChecksums
                                    val total = bytes
                                    withContext(Dispatchers.Main) { onFix(fix, sentences, bad, total) }
                                }
                            }
                        } else if (line.length < 512) {
                            line.append(c)
                        } else {
                            line.setLength(0) // runaway binary data
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) withContext(Dispatchers.Main) { fail("Receiver disconnected: ${e.message ?: "read error"}") }
            }
        }
    }

    private fun onFix(fix: ReceiverFix, sentences: Int, bad: Int, bytes: Long) {
        _stats.value = Stats(fix, SystemClock.elapsedRealtime(), sentences, bad, bytes)
        val loc = toLocation(fix)
        SurveyRepository.onExternalFix(loc, fix)
        if (_mockStatus.value == MockStatus.PUBLISHING) publishMock(loc)
    }

    private fun toLocation(fix: ReceiverFix) = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = fix.lat
        longitude = fix.lon
        fix.altEllipsoid?.let { altitude = it }
        if (Build.VERSION.SDK_INT >= 34) fix.altMsl?.let { mslAltitudeMeters = it }
        accuracy = fix.accuracyM.toFloat()
        fix.verticalAccuracyM?.let { verticalAccuracyMeters = it.toFloat() }
        fix.speedMps?.let { speed = it.toFloat() }
        fix.courseDeg?.let { bearing = it.toFloat() }
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        extras = Bundle().apply {
            putInt("satellites", fix.satellites)
            putString("fixQuality", fix.quality.label)
        }
    }

    // ---------------- mock location publishing ----------------
    @Suppress("DEPRECATION")
    private fun enableMock() {
        val lm = locationManager ?: return
        try {
            try { lm.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (e: Exception) {}
            lm.addTestProvider(
                LocationManager.GPS_PROVIDER,
                false, false, false, false, true, true, true,
                Criteria.POWER_LOW, Criteria.ACCURACY_FINE
            )
            lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            try { fused?.setMockMode(true) } catch (e: SecurityException) {}
            _mockStatus.value = MockStatus.PUBLISHING
        } catch (e: SecurityException) {
            // wayopps is not the selected mock location app
            _mockStatus.value = MockStatus.NOT_ALLOWED
        } catch (e: IllegalArgumentException) {
            _mockStatus.value = MockStatus.NOT_ALLOWED
        }
    }

    private fun publishMock(loc: Location) {
        try {
            locationManager?.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
            fused?.setMockLocation(loc)
        } catch (e: Exception) {
            _mockStatus.value = MockStatus.NOT_ALLOWED
        }
    }

    private fun disableMock() {
        if (_mockStatus.value == MockStatus.PUBLISHING) {
            try { locationManager?.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (e: Exception) {}
            try { fused?.setMockMode(false) } catch (e: Exception) {}
        }
        _mockStatus.value = MockStatus.OFF
    }

    /** Try again after the user has selected wayopps in Developer options. */
    fun retryMock() {
        if (isConnected && _shareAsPhoneLocation.value) enableMock()
    }

    // ---------------- USB permission / detach ----------------
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = pendingUsbDevice
                    pendingUsbDevice = null
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null && granted) connectUsb(context, device)
                    else _state.value = State.Failed("USB permission denied")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (_state.value.let { it is State.Connected && it.link == Link.USB }) fail("USB receiver unplugged")
                }
            }
        }
    }

    private fun registerReceivers() {
        val ctx = appContext ?: return
        if (receiversRegistered) return
        receiversRegistered = true
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(ctx, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Called when the activity is opened by plugging in a receiver. */
    fun onUsbAttached(context: Context, device: UsbDevice) {
        attach(context)
        if (isConnected) return
        scope.launch {
            delay(300) // let the device settle
            connectUsb(context, device)
        }
    }
}
