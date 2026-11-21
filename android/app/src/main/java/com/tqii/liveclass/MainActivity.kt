package com.tqii.liveclass

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.Black.toArgb()),
            navigationBarStyle = SystemBarStyle.light(Ivory.toArgb(), Ivory.toArgb())
        )
        super.onCreate(savedInstanceState)
        setContent { AppTheme { Root() } }
    }

    fun openCall(c: ClassItem, isTeacher: Boolean) {
        startActivity(
            Intent(this, CallActivity::class.java)
                .putExtra("classId", c.id).putExtra("title", c.title).putExtra("teacher", isTeacher)
        )
    }
}

sealed class Screen {
    object Login : Screen()
    object Home : Screen()
    data class Chat(val c: ClassItem) : Screen()
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
    var screen by remember { mutableStateOf<Screen>(if (Api.loggedIn) Screen.Home else Screen.Login) }
    when (val s = screen) {
        is Screen.Login -> LoginScreen(admin = BuildConfig.IS_ADMIN) { screen = Screen.Home }
        is Screen.Home ->
            if (BuildConfig.IS_ADMIN) AdminRoot(onLogout = { screen = Screen.Login })
            else HomeScreen(onLogout = { screen = Screen.Login }, onChat = { screen = Screen.Chat(it) })
        is Screen.Chat -> {
            BackHandler { screen = Screen.Home }
            ChatScreen(s.c) { screen = Screen.Home }
        }
    }
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
                    admin && me.role != "admin" -> { Api.logout(); err = L.t("notAdmin") }
                    !admin && me.role == "admin" -> { Api.logout(); err = L.t("adminUseWeb") }
                    else -> onDone()
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
        // dark hero with the logo
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
            Modifier.fillMaxWidth().weight(1.1f).imePadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Text(L.t("welcomeBack"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(L.t("loginHint"), fontSize = 13.sp, color = Muted)
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                user, { user = it.trim() }, label = { Text(L.t("username")) }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Person, null, tint = GoldDeep) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
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

// ---------------- Home ----------------
fun fmtTime(ms: Long): String {
    val loc = if (L.lang == "bn") Locale("bn", "BD") else Locale.ENGLISH
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.US).let { it.format(Date(ms)) == it.format(Date()) }
    val time = SimpleDateFormat("h:mm a", loc).format(Date(ms))
    return if (sameDay) "${L.t("today")}, $time" else SimpleDateFormat("d MMM, h:mm a", loc).format(Date(ms))
}

@Composable
fun HomeScreen(onLogout: () -> Unit, onChat: (ClassItem) -> Unit) {
    val ctx = LocalContext.current as MainActivity
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf(Api.me) }
    var classes by remember { mutableStateOf<List<ClassItem>>(emptyList()) }
    var profiles by remember { mutableStateOf<Map<String, Profile>>(emptyMap()) }
    var roster by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }

    suspend fun load() {
        try {
            if (me == null) me = Api.loadMe()
            if (me == null || me?.active == false) { Api.logout(); onLogout(); return }
            profiles = Api.profiles()
            roster = Api.roster()
            val now = System.currentTimeMillis()
            classes = Api.classes().filter { it.status != "ended" || now - it.scheduledAt < 2 * 24 * 3600_000L }
                .sortedWith(compareBy({ if (it.status == "live") 0 else if (it.status == "scheduled") 1 else 2 }, { it.scheduledAt }))
            error = ""
        } catch (e: ApiException) {
            if (!Api.loggedIn) { onLogout(); return }
            error = L.t("netError")
        } catch (e: Exception) {
            error = L.t("netError")
        }
        loading = false
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(tick) { load() }
    LaunchedEffect(Unit) { while (true) { delay(15_000); load() } }

    val isTeacher = me?.role == "teacher"
    val live = classes.filter { it.status == "live" }
    val upcoming = classes.filter { it.status == "scheduled" }
    val past = classes.filter { it.status == "ended" }

    Column(Modifier.fillMaxSize().background(Ivory)) {
        BrandHeader(
            title = L.t("hello") + (me?.let { ", " + it.fullName.split(" ").first() } ?: ""),
            subtitle = if (isTeacher) L.t("teacher") else L.t("student")
        ) {
            LangSwitch(dark = true)
            IconButton(onClick = { tick++ }) { Icon(Icons.Filled.Refresh, L.t("refresh"), tint = Ivory) }
            IconButton(onClick = { scope.launch { Api.logout(); onLogout() } }) { Icon(Icons.AutoMirrored.Filled.Logout, L.t("logout"), tint = Ivory) }
        }
        LazyColumn(
            Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(L.t("live"), live.size, LiveRed, Modifier.weight(1f))
                    StatTile(L.t("upcoming"), upcoming.size, GoldDeep, Modifier.weight(1f))
                    StatTile(L.t("ended"), past.size, Muted, Modifier.weight(1f))
                }
            }
            if (error.isNotEmpty()) item { Text(error, color = LiveRed) }
            if (loading) item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Gold) } }
            else if (classes.isEmpty()) item {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Logo(70.dp, light = false)
                        Spacer(Modifier.height(8.dp))
                        Text(L.t("noClasses"), color = Muted, textAlign = TextAlign.Center)
                    }
                }
            }
            if (live.isNotEmpty()) item { SectionTitle(L.t("liveNow")) }
            items(live, key = { "l" + it.id }) { c -> ClassCard(c, profiles, roster[c.id].orEmpty(), isTeacher, { ctx.openCall(c, isTeacher) }, { onChat(c) }) }
            if (upcoming.isNotEmpty()) item { SectionTitle(L.t("upcoming")) }
            items(upcoming, key = { "u" + it.id }) { c -> ClassCard(c, profiles, roster[c.id].orEmpty(), isTeacher, { ctx.openCall(c, isTeacher) }, { onChat(c) }) }
            if (past.isNotEmpty()) item { SectionTitle(L.t("ended")) }
            items(past, key = { "p" + it.id }) { c -> ClassCard(c, profiles, roster[c.id].orEmpty(), isTeacher, { ctx.openCall(c, isTeacher) }, { onChat(c) }) }
        }
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
        "live" -> Color(0xFFFDE4E4) to LiveRed
        "scheduled" -> GoldSoft to GoldDeep
        else -> Color(0xFFEFECE6) to Muted
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (status == "live") { Box(Modifier.size(7.dp).clip(CircleShape).background(LiveRed)); Spacer(Modifier.width(5.dp)) }
        Text(L.t(status), color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ClassCard(c: ClassItem, profiles: Map<String, Profile>, students: List<String>, isTeacher: Boolean, onCall: () -> Unit, onChat: () -> Unit) {
    val isLive = c.status == "live"
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (isLive) Night else Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isLive) Gold else Line)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(if (isLive) Night3 else GoldSoft), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.MenuBook, null, tint = if (isLive) GoldLight else GoldDeep)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = if (isLive) Ivory else Ink, maxLines = 1)
                    c.subject?.let { Text(it, color = if (isLive) Color(0xAAFFFFFF) else Muted, fontSize = 13.sp, maxLines = 1) }
                }
                StatusPill(c.status)
            }
            Spacer(Modifier.height(12.dp))
            val sub = if (isLive) Color(0xCCFFFFFF) else Muted
            InfoRow(Icons.Filled.Schedule, "${fmtTime(c.scheduledAt)} · ${c.durationMin} ${L.t("minutes")}", sub)
            if (!isTeacher) c.teacherId?.let { profiles[it] }?.let { InfoRow(Icons.Filled.School, it.fullName, sub) }
            if (isTeacher && students.isNotEmpty()) InfoRow(Icons.Filled.Groups, students.mapNotNull { profiles[it]?.fullName }.joinToString(", "), sub)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    isTeacher && c.status != "ended" ->
                        GoldButton(if (isLive) L.t("rejoin") else L.t("startClass"), Modifier.weight(1f).height(46.dp), onClick = onCall)
                    !isTeacher && isLive -> GoldButton(L.t("join"), Modifier.weight(1f).height(46.dp), onClick = onCall)
                    !isTeacher && c.status == "scheduled" ->
                        Text(L.t("waiting"), color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    else -> Spacer(Modifier.weight(1f))
                }
                Box(
                    Modifier.height(46.dp).clip(RoundedCornerShape(14.dp))
                        .border(1.dp, if (isLive) Color(0x55F0C66B) else Line, RoundedCornerShape(14.dp))
                        .clickable(onClick = onChat).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.Chat, null, tint = if (isLive) GoldLight else GoldDeep, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(L.t("chat"), color = if (isLive) Ivory else Ink, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(icon, null, tint = Gold, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 13.sp, maxLines = 1)
    }
}

