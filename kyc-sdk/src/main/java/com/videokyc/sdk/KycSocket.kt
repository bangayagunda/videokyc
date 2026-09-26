package com.videokyc.sdk

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class KycSocketEvent(val event: String, val data: JSONObject)

internal class KycSocket(private val config: KycSdkRequest) {
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    var connected = false
        private set
    var onEvent: ((KycSocketEvent) -> Unit)? = null
    var onClosed: (() -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    fun connect(token: String) {
        close(false)
        val base = config.apiBaseUrl.trimEnd('/')
        val wsBase = when {
            base.startsWith("https://") -> base.replaceFirst("https://", "wss://")
            base.startsWith("http://") -> base.replaceFirst("http://", "ws://")
            else -> base
        }
        val url = "$wsBase/ws/user?token=${java.net.URLEncoder.encode(token, "UTF-8")}"
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val message = JSONObject(text)
                    val data = when {
                        message.opt("data") is JSONObject -> message.getJSONObject("data")
                        message.opt("payload") is JSONObject -> message.getJSONObject("payload")
                        else -> JSONObject()
                    }
                    main.post { onEvent?.invoke(KycSocketEvent(message.optString("event"), data)) }
                }.onFailure { main.post { onError?.invoke(it) } }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                main.post { onError?.invoke(t) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                main.post { onClosed?.invoke() }
            }
        })
    }

    fun send(event: String, sessionId: String, payload: JSONObject = JSONObject()) {
        val message = JSONObject().put("event", event).put("sessionId", sessionId)
        if (payload.length() > 0) message.put("payload", payload)
        socket?.send(message.toString())
    }

    fun ready(sessionId: String) = send("webrtc.ready", sessionId)
    fun hangup(sessionId: String) = send("webrtc.hangup", sessionId)
    fun connected(sessionId: String) = send("webrtc.connected", sessionId)
    fun reconnect(sessionId: String) = send("webrtc.reconnect", sessionId)
    fun mute(sessionId: String, muted: Boolean) = send("webrtc.mute_changed", sessionId, JSONObject().put("muted", muted))
    fun video(sessionId: String, enabled: Boolean) = send("webrtc.video_changed", sessionId, JSONObject().put("enabled", enabled))

    fun offer(sessionId: String, sdp: String, type: String) =
        send("webrtc.offer", sessionId, JSONObject().put("sdp", sdp).put("type", type))

    fun answer(sessionId: String, sdp: String, type: String) =
        send("webrtc.answer", sessionId, JSONObject().put("sdp", sdp).put("type", type))

    fun ice(sessionId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?) =
        send("webrtc.ice_candidate", sessionId, JSONObject().apply {
            put("candidate", candidate)
            if (sdpMid != null) put("sdpMid", sdpMid)
            if (sdpMLineIndex != null) put("sdpMLineIndex", sdpMLineIndex)
        })

    fun close(notify: Boolean = true) {
        val old = socket
        socket = null
        connected = false
        old?.close(1000, "client closed")
        if (notify) main.post { onClosed?.invoke() }
    }
}
