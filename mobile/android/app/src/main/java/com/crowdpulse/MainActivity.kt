package com.crowdpulse

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.json.JSONObject
import kotlin.math.*

class MainActivity : ComponentActivity(), SensorEventListener {

    private var webSocketClient: WebSocketClient? = null

    // State Variables
    private var isConnected by mutableStateOf(false)
    private var lastMessage by mutableStateOf("No messages yet")
    private var statusText by mutableStateOf("Disconnected")

    private var hostUrl by mutableStateOf("wss://192.168.1.9:8443")
    private var roomId by mutableStateOf("FEST24")
    private var userId by mutableStateOf("android_user")

    // Location & Sensor State
    private var myLat by mutableStateOf<Double?>(null)
    private var myLon by mutableStateOf<Double?>(null)
    private var peerLat by mutableStateOf<Double?>(null)
    private var peerLon by mutableStateOf<Double?>(null)

    private var peerId by mutableStateOf<String?>(null)
    private var awareClient: WifiAwareSessionManager? = null

    private var bleClient: BleRangingManager? = null
    private var bleDistance by mutableStateOf<Double?>(null)
    private var bleStatus by mutableStateOf("Standby")

        private var wifiAwareStatus by mutableStateOf("Standby")

    private var heading by mutableStateOf(0f)

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null

