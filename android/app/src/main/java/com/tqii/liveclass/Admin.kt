package com.tqii.liveclass

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Data shared by all admin screens. */
class AdminData {
    var profiles by mutableStateOf<List<Profile>>(emptyList())
    var classes by mutableStateOf<List<ClassItem>>(emptyList())
    var roster by mutableStateOf<Map<String, List<String>>>(emptyMap())
    var loading by mutableStateOf(true)
    var error by mutableStateOf("")
    fun byId(id: String?) = profiles.firstOrNull { it.id == id }
    fun studentsOf(cid: String) = roster[cid].orEmpty().mapNotNull { byId(it) }

    suspend fun load() {
        try {
            if (Api.me == null) Api.loadMe()
            val p = Api.profiles(); val c = Api.classes(); val r = Api.roster()
            profiles = p.values.toList(); classes = c; roster = r; error = ""
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
    var tab by remember { mutableIntStateOf(0) }
    var editClass by remember { mutableStateOf<ClassItem?>(null) }
    var newClass by remember { mutableStateOf(false) }
    var newUserRole by remember { mutableStateOf<String?>(null) }
    var chatFor by remember { mutableStateOf<ClassItem?>(null) }
    var toast by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { while (true) { data.load(); delay(15_000) } }
    LaunchedEffect(toast) { if (toast.isNotEmpty()) { delay(2200); toast = "" } }
    fun reload() = scope.launch { data.load() }

    chatFor?.let { c ->
        BackHandler { chatFor = null }
        ChatScreen(c) { chatFor = null }
        return
    }

    Box(Modifier.fillMaxSize().background(Ivory)) {
        Column(Modifier.fillMaxSize()) {
            BrandHeader("TQII Admin", Api.me?.fullName) {
                IconButton(onClick = { reload() }) { Icon(Icons.Filled.Refresh, null, tint = Ivory) }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> AdminDashboard(data, onOpen = { editClass = it }, onChat = { chatFor = it })
                    1 -> AdminClasses(data, onEdit = { editClass = it }, onChat = { chatFor = it }, onChanged = { reload() }, onToast = { toast = it })
                    2 -> AdminUsers(data, "teacher", onChanged = { reload() }, onToast = { toast = it })
                    3 -> AdminUsers(data, "student", onChanged = { reload() }, onToast = { toast = it })
                    else -> AdminMore(data, onLogout, onChanged = { reload() }, onToast = { toast = it })
                }
                // floating action button
                val fab: (() -> Unit)? = when (tab) {
                    0, 1 -> ({ newClass = true })
                    2 -> ({ newUserRole = "teacher" })
                    3 -> ({ newUserRole = "student" })
                    else -> null
                }
                if (fab != null) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(18.dp).size(60.dp).clip(RoundedCornerShape(20.dp))
                            .background(GoldGradient).clickable { fab() },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.Add, null, tint = Night, modifier = Modifier.size(30.dp)) }
                }
            }
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp, modifier = Modifier.border(1.dp, Line)) {
                val items = listOf(
                    Triple(Icons.Filled.Dashboard, "dashboard", 0),
                    Triple(Icons.Filled.CalendarMonth, "classes", 1),
                    Triple(Icons.Filled.School, "teachers", 2),
                    Triple(Icons.Filled.Groups, "students", 3),
                    Triple(Icons.Filled.Settings, "settings", 4),
                )
                items.forEach { (icon, key, i) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icon, null) },
                        label = { Text(L.t(key), fontSize = 11.sp, maxLines = 1) },
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

    if (newClass || editClass != null) {
        ClassEditor(data, editClass, onClose = { newClass = false; editClass = null }, onSaved = {
            newClass = false; editClass = null; toast = L.t("saved"); reload()
        })
    }
    newUserRole?.let { role ->
        UserEditor(role, onClose = { newUserRole = null }, onSaved = { newUserRole = null; toast = L.t("saved"); reload() })
    }
}

