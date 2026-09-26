package com.videokyc.sdk

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.Collections
internal class KycWebRtc(
    private val context: Context,
    private val config: KycSdkRequest,
    private val socket: KycSocket,
    private val sessionId: () -> String?
) {
    companion object {
        private const val TAG = "VideoKycWebRTC"
        @Volatile private var initialized = false

        private fun ensureFactory(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                initialized = true
            }
        }
    }

    val eglBase: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private var peer: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localAudio: AudioTrack? = null
    private var localVideo: VideoTrack? = null
    private var localStream: MediaStream? = null
    private var remoteVideo: VideoTrack? = null
    private val pendingIce =Collections.synchronizedList(mutableListOf<IceCandidate>())
    private var remoteDescriptionSet = false
    private var connectedSent = false

    var onLocalReady: (() -> Unit)? = null
    var onRemoteVideo: (() -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onFailed: ((String) -> Unit)? = null

    init {
        ensureFactory(context)
        val adm = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        adm.release()
    }

    fun initialize(localView: SurfaceViewRenderer, remoteView: SurfaceViewRenderer) {
        localView.init(eglBase.eglBaseContext, null)
        localView.setMirror(true)
        localView.setEnableHardwareScaler(true)
        remoteView.init(eglBase.eglBaseContext, null)
        remoteView.setEnableHardwareScaler(true)
        createPeer(localView, remoteView)
    }

    private fun createPeer(localView: SurfaceViewRenderer, remoteView: SurfaceViewRenderer) {
        val iceServers = mutableListOf<PeerConnection.IceServer>()
        iceServers += PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        iceServers += PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        if (config.turnUrl.isNotBlank() && config.turnUsername.isNotBlank() && config.turnCredential.isNotBlank()) {
            val urls = mutableListOf(config.turnUrl.trim())
            if (!config.turnUrl.contains("?transport=")) {
                urls += "${config.turnUrl.trim()}?transport=tcp"
            }
            iceServers += PeerConnection.IceServer.builder(urls)
                .setUsername(config.turnUsername)
                .setPassword(config.turnCredential)
                .createIceServer()
        }

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peer = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                sessionId()?.let { socket.ice(it, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex) }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState) { Log.d(TAG, "signaling=$state") }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ice=$state")
                if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) markConnected()
                if (state == PeerConnection.IceConnectionState.FAILED) onFailed?.invoke("ICE connection failed")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) { Log.d(TAG, "gathering=$state") }
           // override fun onIceCandidateError( event: PeerConnection.IceCandidateErrorEvent?) { Log.w(TAG, "ice candidate error=$event") }
            override fun onAddStream(stream: MediaStream) {
                stream.videoTracks.firstOrNull()?.let { attachRemote(it, remoteView) }
            }
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dataChannel: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track is VideoTrack) attachRemote(track, remoteView)
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) attachRemote(track, remoteView)
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "connection=$newState")
                if (newState == PeerConnection.PeerConnectionState.CONNECTED) markConnected()
                if (newState == PeerConnection.PeerConnectionState.FAILED) onFailed?.invoke("Peer connection failed")
            }
            override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState) {}
            //override fun onSelectedCandidatePairChanged(event: PeerConnection.PeerConnectionEvent?) {}
        })

        if (peer == null) {
            onFailed?.invoke("Unable to create peer connection")
            return
        }
        createLocalMedia(localView)
    }

    private fun createLocalMedia(localView: SurfaceViewRenderer) {
        val audioConstraints = MediaConstraints()
        audioSource = factory.createAudioSource(audioConstraints)
        localAudio = factory.createAudioTrack("kyc-audio", audioSource)

        val enumerator = Camera2Enumerator(context)
        val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
        if (cameraName == null) {
            onFailed?.invoke("No camera is available")
            return
        }
        cameraCapturer = enumerator.createCapturer(cameraName, object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(errorDescription: String) { Log.e(TAG, "camera error=$errorDescription") }
            override fun onCameraDisconnected() {}
            override fun onCameraFreezed(errorDescription: String) { Log.e(TAG, "camera freezed=$errorDescription") }
            override fun onCameraOpening(cameraName: String) {}
            override fun onFirstFrameAvailable() {}
            override fun onCameraClosed() {}
        })
        surfaceTextureHelper = SurfaceTextureHelper.create("KycCaptureThread", eglBase.eglBaseContext)
        videoSource = factory.createVideoSource(cameraCapturer!!.isScreencast)
        cameraCapturer!!.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
        cameraCapturer!!.startCapture(1280, 720, 30)
        localVideo = factory.createVideoTrack("kyc-video", videoSource)
        localStream = factory.createLocalMediaStream("kyc-stream").also {
            it.addTrack(localAudio)
            it.addTrack(localVideo)
        }
        localVideo?.addSink(localView)
        peer?.addTrack(localAudio, listOf("kyc-stream"))
        peer?.addTrack(localVideo, listOf("kyc-stream"))
        onLocalReady?.invoke()
    }

    private fun attachRemote(track: VideoTrack, remoteView: SurfaceViewRenderer) {
        remoteVideo?.removeSink(remoteView)
        remoteVideo = track
        track.setEnabled(true)
        track.addSink(remoteView)
        onRemoteVideo?.invoke()
    }

    fun handleOffer(sdp: String, type: String) {
        val p = peer ?: return
        p.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                flushPendingIce()
                p.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(description: SessionDescription) {
                        p.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                sessionId()?.let { socket.answer(it, description.description, description.type.canonicalForm()) }
                            }
                            override fun onSetFailure(error: String) { onFailed?.invoke("Unable to set local answer: $error") }
                        }, description)
                    }
                    override fun onCreateFailure(error: String) { onFailed?.invoke("Unable to create answer: $error") }
                }, MediaConstraints())
            }
            override fun onSetFailure(error: String) { onFailed?.invoke("Unable to set remote offer: $error") }
        }, SessionDescription(SessionDescription.Type.fromCanonicalForm(type) ?: SessionDescription.Type.OFFER, sdp))
    }

    fun handleAnswer(sdp: String,type: String) {
        val p = peer ?: run {
            onFailed?.invoke("Cannot process answer: PeerConnection is null")
            return
        }

        val sessionType =SessionDescription.Type.fromCanonicalForm(type)
                ?: SessionDescription.Type.ANSWER
        val answer = SessionDescription(sessionType,sdp)
        p.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    remoteDescriptionSet = true
                    Log.d(TAG,"Remote answer successfully applied")
                    flushPendingIce()
                }
                override fun onSetFailure(error: String) {
                    Log.e(TAG,"Failed to apply remote answer: $error")
                    onFailed?.invoke("Unable to apply remote answer: $error")
                }
            },
            answer
        )
    }

    fun handleIce(candidate: IceCandidate) {
        val p = peer
        if (
            p == null ||
            !remoteDescriptionSet
        ) {
            synchronized(pendingIce) {
                pendingIce.add(candidate)
            }
            Log.d(
                TAG,
                "ICE queued. remoteDescriptionSet=$remoteDescriptionSet"
            )

            return
        }

        val accepted = p.addIceCandidate(candidate)

        Log.d(
            TAG,
            "ICE candidate added=$accepted"
        )
    }

    private fun flushPendingIce() {
        val p = peer ?: return
        if (!remoteDescriptionSet) {
            return
        }
        val candidates = synchronized(pendingIce) {
            val copy = pendingIce.toList()
            pendingIce.clear()
            copy
        }
        candidates.forEach { candidate ->
            val accepted =p.addIceCandidate(candidate)
            Log.d(TAG,"Queued ICE added=$accepted")
        }
    }

    private fun markConnected() {
        if (connectedSent) return
        connectedSent = true
        sessionId()?.let { socket.connected(it) }
        onConnected?.invoke()
    }

    fun setMic(enabled: Boolean) { localAudio?.setEnabled(enabled) }
    fun setCamera(enabled: Boolean) { localVideo?.setEnabled(enabled) }

    fun close() {
        runCatching { cameraCapturer?.stopCapture() }
        runCatching { cameraCapturer?.dispose() }
        cameraCapturer = null
        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null
        localVideo?.dispose()
        localAudio?.dispose()
        localVideo = null
        localAudio = null
        audioSource?.dispose()
        videoSource?.dispose()
        audioSource = null
        videoSource = null
        peer?.close()
        peer?.dispose()
        peer = null
        remoteVideo = null
        pendingIce.clear()
        connectedSent = false
        eglBase.release()
        factory.dispose()
    }

    abstract class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {}
        override fun onSetFailure(error: String) {}
    }
}
