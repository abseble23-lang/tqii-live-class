package com.tqii.liveclass

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Data shared by the admin screens (all from the website database). */
class AdminData {
    var contacts by mutableStateOf<List<Contact>>(emptyList())
    var calls by mutableStateOf<List<CallInfo>>(emptyList())
    var students by mutableStateOf<List<StudentRow>>(emptyList())
    var people by mutableStateOf<List<Person>>(emptyList())
    var access by mutableStateOf<Set<String>>(emptySet())
    var loading by mutableStateOf(true)
    var error by mutableStateOf("")

    suspend fun load() {
        try {
            if (Api.me == null) Api.loadMe()
            contacts = Api.contacts()
            calls = Api.recentCalls(100)
            students = Api.students()
            people = Api.people()
            access = try { Api.accessList() } catch (_: Exception) { emptySet() }
            error = ""
        } catch (e: Exception) { error = L.t("netError") }
        loading = false
    }
}

fun errText(e: Exception): String {
    val code = e.message ?: ""
    val t = L.t("err_$code")
    return if (t != "err_$code") t else if (e is ApiException) code.take(120) else L.t("netError")
}

@Composable
fun AdminRoot(onLogout: () -> Unit) {
    val data = remember { AdminData() }
    val scope = rememberCoroutineScope()
    val act = LocalContext.current as MainActivity
    var tab by remember { mutableIntStateOf(0) }
    var chatFor by remember { mutableStateOf<Contact?>(null) }
    var toast by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { while (true) { data.load(); delay(10_000) } }
    LaunchedEffect(toast) { if (toast.isNotEmpty()) { delay(2200); toast = "" } }
    fun reload() = scope.launch { data.load() }

    chatFor?.let { c ->
        BackHandler { chatFor = null }
        ChatScreen(c) { chatFor = null }
        return
    }

    fun join(c: CallInfo) = act.openCall(c.id, c.teacherId, c.studentId, "${c.teacherName} · ${c.studentName}", outgoing = false)
    fun startFor(c: Contact) = scope.launch {
        try {
            val id = Api.startCall(c.teacherId, c.studentId)
            act.openCall(id, c.teacherId, c.studentId, "${c.teacherName} · ${c.studentName}", outgoing = true)
        } catch (e: Exception) { toast = errText(e) }
    }

    Box(Modifier.fillMaxSize().background(Ivory)) {
        Column(Modifier.fillMaxSize()) {
            BrandHeader("TQII Admin", Api.me?.name) {
                IconButton(onClick = { reload() }) { Icon(Icons.Filled.Refresh, null, tint = Ivory) }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> AdminLive(data, onJoin = { join(it) }, onCall = { startFor(it) }, onChat = { chatFor = it })
                    1 -> AdminStudents(data, onChanged = { reload() }, onToast = { toast = it })
                    2 -> AdminCalls(data)
                    3 -> AdminAccess(data, onChanged = { reload() }, onToast = { toast = it })
                    else -> AdminMore(onLogout, onToast = { toast = it })
                }
            }
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp, modifier = Modifier.border(1.dp, Line)) {
                listOf(
                    Triple(Icons.Filled.Sensors, "liveClasses", 0),
                    Triple(Icons.Filled.Groups, "studentLogins", 1),
                    Triple(Icons.Filled.History, "calls", 2),
                    Triple(Icons.Filled.VerifiedUser, "access", 3),
                    Triple(Icons.Filled.Settings, "settings", 4),
                ).forEach { (icon, key, i) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icon, null) },
                        label = { Text(L.t(key), fontSize = 10.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Night, selectedTextColor = Night, indicatorColor = GoldSoft,
                            unselectedIconColor = Muted, unselectedTextColor = Muted
                        )
                    )
                }
            }
        }
        if (toast.isNotEmpty()) {
            Text(
                toast, color = Ivory, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Night).padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}