// ---------------- Dashboard ----------------
@Composable
fun AdminDashboard(data: AdminData, onOpen: (ClassItem) -> Unit, onChat: (ClassItem) -> Unit) {
    val live = data.classes.filter { it.status == "live" }
    val today = data.classes.filter {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US); f.format(Date(it.scheduledAt)) == f.format(Date())
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigStat(L.t("liveNow"), live.size, Icons.Filled.Sensors, LiveRed, Modifier.weight(1f), dark = true)
                    BigStat(L.t("todayClasses"), today.size, Icons.Filled.Today, GoldDeep, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigStat(L.t("totalTeachers"), data.profiles.count { it.role == "teacher" }, Icons.Filled.School, GoldDeep, Modifier.weight(1f))
                    BigStat(L.t("totalStudents"), data.profiles.count { it.role == "student" }, Icons.Filled.Groups, GoldDeep, Modifier.weight(1f))
                }
            }
        }
        if (data.error.isNotEmpty()) item { Text(data.error, color = LiveRed) }
        item { SectionTitle(L.t("liveNow"), Modifier.padding(top = 6.dp)) }
        if (live.isEmpty()) item { EmptyCard() }
        items(live, key = { "l" + it.id }) { AdminClassRow(data, it, onClick = { onOpen(it) }, onChat = { onChat(it) }) }
        item { SectionTitle(L.t("todayClasses"), Modifier.padding(top = 6.dp)) }
        if (today.isEmpty()) item { EmptyCard() }
        items(today, key = { "t" + it.id }) { AdminClassRow(data, it, onClick = { onOpen(it) }, onChat = { onChat(it) }) }
    }
}

@Composable
fun BigStat(label: String, n: Int, icon: ImageVector, color: Color, modifier: Modifier, dark: Boolean = false) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(if (dark) Night else Color.White)
            .border(1.dp, if (dark) Gold else Line, RoundedCornerShape(20.dp)).padding(16.dp)
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(if (dark) Night3 else GoldSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (dark) GoldLight else GoldDeep, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text("$n", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = if (dark) (if (n > 0) LiveRed else Ivory) else Ink)
        Text(label, fontSize = 12.sp, color = if (dark) Color(0xAAFFFFFF) else Muted)
    }
}

@Composable
fun EmptyCard() {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(16.dp)).padding(22.dp), contentAlignment = Alignment.Center) {
        Text(L.t("none"), color = Muted)
    }
}

@Composable
fun AdminClassRow(data: AdminData, c: ClassItem, onClick: () -> Unit, onChat: () -> Unit, menu: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White)
            .border(1.dp, if (c.status == "live") Gold else Line, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(54.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val loc = if (L.lang == "bn") Locale("bn", "BD") else Locale.ENGLISH
            Text(SimpleDateFormat("d", loc).format(Date(c.scheduledAt)), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(SimpleDateFormat("MMM", loc).format(Date(c.scheduledAt)), fontSize = 11.sp, color = GoldDeep)
            Text(SimpleDateFormat("h:mm a", loc).format(Date(c.scheduledAt)), fontSize = 10.sp, color = Muted)
        }
        Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(48.dp).background(Line))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.title, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp)); StatusPill(c.status)
            }
            Text("👤 " + (data.byId(c.teacherId)?.fullName ?: "—"), fontSize = 12.sp, color = Muted, maxLines = 1)
            val st = data.studentsOf(c.id)
            Text("👥 " + (if (st.isEmpty()) "—" else st.joinToString(", ") { it.fullName }), fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onChat) { Icon(Icons.AutoMirrored.Filled.Chat, null, tint = GoldDeep) }
        menu?.invoke()
    }
}

