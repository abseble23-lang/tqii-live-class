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
fun VideoView(track: VideoTrack?, egl: EglBase, mirror: Boolean, fit: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(egl.eglBaseContext, null)
                setEnableHardwareScaler(true)
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
    val sharing: Boolean, val local: Boolean, val teacher: Boolean
)

@Composable
fun TileView(t: Tile, m: CallManager, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C2421))) {
        val showVideo = t.track != null && (t.camOn || t.sharing)
        if (showVideo) {
            VideoView(t.track, m.egl, mirror = t.local && !t.sharing && m.frontCamera.value, fit = t.sharing, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(Brand), contentAlignment = Alignment.Center) {
                    Text(t.name.trim().take(1).uppercase(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!t.micOn) { Icon(Icons.Filled.MicOff, null, tint = Color(0xFFFCA5A5), modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
            if (t.teacher) { Icon(Icons.Filled.School, null, tint = Color(0xFF7DD3C7), modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
            Text(if (t.local) "${t.name} (${L.t("you")})" else t.name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------- call screen ----------------
@Composable
fun CallScreen(m: CallManager, title: String, onShare: () -> Unit, onLeave: () -> Unit) {
    var chatOpen by remember { mutableStateOf(false) }
    var bgOpen by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }

    BackHandler {
        when {
            chatOpen -> { chatOpen = false; m.chatOpen = false }
            bgOpen -> bgOpen = false
            else -> confirmEnd = true
        }
    }

    // class ended by teacher
    if (m.ended.value) {
        LaunchedEffect(Unit) { delay(2500); onLeave() }
        Box(Modifier.fillMaxSize().background(Color(0xFF101614)), contentAlignment = Alignment.Center) {
            Text(L.t("classEnded"), color = Color.White, fontSize = 20.sp)
        }
        return
    }

    val local = Tile("me", m.myName, m.localVideo.value, m.camOn.value, m.micOn.value, m.sharing.value, true, m.isTeacher)
    val remotes = m.peers.values.sortedByDescending { it.role.value == "teacher" }.map {
        Tile(it.uid, it.name.value, it.video.value, it.cam.value, it.mic.value, it.sharing.value, false, it.role.value == "teacher")
    }
    val sharer = remotes.firstOrNull { it.sharing }

    Column(Modifier.fillMaxSize().background(Color(0xFF101614)).systemBarsPadding()) {
        // header
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (m.connected.value) LiveRed else Color.Gray))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (!m.connected.value) L.t("connecting") else if (remotes.isEmpty()) L.t("waitingOthers") else "${remotes.size + 1} " + (if (L.lang == "bn") "জন যুক্ত" else "people"),
                    color = Color(0x99FFFFFF), fontSize = 12.sp
                )
            }
            LangSwitch(dark = true)
        }

        // videos
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
            if (sharer != null) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TileView(sharer, m, Modifier.weight(1f).fillMaxWidth())
                    Row(Modifier.height(110.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf(local) + remotes.filter { it !== sharer }).forEach { TileView(it, m, Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            } else {
                val all = remotes + local
                when (all.size) {
                    1 -> TileView(all[0], m, Modifier.fillMaxSize())
                    2 -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        all.forEach { TileView(it, m, Modifier.weight(1f).fillMaxWidth()) }
                    }
                    else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Text(
                    L.t("sharingScreen"), color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).clip(RoundedCornerShape(8.dp))
                        .background(Brand).padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        // background picker
        if (bgOpen) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BG_OPTIONS.forEach { opt ->
                    val sel = m.bg.value.key == opt.key
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { m.setBackground(opt) }) {
                        Box(
                            Modifier.size(64.dp, 48.dp).clip(RoundedCornerShape(8.dp))
                                .border(2.dp, if (sel) Color(0xFF2DD4BF) else Color.Transparent, RoundedCornerShape(8.dp))
                                .background(Color(0xFF2A3431)),
                            contentAlignment = Alignment.Center
                        ) {
                            when (opt.resId) {
                                null -> Icon(Icons.Filled.Block, null, tint = Color.White)
                                0 -> Icon(Icons.Filled.BlurOn, null, tint = Color.White)
                                else -> Image(painterResource(opt.resId), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                        }
                        Text(L.t(opt.key), color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }

        // controls
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
        ) {
            CtrlButton(if (m.micOn.value) Icons.Filled.Mic else Icons.Filled.MicOff, L.t("mic"), active = m.micOn.value) { m.toggleMic() }
            CtrlButton(if (m.camOn.value) Icons.Filled.Videocam else Icons.Filled.VideocamOff, L.t("camera"), active = m.camOn.value) { m.toggleCam() }
            CtrlButton(Icons.Filled.Cameraswitch, L.t("flip"), active = true) { m.flipCamera() }
            CtrlButton(if (m.sharing.value) Icons.Filled.StopScreenShare else Icons.Filled.ScreenShare,
                if (m.sharing.value) L.t("stopShare") else L.t("share"), active = !m.sharing.value, highlight = m.sharing.value) { onShare() }
            CtrlButton(Icons.Filled.Wallpaper, L.t("background"), active = true, highlight = bgOpen) { bgOpen = !bgOpen }
            BadgedBox(badge = { if (m.unread.value > 0) Badge { Text("${m.unread.value}") } }) {
                CtrlButton(Icons.AutoMirrored.Filled.Chat, L.t("chat"), active = true) {
                    chatOpen = true; m.chatOpen = true; m.unread.value = 0
                }
            }
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(LiveRed).clickable { confirmEnd = true },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.CallEnd, L.t("leave"), tint = Color.White) }
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
fun CtrlButton(icon: ImageVector, label: String, active: Boolean, highlight: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape)
            .background(if (highlight) Brand else if (active) Color(0xFF2A3431) else Color(0xFFF1F5F4))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, tint = if (highlight || active) Color.White else Ink, modifier = Modifier.size(22.dp))
    }
}
