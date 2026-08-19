package com.crowdpulse.wifi

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.PeerHandle
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

interface WifiRttListener {
    fun onRangingSample(distanceMeters: Double, stdDevMeters: Double, timestamp: Double)
    fun onRangingError(error: String)
}

/**
 * Controller for high-precision Wi-Fi RTT (IEEE 802.11mc) ranging.
 */
class WifiRttController(
    private val context: Context,
    private val listener: WifiRttListener
) {
    companion object {
        private const val TAG = "WifiRttController"
        private const val RANGING_INTERVAL_MS = 150L // ~7 Hz ranging rate
    }

    private val wifiRttManager: WifiRttManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)) {
            context.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager
        } else {
            null
        }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRanging = false
    private var activePeerHandle: PeerHandle? = null

    // Simulation / synthetic ranging for testing
    private var simulatedDistance = 25.0

    fun isSupported(): Boolean = wifiRttManager != null

    fun startRanging(peerHandle: PeerHandle?) {
        activePeerHandle = peerHandle
        isRanging = true

        if (wifiRttManager != null && peerHandle != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            scheduleHardwareRanging()
        } else {
            Log.i(TAG, "Starting simulated Wi-Fi RTT ranging.")
            scheduleSimulatedRanging()
        }
    }

    private fun scheduleHardwareRanging() {
        if (!isRanging) return

        val peer = activePeerHandle ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || wifiRttManager == null) return

        try {
            val request = RangingRequest.Builder()
                .addWifiAwarePeer(peer)
                .build()

            wifiRttManager.startRanging(request, executor, object : RangingResultCallback() {
                override fun onRangingResults(results: List<RangingResult>) {
                    if (!isRanging) return

                    for (res in results) {
                        if (res.status == RangingResult.STATUS_SUCCESS) {
                            val distMeters = res.distanceMm / 1000.0
                            val stdDevMeters = res.distanceStdDevMm / 1000.0
                            val ts = System.currentTimeMillis() / 1000.0

                            mainHandler.post {
                                listener.onRangingSample(distMeters, stdDevMeters, ts)
                            }
                        }
                    }

                    // Schedule next measurement
                    mainHandler.postDelayed({ scheduleHardwareRanging() }, RANGING_INTERVAL_MS)
                }

                override fun onRangingFailure(code: Int) {
                    Log.w(TAG, "RTT ranging failure code: $code")
                    if (isRanging) {
                        mainHandler.postDelayed({ scheduleHardwareRanging() }, RANGING_INTERVAL_MS * 2)
                    }
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing location/Wi-Fi permission for RTT: ${e.message}")
            listener.onRangingError("Permission missing for RTT ranging")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting RTT ranging: ${e.message}")
        }
    }

    private fun scheduleSimulatedRanging() {
        if (!isRanging) return

        // Add slight random noise simulating real RTT measurement jitter
        val jitter = (Math.random() - 0.5) * 0.4
        val dist = (simulatedDistance + jitter).coerceAtLeast(0.5)
        val ts = System.currentTimeMillis() / 1000.0

        listener.onRangingSample(dist, 0.3, ts)

        mainHandler.postDelayed({ scheduleSimulatedRanging() }, RANGING_INTERVAL_MS)
    }

    fun setSimulatedDistance(dist: Double) {
        simulatedDistance = dist
    }

    fun stop() {
        isRanging = false
        activePeerHandle = null
        Log.i(TAG, "Wi-Fi RTT ranging stopped.")
    }
}