// ---------------- Classes ----------------
@Composable
fun AdminClasses(data: AdminData, onEdit: (ClassItem) -> Unit, onChat: (ClassItem) -> Unit, onChanged: () -> Unit, onToast: (String) -> Unit) {
    var filter by remember { mutableStateOf("upcoming") }
    var confirmDel by remember { mutableStateOf<ClassItem?>(null) }
    val scope = rememberCoroutineScope()
    val list = when (filter) {
        "upcoming" -> data.classes.filter { it.status == "scheduled" }
        "live" -> data.classes.filter { it.status == "live" }
        "ended" -> data.classes.filter { it.status == "ended" }.sortedByDescending { it.scheduledAt }
        else -> data.classes.sortedByDescending { it.scheduledAt }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("upcoming", "live", "ended", "all").forEach { k -> Chip(L.t(if (k == "live") "liveNow" else k), filter == k) { filter = k } }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (list.isEmpty()) item { EmptyCard() }
            items(list, key = { it.id }) { c ->
                AdminClassRow(data, c, onClick = { onEdit(c) }, onChat = { onChat(c) }) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, null, tint = Muted) }
                        DropdownMenu(open, { open = false }, containerColor = Color.White) {
                            DropdownMenuItem({ Text(L.t("edit")) }, { open = false; onEdit(c) }, leadingIcon = { Icon(Icons.Filled.Edit, null) })
                            if (c.status == "live") DropdownMenuItem({ Text(L.t("endClass")) }, {
                                open = false
                                scope.launch { try { Api.setStatus(c.id, "ended"); onChanged() } catch (e: Exception) { onToast(errText(e)) } }
                            }, leadingIcon = { Icon(Icons.Filled.StopCircle, null) })
                            DropdownMenuItem({ Text(L.t("delete"), color = LiveRed) }, { open = false; confirmDel = c },
                                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = LiveRed) })
                        }
                    }
                }
            }
        }
    }
    confirmDel?.let { c ->
        ConfirmDialog(L.t("confirmDelete"), c.title, onDismiss = { confirmDel = null }) {
            confirmDel = null
            scope.launch { try { Api.deleteClass(c.id); onChanged() } catch (e: Exception) { onToast(errText(e)) } }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color.White,
        title = { Text(title, fontWeight = FontWeight.Bold) }, text = { Text(text, color = Muted) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(L.t("delete"), color = LiveRed, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.t("cancel"), color = Muted) } }
    )
}

@Composable
fun FullDialog(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Ivory)) {
            Box(Modifier.fillMaxWidth().background(NightGradient).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, null, tint = Ivory) }
                    Text(title, color = Ivory, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Logo(34.dp); Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(GoldGradient))
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(18.dp),
                content = content
            )
        }
    }
}

@Composable
fun FieldLabel(text: String) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Muted, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)) }

