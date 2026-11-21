package com.tqii.liveclass

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack



import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    var openChat by mutableStateOf<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.Black.toArgb()),
            navigationBarStyle = SystemBarStyle.light(Ivory.toArgb(), Ivory.toArgb())
        )
        super.onCreate(savedInstanceState)
        readChatIntent(intent)
        setContent { AppTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readChatIntent(intent)
    }

    private fun readChatIntent(i: Intent?) {
        val t = i?.getStringExtra("chatTeacher"); val s = i?.getStringExtra("chatStudent")
        if (t != null && s != null) openChat = t to s
    }

    /** Opens the call screen for an existing call id. */
    fun openCall(callId: String, teacherId: String, studentId: String, title: String, outgoing: Boolean) {
        startActivity(
            Intent(this, CallActivity::class.java)
                .putExtra("callId", callId).putExtra("teacherId", teacherId).putExtra("studentId", studentId)
                .putExtra("title", title).putExtra("outgoing", outgoing)
        )
    }
}

@Composable
fun UpdateDialog() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<Updater.Update?>(null) }
    var progress by remember { mutableIntStateOf(-1) }
    var msg by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { update = Updater.check(ctx) }
    val u = update ?: return
    AlertDialog(
        onDismissRequest = { if (progress < 0) update = null },
        containerColor = Color.White,
        icon = { Logo(56.dp, light = false) },
        title = { Text(L.t("updateTitle"), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(L.t("updateMsg"), color = Muted)
                Text(u.name, color = GoldDeep, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                if (progress >= 0) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(progress = { progress / 100f }, color = Gold, trackColor = GoldSoft, modifier = Modifier.fillMaxWidth())
                    Text("${L.t("downloading")} $progress%", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                }
                if (msg.isNotEmpty()) Text(msg, color = LiveRed, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            GoldButton(L.t("updateNow"), Modifier.width(150.dp), enabled = progress < 0) {
                msg = ""
                if (!Updater.ensureInstallPermission(ctx)) { msg = L.t("allowInstall"); return@GoldButton }
                progress = 0
                scope.launch {
                    try {
                        val f = Updater.download(ctx, u) { progress = it }
                        Updater.install(ctx, f)
                    } catch (e: Exception) { msg = L.t("updateFailed") }
                    progress = -1
                }
            }
        },
        dismissButton = { if (progress < 0) TextButton(onClick = { update = null }) { Text(L.t("later"), color = Muted) } }
    )
}

@Composable
fun Root() {
    UpdateDialog()
    val act = LocalContext.current as MainActivity
    var loggedIn by remember { mutableStateOf(Api.loggedIn) }
    if (!loggedIn) { LoginScreen(admin = BuildConfig.IS_ADMIN) { loggedIn = true }; return }
    if (BuildConfig.IS_ADMIN) AdminRoot(onLogout = { loggedIn = false })
    else HomeScreen(onLogout = { loggedIn = false }, openChatFor = act.openChat, onChatOpened = { act.openChat = null })
}

@Composable
fun LangSwitch(dark: Boolean = false) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(if (dark) Color(0x22FFFFFF) else Color.White)
            .border(1.dp, if (dark) Color(0x33F0C66B) else Line, RoundedCornerShape(10.dp))
    ) {
        listOf("bn" to "বাংলা", "en" to "EN").forEach { (k, label) ->
            val on = L.lang == k
            Text(
                label,
                Modifier.clickable { L.set(k) }.background(if (on) Gold else Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                color = if (on) Night else if (dark) Ivory else Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ---------------- Login ----------------
@Composable
fun LoginScreen(admin: Boolean, onDone: () -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy || user.isBlank() || pass.isBlank()) return
        busy = true; err = ""
        scope.launch {
            try {
                Api.login(user, pass)
                val me = Api.loadMe()
                when {
                    me == null -> { Api.logout(); err = L.t("wrongLogin") }
                    !me.active -> { Api.logout(); err = L.t("disabled") }
                    admin && !me.isAdmin -> { Api.logout(); err = L.t("notAdmin") }
                    !admin && !me.isTeacher && !me.isStudent && !me.canJoinAny -> { Api.logout(); err = L.t("notLinked") }
                    else -> { Push.register(); onDone() }
                }
            } catch (e: ApiException) {
                err = L.t("wrongLogin")
            } catch (e: Exception) {
                err = L.t("netError")
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(Ivory)) {
        Box(
            Modifier.fillMaxWidth().weight(0.9f).clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
                .background(NightGradient).statusBarsPadding()
        ) {
            Row(Modifier.align(Alignment.TopEnd).padding(14.dp)) { LangSwitch(dark = true) }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Logo(150.dp)
                Spacer(Modifier.height(10.dp))
                Text(if (admin) "TQII Admin" else L.t("app"), color = Ivory, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(if (admin) L.t("adminTagline") else L.t("tagline"), color = GoldLight, fontSize = 13.sp)
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(1.1f).imePadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 22.dp)
        ) {
            Text(L.t("welcomeBack"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(L.t("loginHint"), fontSize = 13.sp, color = Muted)
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                user, { user = it.trim() }, label = { Text(if (admin) L.t("loginIdAdmin") else L.t("loginId")) }, singleLine = true,
                leadingIcon = { Icon(if (admin) Icons.Filled.Person else Icons.Filled.Phone, null, tint = GoldDeep) },
                placeholder = { Text("01XXXXXXXXX") },
                keyboardOptions = KeyboardOptions(keyboardType = if (admin) KeyboardType.Email else KeyboardType.Phone, imeAction = ImeAction.Next),
                shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                pass, { pass = it }, label = { Text(L.t("password")) }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Lock, null, tint = GoldDeep) },
                trailingIcon = {
                    IconButton(onClick = { showPass = !showPass }) {
                        Icon(if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null, tint = Muted)
                    }
                },
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            if (err.isNotEmpty()) Text(err, color = LiveRed, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(20.dp))
            GoldButton(L.t("login"), Modifier.fillMaxWidth(), loading = busy) { submit() }
        }
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Gold, unfocusedBorderColor = Line, focusedLabelColor = GoldDeep,
    cursorColor = GoldDeep, focusedContainerColor = Color.White, unfocusedContainerColor = Color.White
)

// ---------------- helpers ----------------
fun fmtTime(ms: Long): String {
    val loc = if (L.lang == "bn") Locale("bn", "BD") else Locale.ENGLISH
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.US).let { it.format(Date(ms)) == it.format(Date()) }
    val time = SimpleDateFormat("h:mm a", loc).format(Date(ms))
    return if (sameDay) "${L.t("today")}, $time" else SimpleDateFormat("d MMM, h:mm a", loc).format(Date(ms))
}

private val DAY_KEYS = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
private val DAY_BN = mapOf("sun" to "রবি", "mon" to "সোম", "tue" to "মঙ্গল", "wed" to "বুধ", "thu" to "বৃহঃ", "fri" to "শুক্র", "sat" to "শনি")
private val DAY_EN = mapOf("sun" to "Sun", "mon" to "Mon", "tue" to "Tue", "wed" to "Wed", "thu" to "Thu", "fri" to "Fri", "sat" to "Sat")

fun daysLabel(days: List<String>): String =
    DAY_KEYS.filter { it in days }.joinToString(", ") { (if (L.lang == "bn") DAY_BN else DAY_EN)[it] ?: it }

/** Today's weekday key in Bangladesh time. */
fun todayKey(): String {
    val c = Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Dhaka"))
    return DAY_KEYS[c.get(Calendar.DAY_OF_WEEK) - 1]
}

/** "22:00" → "10:00 PM" */
fun time12(t: String?): String {
    if (t == null) return ""
    return try {
        val d = SimpleDateFormat("HH:mm", Locale.US).parse(t)!!
        SimpleDateFormat("h:mm a", if (L.lang == "bn") Locale("bn", "BD") else Locale.ENGLISH).format(d)
    } catch (_: Exception) { t }
}

// ---------------- Home ----------------
@Composable
fun HomeScreen(onLogout: () -> Unit, openChatFor: Pair<String, String>?, onChatOpened: () -> Unit) {
    val act = LocalContext.current as MainActivity
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf(Api.me) }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var calls by remember { mutableStateOf<List<CallInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var chat by remember { mutableStateOf<Contact?>(null) }
    var calling by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        try {
            me = Api.loadMe()
            if (me == null || me?.active == false) { Api.logout(); onLogout(); return }
            contacts = Api.contacts()
            calls = Api.recentCalls(40)
            error = ""
        } catch (e: ApiException) {
            if (!Api.loggedIn) { onLogout(); return }
            error = L.t("netError")
        } catch (e: Exception) {
            error = L.t("netError")
        }
        loading = false
    }

    // permissions needed for ringing
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        Push.register()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // incoming calls while the app is open (also arrive by push)
    DisposableEffect(Api.userId) {
        val ch = RtChannel("live-inbox:${Api.userId}", null, listOf(PgListen("live_calls")), object : RtChannel.Listener {
            override fun onChange(table: String, type: String, record: JSONObject) {
                tick++
                if (type == "INSERT" && record.optString("status") == "ringing" && record.optString("caller_id") != Api.userId) {
                    val c = contacts.firstOrNull { it.teacherId == record.optString("teacher_id") && it.studentId == record.optString("student_id") }
                    val m = Api.me
                    if (m?.teacherId == record.optString("teacher_id") || m?.studentId == record.optString("student_id")) {
                        Push.showIncoming(ctx.applicationContext, IncomingCall(
                            record.optString("id"), record.optString("teacher_id"), record.optString("student_id"),
                            c?.teacherName ?: "", c?.teacherName ?: "", c?.studentName ?: ""))
                    }
                }
            }
        })
        ch.connect()
        onDispose { ch.close() }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(tick) { load() }
    LaunchedEffect(Unit) { while (true) { delay(20_000); load() } }

    // opened from a chat notification
    LaunchedEffect(openChatFor, contacts) {
        val o = openChatFor ?: return@LaunchedEffect
        contacts.firstOrNull { it.teacherId == o.first && it.studentId == o.second }?.let { chat = it; onChatOpened() }
    }

    chat?.let { c ->
        BackHandler { chat = null }
        ChatScreen(c) { chat = null }
        return
    }

    fun startCall(c: Contact) {
        if (calling != null) return
        calling = c.studentId
        scope.launch {
            try {
                val id = Api.startCall(c.teacherId, c.studentId)
                val other = if (me?.studentId == c.studentId) c.teacherName else c.studentName
                act.openCall(id, c.teacherId, c.studentId, other, outgoing = true)
            } catch (e: Exception) { error = L.t("netError") }
            calling = null
        }
    }

    val m = me
    val activeCalls = calls.filter { it.status == "active" || it.status == "ringing" }
    Column(Modifier.fillMaxSize().background(Ivory)) {
        BrandHeader(
            title = L.t("hello") + (m?.let { ", " + it.name.split(" ").take(2).joinToString(" ") } ?: ""),
            subtitle = when { m?.isTeacher == true -> L.t("teacher"); m?.isStudent == true -> L.t("student"); else -> L.t("admins") }
        ) {
            LangSwitch(dark = true)
            IconButton(onClick = { scope.launch { Api.logout(); onLogout() } }) { Icon(Icons.AutoMirrored.Filled.Logout, L.t("logout"), tint = Ivory) }
        }
        // tabs
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(if (m?.isStudent == true) L.t("myTeacher") else L.t("myStudents"), tab == 0) { tab = 0 }
            Chip(L.t("calls"), tab == 1) { tab = 1 }
        }
        if (!Push.canFullScreen(ctx)) {
            PermissionBanner(L.t("allowFullScreen")) {
                if (Build.VERSION.SDK_INT >= 34) ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (error.isNotEmpty()) item { Text(error, color = LiveRed) }
            if (loading) item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Gold) } }
            else if (tab == 0) {
                if (contacts.isEmpty()) item {
                    AppCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Logo(70.dp, light = false); Spacer(Modifier.height(8.dp))
                            Text(L.t("notLinked"), color = Muted, textAlign = TextAlign.Center)
                        }
                    }
                }
                // today's classes first
                val today = todayKey()
                val sorted = contacts.sortedWith(compareBy({ today !in it.classDays }, { it.classTime ?: "99" }))
                items(sorted, key = { it.teacherId + it.studentId }) { c ->
                    val active = activeCalls.firstOrNull { it.teacherId == c.teacherId && it.studentId == c.studentId }
                    ContactCard(
                        c, me = m, today = today in c.classDays, activeCall = active, busy = calling == c.studentId,
                        onCall = {
                            if (active != null) act.openCall(active.id, c.teacherId, c.studentId,
                                if (m?.studentId == c.studentId) c.teacherName else c.studentName, outgoing = false)
                            else startCall(c)
                        },
                        onChat = { chat = c }
                    )
                }
            } else {
                if (calls.isEmpty()) item { Text(L.t("none"), color = Muted, modifier = Modifier.padding(20.dp)) }
                items(calls, key = { it.id }) { CallRow(it, m) }
            }
        }
    }
}

