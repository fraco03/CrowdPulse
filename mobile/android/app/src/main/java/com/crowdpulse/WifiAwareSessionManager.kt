
package com.crowdpulse

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.*
import android.os.Handler
import android.os.Looper
import android.util.Log

class WifiAwareSessionManager(
    private val context: Context,
    private val roomId: String,
    private val isPublisher: Boolean,
    private val onMessageReceived: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val awareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as WifiAwareManager?
    private var awareSession: WifiAwareSession? = null
    private var discoverySession: DiscoverySession? = null
    private var currentPeerHandle: PeerHandle? = null
    private val handler = Handler(Looper.getMainLooper())

    private var messageCounter = 0

    fun start() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) || awareManager?.isAvailable != true) {
            onStatus("Wi-Fi Aware not supported or disabled")
            Log.e("WifiAwareSession", "Wi-Fi Aware not available")
            return
        }

        onStatus("Attaching to Wi-Fi Aware...")
        awareManager.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                awareSession = session
                onStatus("Attached as ${if (isPublisher) "Publisher" else "Subscriber"}")
                if (isPublisher) startPublishing() else startSubscribing()
            }

            override fun onAttachFailed() {
                onStatus("Attach failed")
                Log.e("WifiAwareSession", "Attach failed")
            }
        }, handler)
    }

    @SuppressLint("MissingPermission")
    private fun startPublishing() {
        val config = PublishConfig.Builder()
            .setServiceName("CP_$roomId")
            .build()

        awareSession?.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                discoverySession = session
                onStatus("Publishing...")
                Log.i("WifiAwareSession", "Publish started")
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                currentPeerHandle = peerHandle
                val msgStr = String(message)
                Log.d("WifiAwareSession", "Msg from Sub: $msgStr")
                onMessageReceived(msgStr)
            }
        }, handler)
    }

    @SuppressLint("MissingPermission")
    private fun startSubscribing() {
        val config = SubscribeConfig.Builder()
            .setServiceName("CP_$roomId")
            .build()

        awareSession?.subscribe(config, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                discoverySession = session
                onStatus("Subscribing...")
                Log.i("WifiAwareSession", "Subscribe started")
            }

            override fun onServiceDiscovered(peerHandle: PeerHandle, matchInfo: ByteArray, distanceMm: MutableList<ByteArray>) {
                currentPeerHandle = peerHandle
                onStatus("Publisher discovered!")
                Log.i("WifiAwareSession", "Service discovered")
                // Ping publisher to establish bidirectional msg capability
                sendMessage("HELLO_FROM_SUB")
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                val msgStr = String(message)
                Log.d("WifiAwareSession", "Msg from Pub: $msgStr")
                onMessageReceived(msgStr)
            }
        }, handler)
    }

    fun sendMessage(msg: String) {
        val handle = currentPeerHandle
        val session = discoverySession
        if (handle != null && session != null) {
            session.sendMessage(handle, messageCounter++, msg.toByteArray())
            Log.d("WifiAwareSession", "Sent: $msg")
        } else {
            Log.w("WifiAwareSession", "Cannot send message, peer or session null")
        }
    }

    fun stop() {
        discoverySession?.close()
        awareSession?.close()
        discoverySession = null
        awareSession = null
        currentPeerHandle = null
        onStatus("Stopped")
        Log.i("WifiAwareSession", "Session stopped")
    }
}
