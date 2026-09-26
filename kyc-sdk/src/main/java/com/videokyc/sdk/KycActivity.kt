package com.videokyc.sdk

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.videokyc.sdk.databinding.ActivityKycBinding
import org.webrtc.IceCandidate
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class KycActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_REQUEST = "video_kyc_sdk_request"
        const val EXTRA_RESULT = "video_kyc_sdk_result"
        const val REQUEST_CODE = 29071
        private const val PERMISSION_CODE = 29072
    }

    private lateinit var binding: ActivityKycBinding
    private lateinit var request: KycSdkRequest
    private lateinit var api: KycApi
    private lateinit var socket: KycSocket
    private var rtc: KycWebRtc? = null
    private var session: KycSession? = null
    private var wsToken: String? = null
    private var finishing = false
    private var started = false
    private var micEnabled = true
    private var cameraEnabled = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    private var refreshRunnable: Runnable? = null
    private var reconnectRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKycBinding.inflate(layoutInflater)
        setContentView(binding.root)
        request = intent.getParcelableExtra(EXTRA_REQUEST) ?: run { finishWith(KycSdkResult("INVALID_REQUEST", message = "Missing KYC request")); return }
        api = KycApi(request)
        socket = KycSocket(request)
        binding.title.text = request.title
        binding.brandName.text = "Developed by misba"
        bindUi()
        checkPermissionsAndStart()
    }

    private fun bindUi() {
        binding.mic.setOnClickListener {
            micEnabled = !micEnabled
            rtc?.setMic(micEnabled)
            session?.let { socket.mute(it.sessionId, !micEnabled) }
            binding.mic.tooltipText = if (micEnabled) "Mute" else "Unmute"
        }
        binding.camera.setOnClickListener {
            cameraEnabled = !cameraEnabled
            rtc?.setCamera(cameraEnabled)
            session?.let { socket.video(it.sessionId, cameraEnabled) }
            binding.camera.tooltipText = if (cameraEnabled) "Camera off" else "Camera on"
        }
        binding.end.setOnClickListener { confirmEnd() }
        binding.cancel.setOnClickListener { confirmEnd() }
        binding.mediaControls.visibility = if (request.showMediaControls) View.VISIBLE else View.GONE
    }

    private fun checkPermissionsAndStart() {
        val missing = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startKyc() else ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_CODE && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startKyc()
        else finishWith(KycSdkResult("PERMISSION_DENIED", message = "Camera and microphone permissions are required for Video KYC"))
    }

    private fun startKyc() {
        if (started) return
        started = true
        binding.progress.visibility = View.VISIBLE
        binding.status.text = "Creating KYC request…"
        scope.launch {
            try {
                session = api.createKyc()
                wsToken = session?.userWsToken
                if (wsToken.isNullOrBlank()) throw KycSdkException("KYC session did not return a realtime token")
                api.setWsToken(wsToken!!)
                bindSocket()
                socket.connect(wsToken!!)
                renderSession(session!!)
                startPolling()
                startTokenRefresh()
            } catch (t: Throwable) {
                finishWith(KycSdkResult("ERROR", session?.sessionId, session?.sessionCode, session?.status, message = t.message ?: "Unable to start KYC"))
            }
        }
    }

    private fun bindSocket() {
        socket.onEvent = { event -> handleSocketEvent(event) }
        socket.onError = { t ->
            if (!finishing) {
                binding.error.visibility = View.VISIBLE
                binding.error.text = t.message ?: "Realtime connection error"
                if (isLive(session?.status)) scheduleReconnect()
            }
        }
        socket.onClosed = { if (!finishing && isLive(session?.status)) scheduleReconnect() }
    }

    private fun handleSocketEvent(event: KycSocketEvent) {
        val data = event.data
        val s = session ?: return
        when (event.event) {
            "connection.ready" -> Unit
            "webrtc.ready" -> {
                prepareRtcIfNeeded()
                socket.ready(s.sessionId)
            }
            "kyc.accepted" -> {
                if (sameSession(data)) {
                    session = s.copy(status = "ACCEPTED", agentId = data.optStringOrNull("agentId") ?: s.agentId)
                    renderSession(session!!)
                    prepareRtcIfNeeded()
                }
            }
            "webrtc.call.start" -> prepareRtcIfNeeded()
            "webrtc.offer" -> rtc?.handleOffer(data.optString("sdp"), data.optString("type", "offer"))
            "webrtc.answer" -> rtc?.handleAnswer(data.optString("sdp"), data.optString("type", "answer"))
            "webrtc.ice_candidate" -> {
                val candidate = data.optString("candidate")
                if (candidate.isNotBlank()) rtc?.handleIce(IceCandidate(data.optStringOrNull("sdpMid"), data.optIntOrNull("sdpMLineIndex")!!, candidate))
            }
            "webrtc.connected" -> markConnected()
            "webrtc.reconnect" -> {
                rtc?.close()
                rtc = null
                prepareRtcIfNeeded()
                socket.ready(s.sessionId)
            }
            "kyc.rejected" -> {
                session = s.copy(status = "WAITING", agentId = null)
                renderSession(session!!)
            }
            "kyc.approved", "kyc.rejected.final" -> {
                val status = data.optString("status", if (event.event == "kyc.approved") "COMPLETED" else "REJECTED")
                finishWith(KycSdkResult(
                    if (status == "COMPLETED") "APPROVED" else "REJECTED",
                    s.sessionId, s.sessionCode, status,
                    data.optJSONObject("action")?.optStringOrNull("code") ?: s.finalActionCode,
                    message = if (status == "COMPLETED") "KYC completed" else "KYC rejected"
                ))
            }
            "call.ended", "kyc.completed", "webrtc.hangup" -> {
                val terminal = data.optString("status", "COMPLETED")
                finishWith(KycSdkResult("ENDED", s.sessionId, s.sessionCode, terminal, endedReason = data.optStringOrNull("reason"), message = if (data.optString("reason") == "AGENT_ENDED") "KYC call ended by agent" else "KYC call ended"))
            }
            "webrtc.error", "error" -> {
                binding.error.visibility = View.VISIBLE
                binding.error.text = data.optString("message", "Realtime error")
            }
        }
    }

    private fun prepareRtcIfNeeded() {
        val s = session ?: return
        if (s.status != "ACCEPTED" && s.status != "CONNECTING") return
        if (rtc != null) {
            socket.ready(s.sessionId)
            return
        }
        session = s.copy(status = if (s.status == "ACCEPTED") "CONNECTING" else s.status)
        renderSession(session!!)
        rtc = KycWebRtc(this, request, socket) { session?.sessionId }
        rtc?.onConnected = { runOnUiThread { markConnected() } }
        rtc?.onRemoteVideo = { runOnUiThread { binding.liveBadge.text = "LIVE" } }
        rtc?.onFailed = { message -> runOnUiThread { binding.error.visibility = View.VISIBLE; binding.error.text = message } }
        rtc?.initialize(binding.localView, binding.remoteView)
        binding.videoContainer.visibility = View.VISIBLE
        binding.callActions.visibility = View.VISIBLE
        binding.mediaControls.visibility = if (request.showMediaControls) View.VISIBLE else View.GONE
        socket.ready(s.sessionId)
    }

    private fun markConnected() {
        val s = session ?: return
        if (s.status == "CONNECTED" || s.status == "IN_PROGRESS") return
        session = s.copy(status = "CONNECTED")
        binding.status.text = "Connected with KYC agent"
        binding.liveBadge.text = "LIVE"
        binding.progress.visibility = View.GONE
    }

    private fun renderSession(s: KycSession) {
        binding.sessionCode.text = s.sessionCode
        binding.status.text = when (s.status) {
            "WAITING" -> if (s.queuePosition != null) "Waiting for an available KYC agent • Position ${s.queuePosition}" else "Waiting for an available KYC agent…"
            "ASSIGNED", "RINGING" -> "Agent assigned — waiting for acceptance…"
            "ACCEPTED" -> "Agent accepted — preparing video…"
            "CONNECTING" -> "Connecting video…"
            "CONNECTED" -> "Connected with KYC agent"
            "IN_PROGRESS" -> "KYC verification in progress"
            else -> s.status
        }
        val live = isLive(s.status)
        binding.cancel.visibility = if (!live) View.VISIBLE else View.GONE
        binding.callActions.visibility = if (live) View.VISIBLE else View.GONE
        binding.mediaControls.visibility = if (live && request.showMediaControls) View.VISIBLE else View.GONE
        binding.progress.visibility = if (live && s.status !in setOf("CONNECTED", "IN_PROGRESS")) View.VISIBLE else View.GONE
    }

    private fun startPolling() {
        pollRunnable?.let(handler::removeCallbacks)
        lateinit var pollingTask: Runnable
        pollingTask = object : Runnable {
            override fun run() {
                if (finishing) {
                    return
                }
                val current = session ?: return
                scope.launch {
                    try {
                        val latest = api.getSession(current.sessionId)
                        if (finishing) {
                            return@launch
                        }
                        session = latest
                        renderSession(latest)
                        when (latest.status.uppercase()) {
                            "ACCEPTED", "CONNECTING" -> {
                                prepareRtcIfNeeded()
                            }
                            "CONNECTED", "IN_PROGRESS" -> {
                                // Real WebRTC state is handled by KycWebRtc.
                            }
                            "COMPLETED" -> {
                                finishWith(
                                    KycSdkResult(
                                        resultCode = "APPROVED",
                                        sessionId = latest.sessionId,
                                        sessionCode = latest.sessionCode,
                                        status = latest.status,
                                        finalActionCode = latest.finalActionCode,
                                        endedReason = latest.endedReason,
                                        message = "KYC completed"
                                    )
                                )
                                return@launch
                            }
                            "REJECTED" -> {
                                finishWith(
                                    KycSdkResult(
                                        resultCode = "REJECTED",
                                        sessionId = latest.sessionId,
                                        sessionCode = latest.sessionCode,
                                        status = latest.status,
                                        finalActionCode = latest.finalActionCode,
                                        endedReason = latest.endedReason,
                                        message = "KYC rejected"
                                    )
                                )
                                return@launch
                            }
                            "CANCELLED", "TIMEOUT", "FAILED" -> {
                                finishWith(
                                    KycSdkResult(
                                        resultCode = "ENDED",
                                        sessionId = latest.sessionId,
                                        sessionCode = latest.sessionCode,
                                        status = latest.status,
                                        finalActionCode = latest.finalActionCode,
                                        endedReason = latest.endedReason,
                                        message = "KYC session ended"
                                    )
                                )
                                return@launch
                            }
                        }
                    } catch (t: Throwable) {
                        if (!finishing) {
                            binding.error.visibility = View.VISIBLE
                            binding.error.text = t.message ?: "Unable to refresh KYC session"
                        }
                    }
                    if (!finishing) {
                        handler.postDelayed(
                            pollingTask, 3000L
                        )
                    }
                }
            }
        }
        pollRunnable = pollingTask
        handler.postDelayed(
            pollingTask, 3000L
        )
    }

    private fun startTokenRefresh() {
        refreshRunnable?.let(handler::removeCallbacks)
        lateinit var refreshTask: Runnable
        refreshTask = object : Runnable {
            override fun run() {
                if (finishing) {
                    return
                }
                val token = wsToken
                val currentSession = session
                if (token.isNullOrBlank() ||
                    currentSession == null) {
                    return
                }
                scope.launch {
                    try {
                        val freshToken =api.refreshWsToken(currentSession.sessionId,token)
                        if (!finishing) {
                            wsToken = freshToken
                            api.setWsToken(freshToken)
                        }
                    } catch (t: Throwable) {
                        if (!finishing) {
                            binding.error.visibility = View.VISIBLE
                            binding.error.text =
                                "Realtime token refresh failed"
                        }
                    }
                    if (!finishing) {
                        handler.postDelayed(refreshTask,5 * 60 * 1000L)
                    }
                }
            }
        }
        refreshRunnable = refreshTask
        handler.postDelayed(refreshTask,5 * 60 * 1000L)
    }
    private fun scheduleReconnect() {
        if (finishing || wsToken.isNullOrBlank()) return
        reconnectRunnable?.let(handler::removeCallbacks)
        reconnectRunnable = Runnable {
            val token = wsToken ?: return@Runnable
            runCatching { socket.connect(token) }.onFailure { scheduleReconnect() }
        }
        handler.postDelayed(reconnectRunnable!!, 2000)
    }

    private fun confirmEnd() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("End KYC?")
            .setMessage("The current KYC session will be cancelled.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("End") { _, _ -> endCall() }
            .show()
    }

    private fun endCall() {
        val s = session
        if (s == null) { finishWith(KycSdkResult("CANCELLED", message = "KYC cancelled")); return }
        finishing = true
        socket.hangup(s.sessionId)
        scope.launch { runCatching { api.cancel(s.sessionId) }; finishWith(KycSdkResult("CANCELLED", s.sessionId, s.sessionCode, "CANCELLED", message = "KYC session cancelled")) }
    }

    private fun finishWith(result: KycSdkResult) {
        if (isFinishing) return
        finishing = true
        cleanup()
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT, result))
        finish()
    }

    private fun cleanup() {
        pollRunnable?.let(handler::removeCallbacks)
        refreshRunnable?.let(handler::removeCallbacks)
        reconnectRunnable?.let(handler::removeCallbacks)
        pollRunnable = null; refreshRunnable = null; reconnectRunnable = null
        rtc?.close(); rtc = null
        socket.close(false)
        scope.cancel()
    }

    override fun onBackPressed() {
        if (isLive(session?.status)) confirmEnd() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (!finishing) cleanup()
        super.onDestroy()
    }
    private fun sameSession(data: JSONObject): Boolean =
        data.optStringOrNull("sessionId")?.equals(session?.sessionId, ignoreCase = true) != false
    private fun isLive(status: String?): Boolean = status in setOf("WAITING", "ASSIGNED", "RINGING", "ACCEPTED", "CONNECTING", "CONNECTED", "IN_PROGRESS", "RECONNECTING")
    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key) && optString(key).isNotBlank()) optString(key) else null
    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
}
