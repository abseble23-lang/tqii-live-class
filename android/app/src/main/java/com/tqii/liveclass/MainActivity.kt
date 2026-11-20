package com.tqii.liveclass

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Brand = Color(0xFF0F766E)
val BrandSoft = Color(0xFFE6F4F2)
val LiveRed = Color(0xFFDC2626)
val Ink = Color(0xFF14201B)
val Muted = Color(0xFF5F6F68)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Brand, onPrimary = Color.White, primaryContainer = BrandSoft,
            secondary = Brand, background = Color(0xFFF4F6F5), surface = Color.White, onSurface = Ink
        ),
        content = content
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
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
fun Root() {
    var screen by remember { mutableStateOf<Screen>(if (Api.loggedIn) Screen.Home else Screen.Login) }
    when (val s = screen) {
        is Screen.Login -> LoginScreen { screen = Screen.Home }
        is Screen.Home -> HomeScreen(onLogout = { screen = Screen.Login }, onChat = { screen = Screen.Chat(it) })
        is Screen.Chat -> {
            BackHandler { screen = Screen.Home }
            ChatScreen(s.c) { screen = Screen.Home }
        }
    }
}

@Composable
fun LangSwitch(dark: Boolean = false) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(if (dark) Color(0x33FFFFFF) else BrandSoft)) {
        listOf("bn" to "বাংলা", "en" to "EN").forEach { (k, label) ->
            val on = L.lang == k
            Text(
                label,
                Modifier.clickable { L.set(k) }.background(if (on) Brand else Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                color = if (on) Color.White else if (dark) Color.White else Brand, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ---------------- Login ----------------
@Composable
fun LoginScreen(onDone: () -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
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
                    me.role == "admin" -> { Api.logout(); err = L.t("adminUseWeb") }
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

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { LangSwitch() }
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(Brand), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Videocam, null, tint = Color.White, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(L.t("app"), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            user, { user = it.trim() }, label = { Text(L.t("username")) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            pass, { pass = it }, label = { Text(L.t("password")) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth()
        )
        if (err.isNotEmpty()) Text(err, color = LiveRed, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(20.dp))
        Button(onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            else Text(L.t("login"), fontSize = 16.sp)
        }
    }
}

// ---------------- Home ----------------
fun fmtTime(ms: Long): String {
    val loc = if (L.lang == "bn") Locale("bn", "BD") else Locale.ENGLISH
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.US).let { it.format(Date(ms)) == it.format(Date()) }
    val time = SimpleDateFormat("h:mm a", loc).format(Date(ms))
    return if (sameDay) "${L.t("today")}, $time" else SimpleDateFormat("d MMM, h:mm a", loc).format(Date(ms))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onLogout: () -> Unit, onChat: (ClassItem) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current as MainActivity
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

    // reload on resume + every 20 seconds
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(tick) { load() }
    LaunchedEffect(Unit) { while (true) { delay(20_000); load() } }

    val isTeacher = me?.role == "teacher"
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(L.t("hello") + (me?.let { ", " + it.fullName } ?: ""), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (isTeacher) L.t("teacher") else L.t("student"), fontSize = 12.sp, color = Color(0xCCFFFFFF))
                    }
                },
                actions = {
                    LangSwitch(dark = true)
                    IconButton(onClick = { tick++ }) { Icon(Icons.Filled.Refresh, L.t("refresh")) }
                    IconButton(onClick = { scope.launch { Api.logout(); onLogout() } }) { Icon(Icons.AutoMirrored.Filled.Logout, L.t("logout")) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Brand, titleContentColor = Color.White, actionIconContentColor = Color.White
                )
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text(L.t("myClasses"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink) }
            if (error.isNotEmpty()) item { Text(error, color = LiveRed) }
            if (loading) item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            else if (classes.isEmpty()) item {
                Text(L.t("noClasses"), color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(40.dp))
            }
            items(classes, key = { it.id }) { c ->
                ClassCard(
                    c, profiles, roster[c.id].orEmpty(), isTeacher,
                    onCall = { ctx.openCall(c, isTeacher) },
                    onChat = { onChat(c) }
                )
            }
        }
    }
}

@Composable
fun StatusPill(status: String) {
    val (bg, fg) = when (status) {
        "live" -> Color(0xFFFEE2E2) to LiveRed
        "scheduled" -> BrandSoft to Brand
        else -> Color(0xFFEEF0EF) to Muted
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (status == "live") { Box(Modifier.size(7.dp).clip(CircleShape).background(LiveRed)); Spacer(Modifier.width(5.dp)) }
        Text(L.t(status), color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ClassCard(c: ClassItem, profiles: Map<String, Profile>, students: List<String>, isTeacher: Boolean, onCall: () -> Unit, onChat: () -> Unit) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Ink)
                    c.subject?.let { Text(it, color = Muted, fontSize = 13.sp) }
                }
                StatusPill(c.status)
            }
            Spacer(Modifier.height(8.dp))
            Text("🕒 ${fmtTime(c.scheduledAt)} · ${c.durationMin} ${L.t("minutes")}", color = Muted, fontSize = 13.sp)
            if (!isTeacher) c.teacherId?.let { profiles[it] }?.let {
                Text("👤 ${L.t("teacher")}: ${it.fullName}", color = Muted, fontSize = 13.sp)
            }
            if (isTeacher && students.isNotEmpty()) {
                Text("👥 " + students.mapNotNull { profiles[it]?.fullName }.joinToString(", "), color = Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    isTeacher && c.status != "ended" -> Button(onClick = onCall, shape = RoundedCornerShape(10.dp),
                        colors = if (c.status == "live") ButtonDefaults.buttonColors(containerColor = LiveRed) else ButtonDefaults.buttonColors()) {
                        Icon(Icons.Filled.Videocam, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(if (c.status == "live") L.t("rejoin") else L.t("startClass"))
                    }
                    !isTeacher && c.status == "live" -> Button(onClick = onCall, shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = LiveRed)) {
                        Icon(Icons.Filled.Videocam, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(L.t("join"))
                    }
                    !isTeacher && c.status == "scheduled" -> Text(L.t("waiting"), color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
                if (isTeacher || c.status != "scheduled") Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onChat, shape = RoundedCornerShape(10.dp)) {
                    Icon(Icons.Filled.Chat, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(L.t("chat"))
                }
            }
        }
    }
}

// ---------------- Chat (outside the call) ----------------
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text(c.title, fontSize = 17.sp); Text(L.t("chat"), fontSize = 12.sp, color = Color(0xCCFFFFFF)) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Brand, titleContentColor = Color.White, navigationIconContentColor = Color.White)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            ChatPanel(messages, names, dark = false, modifier = Modifier.weight(1f)) { text ->
                scope.launch {
                    try {
                        Api.sendMessage(c.id, text)
                        Api.messages(c.id).forEach { m -> if (messages.none { it.id == m.id }) messages.add(m) }
                    } catch (_: Exception) {}
                }
            }
        }
    }
}