@Composable
fun PermissionBanner(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(GoldSoft)
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.NotificationsActive, null, tint = GoldDeep); Spacer(Modifier.width(10.dp))
        Text(text, color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(L.t("settingsBtn"), color = GoldDeep, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun ContactCard(c: Contact, me: Me?, today: Boolean, activeCall: CallInfo?, busy: Boolean, onCall: () -> Unit, onChat: () -> Unit) {
    val iAmStudent = me?.studentId == c.studentId
    val title = if (iAmStudent) c.teacherName else c.studentName
    val live = activeCall != null
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (live) Night else Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (live || today) Gold else Line)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InitialAvatar(title, 48.dp, dark = !live)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = if (live) Ivory else Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (me?.canJoinAny == true && me.teacherId != c.teacherId && !iAmStudent)
                        Text("${L.t("teacherLabel")}: ${c.teacherName}", color = if (live) Color(0xAAFFFFFF) else Muted, fontSize = 12.sp, maxLines = 1)
                    else if (iAmStudent) Text(L.t("teacherLabel"), color = if (live) Color(0xAAFFFFFF) else Muted, fontSize = 12.sp)
                }
                when {
                    live -> StatusPill("live")
                    today -> Text(L.t("todayAt"), color = GoldDeep, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(GoldSoft).padding(horizontal = 9.dp, vertical = 4.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            val sub = if (live) Color(0xCCFFFFFF) else Muted
            if (c.classTime != null) InfoRow(Icons.Filled.Schedule, time12(c.classTime), sub)
            if (c.classDays.isNotEmpty()) InfoRow(Icons.Filled.CalendarMonth, daysLabel(c.classDays), sub)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                GoldButton(if (live) L.t("joinNow") else L.t("call"), Modifier.weight(1f).height(48.dp), loading = busy, onClick = onCall)
                Box(
                    Modifier.height(48.dp).clip(RoundedCornerShape(14.dp))
                        .border(1.dp, if (live) Color(0x55F0C66B) else Line, RoundedCornerShape(14.dp))
                        .clickable(onClick = onChat).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.Chat, null, tint = if (live) GoldLight else GoldDeep, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(L.t("chat"), color = if (live) Ivory else Ink, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun CallRow(c: CallInfo, me: Me?) {
    val mine = c.callerId == Api.userId
    val other = if (me?.studentId == c.studentId) c.teacherName else c.studentName
    val (icon, color, label) = when (c.status) {
        "missed", "declined" -> Triple(Icons.Filled.CallMissed, LiveRed, L.t(if (c.status == "missed") "missed" else "declined"))
        "active", "ringing" -> Triple(Icons.Filled.Videocam, LiveRed, L.t("live"))
        else -> Triple(if (mine) Icons.Filled.CallMade else Icons.Filled.CallReceived, Success,
            if (c.status == "cancelled") L.t("cancelled") else "${maxOf(c.teacherMinutes, c.studentMinutes)} ${L.t("minutesShort")}")
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        InitialAvatar(other, 42.dp, dark = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(other, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                Text("$label · ${fmtTime(c.createdAt)}", fontSize = 12.sp, color = Muted)
            }
        }
        if (c.reported) Icon(Icons.Filled.TaskAlt, L.t("reported"), tint = Success, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun StatTile(label: String, n: Int, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(16.dp)).padding(14.dp)
    ) {
        Text("$n", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 12.sp, color = Muted)
    }
}

@Composable
fun StatusPill(status: String) {
    val (bg, fg) = when (status) {
        "live", "active", "ringing" -> Color(0xFFFDE4E4) to LiveRed
        "scheduled" -> GoldSoft to GoldDeep
        else -> Color(0xFFEFECE6) to Muted
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (status == "live" || status == "active") { Box(Modifier.size(7.dp).clip(CircleShape).background(LiveRed)); Spacer(Modifier.width(5.dp)) }
        Text(L.t(if (status == "active") "live" else status), color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(icon, null, tint = Gold, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------- Chat (teacher ↔ student, with files) ----------------
data class PickedFile(val name: String, val mime: String, val bytes: ByteArray)

fun readPicked(ctx: Context, uri: Uri): PickedFile? {
    val cr = ctx.contentResolver
    var name = "file"; var size = -1L
    cr.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (ni >= 0) name = c.getString(ni) ?: name
            if (si >= 0) size = c.getLong(si)
        }
    }
    if (size > 25L * 1024 * 1024) return null
    val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (bytes.size > 25 * 1024 * 1024) return null
    return PickedFile(name, cr.getType(uri) ?: "application/octet-stream", bytes)
}

@Composable
fun ChatScreen(c: Contact, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val messages = remember { mutableStateListOf<ChatMsg>() }
    val scope = rememberCoroutineScope()
    var uploading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val me = Api.me
    val title = if (me?.studentId == c.studentId) c.teacherName else c.studentName
    val names = buildMap {
        c.teacherProfile?.let { put(it, c.teacherName) }
        c.studentProfile?.let { put(it, c.studentName) }
    }

    suspend fun reload() {
        try { Api.messages(c.teacherId, c.studentId).forEach { m -> if (messages.none { it.id == m.id }) messages.add(m) }; messages.sortBy { it.id } } catch (_: Exception) {}
    }

    DisposableEffect(c.teacherId, c.studentId) {
        val ch = RtChannel("live-chat:${c.teacherId}:${c.studentId}", null,
            listOf(PgListen("live_messages", "INSERT", "teacher_id=eq.${c.teacherId}")), object : RtChannel.Listener {
                override fun onChange(table: String, type: String, record: JSONObject) {
                    if (record.optString("student_id") != c.studentId) return
                    val m = try { Api.parseMsg(record) } catch (_: Exception) { return }
                    if (messages.none { it.id == m.id }) messages.add(m)
                }
            })
        ch.connect()
        onDispose { ch.close() }
    }
    LaunchedEffect(c.teacherId, c.studentId) { reload() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploading = true; err = ""
        scope.launch {
            try {
                val f = withContext(Dispatchers.IO) { readPicked(ctx, uri) }
                if (f == null) { err = L.t("fileTooBig") } else {
                    val path = Api.uploadFile(c.teacherId, c.studentId, f.name, f.mime, f.bytes)
                    Api.sendMessage(c.teacherId, c.studentId, null, path, f.name, f.mime, f.bytes.size.toLong())
                    reload()
                }
            } catch (e: Exception) { err = L.t("netError") }
            uploading = false
        }
    }

    Column(Modifier.fillMaxSize().background(Ivory)) {
        Box(Modifier.fillMaxWidth().background(NightGradient).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Ivory) }
                InitialAvatar(title, 38.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = Ivory, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(L.t("chat"), color = GoldLight, fontSize = 12.sp)
                }
                Logo(34.dp); Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(GoldGradient))
        }
        if (err.isNotEmpty()) Text(err, color = LiveRed, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        ChatPanel(
            messages, names, dark = false, modifier = Modifier.weight(1f).imePadding().navigationBarsPadding(),
            uploading = uploading, onAttach = { picker.launch("*/*") }
        ) { text ->
            scope.launch {
                try { Api.sendMessage(c.teacherId, c.studentId, text); reload() } catch (_: Exception) { err = L.t("netError") }
            }
        }
    }
}

/** Shared chat list + input, used in the chat screen and inside the call. */
@Composable
fun ChatPanel(
    messages: List<ChatMsg>, names: Map<String, String>, dark: Boolean, modifier: Modifier = Modifier,
    readOnly: Boolean = false, uploading: Boolean = false, onAttach: (() -> Unit)? = null, onSend: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    val myId = Api.userId
    Column(modifier) {
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(L.t("noMessages"), color = if (dark) Color(0x99FFFFFF) else Muted)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(messages, key = { it.id }) { m ->
                    val mine = m.senderId == myId
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                        if (!mine) Text(names[m.senderId] ?: L.t("admins"), fontSize = 12.sp, color = if (dark) GoldLight else GoldDeep, fontWeight = FontWeight.SemiBold)
                        val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (mine) 16.dp else 4.dp, bottomEnd = if (mine) 4.dp else 16.dp)
                        Column(
                            Modifier.widthIn(max = 290.dp).clip(shape)
                                .background(if (mine) Night else if (dark) Night3 else Color.White)
                                .then(if (!mine && !dark) Modifier.border(1.dp, Line, shape) else Modifier)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            if (m.filePath != null) FileBubble(m, light = mine || dark)
                            m.body?.let { Text(it, color = if (mine || dark) Ivory else Ink, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 2.dp)) }
                        }
                        Text(SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(m.createdAt)), fontSize = 10.sp, color = if (dark) Color(0x88FFFFFF) else Muted)
                    }
                }
            }
        }
        if (!readOnly) Row(
            Modifier.fillMaxWidth().background(if (dark) Night2 else Color.White).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onAttach != null) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(if (dark) Night3 else GoldSoft).clickable(enabled = !uploading, onClick = onAttach),
                    contentAlignment = Alignment.Center
                ) {
                    if (uploading) CircularProgressIndicator(Modifier.size(20.dp), color = Gold, strokeWidth = 2.dp)
                    else Icon(Icons.Filled.AttachFile, L.t("attach"), tint = if (dark) GoldLight else GoldDeep)
                }
                Spacer(Modifier.width(8.dp))
            }
            OutlinedTextField(
                text, { text = it }, placeholder = { Text(if (uploading) L.t("uploading") else L.t("typeMessage")) }, modifier = Modifier.weight(1f), maxLines = 4,
                colors = if (dark) OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Ivory, unfocusedTextColor = Ivory, focusedBorderColor = Gold, unfocusedBorderColor = Night3, cursorColor = Gold
                ) else fieldColors(),
                shape = RoundedCornerShape(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(GoldGradient)
                    .clickable { if (text.isNotBlank()) { onSend(text.trim()); text = "" } },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.AutoMirrored.Filled.Send, L.t("send"), tint = Night) }
        }
    }
}

