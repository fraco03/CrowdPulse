package com.crowdpulse.wifi

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.*
import android.os.Handler
import android.os.Looper
import android.util.Log

interface WifiAwareListener {
    fun onAwareAvailable()
    fun onPeerDiscovered(peerHandle: PeerHandle)
    fun onAwareError(error: String)
}

/**
 * Manages Wi-Fi Aware (NAN) session lifecycle, service publishing and subscription.
 */
class WifiAwareController(
    private val context: Context,
    private val listener: WifiAwareListener
) {
    companion object {
        private const val TAG = "WifiAwareController"
        const val SERVICE_NAME = "CrowdPulse_Direct_NAN"
    }

    private val wifiAwareManager: WifiAwareManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        } else {
            null
        }

    private var awareSession: WifiAwareSession? = null
    private var discoverySession: DiscoverySession? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isSupported(): Boolean = wifiAwareManager != null

    fun startDiscovery(role: String, peerId: String) {
        if (wifiAwareManager == null) {
            Log.w(TAG, "Wi-Fi Aware not supported on this device. Using simulation mode.")
            listener.onAwareError("FEATURE_WIFI_AWARE not supported.")
            return
        }

        if (!wifiAwareManager.isAvailable) {
            listener.onAwareError("Wi-Fi Aware is currently unavailable (Wi-Fi or Location may be off).")
            return
        }

        wifiAwareManager.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                Log.i(TAG, "Wi-Fi Aware session attached successfully.")
                awareSession = session
                listener.onAwareAvailable()

                if (role.equals("publisher", ignoreCase = true)) {
                    publishService()
                } else {
                    subscribeService()
                }
            }

            override fun onAttachFailed() {
                Log.e(TAG, "Wi-Fi Aware attach failed.")
                listener.onAwareError("Wi-Fi Aware attach failed.")
            }
        }, mainHandler)
    }

    private fun publishService() {
        val config = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setRangingEnabled(true)
            .build()

        awareSession?.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                Log.i(TAG, "NAN Publish started.")
                discoverySession = session
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                Log.i(TAG, "Message received from subscriber peer.")
                listener.onPeerDiscovered(peerHandle)
            }
        }, mainHandler)
    }

    private fun subscribeService() {
        val config = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setMaxDistanceMm(50000) // Max 50 meters
            .build()

        awareSession?.subscribe(config, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                Log.i(TAG, "NAN Subscribe started.")
                discoverySession = session
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: List<ByteArray>?
            ) {
                Log.i(TAG, "Discovered CrowdPulse publisher peer!")
                listener.onPeerDiscovered(peerHandle)
            }
        }, mainHandler)
    }

    fun stop() {
        discoverySession?.close()
        discoverySession = null
        awareSession?.close()
        awareSession = null
        Log.i(TAG, "Wi-Fi Aware session stopped.")
    }
}
