package com.crowdpulse

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.crowdpulse.nativebridge.NativeMathEngine
import com.crowdpulse.nativebridge.TrackerResult
import com.crowdpulse.sensors.ImuStepListener
import com.crowdpulse.sensors.ImuStepTracker
import com.crowdpulse.websocket.CrowdPulseWebSocketClient
import com.crowdpulse.websocket.WebSocketEventListener
import com.crowdpulse.wifi.WifiAwareController
import com.crowdpulse.wifi.WifiAwareListener
import com.crowdpulse.wifi.WifiRttController
import com.crowdpulse.wifi.WifiRttListener
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import android.net.wifi.aware.PeerHandle

class CrowdPulseModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext),
    WebSocketEventListener,
    WifiAwareListener,
    WifiRttListener,
    ImuStepListener {

    companion object {
        const val NAME = "CrowdPulseModule"
        private const val TAG = "CrowdPulseModule"
    }

    override fun getName(): String = NAME

    private val mathEngine = NativeMathEngine()
    private val imuTracker = ImuStepTracker(reactContext, this)
    private val wifiAware = WifiAwareController(reactContext, this)
    private val wifiRtt = WifiRttController(reactContext, this)
    private var wsClient: CrowdPulseWebSocketClient? = null

    private var currentStage = "DISCONNECTED"
    private var currentUserAzimuthDeg: Double = 0.0
    private var latestRttDistance: Double = -1.0
    private var isP2PActive = false

    // State machine management
    private fun setStage(newStage: String) {
        currentStage = newStage
        val params = Arguments.createMap().apply {
            putString("stage", newStage)
        }
        sendEvent("onStageChanged", params)
    }

    private fun sendEvent(eventName: String, params: WritableMap?) {
        if (reactApplicationContext.hasActiveReactInstance()) {
            reactApplicationContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, params)
        }
    }

    // ==========================================
    // React Native Exposed Methods
    // ==========================================

    @ReactMethod
    fun connectRoom(serverUrl: String, roomCode: String, peerId: String, promise: Promise) {
        try {
            mathEngine.reset()
            wsClient = CrowdPulseWebSocketClient(serverUrl, this)
            wsClient?.connect(roomCode, peerId)
            setStage("MACRO_TRACKING")
            imuTracker.start()
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("CONNECT_ERROR", e.message)
        }
    }

    @ReactMethod
    fun sendLocationUpdate(lat: Double, lon: Double, accuracy: Double, heading: Double, speed: Double) {
        wsClient?.sendLocation(lat, lon, accuracy, heading, speed)
    }

    @ReactMethod
    fun startMicroP2P(role: String, peerId: String) {
        isP2PActive = true
        setStage("MICRO_P2P")
        wifiAware.startDiscovery(role, peerId)
    }

    @ReactMethod
    fun stopAll() {
        isP2PActive = false
        wsClient?.disconnect()
        wifiRtt.stop()
        wifiAware.stop()
        imuTracker.stop()
        mathEngine.reset()
        setStage("DISCONNECTED")
    }

    // ==========================================
    // WebSocket Listener Callbacks (Stage 1)
    // ==========================================

    override fun onConnected() {
        Log.i(TAG, "WebSocket connected.")
    }

    override fun onPeerJoined(peerId: String, peerCount: Int) {
        val params = Arguments.createMap().apply {
            putString("peerId", peerId)
            putInt("peerCount", peerCount)
        }
        sendEvent("onPeerJoined", params)
    }

    override fun onPeerLeft(peerId: String) {
        val params = Arguments.createMap().apply {
            putString("peerId", peerId)
        }
        sendEvent("onPeerLeft", params)
    }

    override fun onPeerLocation(latitude: Double, longitude: Double, accuracy: Double, timestamp: Double) {
        val params = Arguments.createMap().apply {
            putDouble("latitude", latitude)
            putDouble("longitude", longitude)
            putDouble("accuracy", accuracy)
            putDouble("timestamp", timestamp)
        }
        sendEvent("onPeerLocation", params)
    }

    override fun onSwitchP2P(distanceMeters: Double, suggestedRole: String, peerId: String) {
        Log.i(TAG, "Triggering Stage 2 Micro P2P handoff. Distance: $distanceMeters m, Role: $suggestedRole")
        setStage("TRANSITIONING_TO_P2P")
        startMicroP2P(suggestedRole, peerId)
    }

    override fun onSwitchMacro(distanceMeters: Double) {
        Log.i(TAG, "Returning to Stage 1 Macro tracking.")
        wifiRtt.stop()
        wifiAware.stop()
        isP2PActive = false
        setStage("MACRO_TRACKING")
    }

    override fun onDisconnected(reason: String) {
        setStage("DISCONNECTED")
    }

    override fun onError(error: String) {
        val params = Arguments.createMap().apply {
            putString("error", error)
        }
        sendEvent("onError", params)
    }

    // ==========================================
    // Wi-Fi Aware & RTT Callbacks (Stage 2)
    // ==========================================

    override fun onAwareAvailable() {
        Log.i(TAG, "Wi-Fi Aware NAN ready.")
    }

    override fun onPeerDiscovered(peerHandle: PeerHandle) {
        Log.i(TAG, "Peer discovered via NAN! Initiating Wi-Fi RTT ranging.")
        wifiRtt.startRanging(peerHandle)
    }

    override fun onAwareError(error: String) {
        Log.w(TAG, "Wi-Fi Aware fallback to simulated ranging: $error")
        // Start simulated ranging if hardware is unavailable
        wifiRtt.startRanging(null)
    }

    override fun onRangingSample(distanceMeters: Double, stdDevMeters: Double, timestamp: Double) {
        latestRttDistance = distanceMeters

        // Process measurement in math engine
        val result = mathEngine.update(0.0, 0.0, distanceMeters, timestamp)
        dispatchNavigationUpdate(result)
    }

    override fun onRangingError(error: String) {
        Log.w(TAG, "RTT ranging error: $error")
    }

    // ==========================================
    // IMU Sensor Callbacks
    // ==========================================

    override fun onStep(dx: Double, dy: Double, azimuthRad: Double, timestamp: Double) {
        if (isP2PActive) {
            val result = mathEngine.update(dx, dy, latestRttDistance, timestamp)
            dispatchNavigationUpdate(result)
        }
    }

    override fun onOrientationUpdate(azimuthDeg: Double, pitchDeg: Double, rollDeg: Double) {
        currentUserAzimuthDeg = azimuthDeg
        val params = Arguments.createMap().apply {
            putDouble("azimuth", azimuthDeg)
            putDouble("pitch", pitchDeg)
            putDouble("roll", rollDeg)
        }
        sendEvent("onOrientationUpdate", params)
    }

    private fun dispatchNavigationUpdate(result: TrackerResult) {
        // Calculate relative bearing angle to display on needle relative to user's current facing heading
        var relativeBearing = result.filteredBearingDeg - currentUserAzimuthDeg
        while (relativeBearing < -180.0) relativeBearing += 360.0
        while (relativeBearing > 180.0) relativeBearing -= 360.0

        val params = Arguments.createMap().apply {
            putDouble("distance", result.filteredDistance)
            putDouble("bearingDeg", result.filteredBearingDeg)
            putDouble("relativeBearingDeg", relativeBearing)
            putDouble("confidence", result.confidence)
            putBoolean("isConverged", result.isConverged)
            putDouble("relativeSpeed", result.relativeSpeed)
        }
        sendEvent("onMicroNavigationUpdate", params)
    }
}
