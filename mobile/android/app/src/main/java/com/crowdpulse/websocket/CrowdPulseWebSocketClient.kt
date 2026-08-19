package com.crowdpulse.websocket

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface WebSocketEventListener {
    fun onConnected()
    fun onPeerJoined(peerId: String, peerCount: Int)
    fun onPeerLeft(peerId: String)
    fun onPeerLocation(latitude: Double, longitude: Double, accuracy: Double, timestamp: Double)
    fun onSwitchP2P(distanceMeters: Double, suggestedRole: String, peerId: String)
    fun onSwitchMacro(distanceMeters: Double)
    fun onDisconnected(reason: String)
    fun onError(error: String)
}

class CrowdPulseWebSocketClient(
    private val serverUrl: String,
    private val listener: WebSocketEventListener
) {
    companion object {
        private const val TAG = "CrowdPulseWS"
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun connect(roomCode: String, peerId: String) {
        val fullUrl = "$serverUrl/$roomCode/$peerId"
        Log.i(TAG, "Connecting to WebSocket at $fullUrl")

        val request = Request.Builder().url(fullUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                Log.i(TAG, "WebSocket connected successfully.")
                mainHandler.post { listener.onConnected() }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type")
                    val payload = json.optJSONObject("payload") ?: JSONObject()

                    mainHandler.post {
                        when (type) {
                            "peer_joined" -> {
                                listener.onPeerJoined(
                                    payload.optString("peer_id"),
                                    payload.optInt("peer_count", 2)
                                )
                            }
                            "peer_left" -> {
                                listener.onPeerLeft(payload.optString("peer_id"))
                            }
                            "peer_location" -> {
                                listener.onPeerLocation(
                                    payload.optDouble("latitude"),
                                    payload.optDouble("longitude"),
                                    payload.optDouble("accuracy", 5.0),
                                    payload.optDouble("timestamp", 0.0)
                                )
                            }
                            "switch_p2p" -> {
                                listener.onSwitchP2P(
                                    payload.optDouble("distance_meters"),
                                    payload.optString("suggested_role", "subscriber"),
                                    payload.optString("peer_id")
                                )
                            }
                            "switch_macro" -> {
                                listener.onSwitchMacro(payload.optDouble("distance_meters"))
                            }
                            "error", "room_full" -> {
                                listener.onError(payload.optString("error", "Room is full"))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message: ${e.message}")
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                Log.i(TAG, "WebSocket closing: $reason")
                mainHandler.post { listener.onDisconnected(reason) }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                Log.e(TAG, "WebSocket failure: ${t.message}")
                mainHandler.post { listener.onError(t.message ?: "Connection failed") }
            }
        })
    }

    fun sendLocation(lat: Double, lon: Double, accuracy: Double, heading: Double, speed: Double) {
        if (!isConnected || webSocket == null) return

        try {
            val payload = JSONObject().apply {
                put("latitude", lat)
                put("longitude", lon)
                put("accuracy", accuracy)
                put("heading", heading)
                put("speed", speed)
                put("timestamp", System.currentTimeMillis() / 1000.0)
            }

            val msg = JSONObject().apply {
                put("type", "location")
                put("payload", payload)
            }

            webSocket?.send(msg.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending location: ${e.message}")
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        isConnected = false
    }
}