// ---------------- Chat (outside the call) ----------------
@Composable
fun ChatScreen(c: ClassItem, onBack: () -> Unit) {
    val messages = remember { mutableStateListOf<ChatMsg>() }
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val scope = rememberCoroutineScope()

    DisposableEffect(c.id) {
        val ch = ClassChannel(c.id, null, object : ClassChannel.Listener {
            override fun onNewMessage(msg: ChatMsg) { if (messages.none { it.id == msg.id }) messages.add(msg) }
        })
        ch.connect()
        onDispose { ch.close() }
    }
    LaunchedEffect(c.id) {
        try {
            names = Api.profiles().mapValues { it.value.fullName }
            val list = Api.messages(c.id)
            list.forEach { m -> if (messages.none { it.id == m.id }) messages.add(m) }
            messages.sortBy { it.id }
        } catch (_: Exception) {}
    }

    Column(Modifier.fillMaxSize().background(Ivory)) {
        Box(Modifier.fillMaxWidth().background(NightGradient).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Ivory) }
                Column(Modifier.weight(1f)) {
                    Text(c.title, color = Ivory, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(L.t("chat"), color = GoldLight, fontSize = 12.sp)
                }
                Logo(36.dp); Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(GoldGradient))
        }
        ChatPanel(messages, names, dark = false, modifier = Modifier.weight(1f).imePadding().navigationBarsPadding()) { text ->
            scope.launch {
                try {
                    Api.sendMessage(c.id, text)
                    Api.messages(c.id).forEach { m -> if (messages.none { it.id == m.id }) messages.add(m) }
                } catch (_: Exception) {}
            }
        }
    }
}

/** Shared chat list + input, used in the chat screen and inside the call. */
@Composable
fun ChatPanel(messages: List<ChatMsg>, names: Map<String, String>, dark: Boolean, modifier: Modifier = Modifier, readOnly: Boolean = false, onSend: (String) -> Unit) {
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
                        if (!mine) Text(names[m.senderId] ?: "?", fontSize = 12.sp, color = if (dark) GoldLight else GoldDeep, fontWeight = FontWeight.SemiBold)
                        Box(
                            Modifier.widthIn(max = 290.dp)
                                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (mine) 16.dp else 4.dp, bottomEnd = if (mine) 4.dp else 16.dp))
                                .background(if (mine) Night else if (dark) Night3 else Color.White)
                                .then(if (!mine && !dark) Modifier.border(1.dp, Line, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)) else Modifier)
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Text(m.body, color = if (mine || dark) Ivory else Ink, fontSize = 15.sp)
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
            OutlinedTextField(
                text, { text = it }, placeholder = { Text(L.t("typeMessage")) }, modifier = Modifier.weight(1f), maxLines = 4,
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