/** Shared chat list + input, used in the chat screen and inside the call. */
@Composable
fun ChatPanel(messages: List<ChatMsg>, names: Map<String, String>, dark: Boolean, modifier: Modifier = Modifier, onSend: (String) -> Unit) {
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
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages, key = { it.id }) { m ->
                    val mine = m.senderId == myId
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                        if (!mine) Text(names[m.senderId] ?: "?", fontSize = 12.sp, color = if (dark) Color(0xFF7DD3C7) else Brand, fontWeight = FontWeight.SemiBold)
                        Box(
                            Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(14.dp))
                                .background(if (mine) Brand else if (dark) Color(0xFF2A3431) else Color.White)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(m.body, color = if (mine || dark) Color.White else Ink, fontSize = 15.sp)
                        }
                        Text(SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(m.createdAt)), fontSize = 10.sp, color = if (dark) Color(0x88FFFFFF) else Muted)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(if (dark) Color(0xFF1C2421) else Color.White).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                text, { text = it }, placeholder = { Text(L.t("typeMessage")) }, modifier = Modifier.weight(1f), maxLines = 4,
                colors = if (dark) OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White) else OutlinedTextFieldDefaults.colors(),
                shape = RoundedCornerShape(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            FilledIconButton(onClick = { if (text.isNotBlank()) { onSend(text.trim()); text = "" } }) {
                Icon(Icons.AutoMirrored.Filled.Send, L.t("send"))
            }
        }
    }
}
