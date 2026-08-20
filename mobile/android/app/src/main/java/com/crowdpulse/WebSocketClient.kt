package com.crowdpulse

import android.annotation.SuppressLint
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class WebSocketClient(
    private val url: String,
    private val onMessage: (String) -> Unit,
    private val onStatusChange: (Boolean) -> Unit
) {
    private var webSocket: WebSocket? = null

    // Unsafe client to accept self-signed certificates in local dev (e.g., wss://192.168...)
    private val client: OkHttpClient = createUnsafeOkHttpClient()

    fun connect() {
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i("WebSocketClient", "Connected to $url")
                onStatusChange(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("WebSocketClient", "Msg received: $text")
                onMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("WebSocketClient", "Closed: $reason")
                onStatusChange(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocketClient", "Error: ${t.message}", t)
                onStatusChange(false)
            }
        })
    }

    fun sendLocation(lat: Double, lng: Double, accuracy: Float) {
        webSocket?.let { ws ->
            try {
                val payload = JSONObject().apply {
                    put("latitude", lat)
                    put("longitude", lng)
                    put("accuracy", accuracy.toDouble())
                    put("timestamp", System.currentTimeMillis() / 1000.0)
                }
                val message = JSONObject().apply {
                    put("type", "location")
                    put("payload", payload)
                }
                ws.send(message.toString())
                Log.d("WebSocketClient", "Sent: $message")
            } catch (e: Exception) {
                Log.e("WebSocketClient", "JSON Error: ${e.message}")
            }
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        onStatusChange(false)
    }

    companion object {
        @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
        private fun createUnsafeOkHttpClient(): OkHttpClient {
            try {
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                })

                val sslContext = SSLContext.getInstance("SSL")
                sslContext.init(null, trustAllCerts, SecureRandom())
                val sslSocketFactory = sslContext.socketFactory

                return OkHttpClient.Builder()
                    .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                    .hostnameVerifier { _, _ -> true }
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }
    }
}
