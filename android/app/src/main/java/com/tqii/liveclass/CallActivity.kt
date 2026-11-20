package com.tqii.liveclass

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class CallActivity : ComponentActivity() {
    private var manager: CallManager? = null
    private var showUi by mutableStateOf(true)

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.CAMERA] == true && res[Manifest.permission.RECORD_AUDIO] == true) begin()
        else { Toast.makeText(this, L.t("permsNeeded"), Toast.LENGTH_LONG).show(); finish() }
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) manager?.startShare(data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val existing = CallManager.current
        if (existing != null) {
            // returning to an ongoing call (e.g. from the notification)
            manager = existing
            showContent()
            return
        }
        val classId = intent.getStringExtra("classId")
        if (classId == null) { finish(); return }

        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) begin() else permLauncher.launch(missing.toTypedArray())
    }

    private fun begin() {
        val classId = intent.getStringExtra("classId") ?: return finish()
        val title = intent.getStringExtra("title") ?: ""
        val teacher = intent.getBooleanExtra("teacher", false)
        CallService.start(this, title)
        manager = CallManager(applicationContext, classId, teacher).also { it.start() }
        showContent()
    }

    private fun showContent() {
        val m = manager ?: return
        val title = intent.getStringExtra("title") ?: ""
        setContent {
            AppTheme {
                if (showUi) CallScreen(
                    m, title,
                    onShare = {
                        if (m.sharing.value) m.stopShare()
                        else {
                            val mpm = getSystemService(MediaProjectionManager::class.java)
                            projectionLauncher.launch(mpm.createScreenCaptureIntent())
                        }
                    },
                    onLeave = { leave() }
                ) else Box(Modifier.fillMaxSize().background(Color(0xFF101614)))
            }
        }
    }

    private var leaving = false
    private fun leave() {
        if (leaving) return
        leaving = true
        showUi = false // detach video renderers first
        window.decorView.postDelayed({
            manager?.leave()
            manager = null
            CallService.stop(this)
            finish()
        }, 250)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !leaving) {
            manager?.leave()
            CallService.stop(this)
        }
    }
}

// ---------------- video tile ----------------
@Composable
fun VideoView(track: VideoTrack?, egl: EglBase, mirror: Boolean, fit: Boolean, modifier: Modifier = Modifier, overlay: Boolean = false) {
    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(egl.eglBaseContext, null)
                setEnableHardwareScaler(true)
                if (overlay) setZOrderMediaOverlay(true)
            }
        },
        update = { r ->
            r.setMirror(mirror)
            r.setScalingType(if (fit) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            val old = r.tag as? VideoTrack
            if (old !== track) {
                try { old?.removeSink(r) } catch (_: Exception) {}
                try { track?.addSink(r) } catch (_: Exception) {}
                r.tag = track
                if (track == null) r.clearImage()
            }
        },
        onRelease = { r ->
            try { (r.tag as? VideoTrack)?.removeSink(r) } catch (_: Exception) {}
            r.tag = null
            r.release()
        },
        modifier = modifier
    )
}

data class Tile(
    val key: String, val name: String, val track: VideoTrack?, val camOn: Boolean, val micOn: Boolean,
    val sharing: Boolean, val local: Boolean, val teacher: Boolean, val conn: String
)

