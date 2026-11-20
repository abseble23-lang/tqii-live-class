package com.tqii.liveclass

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.AudioTrack
import org.webrtc.AudioSource
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PeerState(val uid: String, name: String, role: String) {
    val name = mutableStateOf(name)
    val role = mutableStateOf(role)
    val video = mutableStateOf<VideoTrack?>(null)
    val mic = mutableStateOf(true)
    val cam = mutableStateOf(true)
    val sharing = mutableStateOf(false)
    val connected = mutableStateOf(false)
    /** "connecting" | "connected" | "failed" */
    val conn = mutableStateOf("connecting")
}

class CallManager(
    private val ctx: Context,
    val classId: String,
    val isTeacher: Boolean,
) : ClassChannel.Listener {

    companion object { var current: CallManager? = null }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val myId: String = Api.userId ?: ""
    val myName: String = Api.me?.fullName ?: ""

    // ---- UI state ----
    val peers = mutableStateMapOf<String, PeerState>()
    val messages = mutableStateListOf<ChatMsg>()
    val names = mutableStateMapOf<String, String>()
    val micOn = mutableStateOf(true)
    val camOn = mutableStateOf(true)
    val sharing = mutableStateOf(false)
    val frontCamera = mutableStateOf(true)
    val connected = mutableStateOf(false)
    val ended = mutableStateOf(false)
    val unread = mutableStateOf(0)
    val bg = mutableStateOf(BG_OPTIONS[0])
    var chatOpen = false

    // ---- WebRTC ----
    val egl: EglBase = EglBase.create()
    private lateinit var factory: PeerConnectionFactory
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var camCapturer: CameraVideoCapturer? = null
    private var camHelper: SurfaceTextureHelper? = null
    private var camSource: VideoSource? = null
    val localVideo = mutableStateOf<VideoTrack?>(null)
    private val bgProcessor = BackgroundProcessor(ctx)

    private var screenCapturer: ScreenCapturerAndroid? = null
    private var screenHelper: SurfaceTextureHelper? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null

    private var iceServers: List<PeerConnection.IceServer> = emptyList()
    private val pcs = HashMap<String, PeerConnection>()
    private val videoSenders = HashMap<String, RtpSender>()
    private val pendingIce = HashMap<String, MutableList<IceCandidate>>()
    private val iceReady = HashSet<String>()                       // we may send ICE to this peer
    private val outIce = HashMap<String, MutableList<JSONObject>>() // ICE waiting for our SDP to go out first
    private val retries = HashMap<String, Int>()
    private val watchdogs = HashMap<String, Job>()
    val speakerOn = mutableStateOf(true)
    private var channel: ClassChannel? = null
    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun start() {
        current = this
        Diag.start(ctx, classId)
        Diag.log("start me=$myId teacher=$isTeacher sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL}")
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setAudioDeviceModule(
                JavaAudioDeviceModule.builder(ctx)
                    .setUseHardwareAcousticEchoCanceler(true)
                    .setUseHardwareNoiseSuppressor(true)
                    .createAudioDeviceModule()
            )
            .createPeerConnectionFactory()

        // audio
        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("audio0", audioSource)
        setupAudioRouting()

        // camera
        startCamera(front = true)

        scope.launch {
            names[myId] = myName
            try {
                Api.profiles().values.forEach { names[it.id] = it.fullName }
            } catch (_: Exception) {}
            iceServers = Api.iceServers()
            Diag.log("ice servers: " + iceServers.joinToString { it.urls.joinToString("|") + (if (it.username.isNullOrEmpty()) "" else "(auth)") })
            try {
                messages.addAll(Api.messages(classId))
            } catch (_: Exception) {}
            if (isTeacher) {
                try { Api.setStatus(classId, "live") } catch (e: Exception) { Log.w("Call", "status", e) }
            }
            val presence = JSONObject().put("uid", myId).put("name", myName).put("role", if (isTeacher) "teacher" else "student")
            channel = ClassChannel(classId, presence, this@CallManager).also { it.connect() }
        }
    }

    private fun setupAudioRouting() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        applySpeaker()
    }

    private fun applySpeaker() {
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = audioManager.availableCommunicationDevices
            val headset = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            val target = headset
                ?: devices.firstOrNull { it.type == if (speakerOn.value) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            target?.let { audioManager.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = speakerOn.value && !audioManager.isWiredHeadsetOn
        }
    }

    fun toggleSpeaker() {
        speakerOn.value = !speakerOn.value
        applySpeaker()
    }

    private fun startCamera(front: Boolean) {
        val enumerator = Camera2Enumerator(ctx)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) == front }
            ?: enumerator.deviceNames.firstOrNull() ?: return
        frontCamera.value = enumerator.isFrontFacing(name)
        val capturer = enumerator.createCapturer(name, null)
        val helper = SurfaceTextureHelper.create("camera", egl.eglBaseContext)
        val source = factory.createVideoSource(false)
        source.setVideoProcessor(bgProcessor)
        capturer.initialize(helper, ctx, source.capturerObserver)
        capturer.startCapture(640, 480, 24)
        camCapturer = capturer; camHelper = helper; camSource = source
        val track = factory.createVideoTrack("video0", source)
        track.setEnabled(camOn.value)
        localVideo.value = track
    }

    // ---------------- controls ----------------
    fun toggleMic() {
        micOn.value = !micOn.value
        audioTrack?.setEnabled(micOn.value)
        sendState()
    }

    fun toggleCam() {
        camOn.value = !camOn.value
        localVideo.value?.setEnabled(camOn.value)
        if (camOn.value) camCapturer?.startCapture(640, 480, 24) else try { camCapturer?.stopCapture() } catch (_: Exception) {}
        sendState()
    }

    fun flipCamera() {
        camCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) { scope.launch { frontCamera.value = isFront } }
            override fun onCameraSwitchError(error: String?) {}
        })
    }

    fun setBackground(opt: BgOption) {
        bg.value = opt
        bgProcessor.option = opt
    }

    fun startShare(projectionData: Intent) {
        if (sharing.value) return
        CallService.instance?.promoteForProjection()
        val metrics = ctx.resources.displayMetrics
        var w = metrics.widthPixels; var h = metrics.heightPixels
        val scale = 1280f / maxOf(w, h)
        if (scale < 1f) { w = (w * scale).toInt(); h = (h * scale).toInt() }
        w = w and 1.inv(); h = h and 1.inv()
        val capturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() { scope.launch { stopShare() } }
        })
        val helper = SurfaceTextureHelper.create("screen", egl.eglBaseContext)
        val source = factory.createVideoSource(true)
        capturer.initialize(helper, ctx, source.capturerObserver)
        try {
            capturer.startCapture(w, h, 15)
        } catch (e: Exception) {
            Log.w("Call", "screen capture failed", e)
            helper.dispose(); source.dispose(); return
        }
        val track = factory.createVideoTrack("screen0", source)
        screenCapturer = capturer; screenHelper = helper; screenSource = source; screenTrack = track
        videoSenders.values.forEach { it.setTrack(track, false) }
        sharing.value = true
        sendState()
    }

    fun stopShare() {
        if (!sharing.value) return
        sharing.value = false
        localVideo.value?.let { cam -> videoSenders.values.forEach { it.setTrack(cam, false) } }
        try { screenCapturer?.stopCapture() } catch (_: Exception) {}
        screenCapturer?.dispose(); screenCapturer = null
        screenTrack?.dispose(); screenTrack = null
        screenSource?.dispose(); screenSource = null
        screenHelper?.dispose(); screenHelper = null
        sendState()
    }

    fun sendChat(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        scope.launch {
            try {
                Api.sendMessage(classId, body)
                val latest = Api.messages(classId)
                latest.forEach { m -> if (messages.none { it.id == m.id }) messages.add(m) }
            } catch (e: Exception) { Log.w("Call", "chat", e) }
        }
    }

    fun endForAll() {
        scope.launch {
            channel?.broadcast("end", JSONObject().put("from", myId))
            try { Api.setStatus(classId, "ended") } catch (_: Exception) {}
            ended.value = true
        }
    }

    private fun sendState() {
        channel?.broadcast("state", JSONObject().put("from", myId)
            .put("mic", micOn.value).put("cam", camOn.value).put("sharing", sharing.value))
    }

    // ---------------- signaling ----------------
    override fun onConnection(connected: Boolean) { this.connected.value = connected; Diag.log("channel connected=$connected") }

    override fun onJoined() { sendState() }

    override fun onPresence(online: Map<String, JSONObject>) {
        val others = online.filterKeys { it != myId && it.isNotBlank() }
        Diag.log("presence: ${online.keys}")
        // left
        peers.keys.filter { it !in others }.forEach { closePeer(it) }
        // joined
        others.forEach { (uid, meta) ->
            if (!peers.containsKey(uid)) {
                val nm = meta.optString("name").ifBlank { names[uid] ?: "?" }
                peers[uid] = PeerState(uid, nm, meta.optString("role"))
                names[uid] = nm
                retries[uid] = 0
                if (myId < uid) createPeer(uid, makeOffer = true) else watch(uid)
                sendState()
            }
        }
    }

    override fun onBroadcast(event: String, payload: JSONObject) {
        when (event) {
            "signal" -> {
                if (payload.optString("to") != myId) return
                val from = payload.optString("from")
                val tp = payload.optString("type")
                if (tp == "ice") Diag.log("recv ice from $from: " + candType(payload.optJSONObject("candidate")?.optString("candidate")))
                else Diag.log("recv $tp from $from")
                when (tp) {
                    "offer" -> handleOffer(from, payload.getString("sdp"))
                    "answer" -> handleAnswer(from, payload.getString("sdp"))
                    "ice" -> handleIce(from, payload.getJSONObject("candidate"))
                    "restart" -> if (myId < from && peers.containsKey(from)) createPeer(from, makeOffer = true)
                }
            }
            "state" -> {
                val p = peers[payload.optString("from")] ?: return
                p.mic.value = payload.optBoolean("mic", true)
                p.cam.value = payload.optBoolean("cam", true)
                p.sharing.value = payload.optBoolean("sharing", false)
            }
            "end" -> ended.value = true
        }
    }

    override fun onNewMessage(msg: ChatMsg) {
        if (messages.none { it.id == msg.id }) {
            messages.add(msg)
            if (!chatOpen && msg.senderId != myId) unread.value = unread.value + 1
        }
    }

    override fun onClassChanged(record: JSONObject) {
        if (record.optString("status") == "ended") ended.value = true
    }

    private fun signal(to: String, type: String, extra: JSONObject.() -> Unit) {
        val p = JSONObject().put("from", myId).put("to", to).put("type", type)
        p.extra()
        if (type != "ice") Diag.log("send $type to $to")
        channel?.broadcast("signal", p)
    }

    /** Our SDP went out: from now on ICE candidates can be sent directly. */
    private fun markIceReady(uid: String) {
        iceReady += uid
        outIce.remove(uid)?.forEach { c -> signal(uid, "ice") { put("candidate", c) } }
    }

    /** If a peer is not connected within 20 s, try again (offerer re-offers, answerer asks for a restart). */
    private fun watch(uid: String) {
        watchdogs.remove(uid)?.cancel()
        watchdogs[uid] = scope.launch {
            delay(20_000)
            if (peers[uid]?.conn?.value != "connected") onPeerFailed(uid)
        }
    }

    private fun onPeerFailed(uid: String) {
        val p = peers[uid] ?: return
        val n = (retries[uid] ?: 0) + 1
        retries[uid] = n
        Diag.log("peer failed $uid retry=$n")
        if (n > 5) { p.conn.value = "failed"; return }
        p.conn.value = "connecting"
        Log.i("Call", "retry $n with $uid")
        if (myId < uid) createPeer(uid, makeOffer = true)
        else { signal(uid, "restart") {}; watch(uid) }
    }

    private fun createPeer(uid: String, makeOffer: Boolean): PeerConnection? {
        closePc(uid)
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        val observer = PcObserver(uid)
        val pc = factory.createPeerConnection(config, observer) ?: return null
        observer.pc = pc
        pcs[uid] = pc
        peers[uid]?.conn?.value = "connecting"
        audioTrack?.let { pc.addTrack(it, listOf("s0")) }
        val vt = if (sharing.value) screenTrack else localVideo.value
        vt?.let { t -> pc.addTrack(t, listOf("s0"))?.let { videoSenders[uid] = it } }
        if (makeOffer) {
            scope.launch {
                try {
                    val offer = pc.awaitCreate(true)
                    pc.awaitSetLocal(offer)
                    if (pcs[uid] !== pc) return@launch
                    signal(uid, "offer") { put("sdp", offer.description) }
                    markIceReady(uid)
                } catch (e: Exception) { Log.w("Call", "offer failed", e); Diag.log("offer error: ${e.message}") }
            }
        }
        watch(uid)
        return pc
    }

    private fun handleOffer(from: String, sdp: String) {
        if (!peers.containsKey(from)) {
            peers[from] = PeerState(from, names[from] ?: "?", "")
            retries[from] = 0
        }
        val queued = pendingIce.remove(from) // candidates that raced ahead of the offer
        val pc = createPeer(from, makeOffer = false) ?: return
        if (queued != null) pendingIce[from] = queued
        scope.launch {
            try {
                pc.awaitSetRemote(SessionDescription(SessionDescription.Type.OFFER, sdp))
                flushIce(from)
                val answer = pc.awaitCreate(false)
                pc.awaitSetLocal(answer)
                if (pcs[from] !== pc) return@launch
                signal(from, "answer") { put("sdp", answer.description) }
                markIceReady(from)
            } catch (e: Exception) { Log.w("Call", "answer failed", e); Diag.log("answer error: ${e.message}") }
        }
    }

    private fun handleAnswer(from: String, sdp: String) {
        val pc = pcs[from] ?: return
        if (pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) { Diag.log("answer ignored, state=${pc.signalingState()}"); return }
        scope.launch {
            try {
                pc.awaitSetRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                flushIce(from)
            } catch (e: Exception) { Log.w("Call", "setRemote answer failed", e); Diag.log("setRemote answer error: ${e.message}") }
        }
    }

    private fun handleIce(from: String, c: JSONObject) {
        val cand = IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate"))
        val pc = pcs[from]
        if (pc?.remoteDescription != null) pc.addIceCandidate(cand)
        else pendingIce.getOrPut(from) { mutableListOf() }.add(cand)
    }

    private fun flushIce(uid: String) {
        val pc = pcs[uid] ?: return
        pendingIce.remove(uid)?.forEach { pc.addIceCandidate(it) }
    }

    private fun closePc(uid: String) {
        videoSenders.remove(uid)
        iceReady.remove(uid)
        outIce.remove(uid)
        peers[uid]?.video?.value = null
        pcs.remove(uid)?.let { pc ->
            scope.launch {
                delay(400) // let the UI detach renderers first
                try { pc.dispose() } catch (_: Exception) {}
            }
        }
    }

    private fun closePeer(uid: String) {
        watchdogs.remove(uid)?.cancel()
        pendingIce.remove(uid)
        closePc(uid)
        peers.remove(uid)
    }

    private inner class PcObserver(val uid: String) : PeerConnection.Observer {
        var pc: PeerConnection? = null
        private fun current() = pc != null && pcs[uid] === pc

        override fun onIceCandidate(c: IceCandidate) {
            val json = JSONObject().put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex).put("candidate", c.sdp)
            scope.launch {
                if (!current()) return@launch
                Diag.log("local ice for $uid: " + candType(c.sdp))
                if (uid in iceReady) signal(uid, "ice") { put("candidate", json) }
                else outIce.getOrPut(uid) { mutableListOf() }.add(json)
            }
        }
        override fun onTrack(transceiver: RtpTransceiver) {
            val t = transceiver.receiver.track()
            if (t is VideoTrack) scope.launch { if (current()) peers[uid]?.video?.value = t }
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            scope.launch {
                if (!current()) return@launch
                val p = peers[uid] ?: return@launch
                Log.i("Call", "peer $uid -> $newState")
                Diag.log("pc $uid -> $newState")
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        p.connected.value = true; p.conn.value = "connected"
                        retries[uid] = 0
                        watchdogs.remove(uid)?.cancel()
                    }
                    PeerConnection.PeerConnectionState.FAILED -> {
                        p.connected.value = false
                        onPeerFailed(uid)
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        p.connected.value = false; p.conn.value = "connecting"
                        val mine = pc
                        delay(6000)
                        if (pcs[uid] === mine && mine?.connectionState() != PeerConnection.PeerConnectionState.CONNECTED) onPeerFailed(uid)
                    }
                    else -> {}
                }
            }
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) { Diag.log("ice $uid -> $state") }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) { Diag.log("gathering $uid -> $state") }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(dc: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    // ---------------- teardown ----------------
    private fun candType(sdp: String?): String {
        if (sdp == null) return "?"
        val typ = Regex("typ (\\w+)").find(sdp)?.groupValues?.get(1) ?: "?"
        val proto = if (sdp.contains(" udp ", true)) "udp" else if (sdp.contains(" tcp ", true)) "tcp" else "?"
        return "$typ/$proto"
    }

    fun leave() {
        Diag.log("leave")
        kotlinx.coroutines.runBlocking { try { kotlinx.coroutines.withTimeout(1500) { Diag.flush() } } catch (_: Exception) {} }
        if (current === this) current = null
        channel?.close(); channel = null
        watchdogs.values.forEach { it.cancel() }; watchdogs.clear()
        pcs.keys.toList().forEach { uid -> videoSenders.remove(uid); pcs.remove(uid)?.let { try { it.dispose() } catch (_: Exception) {} } }
        peers.clear()
        stopShare()
        try { camCapturer?.stopCapture() } catch (_: Exception) {}
        camCapturer?.dispose(); camCapturer = null
        localVideo.value?.dispose(); localVideo.value = null
        camSource?.dispose(); camSource = null
        camHelper?.dispose(); camHelper = null
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
        bgProcessor.release()
        if (::factory.isInitialized) factory.dispose()
        egl.release()
        audioManager.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice()
        scope.cancel()
    }
}

// ---------- coroutine helpers for SDP ----------
private suspend fun PeerConnection.awaitCreate(offer: Boolean): SessionDescription =
    suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { if (cont.isActive) cont.resume(sdp) }
            override fun onCreateFailure(error: String?) { if (cont.isActive) cont.resumeWithException(RuntimeException(error)) }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }
        if (offer) createOffer(obs, MediaConstraints()) else createAnswer(obs, MediaConstraints())
    }

private suspend fun PeerConnection.awaitSetLocal(sdp: SessionDescription): Unit =
    suspendCancellableCoroutine { cont ->
        setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(error: String?) { if (cont.isActive) cont.resumeWithException(RuntimeException(error)) }
        }, sdp)
    }

private suspend fun PeerConnection.awaitSetRemote(sdp: SessionDescription): Unit =
    suspendCancellableCoroutine { cont ->
        setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(error: String?) { if (cont.isActive) cont.resumeWithException(RuntimeException(error)) }
        }, sdp)
    }