// ---------------- Live now + today ----------------
@Composable
fun AdminLive(data: AdminData, onJoin: (CallInfo) -> Unit, onCall: (Contact) -> Unit, onChat: (Contact) -> Unit) {
    val live = data.calls.filter { it.status == "active" || it.status == "ringing" }
    val today = todayKey()
    val todays = data.contacts.filter { today in it.classDays }.sortedBy { it.classTime ?: "99" }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigStat(L.t("liveNow"), live.size, Icons.Filled.Sensors, Modifier.weight(1f), dark = true)
                BigStat(L.t("todayClasses"), todays.size, Icons.Filled.Today, Modifier.weight(1f))
                BigStat(L.t("totalStudents"), data.students.count { it.status == "active" }, Icons.Filled.Groups, Modifier.weight(1f))
            }
        }
        if (data.error.isNotEmpty()) item { Text(data.error, color = LiveRed) }
        item { SectionTitle(L.t("liveNow"), Modifier.padding(top = 6.dp)) }
        if (live.isEmpty()) item { EmptyCard() }
        items(live, key = { "l" + it.id }) { c ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Night).border(1.dp, Gold, RoundedCornerShape(18.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { StatusPill(c.status); Spacer(Modifier.width(8.dp)); Text("${c.inCall} ${L.t("inCall")}", color = GoldLight, fontSize = 12.sp) }
                    Spacer(Modifier.height(6.dp))
                    Text(c.teacherName, color = Ivory, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("→ " + c.studentName, color = Color(0xCCFFFFFF), fontSize = 13.sp, maxLines = 1)
                    Text(fmtTime(c.answeredAt.takeIf { it > 0 } ?: c.createdAt), color = Color(0x99FFFFFF), fontSize = 11.sp)
                }
                GoldButton(L.t("joinNow"), Modifier.width(96.dp).height(44.dp)) { onJoin(c) }
            }
        }
        item { SectionTitle(L.t("todayClasses"), Modifier.padding(top = 6.dp)) }
        if (todays.isEmpty()) item { EmptyCard() }
        items(todays, key = { "t" + it.teacherId + it.studentId }) { c ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.width(70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Schedule, null, tint = GoldDeep)
                    Text(time12(c.classTime), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Ink)
                }
                Column(Modifier.weight(1f)) {
                    Text(c.studentName, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("👤 " + c.teacherName, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onChat(c) }) { Icon(Icons.AutoMirrored.Filled.Chat, null, tint = GoldDeep) }
                IconButton(onClick = { onCall(c) }) { Icon(Icons.Filled.Videocam, null, tint = GoldDeep) }
            }
        }
    }
}

@Composable
fun BigStat(label: String, n: Int, icon: ImageVector, modifier: Modifier, dark: Boolean = false) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(if (dark) Night else Color.White)
            .border(1.dp, if (dark) Gold else Line, RoundedCornerShape(20.dp)).padding(14.dp)
    ) {
        Icon(icon, null, tint = if (dark) GoldLight else GoldDeep, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(6.dp))
        Text("$n", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (dark) (if (n > 0) LiveRed else Ivory) else Ink)
        Text(label, fontSize = 11.sp, color = if (dark) Color(0xAAFFFFFF) else Muted, maxLines = 1)
    }
}

@Composable
fun EmptyCard() {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(16.dp)).padding(22.dp), contentAlignment = Alignment.Center) {
        Text(L.t("none"), color = Muted)
    }
}