@Composable
fun ClassEditor(data: AdminData, existing: ClassItem?, onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var subject by remember { mutableStateOf(existing?.subject ?: "") }
    var teacherId by remember { mutableStateOf(existing?.teacherId) }
    val cal = remember {
        Calendar.getInstance().apply {
            if (existing != null) timeInMillis = existing.scheduledAt
            else { add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0) }
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
    }
    var whenMs by remember { mutableLongStateOf(cal.timeInMillis) }
    var duration by remember { mutableIntStateOf(existing?.durationMin ?: 60) }
    val chosen = remember { mutableStateListOf<String>().apply { if (existing != null) addAll(data.roster[existing.id].orEmpty()) } }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val teachers = data.profiles.filter { it.role == "teacher" && it.active }
    val students = data.profiles.filter { it.role == "student" && it.active }
    val loc = if (L.lang == "bn") Locale("bn", "BD") else Locale.ENGLISH

    FullDialog(if (existing == null) L.t("newClass") else L.t("editClass"), onClose) {
        FieldLabel(L.t("title"))
        OutlinedTextField(title, { title = it }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors(), modifier = Modifier.fillMaxWidth())
        FieldLabel(L.t("subject"))
        OutlinedTextField(subject, { subject = it }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors(), modifier = Modifier.fillMaxWidth())

        FieldLabel(L.t("selectTeacher"))
        if (teachers.isEmpty()) Text(L.t("noTeachersYet"), color = LiveRed, fontSize = 13.sp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            teachers.forEach { t -> PersonPick(t, teacherId == t.id) { teacherId = t.id } }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                FieldLabel(L.t("date"))
                PickerBox(Icons.Filled.Event, SimpleDateFormat("d MMM yyyy", loc).format(Date(whenMs))) {
                    val c = Calendar.getInstance().apply { timeInMillis = whenMs }
                    DatePickerDialog(ctx, { _, y, m, d -> c.set(y, m, d); whenMs = c.timeInMillis }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                }
            }
            Column(Modifier.weight(1f)) {
                FieldLabel(L.t("time"))
                PickerBox(Icons.Filled.Schedule, SimpleDateFormat("h:mm a", loc).format(Date(whenMs))) {
                    val c = Calendar.getInstance().apply { timeInMillis = whenMs }
                    TimePickerDialog(ctx, { _, h, min -> c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, min); whenMs = c.timeInMillis },
                        c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
                }
            }
        }

        FieldLabel(L.t("duration"))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 45, 60, 90, 120).forEach { d -> Chip("$d ${L.t("minutes")}", duration == d) { duration = d } }
        }

        FieldLabel(L.t("studentsMax") + "  (${chosen.size}/3)")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            students.forEach { s ->
                PersonPick(s, s.id in chosen, check = true) {
                    if (s.id in chosen) chosen.remove(s.id)
                    else if (chosen.size >= 3) err = L.t("max3") else { chosen.add(s.id); err = "" }
                }
            }
        }

        if (err.isNotEmpty()) Text(err, color = LiveRed, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(20.dp))
        GoldButton(L.t("save"), Modifier.fillMaxWidth(), loading = busy) {
            if (title.isBlank()) { err = L.t("needTitle"); return@GoldButton }
            if (teacherId == null) { err = L.t("needTeacher"); return@GoldButton }
            busy = true; err = ""
            scope.launch {
                try {
                    val row = JSONObject().put("title", title.trim()).put("subject", if (subject.isBlank()) JSONObject.NULL else subject.trim())
                        .put("teacher_id", teacherId).put("scheduled_at", Api.isoOf(whenMs)).put("duration_min", duration)
                    val id = if (existing != null) { Api.updateClass(existing.id, row); existing.id } else Api.createClass(row)
                    Api.setRoster(id, chosen.toList())
                    onSaved()
                } catch (e: Exception) { err = errText(e) }
                busy = false
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
fun PickerBox(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(14.dp)).background(Color.White)
            .border(1.dp, Line, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = GoldDeep, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
        Text(text, color = Ink, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PersonPick(p: Profile, selected: Boolean, check: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) Night else Color.White)
            .border(1.dp, if (selected) Gold else Line, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        InitialAvatar(p.fullName, 36.dp, dark = !selected)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(p.fullName, color = if (selected) Ivory else Ink, fontWeight = FontWeight.SemiBold)
            Text("@" + p.username, color = if (selected) GoldLight else Muted, fontSize = 12.sp)
        }
        if (selected) Icon(if (check) Icons.Filled.CheckBox else Icons.Filled.CheckCircle, null, tint = GoldLight)
        else if (check) Icon(Icons.Filled.CheckBoxOutlineBlank, null, tint = Line)
    }
}

// ---------------- Users ----------------
@Composable
fun AdminUsers(data: AdminData, role: String, onChanged: () -> Unit, onToast: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var resetFor by remember { mutableStateOf<Profile?>(null) }
    var delFor by remember { mutableStateOf<Profile?>(null) }
    val scope = rememberCoroutineScope()
    val list = data.profiles.filter { it.role == role && (q.isBlank() || (it.fullName + " " + it.username + " " + (it.phone ?: "")).contains(q, true)) }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            q, { q = it }, placeholder = { Text(L.t("search")) }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = Muted) },
            shape = RoundedCornerShape(16.dp), colors = fieldColors(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (list.isEmpty()) item { EmptyCard() }
            items(list, key = { it.id }) { p ->
                UserRow(p,
                    onReset = { resetFor = p },
                    onToggle = {
                        scope.launch {
                            try { Api.adminUsers(JSONObject().put("action", "set_active").put("user_id", p.id).put("active", !p.active)); onChanged() }
                            catch (e: Exception) { onToast(errText(e)) }
                        }
                    },
                    onDelete = { delFor = p })
            }
        }
    }
    resetFor?.let { p -> ResetPwDialog(p, onClose = { resetFor = null }, onDone = { resetFor = null; onToast(L.t("saved")) }) }
    delFor?.let { p ->
        ConfirmDialog(L.t("confirmDelete"), p.fullName + " (@" + p.username + ")", onDismiss = { delFor = null }) {
            delFor = null
            scope.launch {
                try { Api.adminUsers(JSONObject().put("action", "delete").put("user_id", p.id)); onChanged() }
                catch (e: Exception) { onToast(errText(e)) }
            }
        }
    }
}

@Composable
fun UserRow(p: Profile, onReset: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    val self = p.id == Api.userId
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        InitialAvatar(p.fullName, 44.dp, dark = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.fullName, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1)
            Text("@" + p.username + (p.phone?.let { " · $it" } ?: ""), fontSize = 12.sp, color = Muted, maxLines = 1)
        }
        Text(
            if (p.active) L.t("active") else L.t("inactive"),
            color = if (p.active) Success else LiveRed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (p.active) Color(0xFFE3F4EA) else Color(0xFFFDE4E4)).padding(horizontal = 8.dp, vertical = 3.dp)
        )
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, null, tint = Muted) }
            DropdownMenu(open, { open = false }, containerColor = Color.White) {
                DropdownMenuItem({ Text(L.t("resetPw")) }, { open = false; onReset() }, leadingIcon = { Icon(Icons.Filled.Key, null) })
                if (!self) {
                    DropdownMenuItem({ Text(if (p.active) L.t("disable") else L.t("enable")) }, { open = false; onToggle() },
                        leadingIcon = { Icon(if (p.active) Icons.Filled.Block else Icons.Filled.CheckCircle, null) })
                    DropdownMenuItem({ Text(L.t("delete"), color = LiveRed) }, { open = false; onDelete() },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = LiveRed) })
                }
            }
        }
    }
}