@Composable
fun Avatar(name: String, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Brand), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), color = Color.White, fontSize = (size * 0.42f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun TileView(t: Tile, m: CallManager, modifier: Modifier, overlay: Boolean = false, compact: Boolean = false) {
    Box(modifier.clip(RoundedCornerShape(if (compact) 12.dp else 16.dp)).background(Color(0xFF1C2421))) {
        val connected = t.local || t.conn == "connected"
        val showVideo = t.track != null && (t.camOn || t.sharing) && connected
        if (showVideo) {
            VideoView(t.track, m.egl, mirror = t.local && !t.sharing && m.frontCamera.value, fit = t.sharing,
                modifier = Modifier.fillMaxSize(), overlay = overlay)
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Avatar(t.name, if (compact) 40 else 76)
                if (!connected && !compact) {
                    Spacer(Modifier.height(12.dp))
                    if (t.conn == "failed") {
                        Text(L.t("connFailed"), color = Color(0xFFFCA5A5), fontSize = 13.sp)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = Color(0xFF7DD3C7), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(L.t("connecting"), color = Color(0xCCFFFFFF), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(if (compact) 5.dp else 8.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000))
                .padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!t.micOn) { Icon(Icons.Filled.MicOff, null, tint = Color(0xFFFCA5A5), modifier = Modifier.size(13.dp)); Spacer(Modifier.width(3.dp)) }
            if (t.teacher) { Icon(Icons.Filled.School, null, tint = Color(0xFF7DD3C7), modifier = Modifier.size(13.dp)); Spacer(Modifier.width(3.dp)) }
            Text(if (t.local) L.t("you") else t.name, color = Color.White, fontSize = if (compact) 10.sp else 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ElapsedTimer(): String {
    var secs by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); secs++ } }
    val h = secs / 3600; val mm = (secs % 3600) / 60; val ss = secs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, mm, ss) else "%02d:%02d".format(mm, ss)
}

// ---------------- call screen ----------------
@Composable
fun CallScreen(m: CallManager, title: String, onShare: () -> Unit, onLeave: () -> Unit) {
    var chatOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }

    BackHandler {
        when {
            chatOpen -> { chatOpen = false; m.chatOpen = false }
            moreOpen -> moreOpen = false
            else -> confirmEnd = true
        }
    }

    if (m.ended.value) {
        LaunchedEffect(Unit) { delay(2500); onLeave() }
        Box(Modifier.fillMaxSize().background(Color(0xFF101614)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF2DD4BF), modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text(L.t("classEnded"), color = Color.White, fontSize = 20.sp)
            }
        }
        return
    }

    val local = Tile("me", m.myName, m.localVideo.value, m.camOn.value, m.micOn.value, m.sharing.value, true, m.isTeacher, "connected")
    val remotes = m.peers.values.sortedByDescending { it.role.value == "teacher" }.map {
        Tile(it.uid, it.name.value, it.video.value, it.cam.value, it.mic.value, it.sharing.value, false, it.role.value == "teacher", it.conn.value)
    }
    val sharer = remotes.firstOrNull { it.sharing }
    val elapsed = ElapsedTimer()

    Column(Modifier.fillMaxSize().background(Color(0xFF101614)).systemBarsPadding()) {
        // header
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (m.connected.value) LiveRed else Color.Gray))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            !m.connected.value -> L.t("connecting")
                            remotes.isEmpty() -> "$elapsed · ${L.t("waitingOthers")}"
                            else -> "$elapsed · ${remotes.size + 1} ${L.t("joined")}"
                        },
                        color = Color(0x99FFFFFF), fontSize = 12.sp
                    )
                }
            }
            LangSwitch(dark = true)
        }

        // stage
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
            when {
                sharer != null -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TileView(sharer, m, Modifier.weight(1f).fillMaxWidth())
                    Row(Modifier.height(104.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf(local) + remotes.filter { it !== sharer }).forEach { TileView(it, m, Modifier.weight(1f).fillMaxHeight(), compact = true) }
                    }
                }
                remotes.isEmpty() -> TileView(local, m, Modifier.fillMaxSize())
                remotes.size == 1 -> Box(Modifier.fillMaxSize()) {
                    // one-to-one: other person full screen, yourself in a floating window
                    TileView(remotes[0], m, Modifier.fillMaxSize())
                    TileView(local, m, Modifier.align(Alignment.TopEnd).padding(10.dp).size(108.dp, 150.dp)
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp)), overlay = true, compact = true)
                }
                else -> {
                    val all = remotes + local
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        all.chunked(2).forEach { row ->
                            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { TileView(it, m, Modifier.weight(1f).fillMaxHeight()) }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            if (m.sharing.value) {
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 8.dp).clip(RoundedCornerShape(10.dp))
                        .background(Brand).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.ScreenShare, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(L.t("sharingScreen"), color = Color.White, fontSize = 12.sp)
                }
            }
        }

        // "more" panel: flip, speaker, backgrounds
        if (moreOpen) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1C2421)).padding(12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillAction(Icons.Filled.Cameraswitch, L.t("flip")) { m.flipCamera() }
                    PillAction(if (m.speakerOn.value) Icons.Filled.VolumeUp else Icons.Filled.Hearing,
                        L.t("speaker"), on = m.speakerOn.value) { m.toggleSpeaker() }
                }
                Spacer(Modifier.height(12.dp))
                Text(L.t("background"), color = Color(0xCCFFFFFF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BG_OPTIONS.forEach { opt ->
                        val sel = m.bg.value.key == opt.key
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { m.setBackground(opt) }) {
                            Box(
                                Modifier.size(68.dp, 50.dp).clip(RoundedCornerShape(10.dp))
                                    .border(2.dp, if (sel) Color(0xFF2DD4BF) else Color(0x22FFFFFF), RoundedCornerShape(10.dp))
                                    .background(Color(0xFF2A3431)),
                                contentAlignment = Alignment.Center
                            ) {
                                when (opt.resId) {
                                    null -> Icon(Icons.Filled.Block, null, tint = Color.White)
                                    0 -> Icon(Icons.Filled.BlurOn, null, tint = Color.White)
                                    else -> Image(painterResource(opt.resId), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(L.t(opt.key), color = if (sel) Color(0xFF2DD4BF) else Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // control bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top
        ) {
            CtrlButton(if (m.micOn.value) Icons.Filled.Mic else Icons.Filled.MicOff, L.t("mic"), active = m.micOn.value) { m.toggleMic() }
            CtrlButton(if (m.camOn.value) Icons.Filled.Videocam else Icons.Filled.VideocamOff, L.t("camera"), active = m.camOn.value) { m.toggleCam() }
            CtrlButton(if (m.sharing.value) Icons.Filled.StopScreenShare else Icons.Filled.ScreenShare,
                if (m.sharing.value) L.t("stopShare") else L.t("share"), active = true, highlight = m.sharing.value) { onShare() }
            CtrlButton(Icons.AutoMirrored.Filled.Chat, L.t("chat"), active = true, badge = m.unread.value) {
                chatOpen = true; m.chatOpen = true; m.unread.value = 0
            }
            CtrlButton(Icons.Filled.MoreHoriz, L.t("more"), active = true, highlight = moreOpen) { moreOpen = !moreOpen }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(width = 60.dp, height = 48.dp).clip(RoundedCornerShape(24.dp)).background(LiveRed).clickable { confirmEnd = true },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.CallEnd, L.t("leave"), tint = Color.White) }
                Spacer(Modifier.height(4.dp))
                Text(if (m.isTeacher) L.t("endClass") else L.t("leave"), color = Color(0xCCFFFFFF), fontSize = 10.sp, maxLines = 1)
            }
        }
    }

    // chat overlay
    if (chatOpen) {
        Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable { chatOpen = false; m.chatOpen = false }) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.65f)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(Color(0xFF151C1A))
                    .clickable(enabled = false) {}.imePadding()
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(L.t("chat"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { chatOpen = false; m.chatOpen = false }) { Icon(Icons.Filled.Close, L.t("close"), tint = Color.White) }
                }
                ChatPanel(m.messages, m.names, dark = true, modifier = Modifier.weight(1f)) { m.sendChat(it) }
            }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(if (m.isTeacher) L.t("endClass") else L.t("leave")) },
            text = { if (m.isTeacher) Text(L.t("endForAll")) },
            confirmButton = {
                TextButton(onClick = { confirmEnd = false; if (m.isTeacher) m.endForAll() else onLeave() }) {
                    Text(if (m.isTeacher) L.t("endClass") else L.t("leave"), color = LiveRed)
                }
            },
            dismissButton = {
                Row {
                    if (m.isTeacher) TextButton(onClick = { confirmEnd = false; onLeave() }) { Text(L.t("leave")) }
                    TextButton(onClick = { confirmEnd = false }) { Text(L.t("no")) }
                }
            }
        )
    }
}

@Composable
fun PillAction(icon: ImageVector, label: String, on: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) Brand else Color(0xFF2A3431)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
fun CtrlButton(icon: ImageVector, label: String, active: Boolean, highlight: Boolean = false, badge: Int = 0, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
        Box {
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .background(if (highlight) Brand else if (active) Color(0xFF2A3431) else Color(0xFFF1F5F4))
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, label, tint = if (highlight || active) Color.White else Ink, modifier = Modifier.size(22.dp))
            }
            if (badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(LiveRed),
                    contentAlignment = Alignment.Center
                ) { Text(if (badge > 9) "9+" else "$badge", color = Color.White, fontSize = 10.sp) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color(0xCCFFFFFF), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