// ---------------- Student logins ----------------
@Composable
fun AdminStudents(data: AdminData, onChanged: () -> Unit, onToast: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<StudentRow?>(null) }
    val list = data.students.filter { it.status in setOf("active", "paused") && (q.isBlank() || (it.name + " " + (it.whatsapp ?: "")).contains(q, true)) }
    val teacherName = data.contacts.associate { it.teacherId to it.teacherName }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            q, { q = it }, placeholder = { Text(L.t("search")) }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = Muted) },
            shape = RoundedCornerShape(16.dp), colors = fieldColors(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (list.isEmpty()) item { EmptyCard() }
            items(list, key = { it.id }) { s ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InitialAvatar(s.name, 44.dp, dark = true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.whatsapp ?: "", fontSize = 12.sp, color = Muted)
                        Text("👤 " + (s.teacherId?.let { teacherName[it] } ?: L.t("noTeacher")), fontSize = 11.sp, color = Muted, maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            if (s.profileId != null) L.t("hasLogin") else L.t("noLogin"),
                            color = if (s.profileId != null) Success else GoldDeep, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (s.profileId != null) Color(0xFFE3F4EA) else GoldSoft).padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                        TextButton(onClick = { editing = s }) {
                            Text(if (s.profileId != null) L.t("updateLogin") else L.t("createLogin"), color = GoldDeep, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
    editing?.let { s -> StudentLoginDialog(s, onClose = { editing = null }, onDone = { editing = null; onToast(L.t("saved")); onChanged() }) }
}

@Composable
fun StudentLoginDialog(s: StudentRow, onClose: () -> Unit, onDone: () -> Unit) {
    var phone by remember { mutableStateOf(s.whatsapp ?: "") }
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose, containerColor = Color.White,
        title = { Text(if (s.profileId != null) L.t("updateLogin") else L.t("createLogin"), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(s.name, color = Muted)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(phone, { phone = it }, label = { Text(L.t("loginId")) }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(pw, { pw = it }, label = { Text(L.t("newPw")) }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors())
                if (err.isNotEmpty()) Text(err, color = LiveRed, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            GoldButton(L.t("save"), Modifier.width(120.dp).height(44.dp), loading = busy) {
                busy = true; err = ""
                scope.launch {
                    try { Api.studentLogin(s.id, phone, pw); onDone() } catch (e: Exception) { err = errText(e) }
                    busy = false
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(L.t("cancel"), color = Muted) } }
    )
}

// ---------------- Call history with attendance ----------------
@Composable
fun AdminCalls(data: AdminData) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (data.calls.isEmpty()) item { EmptyCard() }
        items(data.calls, key = { it.id }) { c ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.studentName, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1)
                        Text("👤 " + c.teacherName, fontSize = 12.sp, color = Muted, maxLines = 1)
                    }
                    StatusPill(if (c.status in setOf("ended")) "ended" else c.status)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Schedule, null, tint = Gold, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                    Text(fmtTime(c.createdAt), fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
                    Text("${L.t("attendance")}: ${L.t("teacher")} ${c.teacherMinutes} · ${L.t("student")} ${c.studentMinutes} ${L.t("minutesShort")}", fontSize = 11.sp, color = Ink)
                }
                if (c.reported) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Icon(Icons.Filled.TaskAlt, null, tint = Success, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                    Text(L.t("reported"), color = Success, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ---------------- Join access ----------------
@Composable
fun AdminAccess(data: AdminData, onChanged: () -> Unit, onToast: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val staff = data.people.filter { it.role != null && it.role != "super_admin" && it.active }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(L.t("accessHint"), color = Muted, fontSize = 13.sp) }
        if (staff.isEmpty()) item { EmptyCard() }
        items(staff, key = { it.id }) { p ->
            val on = p.id in data.access
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).border(1.dp, if (on) Gold else Line, RoundedCornerShape(18.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InitialAvatar(p.name, 42.dp, dark = true)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text((if (p.role == "teacher") L.t("teacher") else L.t("admins")) + (p.phone?.let { " · $it" } ?: ""), fontSize = 12.sp, color = Muted)
                }
                Switch(
                    checked = on,
                    onCheckedChange = { v ->
                        scope.launch { try { Api.setAccess(p.id, v); onChanged() } catch (e: Exception) { onToast(errText(e)) } }
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = Night, checkedTrackColor = Gold)
                )
            }
        }
    }
}

// ---------------- Settings ----------------
@Composable
fun AdminMore(onLogout: () -> Unit, onToast: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var changePw by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(NightGradient).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(90.dp)
            Text(Api.me?.name ?: "", color = Ivory, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(if (Api.me?.isSuper == true) "প্রধান অ্যাডমিন" else L.t("admins"), color = GoldLight, fontSize = 13.sp)
        }
        SettingRow(Icons.Filled.Key, L.t("changePw")) { changePw = true }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(16.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Language, null, tint = GoldDeep); Spacer(Modifier.width(12.dp))
            Text(L.t("language"), modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            LangSwitch()
        }
        SettingRow(Icons.AutoMirrored.Filled.Logout, L.t("logout"), danger = true) { scope.launch { Api.logout(); onLogout() } }
        Text("${L.t("version")} 1.0.${Updater.currentVersion(ctx)}", color = Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
    }
    if (changePw) {
        var pw by remember { mutableStateOf("") }
        var err by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { changePw = false }, containerColor = Color.White,
            title = { Text(L.t("changePw"), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(pw, { pw = it }, label = { Text(L.t("newPw")) }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors())
                    if (err.isNotEmpty()) Text(err, color = LiveRed, fontSize = 13.sp)
                }
            },
            confirmButton = {
                GoldButton(L.t("save"), Modifier.width(120.dp).height(44.dp)) {
                    if (pw.length < 8) { err = L.t("err_weak_password"); return@GoldButton }
                    scope.launch {
                        try { Api.changeMyPassword(pw); changePw = false; onToast(L.t("saved")) } catch (e: Exception) { err = errText(e) }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { changePw = false }) { Text(L.t("cancel"), color = Muted) } }
        )
    }
}

@Composable
fun SettingRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (danger) LiveRed else GoldDeep); Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = if (danger) LiveRed else Ink)
        Icon(Icons.Filled.ChevronRight, null, tint = Muted)
    }
}