    private val permissionsToRequest = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.toTypedArray()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            connectWebSocket()
            startLocationUpdates()
        } else {
            statusText = "Permissions required to connect!"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF050B14))) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        if (!isConnected) stopLocationUpdates()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            
            val remappedMatrix = FloatArray(9)
            // Remap coordinate system for a phone held upright in portrait mode
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_X,
                SensorManager.AXIS_Z,
                remappedMatrix
            )
            
            val orientation = FloatArray(3)
            SensorManager.getOrientation(remappedMatrix, orientation) // Use remapped
            var azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
            heading = (azimuth + 360) % 360
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @Composable
    fun MainScreen() {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("CROWDPULSE", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color(0xFF00E5FF))
            Spacer(modifier = Modifier.height(16.dp))

            if (!isConnected) {
                // Settings Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = hostUrl, onValueChange = { hostUrl = it },
                            label = { Text("Server URL") }, modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = roomId, onValueChange = { roomId = it },
                            label = { Text("Room ID") }, modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = userId, onValueChange = { userId = it },
                            label = { Text("User ID") }, modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { checkPermissionsAndConnect() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black)
                        ) {
                            Text("CONNETTI E AVVIA TRACKING", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                // Tracking Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("STATUS: $statusText", color = Color(0xFF34D399), fontWeight = FontWeight.Bold)
                            Button(
                                onClick = { disconnectWebSocket() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                            ) {
                                Text("ESCI")
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        
                        // RADAR
                        RadarView(myLat, myLon, peerLat, peerLon, heading, wifiAwareStatus, bleDistance, bleStatus)
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Raw Data Debug
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .background(Color(0xFF050B14))
                                .padding(8.dp)
                        ) {
                            Text("Logs: $lastMessage", color = Color.Gray, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissionsAndConnect() {
        val missingPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) {
            connectWebSocket()
            startLocationUpdates()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    myLat = loc.latitude
                    myLon = loc.longitude
                    webSocketClient?.sendLocation(loc.latitude, loc.longitude, loc.accuracy)
                }
            }
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
    }

    private fun connectWebSocket() {
        statusText = "Connecting..."
        webSocketClient = WebSocketClient(
            url = "$hostUrl/ws/$roomId/$userId",
            onMessage = { msg ->
                runOnUiThread { 
                    lastMessage = msg 
                    try {
                        val json = JSONObject(msg)
                        val t = json.optString("type")
                        if (t == "peer_location" || t == "location") {
                            val senderId = json.optString("sender_id")
                            if (senderId.isNotEmpty() && peerId == null && senderId != userId) {
                                peerId = senderId
                                val isPublisher = userId < peerId!!
                                startWifiAware(isPublisher)
                            }
                            val payload = json.optJSONObject("payload")
                            if (payload != null && payload.has("latitude")) {
                                peerLat = payload.getDouble("latitude")
                                peerLon = payload.getDouble("longitude")
                            } else if (json.has("latitude")) {
                                peerLat = json.getDouble("latitude")
                                peerLon = json.getDouble("longitude")
                            }
                        } else if (t == "peer_left") {
                            peerLat = null
                            peerLon = null
                            peerId = null
                            stopWifiAware()
                        }
                    } catch (e: Exception) {}
                }
            },
            onStatusChange = { connected ->
                runOnUiThread {
                    isConnected = connected
                    statusText = if (connected) "Connected" else "Disconnected"
                    if (!connected) {
                        peerLat = null
                        peerLon = null
                    }
                }
            }
        )
        webSocketClient?.connect()
    }

    private fun disconnectWebSocket() {
        webSocketClient?.disconnect()
        webSocketClient = null
        stopLocationUpdates()
        stopWifiAware()
        peerLat = null
        peerLon = null
        myLat = null
        myLon = null
        peerId = null
    }

    private fun startWifiAware(isPublisher: Boolean) {
        if (awareClient != null) return
        awareClient = WifiAwareSessionManager(
            context = this,
            roomId = roomId,
            isPublisher = isPublisher,
            onMessageReceived = { msg -> Log.d("MainActivity", "Aware Msg: $msg") },
            onStatus = { st -> runOnUiThread { wifiAwareStatus = st } }
        )
        awareClient?.start()
        
        // Start BLE directly when P2P triggers
        if (bleClient == null) {
            bleClient = BleRangingManager(
                context = this,
                roomId = roomId,
                onDistanceUpdated = { dist -> runOnUiThread { bleDistance = dist } },
                onStatus = { st -> runOnUiThread { bleStatus = st } }
            )
            bleClient?.start()
        }
    }

    private fun stopWifiAware() {
        awareClient?.stop()
        awareClient = null
        wifiAwareStatus = "Standby"
        
        bleClient?.stop()
        bleClient = null
        bleStatus = "Standby"
        bleDistance = null
    }
}

// --- Radar Components ---

@Composable
fun RadarView(myLat: Double?, myLon: Double?, peerLat: Double?, peerLon: Double?, heading: Float, wifiAwareStatus: String, bleDistance: Double?, bleStatus: String) {
    val RADAR_MAX_M = 100.0
    var distance by remember { mutableStateOf<Double?>(null) }
    var distanceText by remember { mutableStateOf("--") }

    
    
    if (bleDistance != null) {
        distance = bleDistance
        distanceText = String.format("%.1f", distance)
    } else if (myLat != null && myLon != null && peerLat != null && peerLon != null) {


        distance = haversine(myLat, myLon, peerLat, peerLon)
        distanceText = if (distance!! < 1000) String.format("%.1f", distance) else String.format("%.2fk", distance!! / 1000)
    } else {
        distance = null
        distanceText = "--"
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2, size.height / 2)
                
                // Background
                drawCircle(color = Color(0xFF0F1E35), radius = radius, center = center)
                drawCircle(color = Color(0xFF1E293B), radius = radius, center = center, style = Stroke(width = 3f))
                
                // Rings
                val rings = listOf(0.75f, 0.5f, 0.25f)
                rings.forEach { ratio ->
                    drawCircle(
                        color = Color(0xFF1E2D45),
                        radius = radius * ratio,
                        center = center,
                        style = Stroke(
                            width = 2f, 
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
                        )
                    )
                }

                // Crosshairs
                drawLine(Color(0xFF1E293B), Offset(center.x, 0f), Offset(center.x, size.height), strokeWidth = 2f)
                drawLine(Color(0xFF1E293B), Offset(0f, center.y), Offset(size.width, center.y), strokeWidth = 2f)

                // Needle (Compass)
                rotate(-heading, center) {
                    val needlePath = Path().apply {
                        moveTo(center.x, center.y - 40f)
                        lineTo(center.x - 15f, center.y)
                        lineTo(center.x + 15f, center.y)
                        close()
                    }
                    drawPath(needlePath, color = Color(0xFF00E5FF))
                    
                    val southPath = Path().apply {
                        moveTo(center.x, center.y + 40f)
                        lineTo(center.x - 15f, center.y)
                        lineTo(center.x + 15f, center.y)
                        close()
                    }
                    drawPath(southPath, color = Color(0xFFEF4444), alpha = 0.6f)
                    
                    drawCircle(Color(0xFF0B1628), radius = 12f, center = center)
                    drawCircle(Color(0xFF00E5FF), radius = 12f, center = center, style = Stroke(width = 4f))
                }

                // Peer dot
                if (myLat != null && myLon != null && peerLat != null && peerLon != null && distance != null) {
                    val brng = bearing(myLat, myLon, peerLat, peerLon)
                    val relRad = Math.toRadians((brng - heading + 360) % 360)
                    
                    val rPx = min(distance!! / RADAR_MAX_M, 1.0) * radius
                    val cx = center.x + (rPx * sin(relRad)).toFloat()
                    val cy = center.y - (rPx * cos(relRad)).toFloat()
                    
                    drawCircle(Color(0xFF00E5FF), radius = 24f, center = Offset(cx, cy), alpha = 0.3f)
                    drawCircle(Color(0xFF00E5FF), radius = 14f, center = Offset(cx, cy))
                    drawCircle(Color.White, radius = 6f, center = Offset(cx, cy))
                }
                
                // You (Center dot)
                drawCircle(Color.White, radius = 8f, center = center)
            }
            
            // Labels
            Text("N", color = Color(0xFF64748B), fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp))
            Text("S", color = Color(0xFF64748B), fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
            Text("W", color = Color(0xFF64748B), fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp))
            Text("E", color = Color(0xFF64748B), fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))
        val color = if (distance != null) {
            if (distance!! < 20.0) Color(0xFF00FF66) else if (distance!! < 50.0) Color(0xFFFBBF24) else Color.White
        } else Color.White
        
        Text(text = distanceText, fontSize = 56.sp, fontWeight = FontWeight.Black, color = color, lineHeight = 56.sp)
        Text(text = "METRI", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF), letterSpacing = 3.sp)
        Spacer(modifier = Modifier.height(4.dp))
        if (bleDistance != null) {
            Text(text = "SOURCE: BLUETOOTH (RSSI)", color = Color(0xFFA855F7), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        } else {
            Text(text = "SOURCE: GPS", color = Color.Gray, fontSize = 10.sp)
        }
        Text(text = "BLE: $bleStatus", color = Color.Gray, fontSize = 9.sp)
        Text(text = "Wi-Fi Aware: $wifiAwareStatus", color = Color.Gray, fontSize = 9.sp)
    }
}

fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0
    val r = Math.PI / 180.0
    val dLat = (lat2 - lat1) * r
    val dLon = (lon2 - lon1) * r
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * r) * cos(lat2 * r) * sin(dLon / 2) * sin(dLon / 2)
    return R * 2 * atan2(sqrt(a), sqrt(1 - a))
}

fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = Math.PI / 180.0
    val y = sin((lon2 - lon1) * r) * cos(lat2 * r)
    val x = cos(lat1 * r) * sin(lat2 * r) - sin(lat1 * r) * cos(lat2 * r) * cos((lon2 - lon1) * r)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}