/** A file inside a chat bubble: photo preview or a file card; tap to open. */
@Composable
fun FileBubble(m: ChatMsg, light: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isImage = m.fileMime?.startsWith("image/") == true
    val url by produceState<String?>(null, m.filePath) { value = try { Api.fileUrl(m.filePath!!) } catch (_: Exception) { null } }
    fun open() {
        scope.launch {
            val u = url ?: try { Api.fileUrl(m.filePath!!) } catch (_: Exception) { null } ?: return@launch
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }
    if (isImage) {
        Box(Modifier.padding(bottom = 4.dp).size(220.dp, 180.dp).clip(RoundedCornerShape(12.dp)).background(Night3).clickable { open() }, contentAlignment = Alignment.Center) {
            if (url != null) AsyncImage(model = url, contentDescription = m.fileName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else CircularProgressIndicator(Modifier.size(22.dp), color = Gold, strokeWidth = 2.dp)
        }
    } else {
        Row(
            Modifier.padding(bottom = 4.dp).widthIn(min = 200.dp).clip(RoundedCornerShape(12.dp))
                .background(if (light) Color(0x22FFFFFF) else GoldSoft).clickable { open() }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = when {
                m.fileMime?.contains("pdf") == true -> Icons.Filled.PictureAsPdf
                m.fileMime?.startsWith("audio/") == true -> Icons.Filled.Audiotrack
                m.fileMime?.startsWith("video/") == true -> Icons.Filled.Movie
                else -> Icons.Filled.Description
            }
            Icon(icon, null, tint = if (light) GoldLight else GoldDeep, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(m.fileName ?: "file", color = if (light) Ivory else Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(sizeLabel(m.fileSize), color = if (light) Color(0x99FFFFFF) else Muted, fontSize = 11.sp)
            }
            Icon(Icons.Filled.Download, L.t("open"), tint = if (light) GoldLight else GoldDeep)
        }
    }
}

fun sizeLabel(b: Long): String = when {
    b >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}