@Composable
fun ResetPwDialog(p: Profile, onClose: () -> Unit, onDone: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose, containerColor = Color.White,
        title = { Text(L.t("resetPw"), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(p.fullName + " (@" + p.username + ")", color = Muted)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(pw, { pw = it }, label = { Text(L.t("newPw")) }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors())
                if (err.isNotEmpty()) Text(err, color = LiveRed, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            GoldButton(L.t("save"), Modifier.width(120.dp).height(44.dp), loading = busy) {
                busy = true
                scope.launch {
                    try { Api.adminUsers(JSONObject().put("action", "reset_password").put("user_id", p.id).put("password", pw)); onDone() }
                    catch (e: Exception) { err = errText(e) }
                    busy = false
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(L.t("cancel"), color = Muted) } }
    )
}

@Composable
fun UserEditor(role: String, onClose: () -> Unit, onSaved: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val titleKey = when (role) { "teacher" -> "newTeacher"; "admin" -> "newAdmin"; else -> "newStudent" }
    FullDialog(L.t(titleKey), onClose) {
        FieldLabel(L.t("name"))
        OutlinedTextField(name, { name = it }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors(), modifier = Modifier.fillMaxWidth())
        FieldLabel(L.t("username"))
        OutlinedTextField(user, { user = it.trim().lowercase() }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors(),
            leadingIcon = { Text("@", color = GoldDeep, fontWeight = FontWeight.Bold) }, modifier = Modifier.fillMaxWidth())
        Text(L.t("userHint"), fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
        FieldLabel(L.t("password"))
        OutlinedTextField(pw, { pw = it }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors(), modifier = Modifier.fillMaxWidth())
        FieldLabel(L.t("phone"))
        OutlinedTextField(phone, { phone = it }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors(), modifier = Modifier.fillMaxWidth())
        if (err.isNotEmpty()) Text(err, color = LiveRed, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(22.dp))
        GoldButton(L.t("save"), Modifier.fillMaxWidth(), loading = busy) {
            busy = true; err = ""
            scope.launch {
                try {
                    Api.adminUsers(JSONObject().put("action", "create").put("role", role).put("full_name", name)
                        .put("username", user).put("password", pw).put("phone", phone))
                    onSaved()
                } catch (e: Exception) { err = errText(e) }
                busy = false
            }
        }
    }
}

// ---------------- More / settings ----------------
@Composable
fun AdminMore(data: AdminData, onLogout: () -> Unit, onChanged: () -> Unit, onToast: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAdmins by remember { mutableStateOf(false) }
    var newAdmin by remember { mutableStateOf(false) }
    var changePw by remember { mutableStateOf(false) }
    if (showAdmins) {
        BackHandler { showAdmins = false }
        Box(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showAdmins = false }) { Icon(Icons.Filled.ArrowBack, null) }
                    SectionTitle(L.t("admins"))
                }
                AdminUsers(data, "admin", onChanged, onToast)
            }
            Box(
                Modifier.align(Alignment.BottomEnd).padding(18.dp).size(60.dp).clip(RoundedCornerShape(20.dp)).background(GoldGradient).clickable { newAdmin = true },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.PersonAdd, null, tint = Night) }
        }
        if (newAdmin) UserEditor("admin", onClose = { newAdmin = false }, onSaved = { newAdmin = false; onToast(L.t("saved")); onChanged() })
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(NightGradient).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(90.dp)
            Text(Api.me?.fullName ?: "", color = Ivory, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("@" + (Api.me?.username ?: ""), color = GoldLight, fontSize = 13.sp)
        }
        SettingRow(Icons.Filled.AdminPanelSettings, L.t("admins")) { showAdmins = true }
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
        val me = Api.me
        if (me != null) ResetPwDialog(me, onClose = { changePw = false }, onDone = { changePw = false; onToast(L.t("saved")) })
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
